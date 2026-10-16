package app.gtdial.lab;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/** One outstanding, fail-stop notification transaction on the proven GT01 MCF transport. */
final class NotificationSessionProtocol {
    interface Scheduler { void schedule(Runnable action,long delayMs); void cancel(Runnable action); }
    private enum Step { IDLE, READ_BEFORE, SET, READ_AFTER, PUSH, FAILED }
    private static final byte[] READ="{\"keys\":[\"notificationEnable\"]}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ENABLE="{\"values\":{\"notificationEnable\":1}}".getBytes(StandardCharsets.UTF_8);
    private final Predicate<byte[]> write;
    private final IntPredicate backup;
    private final Consumer<String> log;
    private final Consumer<Boolean> terminal;
    private final Scheduler scheduler;
    private final int maxFrameBytes;
    private final ByteArrayOutputStream incoming=new ByteArrayOutputStream();
    private final Runnable timeout=()->fail("timeout",true);
    private Step step=Step.IDLE;
    private NotificationRequest notice;
    private boolean configured,recoverablePushFailure;
    private int packetId=1,frameId=1,expectedId=-1;
    private int priorValue=-1;

    NotificationSessionProtocol(Predicate<byte[]> write,IntPredicate backup,Consumer<String> log,Consumer<Boolean> terminal,Scheduler scheduler,int maxFrameBytes){
        if(maxFrameBytes<23||maxFrameBytes>512)throw new IllegalArgumentException("frame limit");
        this.write=write;this.backup=backup;this.log=log;this.terminal=terminal;this.scheduler=scheduler;this.maxFrameBytes=maxFrameBytes;
    }
    boolean isRunning(){return step!=Step.IDLE&&step!=Step.FAILED;}
    boolean isFailed(){return step==Step.FAILED;}
    boolean canRecoverPushTransport(){return step==Step.PUSH&&configured||step==Step.FAILED&&recoverablePushFailure;}
    int priorValue(){return priorValue;}
    byte[] start(NotificationRequest request){
        if(step!=Step.IDLE||request==null)throw new IllegalStateException("notification unavailable");
        notice=request;
        if(configured){step=Step.PUSH;return send("ffi",NotificationProtocol.request(notice),10_000L);}
        step=Step.READ_BEFORE;return send("svc_systemstorage_get",READ,3_000L);
    }
    void cancel(String reason){if(isRunning())fail(reason);}
    void onChunk(byte[] bytes){
        if(!isRunning()||bytes==null||bytes.length==0)return;
        if(incoming.size()+bytes.length>8192){fail("response too large");return;}
        incoming.write(bytes,0,bytes.length);
        byte[] data=incoming.toByteArray();int offset=0;
        while(isRunning()&&data.length-offset>=3){
            if((data[offset]&255)!=0xfc){fail("invalid frame prefix");return;}
            int length=((data[offset+1]&255)<<8)|(data[offset+2]&255);
            if(length<13||length>8192){fail("invalid frame length");return;}
            if(data.length-offset<length)break;
            handle(Arrays.copyOfRange(data,offset,offset+length));offset+=length;
        }
        incoming.reset();if(isRunning()&&offset<data.length)incoming.write(data,offset,data.length-offset);
    }
    private void handle(byte[] frame){
        if(frame[frame.length-1]!=(byte)0xcf||crc(frame,1,frame.length-4)!=(((frame[frame.length-3]&255)<<8)|(frame[frame.length-2]&255))){fail("invalid frame CRC");return;}
        if(frame[5]!=1){fail("unexpected transport");return;}
        int type=(frame[9]&255)>>>6,id=frame[8]&255;
        if(type==0)return; // Independent watch-originated requests are not responses.
        if(id!=expectedId)return; // Late response from a completed request.
        if(type!=1||frame[6]!=1||frame[7]!=0){fail("unexpected response address/type");return;}
        scheduler.cancel(timeout);
        byte[] payload=Arrays.copyOfRange(frame,10,frame.length-3);
        try{
            switch(step){
                case READ_BEFORE -> {
                    priorValue=parseSetting(payload);
                    if(priorValue==1){configured=true;step=Step.PUSH;send("ffi",NotificationProtocol.request(notice),10_000L);}
                    else {
                        if(!backup.test(priorValue))throw new IllegalArgumentException("prior setting backup failed");
                        step=Step.SET;send("svc_systemstorage_set",ENABLE,3_000L);
                    }
                }
                case SET -> {step=Step.READ_AFTER;send("svc_systemstorage_get",READ,3_000L);}
                case READ_AFTER -> {
                    if(parseSetting(payload)!=1)throw new IllegalArgumentException("setting readback mismatch");
                    configured=true;step=Step.PUSH;send("ffi",NotificationProtocol.request(notice),10_000L);
                }
                case PUSH -> {step=Step.IDLE;notice=null;terminal.accept(true);}
                default -> throw new IllegalArgumentException("unexpected response state");
            }
        }catch(Exception error){fail("invalid response: "+error.getMessage());}
    }
    private static int parseSetting(byte[] payload) throws Exception {
        if(payload.length==0||payload.length>1024)throw new IllegalArgumentException("setting response size");
        String decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(payload)).toString();
        JSONObject object=new JSONObject(decoded);
        Object value=object.opt("notificationEnable");
        if(!(value instanceof Integer||value instanceof Long)||!(((Number)value).intValue()==0||((Number)value).intValue()==1))throw new IllegalArgumentException("notificationEnable value");
        return ((Number)value).intValue();
    }
    private byte[] send(String service,byte[] payload,long timeoutMs){
        byte[] name=service.getBytes(StandardCharsets.UTF_8),rpc=new byte[name.length+2+payload.length];
        System.arraycopy(name,0,rpc,0,name.length);rpc[name.length+1]=1;System.arraycopy(payload,0,rpc,name.length+2,payload.length);
        expectedId=packetId&255;packetId=(packetId+1)&255;
        byte[] d2d=new byte[5+rpc.length];d2d[0]=1;d2d[2]=1;d2d[3]=(byte)expectedId;d2d[4]=0x10;System.arraycopy(rpc,0,d2d,5,rpc.length);
        int length=d2d.length+8;byte[] frame=new byte[length];frame[0]=(byte)0xfc;frame[1]=(byte)(length>>>8);frame[2]=(byte)length;frame[3]=(byte)(frameId++);System.arraycopy(d2d,0,frame,5,d2d.length);int crc=crc(frame,1,length-4);frame[length-3]=(byte)(crc>>>8);frame[length-2]=(byte)crc;frame[length-1]=(byte)0xcf;
        if(length>maxFrameBytes){fail("notification frame exceeds MTU");return frame;}
        if(!write.test(frame))fail("Android rejected notification TX",true);else scheduler.schedule(timeout,timeoutMs);
        return frame;
    }
    private void fail(String reason){fail(reason,false);}
    private void fail(String reason,boolean transportFailure){
        if(step==Step.FAILED)return;
        recoverablePushFailure=transportFailure&&step==Step.PUSH&&configured;
        scheduler.cancel(timeout);step=Step.FAILED;notice=null;configured=false;incoming.reset();
        log.accept("NOTIFICATION stopped: "+reason+"; no automatic retry; prior notificationEnable="+priorValue);
        terminal.accept(false);
    }
    private static int crc(byte[] bytes,int offset,int count){int value=0xffff;for(int i=offset;i<offset+count;i++){value^=bytes[i]&255;for(int bit=0;bit<8;bit++)value=(value&1)!=0?(value>>>1)^0xa001:value>>>1;}return value&0xffff;}
}
