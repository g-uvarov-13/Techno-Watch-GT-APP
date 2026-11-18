package app.gtdial.lab;

import android.os.Handler;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.TimeZone;
import org.json.JSONObject;

/** One explicitly confirmed official time_sync_svc request; acknowledgement is not RTC readback. */
final class PhoneTimeSyncProtocol {
    static final class Prepared {
        final String deviceAddress,zoneId;
        final long phoneTimeMillis,expiresElapsed;
        final long preparedElapsed;
        final int offsetMinutes;
        Prepared(String address){deviceAddress=address;phoneTimeMillis=System.currentTimeMillis();preparedElapsed=SystemClock.elapsedRealtime();expiresElapsed=preparedElapsed+120000;TimeZone zone=TimeZone.getDefault();zoneId=zone.getID();offsetMinutes=zone.getOffset(phoneTimeMillis)/60000;}
        String refusal(){long elapsed=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();TimeZone zone=TimeZone.getDefault();if(elapsed<preparedElapsed||elapsed>expiresElapsed)return "CONFIRMATION_EXPIRED";if(!zoneId.equals(zone.getID())||offsetMinutes!=zone.getOffset(wall)/60000||Math.abs((wall-phoneTimeMillis)-(elapsed-preparedElapsed))>2000)return "CONFIRMATION_CHANGED";return "";}
    }
    interface Transport {boolean write(byte[] frame);void finished(boolean success,String detail);}
    private enum State { IDLE, WAIT_TX_CALLBACK, WAIT_RESPONSE, ACKNOWLEDGED, FAILED }
    private final Handler handler;
    private final Transport transport;
    private final int packetId;
    private final byte[] request;
    private final ByteArrayOutputStream incoming=new ByteArrayOutputStream();
    private State state=State.IDLE;
    private boolean earlyResponse;
    private long txStartedElapsed,responseStartedElapsed;
    private final Runnable txDeadline=()->cancel("TX_CALLBACK_TIMEOUT");
    private final Runnable responseDeadline=()->cancel("RESPONSE_TIMEOUT");
    PhoneTimeSyncProtocol(Handler handler,int packetId,int capacity,Transport transport){
        this.handler=handler;this.packetId=packetId;this.transport=transport;
        long wall=System.currentTimeMillis(),seconds=wall/1000;
        if(packetId<0||packetId>255||seconds<=0||seconds>Integer.MAX_VALUE)throw new IllegalArgumentException("LOCAL_GATE");
        int offset=TimeZone.getDefault().getOffset(wall)/60000;
        try{byte[] payload=new JSONObject().put("timestamp",seconds).put("timezone",offset/60).put("minute_offset",offset%60).toString().getBytes(StandardCharsets.UTF_8);
            byte[] service="time_sync_svc".getBytes(StandardCharsets.UTF_8);int length=13+service.length+2+payload.length;
            if(length>capacity)throw new IllegalArgumentException("LOCAL_GATE");request=new byte[length];request[0]=(byte)0xfc;request[1]=(byte)(length>>>8);request[2]=(byte)length;request[3]=1;request[5]=1;request[7]=1;request[8]=(byte)packetId;request[9]=0x10;System.arraycopy(service,0,request,10,service.length);request[11+service.length]=1;System.arraycopy(payload,0,request,12+service.length,payload.length);int crc=crc(request,1,length-4);request[length-3]=(byte)(crc>>>8);request[length-2]=(byte)crc;request[length-1]=(byte)0xcf;
        }catch(org.json.JSONException error){throw new IllegalArgumentException("LOCAL_GATE",error);}
    }
    boolean isRunning(){return state==State.WAIT_TX_CALLBACK||state==State.WAIT_RESPONSE;}
    void start(){
        if(state!=State.IDLE)return;state=State.WAIT_TX_CALLBACK;txStartedElapsed=SystemClock.elapsedRealtime();handler.postDelayed(txDeadline,3000);
        boolean accepted;
        try{accepted=transport.write(request);}catch(RuntimeException error){cancel("TX_EXCEPTION");return;}
        if(!accepted)cancel("TX_REJECTED");
    }
    void onWritten(int status){if(state!=State.WAIT_TX_CALLBACK){if(isRunning())cancel("UNEXPECTED_TX_CALLBACK");return;}if(SystemClock.elapsedRealtime()-txStartedElapsed>=3000){cancel("TX_CALLBACK_TIMEOUT");return;}handler.removeCallbacks(txDeadline);if(status!=0){cancel("TX_CALLBACK_FAILED");return;}if(earlyResponse)finish(true,"Команда принята. Проверьте время на часах");else{state=State.WAIT_RESPONSE;responseStartedElapsed=SystemClock.elapsedRealtime();handler.postDelayed(responseDeadline,2000);}}
    void onChunk(byte[] bytes){
        if(!isRunning()||bytes==null||bytes.length==0)return;
        long elapsed=SystemClock.elapsedRealtime();if(state==State.WAIT_TX_CALLBACK&&elapsed-txStartedElapsed>=3000||state==State.WAIT_RESPONSE&&elapsed-responseStartedElapsed>=2000){cancel("RESPONSE_OR_TX_TIMEOUT");return;}
        if(incoming.size()+bytes.length>2074){cancel("RESPONSE_TOO_LARGE");return;}incoming.write(bytes,0,bytes.length);byte[] all=incoming.toByteArray();int offset=0;
        while(isRunning()&&all.length-offset>=3){int length=((all[offset+1]&255)<<8)|(all[offset+2]&255);if((all[offset]&255)!=0xfc||length<13||length>1037){cancel("BAD_MCF_LENGTH");return;}if(all.length-offset<length)break;byte[] frame=Arrays.copyOfRange(all,offset,offset+length);offset+=length;
            if((frame[length-1]&255)!=0xcf||crc(frame,1,length-4)!=(((frame[length-3]&255)<<8)|(frame[length-2]&255))){cancel("BAD_MCF_CRC");return;}
            int flags=frame[9]&255;boolean request=flags==0x10;
            if(frame[4]!=0||frame[5]!=1||frame[6]!=1||frame[7]!=0||(!request&&flags!=0x40)){cancel("BAD_MCF_ENVELOPE");return;}
            if(request||(frame[8]&255)!=packetId)continue;
            if(length==13){cancel("EMPTY_RESPONSE");return;}
            if(state==State.WAIT_TX_CALLBACK)earlyResponse=true;else finish(true,"Команда принята. Проверьте время на часах");
        }
        incoming.reset();if(isRunning()&&offset<all.length)incoming.write(all,offset,all.length-offset);
    }
    void cancel(String detail){if(isRunning())finish(false,"SESSION_STOPPED: "+detail);}
    private void finish(boolean success,String detail){handler.removeCallbacks(txDeadline);handler.removeCallbacks(responseDeadline);state=success?State.ACKNOWLEDGED:State.FAILED;incoming.reset();transport.finished(success,detail);}
    private static int crc(byte[] bytes,int offset,int count){int crc=0xffff;for(int i=offset;i<offset+count;i++){crc^=bytes[i]&255;for(int bit=0;bit<8;bit++)crc=(crc&1)!=0?(crc>>>1)^0xa001:crc>>>1;}return crc&0xffff;}
}
