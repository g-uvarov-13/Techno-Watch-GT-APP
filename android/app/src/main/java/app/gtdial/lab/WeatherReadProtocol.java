package app.gtdial.lab;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/** One bounded, read-only system-storage weather request for an already-ready GT01 session. */
final class WeatherReadProtocol {
    static final int MAX_RESPONSE_BYTES = 8192;
    private static final long TIMEOUT_MS = 3000L;
    private static final String SERVICE = "svc_systemstorage_get";
    private static final String[] KEYS = {"todayWeather", "unitTemperature", "weatherEnable", "futureDayWeather"};

    interface Transport { boolean write(byte[] frame); void log(String message); void finished(boolean success, Result result); }

    static final class Result {
        final String todayWeather;
        final String unitTemperature;
        final String weatherEnable;
        final String futureDayWeather;
        final long receivedAt;
        final String deviceIdentity;
        final String sha256;
        Result(String todayWeather, String unitTemperature, String weatherEnable, long receivedAt, String deviceIdentity, String sha256) {
            this(todayWeather,unitTemperature,weatherEnable,"",receivedAt,deviceIdentity,sha256);
        }
        Result(String todayWeather,String unitTemperature,String weatherEnable,String futureDayWeather,long receivedAt,String deviceIdentity,String sha256){
            this.todayWeather=todayWeather; this.unitTemperature=unitTemperature; this.weatherEnable=weatherEnable;
            this.futureDayWeather=futureDayWeather;
            this.receivedAt=receivedAt; this.deviceIdentity=deviceIdentity; this.sha256=sha256;
        }
    }

    private final Context context;
    private final String deviceIdentity;
    private final Transport transport;
    private final Handler handler;
    private final ByteArrayOutputStream received=new ByteArrayOutputStream();
    private final Runnable timeout=()->fail("timeout");
    private boolean running;
    private int expectedPacketId=1;
    private int frameId=1;

    WeatherReadProtocol(Context context, String deviceIdentity, Transport transport) {
        this.context=context.getApplicationContext(); this.deviceIdentity=deviceIdentity; this.transport=transport;
        handler=new Handler(Looper.myLooper()==null ? Looper.getMainLooper() : Looper.myLooper());
    }
    boolean isRunning(){ return running; }
    void start() { start(1); }
    void start(int reservedId) {
        if(reservedId<0||reservedId>255)throw new IllegalArgumentException("Weather request id must be 0..255");
        if(running) return;
        running=true; received.reset(); expectedPacketId=reservedId&255; frameId=1;
        try {
            JSONObject request=new JSONObject().put("keys",new JSONArray().put(KEYS[0]).put(KEYS[1]).put(KEYS[2]).put(KEYS[3]));
            byte[] json=request.toString().getBytes(StandardCharsets.UTF_8);
            transport.log("WEATHER_READ TX exact JSON " + new String(json, StandardCharsets.UTF_8));
            byte[] frame=frame(service(SERVICE,json));
            if(!transport.write(frame)){ fail("Android rejected TX write"); return; }
            handler.postDelayed(timeout,TIMEOUT_MS);
        } catch(Exception e) { fail("request construction failed: "+e.getClass().getSimpleName()); }
    }
    void cancel(String reason){ fail(reason); }
    void onChunk(byte[] value) {
        if(!running || value==null || value.length==0) return;
        if(received.size()+value.length>MAX_RESPONSE_BYTES+256){ fail("response exceeds "+MAX_RESPONSE_BYTES+" B"); return; }
        received.write(value,0,value.length); parseFrames();
    }
    private void parseFrames(){
        byte[] data=received.toByteArray(); int offset=0;
        while(running && data.length-offset>=3){
            if((data[offset]&255)!=0xFC){ fail("malformed MCF prefix"); return; }
            int length=((data[offset+1]&255)<<8)|(data[offset+2]&255);
            if(length<13 || length>MAX_RESPONSE_BYTES+13){ fail("malformed MCF length"); return; }
            if(data.length-offset<length) break;
            byte[] frame=Arrays.copyOfRange(data,offset,offset+length); offset+=length; handleFrame(frame);
        }
        received.reset(); if(running && offset<data.length) received.write(data,offset,data.length-offset);
    }
    private void handleFrame(byte[] frame){
        if(!running) return;
        if((frame[frame.length-1]&255)!=0xCF){ fail("malformed MCF terminator"); return; }
        int crc=((frame[frame.length-3]&255)<<8)|(frame[frame.length-2]&255);
        if(crc!=crc16(frame,1,frame.length-4)){ fail("malformed MCF CRC"); return; }
        if(frame[5]!=1){ fail("malformed MCF transport"); return; }
        int source=frame[6]&255, destination=frame[7]&255, id=frame[8]&255, type=(frame[9]&255)>>>6;
        if(type==0 || id!=expectedPacketId){ transport.log("WEATHER_READ unrelated or late frame ignored id="+id); return; }
        if(type!=1 || source!=1 || destination!=0){ fail("malformed weather response envelope"); return; }
        byte[] payload=Arrays.copyOfRange(frame,10,frame.length-3);
        if(payload.length>MAX_RESPONSE_BYTES){ fail("response exceeds "+MAX_RESPONSE_BYTES+" B"); return; }
        try { complete(parseAndPersist(payload)); } catch(Exception e) { fail("weather response rejected: "+e.getMessage()); }
    }
    private Result parseAndPersist(byte[] payload) throws Exception {
        String raw=new String(payload,StandardCharsets.UTF_8);
        if(!Arrays.equals(payload,raw.getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException("invalid UTF-8");
        JSONObject object=new JSONObject(raw);
        if(object.length()!=4)throw new IllegalArgumentException("expected exactly four weather keys");
        if(object.has("error") && !object.isNull("error")) throw new IllegalArgumentException("error response");
        Object[] values=new Object[KEYS.length];
        for(int i=0;i<KEYS.length;i++){
            String key=KEYS[i]; if(!object.has(key)||object.isNull(key)) throw new IllegalArgumentException("missing "+key);
            Object value=object.get(key);
            if(value==JSONObject.NULL || i!=3&&value instanceof JSONArray || value instanceof String && ((String)value).contains("key was get value failed")) throw new IllegalArgumentException("invalid "+key);
            if(i==0 && !(value instanceof JSONObject || (value instanceof Integer || value instanceof Long) && ((Number)value).longValue()==0)) throw new IllegalArgumentException("invalid todayWeather");
            // The captured disabled response uses integer 0.  This read-only path records it
            // verbatim; write eligibility is deliberately decided by WeatherSessionProtocol.
            if(i>0&&i<3 && !(value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException("invalid integer scalar "+key);
            if(i==3)WeatherSessionProtocol.requireKnownForecast(value);
            values[i]=value;
        }
        long now=System.currentTimeMillis(); String sha=sha256(payload);
        JSONObject state=new JSONObject().put("deviceIdentity",deviceIdentity).put("receivedAt",now).put("sha256",sha)
                .put("rawResponseBase64",Base64.encodeToString(payload,Base64.NO_WRAP))
                .put("todayWeather",values[0]).put("unitTemperature",values[1]).put("weatherEnable",values[2]).put("futureDayWeather",values[3]);
        AtomicFile destination=new AtomicFile(new File(context.getFilesDir(),"weather-read-state.json"));
        FileOutputStream stream=null;
        byte[] serialized=state.toString().getBytes(StandardCharsets.UTF_8);
        try { stream=destination.startWrite(); stream.write(serialized); stream.flush(); destination.finishWrite(stream); }
        catch(Exception e){ if(stream!=null) destination.failWrite(stream); throw e; }
        try(java.io.FileInputStream input=destination.openRead();ByteArrayOutputStream copy=new ByteArrayOutputStream()){byte[] buffer=new byte[2048];for(int n;(n=input.read(buffer))!=-1;){if(copy.size()+n>serialized.length)throw new IllegalArgumentException("weather read evidence size");copy.write(buffer,0,n);}if(!Arrays.equals(serialized,copy.toByteArray()))throw new IllegalArgumentException("weather read evidence durable mismatch");}
        return new Result(values[0].toString(),values[1].toString(),values[2].toString(),values[3].toString(),now,deviceIdentity,sha);
    }
    private void complete(Result result){ if(!running)return; running=false; handler.removeCallbacks(timeout); transport.log("WEATHER_READ COMPLETE raw="+result.sha256); transport.finished(true,result); }
    private void fail(String reason){ if(!running)return; running=false; handler.removeCallbacks(timeout); transport.log("WEATHER_READ FAILED: "+reason); transport.finished(false,null); }
    private byte[] service(String name,byte[] input){ byte[] nameBytes=name.getBytes(StandardCharsets.UTF_8); byte[] out=new byte[nameBytes.length+2+input.length]; System.arraycopy(nameBytes,0,out,0,nameBytes.length); out[nameBytes.length]=0; out[nameBytes.length+1]=1; System.arraycopy(input,0,out,nameBytes.length+2,input.length); return out; }
    private byte[] frame(byte[] rpc){ byte[] d2d=new byte[5+rpc.length]; d2d[0]=1; d2d[1]=0; d2d[2]=1; d2d[3]=(byte)expectedPacketId; d2d[4]=0x10; System.arraycopy(rpc,0,d2d,5,rpc.length); int length=5+d2d.length+3; byte[] out=new byte[length]; out[0]=(byte)0xFC; out[1]=(byte)(length>>>8); out[2]=(byte)length; out[3]=(byte)frameId++; System.arraycopy(d2d,0,out,5,d2d.length); int crc=crc16(out,1,4+d2d.length); out[length-3]=(byte)(crc>>>8); out[length-2]=(byte)crc; out[length-1]=(byte)0xCF; return out; }
    private static int crc16(byte[] value,int offset,int length){ int crc=0xFFFF; for(int i=offset;i<offset+length;i++){ crc^=value[i]&255; for(int bit=0;bit<8;bit++)crc=(crc&1)!=0?(crc>>>1)^0xA001:crc>>>1; } return crc&0xFFFF; }
    private static String sha256(byte[] value) throws Exception { StringBuilder out=new StringBuilder(64); for(byte b:MessageDigest.getInstance("SHA-256").digest(value)) out.append(String.format(Locale.US,"%02x",b&255)); return out.toString(); }
}
