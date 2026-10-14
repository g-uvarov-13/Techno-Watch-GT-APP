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

/** A single user-initiated GT01 weather write, with durable backup and exact readback. */
final class WeatherSessionProtocol {
    interface Transport { boolean write(byte[] frame); void log(String message); void finished(boolean success,String detail);default int nextPacketId(){throw new IllegalStateException("shared packet IDs unavailable");}default int fullFrameLimit(){return 2048;} }
    private enum Step { IDLE, READ_PRIOR, ENABLE, ENABLE_READBACK, SET, READBACK, FORECAST_SET, FORECAST_READBACK, RESTORE_CHECK, RESTORE_SET, RESTORE_READBACK, FAILED, DONE }
    private static final String[] KEYS={"todayWeather","unitTemperature","weatherEnable"};
    private static final String[] WEATHER_KEYS={"pm25","city","currentTemperature","aqi","updateTime","describe","maxTemperature","minTemperature","humidity","ultraviolet","unit"};
    private static final String[] FORECAST_KEYS={"describe","maxTemperature","minTemperature","timestamp","moonPhase"};
    private static final long TIMEOUT_MS=3000L;
    private final Context context; private final Transport transport; private final Handler handler;
    private final String deviceIdentity; private final int maxFrameBytes;
    private final WeatherSnapshot snapshot; private final String city; private final boolean restore;
    private final ByteArrayOutputStream incoming=new ByteArrayOutputStream(); private final Runnable timeout=()->fail("timeout");
    private Step step=Step.IDLE; private int packetId=1,frameId=1,expectedId=-1; private JSONObject prior,candidate; private byte[] lastRawResponse; private boolean writeAttempted,safeOutcome,full,awaitingFrame; private JSONObject intermediate,todayIntermediate;
    private WeatherDeliveryController.Hold experimentHold;
    private WeatherDeliveryController.WeeklyStatus weeklyOwner;
    void weeklyOwner(WeatherDeliveryController.WeeklyStatus owner){if(step!=Step.IDLE)throw new IllegalStateException("weekly owner late");weeklyOwner=owner;}
    WeatherSessionProtocol(Context context,String deviceIdentity,int maxFrameBytes,WeatherSnapshot snapshot,String city,boolean restore,Transport transport){this(context,deviceIdentity,maxFrameBytes,snapshot,city,restore,false,transport);}
    WeatherSessionProtocol(Context context,String deviceIdentity,int maxFrameBytes,WeatherSnapshot snapshot,String city,boolean restore,boolean includeForecast,Transport transport){this(context,deviceIdentity,maxFrameBytes,snapshot,city,restore,includeForecast,null,transport);}
    WeatherSessionProtocol(Context context,String deviceIdentity,int maxFrameBytes,WeatherSnapshot snapshot,String city,boolean restore,boolean includeForecast,WeatherDeliveryController.Hold claimedHold,Transport transport){this.context=context.getApplicationContext();this.deviceIdentity=deviceIdentity;this.maxFrameBytes=maxFrameBytes;this.snapshot=snapshot;this.city=city;this.restore=restore;this.full=includeForecast;this.experimentHold=claimedHold;this.transport=transport;handler=new Handler(Looper.myLooper()==null?Looper.getMainLooper():Looper.myLooper());}
    boolean fullMode(){return full;}
    boolean allowFrameChunk(){if(!isRunning())return false;try{requireExperimentHold();}catch(Exception e){refuse(e.getMessage());return false;}if(full&&!restore&&!new WeatherPayload(snapshot).readyForWeekly()){refuse("forecast expired/date or zone changed before chunk TX");return false;}return true;}
    boolean safeOutcome(){return safeOutcome;}
    boolean isRunning(){return step!=Step.IDLE&&step!=Step.FAILED&&step!=Step.DONE;}
    void start(){if(step!=Step.IDLE)throw new IllegalStateException("weather unavailable");try{if(restore){WeatherDeliveryController.WeeklyStatus weekly=WeatherDeliveryController.weeklyStatus(context);if(weekly.enabled&&!WeatherDeliveryController.pauseWeekly(context,weekly.owner,"EXPLICIT_RESTORE"))throw new IllegalArgumentException("weekly pause persistence failed");loadBackup();preflightRequests();step=Step.RESTORE_CHECK;sendGet();}else{requireExperimentHold();WeatherPayload payload=new WeatherPayload(snapshot);if(!payload.readyForBle()||full&&!payload.readyForWeekly())throw new IllegalArgumentException("weather or forecast unavailable/expired");candidate=triple(payload.create(city),1,1);if(full)candidate.put("futureDayWeather",payload.createForecast());intermediate=triple(0,1,1);checkSize("svc_systemstorage_get",getPayload());step=Step.READ_PRIOR;sendGet();}}catch(Exception e){refuse("preflight: "+errorDetail(e));}}
    private void requireExperimentHold(){if(weeklyOwner!=null){WeatherDeliveryController.WeeklyStatus state=WeatherDeliveryController.weeklyStatus(context);if(!"IN_FLIGHT".equals(state.status)||!weeklyOwner.attempt.equals(state.attempt)||!weeklyOwner.owner.same(state.owner))throw new IllegalArgumentException("WEEKLY_HOLD claim no longer active");}WeatherDeliveryController.Hold active=WeatherDeliveryController.hold(context);if(full){if(experimentHold==null||!experimentHold.valid()||!deviceIdentity.equals(experimentHold.device)||!experimentHold.same(active))throw new IllegalArgumentException("FULL_EXPERIMENT_HOLD owner changed or unavailable");}else if(active!=null||WeatherDeliveryController.weeklyStatus(context).enabled)throw new IllegalArgumentException("FULL_EXPERIMENT_HOLD blocks ordinary weather writes");}
    void cancel(String reason){fail(reason);}
    void onChunk(byte[] bytes){if(!isRunning()||bytes==null||bytes.length==0)return;if(incoming.size()+bytes.length>8448){fail("response too large");return;}incoming.write(bytes,0,bytes.length);if(!awaitingFrame)parseIncoming();}
    private void parseIncoming(){byte[] all=incoming.toByteArray();int offset=0;while(isRunning()&&!awaitingFrame&&all.length-offset>=3){if((all[offset]&255)!=0xfc){fail("invalid MCF prefix");return;}int n=((all[offset+1]&255)<<8)|(all[offset+2]&255);if(n<13||n>8448){fail("invalid MCF length");return;}if(all.length-offset<n)break;handle(Arrays.copyOfRange(all,offset,offset+n));offset+=n;}incoming.reset();if(isRunning()&&offset<all.length)incoming.write(all,offset,all.length-offset);}
    void onFrameWritten(){if(!isRunning())return;if(!full||!awaitingFrame){fail("unexpected completed weather frame");return;}awaitingFrame=false;handler.postDelayed(timeout,TIMEOUT_MS);parseIncoming();}
    private void handle(byte[] frame){if((frame[frame.length-1]&255)!=0xcf||crc(frame,1,frame.length-4)!=(((frame[frame.length-3]&255)<<8)|(frame[frame.length-2]&255))){fail("invalid MCF CRC");return;}if(frame[5]!=1){fail("unexpected transport");return;}int type=(frame[9]&255)>>>6,id=frame[8]&255;if(type==0||id!=expectedId){transport.log("WEATHER late/unrelated frame ignored id="+id);return;}if(type!=1||frame[4]!=0||frame[6]!=1||frame[7]!=0||(frame[9]&255)!=0x40){fail("unexpected response envelope reserved="+(frame[4]&255)+" source="+(frame[6]&255)+" destination="+(frame[7]&255)+" flags="+(frame[9]&255));return;}handler.removeCallbacks(timeout);try{
        // The official system-storage setter discards the response body. A correlated
        // response only advances to a fresh read; it does not prove storage.
        if(step==Step.ENABLE){step=Step.ENABLE_READBACK;sendGet();return;}
        if(step==Step.SET){step=Step.READBACK;sendGet();return;}
        if(step==Step.FORECAST_SET){step=Step.FORECAST_READBACK;sendGet();return;}
        if(step==Step.RESTORE_SET){step=Step.RESTORE_READBACK;sendGet();return;}
        byte[] getRaw=Arrays.copyOfRange(frame,10,frame.length-3);if(writeAttempted||full&&step==Step.RESTORE_CHECK)persistReadback(getRaw);
        JSONObject values=parseValues(getRaw);
        switch(step){
            case READ_PRIOR->{
                // A well-formed but unsupported prior state is a local refusal before writes.
                try{requireWritablePrior(values);if(!new WeatherPayload(snapshot).readyForBle()||full&&!new WeatherPayload(snapshot).readyForWeekly())throw new IllegalArgumentException("snapshot expired/date or zone changed before SET");if(weeklyOwner!=null){WeatherDeliveryController.WeeklyStatus state=WeatherDeliveryController.weeklyStatus(context);if(!"IN_FLIGHT".equals(state.status)||!weeklyOwner.attempt.equals(state.attempt)||!weeklyOwner.owner.same(state.owner))throw new IllegalArgumentException("weekly claim changed");JSONObject baseline=weeklyOwner.baseline();if(baseline!=null&&!sameTriple(baseline,values))throw new IllegalArgumentException("weekly baseline differs from current watch state");}prior=copyState(values);if(!isZero(prior.get("todayWeather")))intermediate=null;if(full){if(intermediate!=null)intermediate.put("futureDayWeather",prior.get("futureDayWeather"));todayIntermediate=triple(candidate.get("todayWeather"),1,1).put("futureDayWeather",prior.get("futureDayWeather"));}preflightRequests();persistBackup(values,prior,candidate);}
                catch(Exception local){refuse("prewrite: "+errorDetail(local));return;}
                if(isZero(prior.get("todayWeather"))){step=Step.ENABLE;sendSetValues(new JSONObject().put("weatherEnable",1));}
                else{step=Step.SET;sendSetValues(new JSONObject().put("todayWeather",candidate.get("todayWeather")));}
            }
            case ENABLE_READBACK->{if(!sameTriple(intermediate,values))throw new IllegalArgumentException("initialization readback mismatch");step=Step.SET;sendSetValues(new JSONObject().put("todayWeather",candidate.get("todayWeather")));}
            case READBACK->{if(!sameTriple(full?todayIntermediate:candidate,values))throw new IllegalArgumentException("candidate today readback mismatch");if(full){step=Step.FORECAST_SET;sendSetValues(new JSONObject().put("futureDayWeather",candidate.get("futureDayWeather")));}else succeed("weather stored and all eleven fields read back");}
            case FORECAST_READBACK->{if(!sameTriple(candidate,values))throw new IllegalArgumentException("forecast readback mismatch");if(weeklyOwner!=null&&!WeatherDeliveryController.completeWeekly(context,weeklyOwner,values))throw new IllegalArgumentException("weekly verified baseline persistence failed");succeed("FULL_FORECAST: "+snapshot.forecast.size()+" days and today's eleven fields stored and read back");}
            case RESTORE_CHECK->{
                if(!sameTriple(candidate,values)&&(intermediate==null||!sameTriple(intermediate,values))&&(todayIntermediate==null||!sameTriple(todayIntermediate,values))){refuse("current state differs from recorded candidate/intermediate");return;}
                step=Step.RESTORE_SET;sendSetValues(prior);
            }
            case RESTORE_READBACK->{if(!sameTriple(prior,values))throw new IllegalArgumentException("prior readback mismatch");if(full&&!WeatherDeliveryController.releaseExactHold(context,experimentHold)){succeed("RESTORED_PAUSED: exact prior weather restored and read back; FULL_EXPERIMENT_HOLD release could not be saved");}else succeed("exact prior weather settings restored and read back");}
            default->fail("unexpected state");
        }
    }catch(Exception e){if(!writeAttempted&&(step==Step.READ_PRIOR||step==Step.RESTORE_CHECK))refuse("response rejected: "+errorDetail(e));else fail("response rejected: "+errorDetail(e));}}
    private JSONObject getPayload()throws Exception{JSONArray keys=new JSONArray().put(KEYS[0]).put(KEYS[1]).put(KEYS[2]);if(full)keys.put("futureDayWeather");return new JSONObject().put("keys",keys);}
    private JSONObject copyState(JSONObject values)throws Exception{JSONObject state=copyTriple(values);if(full)state.put("futureDayWeather",values.get("futureDayWeather"));return state;}
    // Match Python json.dumps(ensure_ascii=True): escape UTF-16 code units,
    // including each surrogate separately, without changing the city value.
    static byte[] encodeWeatherJson(JSONObject value){
        String json=value.toString();StringBuilder ascii=new StringBuilder(json.length());
        final String hex="0123456789abcdef";
        for(int i=0;i<json.length();i++){char c=json.charAt(i);if(c<0x80)ascii.append(c);else{ascii.append('\\').append('u');for(int shift=12;shift>=0;shift-=4)ascii.append(hex.charAt((c>>>shift)&15));}}
        return ascii.toString().getBytes(StandardCharsets.US_ASCII);
    }
    private void persistReadback(byte[] raw)throws Exception{
        if(raw.length>8448)throw new IllegalArgumentException("readback evidence size");
        JSONObject evidence=new JSONObject().put("deviceIdentity",deviceIdentity).put("readPhase",step.name()).put("receivedAt",System.currentTimeMillis()).put("rawResponseBase64",Base64.encodeToString(raw,Base64.NO_WRAP)).put("rawResponseSha256",sha(raw)).put("expectedState",step==Step.ENABLE_READBACK?intermediate:step==Step.RESTORE_READBACK?prior:full&&step==Step.READBACK?todayIntermediate:candidate).put("includesForecast",full);
        byte[] serialized=evidence.toString().getBytes(StandardCharsets.UTF_8);if(serialized.length>32*1024)throw new IllegalArgumentException("readback evidence size");
        AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"weather-last-readback.json"));FileOutputStream output=null;
        try{output=file.startWrite();output.write(serialized);output.flush();file.finishWrite(output);}catch(Exception e){if(output!=null)file.failWrite(output);throw e;}
        try(java.io.FileInputStream input=file.openRead();ByteArrayOutputStream read=new ByteArrayOutputStream()){byte[] chunk=new byte[2048];for(int n;(n=input.read(chunk))!=-1;){if(read.size()+n>serialized.length)throw new IllegalArgumentException("readback evidence read size");read.write(chunk,0,n);}if(!Arrays.equals(serialized,read.toByteArray()))throw new IllegalArgumentException("readback evidence durable mismatch");}
    }
    private void sendGet()throws Exception{send("svc_systemstorage_get",encodeWeatherJson(getPayload()));}
    private void sendSetValues(JSONObject values)throws Exception{if(full&&!restore&&!new WeatherPayload(snapshot).readyForWeekly())throw new IllegalArgumentException("forecast expired/date or zone changed before TX");writeAttempted=true;send("svc_systemstorage_set",encodeWeatherJson(new JSONObject().put("values",values)));}
    private int frameLimit(){return full?transport.fullFrameLimit():maxFrameBytes;}
    private void checkSize(String service,JSONObject payload)throws Exception{if(13+service.getBytes(StandardCharsets.UTF_8).length+2+encodeWeatherJson(payload).length>frameLimit())throw new IllegalArgumentException("request exceeds bounded BLE frame capacity");}
    private void preflightRequests()throws Exception{
        checkSize("svc_systemstorage_get",getPayload());
        checkSize("svc_systemstorage_set",new JSONObject().put("values",new JSONObject().put("weatherEnable",1)));
        checkSize("svc_systemstorage_set",new JSONObject().put("values",new JSONObject().put("todayWeather",candidate.get("todayWeather"))));
        if(full)checkSize("svc_systemstorage_set",new JSONObject().put("values",new JSONObject().put("futureDayWeather",candidate.get("futureDayWeather"))));
        checkSize("svc_systemstorage_set",new JSONObject().put("values",prior));
    }
    private void send(String service,byte[] payload){
        try{requireExperimentHold();}catch(Exception e){refuse(e.getMessage());return;}
        if(full&&!restore&&!new WeatherPayload(snapshot).readyForWeekly()){refuse("forecast expired/date or zone changed before TX");return;}
        byte[] s=service.getBytes(StandardCharsets.UTF_8),rpc=new byte[s.length+2+payload.length];System.arraycopy(s,0,rpc,0,s.length);rpc[s.length+1]=1;System.arraycopy(payload,0,rpc,s.length+2,payload.length);expectedId=full?transport.nextPacketId():packetId++&255;if(expectedId<0||expectedId>255){fail("invalid shared packet ID");return;}byte[] d=new byte[5+rpc.length];d[0]=1;d[2]=1;d[3]=(byte)expectedId;d[4]=0x10;System.arraycopy(rpc,0,d,5,rpc.length);int n=d.length+8;if(n>frameLimit()){refuse("request exceeds bounded BLE frame capacity");return;}byte[] f=new byte[n];f[0]=(byte)0xfc;f[1]=(byte)(n>>>8);f[2]=(byte)n;f[3]=(byte)(frameId++);System.arraycopy(d,0,f,5,d.length);int c=crc(f,1,n-4);f[n-3]=(byte)(c>>>8);f[n-2]=(byte)c;f[n-1]=(byte)0xcf;awaitingFrame=full;if(!transport.write(f))fail("Android rejected TX");else if(!full)handler.postDelayed(timeout,TIMEOUT_MS);
    }
    private JSONObject parseValues(byte[] raw)throws Exception{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString();lastRawResponse=raw.clone();JSONObject v=new JSONObject(text);int missing=0,known=0;for(String key:KEYS){if(v.has(key))known++;if(!v.has(key)||v.isNull(key))missing++;}transport.log("WEATHER GET fields="+v.length()+" missing="+missing+" extra="+(v.length()-known)+" unitTemperature="+diagnostic(v.opt("unitTemperature"))+" weatherEnable="+diagnostic(v.opt("weatherEnable")));for(String k:KEYS)if(!v.has(k)||v.isNull(k))throw new IllegalArgumentException("missing "+k);if(full){if(v.length()!=4||!v.has("futureDayWeather")||v.isNull("futureDayWeather"))throw new IllegalArgumentException("full weather GET requires exactly four keys");requireKnownForecast(v.get("futureDayWeather"));}return v;}
    static void requireKnownTodayWeather(JSONObject o)throws Exception{if(o==null||o.length()!=WEATHER_KEYS.length)throw new IllegalArgumentException("TodayWeather field count expected="+WEATHER_KEYS.length+" actual="+(o==null?-1:o.length())+" missing="+(o==null?WEATHER_KEYS.length:missingFields(o))+" extra="+(o==null?0:extraFields(o)));for(String k:WEATHER_KEYS){if(!o.has(k)||o.isNull(k))throw new IllegalArgumentException("missing "+k);Object v=o.get(k);if("city".equals(k)){if(!(v instanceof String)||((String)v).isEmpty())throw new IllegalArgumentException("city type");}else if(!(v instanceof Integer||v instanceof Long)||((Number)v).longValue()<Integer.MIN_VALUE||((Number)v).longValue()>Integer.MAX_VALUE)throw new IllegalArgumentException(k+" must be int");}}
    private static boolean isZero(Object value){return value instanceof Integer||value instanceof Long?((Number)value).longValue()==0:false;}
    private static void requireInt(JSONObject v,String key,int wanted)throws Exception{Object value=v.get(key);if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()!=wanted)throw new IllegalArgumentException(key+" must be integer "+wanted);}
    private static void requireWritablePrior(JSONObject values)throws Exception{
        requireInt(values,"unitTemperature",1);Object today=values.get("todayWeather");
        if(isZero(today)){requireInt(values,"weatherEnable",0);return;}
        if(!(today instanceof JSONObject))throw new IllegalArgumentException("unsupported todayWeather state");
        requireKnownTodayWeather((JSONObject)today);requireInt(values,"weatherEnable",1);
    }
    static void requireKnownForecast(Object value)throws Exception{
        if(isZero(value))return;if(!(value instanceof JSONArray))throw new IllegalArgumentException("unsupported futureDayWeather state");JSONArray days=(JSONArray)value;if(days.length()>7)throw new IllegalArgumentException("forecast prior day count");
        for(int i=0;i<days.length();i++){JSONObject day=days.getJSONObject(i);if(day.length()!=5)throw new IllegalArgumentException("forecast field count");for(String key:FORECAST_KEYS){Object field=day.get(key);if(!(field instanceof Integer||field instanceof Long)||((Number)field).longValue()<Integer.MIN_VALUE||((Number)field).longValue()>Integer.MAX_VALUE)throw new IllegalArgumentException("forecast "+key+" must be int32");}
            int code=day.getInt("describe");if(!java.util.Arrays.asList(0,53,2,1,181,3,62,7,141,14,61,6,19,42,43,44,35,99).contains(code))throw new IllegalArgumentException("unknown QC forecast describe");
            int low=day.getInt("minTemperature"),high=day.getInt("maxTemperature"),moon=day.getInt("moonPhase");if(low<-90||high>60||low>high||day.getInt("timestamp")<=0||moon<0||moon>8)throw new IllegalArgumentException("forecast prior bounds");
        }
    }
    private static JSONObject triple(Object today,int unit,int enabled)throws Exception{return new JSONObject().put("todayWeather",today).put("unitTemperature",unit).put("weatherEnable",enabled);}
    private static JSONObject copyTriple(JSONObject values)throws Exception{return new JSONObject().put("todayWeather",values.get("todayWeather")).put("unitTemperature",values.get("unitTemperature")).put("weatherEnable",values.get("weatherEnable"));}
    private boolean sameTriple(JSONObject a,JSONObject b)throws Exception{
        if(full){requireFourKeyState(a);requireFourKeyState(b);}
        boolean matches=true;
        for(String key:new String[]{"unitTemperature","weatherEnable"}){Object x=a.get(key),y=b.get(key);if(!(x instanceof Integer||x instanceof Long)||!(y instanceof Integer||y instanceof Long)||((Number)x).longValue()!=((Number)y).longValue()){mismatch(key,x,y);matches=false;}}
        Object x=a.get("todayWeather"),y=b.get("todayWeather");
        if(isZero(x)){if(!isZero(y)){mismatch("todayWeather",x,y);matches=false;}}
        else if(!(x instanceof JSONObject)||!(y instanceof JSONObject)){mismatch("todayWeather",x,y);matches=false;}
        else if(!same((JSONObject)x,(JSONObject)y))matches=false;
        if(full){Object expected=a.get("futureDayWeather"),actual=b.get("futureDayWeather");requireKnownForecast(expected);requireKnownForecast(actual);if(!sameForecast(expected,actual)){mismatch("futureDayWeather",expected,actual);matches=false;}}
        return matches;
    }
    static void validateFourValues(JSONObject state)throws Exception{requireFourKeyState(state);requireWritablePrior(state);}
    private static void requireFourKeyState(JSONObject state)throws Exception{if(state==null||state.length()!=4)throw new IllegalArgumentException("weather state must contain exactly four keys");for(String key:KEYS)if(!state.has(key)||state.isNull(key))throw new IllegalArgumentException("weather state missing "+key);if(!state.has("futureDayWeather")||state.isNull("futureDayWeather"))throw new IllegalArgumentException("weather state missing futureDayWeather");requireKnownForecast(state.get("futureDayWeather"));}
    private static boolean sameForecast(Object expected,Object actual)throws Exception{if(isZero(expected))return isZero(actual);if(!(expected instanceof JSONArray)||!(actual instanceof JSONArray))return false;JSONArray a=(JSONArray)expected,b=(JSONArray)actual;if(a.length()!=b.length())return false;for(int i=0;i<a.length();i++)for(String key:FORECAST_KEYS)if(a.getJSONObject(i).getLong(key)!=b.getJSONObject(i).getLong(key))return false;return true;}
    private boolean same(JSONObject a,JSONObject b)throws Exception{
        boolean matches=true;
        transport.log("WEATHER compare fields expected="+a.length()+" actual="+b.length()+" missing="+missingFields(b)+" extra="+extraFields(b));
        for(String k:WEATHER_KEYS){Object x=a.opt(k),y=b.opt(k);if(x==null||y==null||!x.equals(y)){mismatch(k,x,y);matches=false;}}
        // Keep structural/type validation strict; diagnostics never convert invalid data.
        requireKnownTodayWeather(a);requireKnownTodayWeather(b);return matches;
    }
    private static int missingFields(JSONObject object){int count=0;for(String key:WEATHER_KEYS)if(!object.has(key)||object.isNull(key))count++;return count;}
    private static int extraFields(JSONObject object){int known=0;for(String key:WEATHER_KEYS)if(object.has(key))known++;return object.length()-known;}
    private void mismatch(String key,Object expected,Object actual)throws Exception{transport.log("WEATHER mismatch phase="+step.name()+" field="+key+" expected="+diagnostic(expected)+" actual="+diagnostic(actual));}
    private static String diagnostic(Object value)throws Exception{
        if(value==null||value==JSONObject.NULL)return "null";
        String type=value.getClass().getSimpleName();
        if(value instanceof String){String text=(String)value;int invalid=0,replacement=0;for(int i=0;i<text.length();i++){if(text.charAt(i)==0xffff)invalid++;if(text.charAt(i)==0xfffd)replacement++;}return type+" utf16Length="+text.length()+" codePoints="+text.codePointCount(0,text.length())+" UFFFF="+invalid+" UFFFD="+replacement+" sha256="+sha(text.getBytes(StandardCharsets.UTF_8));}
        if(value instanceof Number||value instanceof Boolean)return type+" value="+value;
        if(value instanceof JSONObject)return type+" fields="+((JSONObject)value).length();
        if(value instanceof JSONArray)return type+" items="+((JSONArray)value).length();
        return type;
    }
    private void persistBackup(JSONObject values,JSONObject prior,JSONObject candidate)throws Exception{synchronized(WeatherDeliveryController.class){requireExperimentHold();persistBackupFile(values,prior,candidate);}}
    private void persistBackupFile(JSONObject values,JSONObject prior,JSONObject candidate)throws Exception{if(lastRawResponse==null)throw new IllegalArgumentException("missing raw response");JSONObject state=new JSONObject().put("deviceIdentity",deviceIdentity).put("savedAt",System.currentTimeMillis()).put("rawResponseBase64",Base64.encodeToString(lastRawResponse,Base64.NO_WRAP)).put("rawResponseSha256",sha(lastRawResponse)).put("formatVersion",full?3:2).put("prior",prior).put("candidate",candidate).put("intermediate",intermediate==null?JSONObject.NULL:intermediate);if(full)state.put("todayIntermediate",todayIntermediate).put("forecastZoneId",snapshot.forecastZoneId).put("forecastBaseDate",snapshot.forecastBaseDate).put("fullExperimentClaim",experimentHold.claim);if(snapshot!=null)state.put("sourceWmo",snapshot.wmoCode);byte[] raw=state.toString().getBytes(StandardCharsets.UTF_8);String hash=sha(raw);state.put("sha256",hash);raw=state.toString().getBytes(StandardCharsets.UTF_8);if(raw.length>64*1024)throw new IllegalArgumentException("backup size");AtomicFile f=new AtomicFile(new File(context.getFilesDir(),"weather-write-backup.json"));FileOutputStream out=null;try{out=f.startWrite();out.write(raw);out.flush();f.finishWrite(out);}catch(Exception e){if(out!=null)f.failWrite(out);throw e;}byte[] verified=java.nio.file.Files.readAllBytes(f.getBaseFile().toPath());if(!Arrays.equals(raw,verified))throw new IllegalArgumentException("backup durable readback mismatch");JSONObject reread=new JSONObject(new String(verified,StandardCharsets.UTF_8));String stored=reread.getString("sha256");reread.remove("sha256");if(!sha(reread.toString().getBytes(StandardCharsets.UTF_8)).equals(stored)||!sha(Base64.decode(reread.getString("rawResponseBase64"),Base64.NO_WRAP)).equals(reread.getString("rawResponseSha256")))throw new IllegalArgumentException("backup hash verification failed");}
    private void loadBackup()throws Exception{File f=new File(context.getFilesDir(),"weather-write-backup.json");if(f.length()>64*1024)throw new IllegalArgumentException("backup size");JSONObject s=new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));String hash=s.getString("sha256");s.remove("sha256");if(!sha(s.toString().getBytes(StandardCharsets.UTF_8)).equals(hash))throw new IllegalArgumentException("backup hash verification failed");if(!deviceIdentity.equals(s.getString("deviceIdentity")))throw new IllegalArgumentException("backup belongs to another watch");int version=s.optInt("formatVersion",1);if(version<1||version>3)throw new IllegalArgumentException("backup version");full=version==3;if(full){experimentHold=WeatherDeliveryController.hold(context);if(experimentHold==null||!experimentHold.claim.equals(s.getString("fullExperimentClaim")))throw new IllegalArgumentException("FULL_EXPERIMENT_HOLD backup claim differs");}requireExperimentHold();if(!sha(Base64.decode(s.getString("rawResponseBase64"),Base64.NO_WRAP)).equals(s.getString("rawResponseSha256")))throw new IllegalArgumentException("raw backup hash mismatch");JSONObject rawPrior=parseValues(Base64.decode(s.getString("rawResponseBase64"),Base64.NO_WRAP));requireWritablePrior(rawPrior);
        if(version>=2){prior=s.getJSONObject("prior");candidate=s.getJSONObject("candidate");intermediate=s.isNull("intermediate")?null:s.getJSONObject("intermediate");if(!sameTriple(prior,rawPrior))throw new IllegalArgumentException("prior differs from raw backup");if(full)todayIntermediate=s.getJSONObject("todayIntermediate");}
        else{prior=copyTriple(rawPrior);candidate=triple(s.getJSONObject("candidate"),1,1);intermediate=null;if(!same(prior.getJSONObject("todayWeather"),s.getJSONObject("prior")))throw new IllegalArgumentException("legacy prior differs from raw backup");}
        requireInt(candidate,"unitTemperature",1);requireInt(candidate,"weatherEnable",1);requireKnownTodayWeather(candidate.getJSONObject("todayWeather"));
        if(full){
            requireFourKeyState(candidate);
            java.time.ZoneId zone=java.time.ZoneId.of(s.getString("forecastZoneId"));java.time.LocalDate date=java.time.LocalDate.parse(s.getString("forecastBaseDate"));requireKnownForecast(candidate.get("futureDayWeather"));JSONArray forecast=candidate.getJSONArray("futureDayWeather");if(forecast.length()<1||forecast.length()>6)throw new IllegalArgumentException("candidate forecast count");
            for(int i=0;i<forecast.length();i++){JSONObject day=forecast.getJSONObject(i);if(day.getLong("timestamp")!=date.plusDays(i+1).atStartOfDay(zone).toEpochSecond()||day.getInt("moonPhase")!=0)throw new IllegalArgumentException("candidate forecast date/moon");}
            if(!sameTriple(todayIntermediate,triple(candidate.get("todayWeather"),1,1).put("futureDayWeather",prior.get("futureDayWeather"))))throw new IllegalArgumentException("invalid today intermediate backup");
            if(intermediate!=null&&(!isZero(prior.get("todayWeather"))||!sameTriple(intermediate,triple(0,1,1).put("futureDayWeather",prior.get("futureDayWeather")))))throw new IllegalArgumentException("invalid initialization backup");
        }else{if(prior.length()!=3||candidate.length()!=3||intermediate!=null&&intermediate.length()!=3)throw new IllegalArgumentException("legacy backup requires exact three-key states");if(intermediate!=null&&!sameTriple(intermediate,triple(0,1,1)))throw new IllegalArgumentException("invalid intermediate backup");}
    }
    private static String errorDetail(Exception error){return error instanceof org.json.JSONException?"JSON structure invalid":error.getMessage();}
    private void refuse(String detail){if(writeAttempted){fail(detail);return;}safeOutcome=true;fail("SAFE_PREWRITE: "+detail);}
    private void succeed(String detail){if(!isRunning())return;handler.removeCallbacks(timeout);step=Step.DONE;transport.log("WEATHER COMPLETE: "+detail);transport.finished(true,detail);}
    private void fail(String detail){if(step==Step.FAILED||step==Step.DONE)return;if(weeklyOwner!=null){WeatherDeliveryController.pauseWeekly(context,weeklyOwner.owner,detail);safeOutcome=false;}handler.removeCallbacks(timeout);step=Step.FAILED;incoming.reset();transport.log((safeOutcome?"WEATHER REFUSED: ":"WEATHER STOPPED: ")+detail+"; no retry or automatic rollback");transport.finished(false,detail);}
    private static int crc(byte[] b,int off,int len){int c=0xffff;for(int i=off;i<off+len;i++){c^=b[i]&255;for(int bit=0;bit<8;bit++)c=(c&1)!=0?(c>>>1)^0xa001:c>>>1;}return c&0xffff;}
    private static String sha(byte[] raw)throws Exception{StringBuilder b=new StringBuilder(64);for(byte x:MessageDigest.getInstance("SHA-256").digest(raw))b.append(String.format(Locale.US,"%02x",x&255));return b.toString();}
}
