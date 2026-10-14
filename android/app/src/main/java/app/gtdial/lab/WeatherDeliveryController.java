package app.gtdial.lab;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Service owns only the newest immutable snapshot; BLE owns the durable once-only claim. */
final class WeatherDeliveryController implements SharedPreferences.OnSharedPreferenceChangeListener {
    interface Sender {void deliver(Delivery delivery,java.util.function.Consumer<Boolean> accepted);}
    private static final String HOLD_DEVICE="weather.full-experiment.device",HOLD_CLAIM="weather.full-experiment.claim",HOLD_STARTED="weather.full-experiment.startedAt";
    static final class Hold {
        final String device,claim;final long startedAt;
        Hold(String device,String claim,long startedAt){this.device=device;this.claim=claim;this.startedAt=startedAt;}
        boolean valid(){return device!=null&&device.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")&&claim!=null&&claim.matches("[0-9a-f]{64}")&&startedAt>0;}
        boolean same(Hold other){return other!=null&&java.util.Objects.equals(device,other.device)&&java.util.Objects.equals(claim,other.claim)&&startedAt==other.startedAt;}
    }
    static Hold hold(Context context){synchronized(WeatherDeliveryController.class){return readHold(context.getSharedPreferences("gt_dial_preferences",Context.MODE_PRIVATE));}}
    private static Hold readHold(SharedPreferences preferences){if(!preferences.contains(HOLD_DEVICE)&&!preferences.contains(HOLD_CLAIM)&&!preferences.contains(HOLD_STARTED))return null;try{return new Hold(preferences.getString(HOLD_DEVICE,""),preferences.getString(HOLD_CLAIM,""),preferences.getLong(HOLD_STARTED,0));}catch(RuntimeException malformed){return new Hold("","",0);}}
    static boolean releaseExactHold(Context context,Hold expected){return finishHoldAndDisableWeekly(context,expected);}
    private static final String WEEKLY="weather.weekly-state";
    private static final String AUTOMATIC_WEEKLY="weather.weekly-auto-enabled";
    private static boolean automaticStorageClosed;
    private static boolean weeklyLoaded,weeklyStorageClosed;
    static boolean initializeAutomaticPolicy(Context context){synchronized(WeatherDeliveryController.class){
        SharedPreferences p=prefs(context);
        if(p.contains(AUTOMATIC_WEEKLY)){try{return !automaticStorageClosed&&p.getBoolean(AUTOMATIC_WEEKLY,false);}catch(RuntimeException e){automaticStorageClosed=true;return false;}}
        boolean enabled=p.getAll().isEmpty();
        if(!enabled&&p.contains(WEEKLY))try{
            JSONObject j=new JSONObject(p.getString(WEEKLY,""));Hold owner=new Hold(j.getString("holdDevice"),j.getString("holdClaim"),j.getLong("holdStartedAt"));
            String status=j.getString("status"),attempt=j.getString("attempt");
            enabled=p.getString(WEEKLY,"").getBytes(StandardCharsets.UTF_8).length<=16*1024&&j.length()==10&&j.get("version") instanceof Integer&&(j.get("holdStartedAt") instanceof Long||j.get("holdStartedAt") instanceof Integer)&&j.getInt("version")==1&&owner.valid()&&owner.same(readHold(p))&&owner.device.equals(j.getString("device"))
                &&j.getString("selectionIdentity").matches("[0-9a-f]{64}")&&java.util.Arrays.asList("READY","PAUSED","IN_FLIGHT").contains(status)
                &&(attempt.isEmpty()||attempt.matches("[0-9a-f]{64}"))&&(!"IN_FLIGHT".equals(status)||!attempt.isEmpty())&&j.getString("reason").length()<=256;
            if(!j.isNull("baseline")){WeatherSessionProtocol.validateFourValues(j.getJSONObject("baseline"));enabled&=j.getJSONObject("baseline").getJSONArray("futureDayWeather").length()==6;}
        }catch(Exception invalid){enabled=false;}
        if(!p.edit().putBoolean(AUTOMATIC_WEEKLY,enabled).commit()){automaticStorageClosed=true;weeklyStorageClosed=true;return false;}return enabled;
    }}
    static boolean automaticWeeklyEnabled(Context context){return initializeAutomaticPolicy(context);}
    static boolean prepareAutomaticWeekly(Context context,String device,Delivery delivery){synchronized(WeatherDeliveryController.class){
        if(!automaticWeeklyEnabled(context)||automaticStorageClosed||weeklyStorageClosed||delivery==null||!delivery.selectionIdentity.equals(selectionIdentity(prefs(context)))||!new WeatherPayload(delivery.snapshot).readyForWeekly())return false;
        SharedPreferences p=prefs(context);
        if(!p.contains(WEEKLY)){if(readHold(p)!=null||!enableWeekly(context,null,device,delivery.selectionIdentity))return false;}
        WeeklyStatus state=weeklyStatus(context);return !weeklyStorageClosed&&"READY".equals(state.status)&&state.matches(device,delivery,readHold(p));
    }}
    static boolean setAutomaticWeeklyEnabled(Context context,boolean enabled){synchronized(WeatherDeliveryController.class){
        initializeAutomaticPolicy(context);if(automaticStorageClosed||weeklyStorageClosed)return false;
        SharedPreferences p=prefs(context);WeeklyStatus current=weeklyStatus(context);if(weeklyStorageClosed||"IN_FLIGHT".equals(current.status))return false;
        WeeklyStatus next=current;
        if(current.enabled){
            if(enabled&&"PAUSED".equals(current.status)){if(!"EXPLICIT_PAUSE".equals(current.reason)&&!"EXPLICIT_DISABLE".equals(current.reason))return false;next=new WeeklyStatus(true,"READY",current.device,current.selectionIdentity,"",current.attempt,current.owner,current.baseline);}
            else if(!enabled&&"READY".equals(current.status))next=paused(current,"EXPLICIT_DISABLE");
        }else if(enabled&&readHold(p)!=null)return false;
        try{SharedPreferences.Editor edit=p.edit().putBoolean(AUTOMATIC_WEEKLY,enabled);if(next!=current)edit.putString(WEEKLY,weeklyJson(next));
            if(edit.commit())return true;
        }catch(Exception ignored){}automaticStorageClosed=true;weeklyStorageClosed=true;return false;
    }}
    static final class WeeklyStatus {
        final boolean enabled;final String status,device,selectionIdentity,reason,attempt;final Hold owner;final int verifiedDays;
        private final String baseline;
        WeeklyStatus(boolean enabled,String status,String device,String selection,String reason,String attempt,Hold owner,String baseline){this.enabled=enabled;this.status=status;this.device=device;selectionIdentity=selection;this.reason=reason;this.attempt=attempt;this.owner=owner;this.baseline=baseline;verifiedDays=baseline.isEmpty()?0:6;}
        JSONObject baseline(){try{return baseline.isEmpty()?null:new JSONObject(baseline);}catch(Exception e){return null;}}
        boolean matches(String device,Delivery delivery,Hold hold){return enabled&&owner!=null&&owner.same(hold)&&this.device.equals(device)&&selectionIdentity.equals(delivery.selectionIdentity);}
    }
    private static WeeklyStatus disabled(){return new WeeklyStatus(false,"DISABLED","","","","",null,"");}
    private static WeeklyStatus paused(WeeklyStatus state,String reason){return new WeeklyStatus(true,"PAUSED",state.device,state.selectionIdentity,reason,state.attempt,state.owner,state.baseline);}
    private static SharedPreferences prefs(Context context){return context.getSharedPreferences("gt_dial_preferences",Context.MODE_PRIVATE);}
    private static String weeklyJson(WeeklyStatus state)throws Exception{return new JSONObject().put("version",1).put("device",state.device).put("selectionIdentity",state.selectionIdentity).put("holdDevice",state.owner.device).put("holdClaim",state.owner.claim).put("holdStartedAt",state.owner.startedAt).put("status",state.status).put("attempt",state.attempt).put("reason",state.reason).put("baseline",state.baseline.isEmpty()?JSONObject.NULL:new JSONObject(state.baseline)).toString();}
    private static boolean saveWeekly(SharedPreferences p,WeeklyStatus state){try{String value=weeklyJson(state);if(value.getBytes(StandardCharsets.UTF_8).length>16*1024){weeklyStorageClosed=true;return false;}if(p.edit().putString(WEEKLY,value).commit())return true;}catch(Exception ignored){}weeklyStorageClosed=true;return false;}
    static WeeklyStatus weeklyStatus(Context context){synchronized(WeatherDeliveryController.class){SharedPreferences p=prefs(context);WeeklyStatus state;
        if(!p.contains(WEEKLY)){weeklyLoaded=true;return weeklyStorageClosed?new WeeklyStatus(true,"PAUSED","","","STORAGE_FAILED","",readHold(p),""):disabled();}
        try{String text=p.getString(WEEKLY,"");if(text.getBytes(StandardCharsets.UTF_8).length>16*1024)throw new IllegalArgumentException("weekly size");JSONObject json=new JSONObject(text);if(json.length()!=10||!(json.get("version") instanceof Integer)||!(json.get("holdStartedAt") instanceof Long||json.get("holdStartedAt") instanceof Integer)||json.getInt("version")!=1)throw new IllegalArgumentException("weekly version");Hold owner=new Hold(json.getString("holdDevice"),json.getString("holdClaim"),json.getLong("holdStartedAt"));String status=json.getString("status"),device=json.getString("device"),selection=json.getString("selectionIdentity"),attempt=json.getString("attempt"),reason=json.getString("reason");if(!owner.valid()||!owner.same(readHold(p))||!device.equals(owner.device)||!selection.matches("[0-9a-f]{64}")||!java.util.Arrays.asList("READY","IN_FLIGHT","PAUSED").contains(status)||!attempt.isEmpty()&&!attempt.matches("[0-9a-f]{64}")||"IN_FLIGHT".equals(status)&&attempt.isEmpty()||reason.length()>256)throw new IllegalArgumentException("weekly ownership/state");JSONObject baseline=json.isNull("baseline")?null:json.getJSONObject("baseline");if(baseline!=null){WeatherSessionProtocol.validateFourValues(baseline);if(baseline.getJSONArray("futureDayWeather").length()!=6)throw new IllegalArgumentException("weekly baseline coverage");}state=new WeeklyStatus(true,status,device,selection,reason,attempt,owner,baseline==null?"":baseline.toString());
        }catch(Exception invalid){state=new WeeklyStatus(true,"PAUSED","","","INVALID_STATE","",readHold(p),"");weeklyStorageClosed=true;}
        if(!weeklyLoaded){weeklyLoaded=true;if("IN_FLIGHT".equals(state.status)){state=paused(state,"INTERRUPTED_PROCESS");saveWeekly(p,state);}}
        if(!weeklyStorageClosed&&state.enabled&&!state.selectionIdentity.equals(selectionIdentity(p))&&!"PAUSED".equals(state.status)){state=paused(state,"SELECTION_CHANGED");saveWeekly(p,state);}
        return weeklyStorageClosed?paused(state,"STORAGE_FAILED"):state;
    }}
    static boolean enableWeekly(Context context,Hold expected,String device,String selection){synchronized(WeatherDeliveryController.class){SharedPreferences p=prefs(context);Hold current=readHold(p);if(expected==null?current!=null:!expected.valid()||!expected.same(current))return false;if(current!=null&&!device.equals(current.device)||!selection.equals(selectionIdentity(p)))return false;Hold owner=current==null?new Hold(device,hash("weekly-consent:"+device+":"+selection+":"+java.util.UUID.randomUUID()),System.currentTimeMillis()):current;if(!owner.valid())return false;WeeklyStatus state=new WeeklyStatus(true,"READY",device,selection,"","",owner,"");try{String json=weeklyJson(state);boolean saved=p.edit().putString(HOLD_DEVICE,owner.device).putString(HOLD_CLAIM,owner.claim).putLong(HOLD_STARTED,owner.startedAt).putString(WEEKLY,json).commit();weeklyLoaded=true;weeklyStorageClosed=!saved;return saved;}catch(Exception e){weeklyStorageClosed=true;return false;}}}
    static String weeklyIdentity(String device,Delivery delivery){try{WeatherSnapshot s=delivery.snapshot;JSONObject forecast=new JSONObject(s.forecastJson());forecast.remove("evaluatedAtMs");return hash(new JSONObject().put("device",device).put("selection",delivery.selectionIdentity).put("source",s.source).put("modelIssued",s.modelIssuedAtUnix).put("forecastValid",s.forecastValidAtUnix).put("providerTime",s.providerTimeUnix).put("temperature",s.temperatureC).put("low",s.dailyMinC).put("high",s.dailyMaxC).put("wmo",s.wmoCode).put("forecast",forecast).toString());}catch(Exception e){throw new IllegalArgumentException("weekly identity",e);}}
    private static boolean weeklyAttemptConsumed(Context context,String key){synchronized(WeatherDeliveryController.class){try{return prefs(context).getBoolean(key,false);}catch(RuntimeException malformed){WeeklyStatus state=weeklyStatus(context);weeklyStorageClosed=true;if(state.owner!=null)saveWeekly(prefs(context),paused(state,"ATTEMPT_METADATA_INVALID"));return true;}}}
    static WeeklyStatus claimWeekly(Context context,String device,Delivery delivery){synchronized(WeatherDeliveryController.class){SharedPreferences p=prefs(context);WeeklyStatus state=weeklyStatus(context);if(weeklyStorageClosed||!"READY".equals(state.status)||!state.matches(device,delivery,readHold(p))||!delivery.selectionIdentity.equals(selectionIdentity(p))||!new WeatherPayload(delivery.snapshot).readyForWeekly())return null;String attempt=weeklyIdentity(device,delivery),key="weather.weekly-attempt."+attempt;if(weeklyAttemptConsumed(context,key))return null;WeeklyStatus next=new WeeklyStatus(true,"IN_FLIGHT",state.device,state.selectionIdentity,"",attempt,state.owner,state.baseline);try{String json=weeklyJson(next);if(json.getBytes(StandardCharsets.UTF_8).length<=16*1024&&p.edit().putBoolean(key,true).putString(WEEKLY,json).commit())return next;}catch(Exception ignored){}weeklyStorageClosed=true;return null;}}
    static boolean completeWeekly(Context context,WeeklyStatus owner,JSONObject verified){synchronized(WeatherDeliveryController.class){WeeklyStatus state=weeklyStatus(context);if(weeklyStorageClosed||owner==null||!"IN_FLIGHT".equals(state.status)||!owner.attempt.equals(state.attempt)||!owner.owner.same(state.owner))return false;try{WeatherSessionProtocol.validateFourValues(verified);if(!(verified.get("futureDayWeather") instanceof org.json.JSONArray)||verified.getJSONArray("futureDayWeather").length()!=6)return false;return saveWeekly(prefs(context),new WeeklyStatus(true,"READY",state.device,state.selectionIdentity,"",state.attempt,state.owner,verified.toString()));}catch(Exception e){weeklyStorageClosed=true;return false;}}}
    static boolean pauseWeekly(Context context,Hold owner,String reason){synchronized(WeatherDeliveryController.class){WeeklyStatus state=weeklyStatus(context);if(!state.enabled)return true;if(owner==null||state.owner==null||!owner.same(state.owner))return false;String safe=reason==null?"PAUSED":reason;return saveWeekly(prefs(context),paused(state,safe.substring(0,Math.min(256,safe.length()))));}}
    static boolean finishHoldAndDisableWeekly(Context context,Hold expected){synchronized(WeatherDeliveryController.class){
        SharedPreferences p=prefs(context);Hold current=readHold(p);if(expected==null||!expected.valid()||!expected.same(current))return false;
        String old;boolean automaticPresent=p.contains(AUTOMATIC_WEEKLY),automaticValue;
        try{old=p.getString(WEEKLY,null);automaticValue=p.getBoolean(AUTOMATIC_WEEKLY,false);}catch(RuntimeException malformed){weeklyStorageClosed=true;automaticStorageClosed=true;return false;}
        if(p.edit().remove(WEEKLY).remove(HOLD_DEVICE).remove(HOLD_CLAIM).remove(HOLD_STARTED).putBoolean(AUTOMATIC_WEEKLY,false).commit()){weeklyStorageClosed=false;weeklyLoaded=true;return true;}
        SharedPreferences.Editor restore=p.edit().putString(HOLD_DEVICE,current.device).putString(HOLD_CLAIM,current.claim).putLong(HOLD_STARTED,current.startedAt);
        if(old!=null)restore.putString(WEEKLY,old);else restore.remove(WEEKLY);
        if(automaticPresent)restore.putBoolean(AUTOMATIC_WEEKLY,automaticValue);else restore.remove(AUTOMATIC_WEEKLY);
        restore.commit();weeklyStorageClosed=true;automaticStorageClosed=true;return false;
    }}
    static final class Delivery {
        final WeatherSnapshot snapshot;final String city,selectionIdentity,snapshotIdentity;
        Delivery(WeatherSnapshot snapshot,String city,String selection){this.snapshot=snapshot;this.city=city;selectionIdentity=selection;snapshotIdentity=snapshotIdentity(snapshot);}
    }
    private final Context context;private final Handler handler;private final Sender sender;private final SharedPreferences preferences;
    private Delivery pending;private boolean dispatching,closed;private String consumed="";private final java.util.function.Consumer<String> log;
    WeatherDeliveryController(Context context,Handler handler,Sender sender,java.util.function.Consumer<String> log){this.log=log;this.context=context.getApplicationContext();this.handler=handler;this.sender=sender;preferences=context.getSharedPreferences("gt_dial_preferences",Context.MODE_PRIVATE);preferences.registerOnSharedPreferenceChangeListener(this);}
    static boolean selectionKey(String key){return "weather.city".equals(key)||"weather.city-name".equals(key)||"weather.latitude-bits".equals(key)||"weather.longitude-bits".equals(key)||WeatherCredentialStore.REVISION_PREF.equals(key);}
    static String selectionIdentity(SharedPreferences p){try{return hash(new JSONObject().put("city",p.getString("weather.city","")).put("label",p.getString("weather.city-name","")).put("latitude",p.getLong("weather.latitude-bits",0)).put("longitude",p.getLong("weather.longitude-bits",0)).put("credentialRevision",p.getLong(WeatherCredentialStore.REVISION_PREF,0)).toString());}catch(Exception e){throw new IllegalStateException("selection identity unavailable",e);}}
    static String snapshotIdentity(WeatherSnapshot s){try{return hash(new JSONObject().put("latitude",Double.doubleToRawLongBits(s.latitude)).put("longitude",Double.doubleToRawLongBits(s.longitude)).put("temperature",Double.doubleToRawLongBits(s.temperatureC)).put("min",Double.doubleToRawLongBits(s.dailyMinC)).put("max",Double.doubleToRawLongBits(s.dailyMaxC)).put("wmo",s.wmoCode).put("source",s.source).put("providerTime",s.providerTimeUnix).put("fetchedAt",s.fetchedAtMs).toString());}catch(Exception e){throw new IllegalStateException("snapshot identity unavailable",e);}}
    static Delivery cached(Context context){SharedPreferences p=context.getSharedPreferences("gt_dial_preferences",Context.MODE_PRIVATE);String binding=selectionIdentity(p);if(!binding.equals(p.getString("weather.snapshot-binding","")))return null;
        WeatherSnapshot snapshot=new WeatherSnapshot(Double.longBitsToDouble(p.getLong("weather.latitude-bits",0)),Double.longBitsToDouble(p.getLong("weather.longitude-bits",0)),Double.longBitsToDouble(p.getLong("weather.temperature-bits",Double.doubleToRawLongBits(Double.NaN))),p.getInt("weather.wmo-code",-1),p.getLong("weather.fetched-at",0),p.getLong("weather.provider-time",0),Double.longBitsToDouble(p.getLong("weather.min-bits",0)),Double.longBitsToDouble(p.getLong("weather.max-bits",0)),p.getString("weather.source",""),java.util.Collections.emptyList(),"","",p.getLong("weather.model-issued-at",0),p.getLong("weather.forecast-valid-at",0),p.getLong("weather.evaluated-at",p.getLong("weather.fetched-at",0)),p.getString("weather.range-basis","provider daily"));
        snapshot=WeatherSnapshot.withCachedForecast(snapshot,p.getString("weather.forecast-json",""));if(!new WeatherPayload(snapshot).readyForBle())return null;String city=p.getString("weather.city-name","");if(city.isEmpty())return null;return new Delivery(snapshot,city,binding);
    }
    enum Claim {CLAIMED,DUPLICATE,SELECTION_CHANGED,SAVE_FAILED,HOLD_ACTIVE}
    private static String attemptKey(String mac,Delivery d){return "weather.watch-attempt."+hash(mac+":"+d.selectionIdentity+":"+d.snapshotIdentity);}
    static Claim claim(Context context,String mac,Delivery d){SharedPreferences p=context.getSharedPreferences("gt_dial_preferences",Context.MODE_PRIVATE);synchronized(WeatherDeliveryController.class){if(readHold(p)!=null||weeklyStatus(context).enabled)return Claim.HOLD_ACTIVE;if(!d.selectionIdentity.equals(selectionIdentity(p)))return Claim.SELECTION_CHANGED;String key=attemptKey(mac,d);if(p.getBoolean(key,false))return Claim.DUPLICATE;return p.edit().putBoolean(key,true).commit()?Claim.CLAIMED:Claim.SAVE_FAILED;}}
    static Claim claimForecast(Context context,String mac,Delivery delivery){SharedPreferences p=context.getSharedPreferences("gt_dial_preferences",Context.MODE_PRIVATE);synchronized(WeatherDeliveryController.class){if(readHold(p)!=null||weeklyStatus(context).enabled)return Claim.HOLD_ACTIVE;if(!delivery.selectionIdentity.equals(selectionIdentity(p))||!new WeatherPayload(delivery.snapshot).readyForWeekly())return Claim.SELECTION_CHANGED;String claim=hash(mac+":"+delivery.selectionIdentity+":"+delivery.snapshotIdentity+":"+delivery.snapshot.forecastJson()),key="weather.full-manual-attempt."+claim;Hold next=new Hold(mac,claim,System.currentTimeMillis());if(!next.valid())return Claim.SAVE_FAILED;if(p.getBoolean(key,false))return Claim.DUPLICATE;return p.edit().putBoolean(key,true).putString(HOLD_DEVICE,next.device).putString(HOLD_CLAIM,next.claim).putLong(HOLD_STARTED,next.startedAt).commit()?Claim.CLAIMED:Claim.SAVE_FAILED;}}
    static boolean matchesForecastClaim(Hold hold,String device,Delivery delivery){return hold!=null&&hold.valid()&&device.equals(hold.device)&&hold.claim.equals(hash(device+":"+delivery.selectionIdentity+":"+delivery.snapshotIdentity+":"+delivery.snapshot.forecastJson()));}
    private static String hash(String text){try{StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))result.append(String.format(java.util.Locale.US,"%02x",b&255));return result.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    void refreshed(){if(closed)return;pending=cached(context);pump();}
    void ready(){if(closed)return;if(pending==null)pending=cached(context);pump();}
    private void pump(){
        if(closed||dispatching||pending==null)return;Delivery selected=pending;
        if(!automaticWeeklyEnabled(context)||!new WeatherPayload(selected.snapshot).readyForWeekly())return;
        if(!selected.selectionIdentity.equals(selectionIdentity(preferences))){pending=null;log.accept("WEATHER_DELIVERY automatic skip=selection_changed");return;}
        String mac=preferences.getString("verified_device_address","");String key="weather.weekly-attempt."+weeklyIdentity(mac,selected);
        if(key.equals(consumed)){pending=null;return;}if(weeklyAttemptConsumed(context,key)){pending=null;consumed=key;log.accept("WEATHER_DELIVERY automatic skip=consumed_snapshot");return;}
        dispatching=true;sender.deliver(selected,accepted->handler.post(()->{dispatching=false;if(closed)return;if(accepted){consumed=key;if(pending==selected)pending=null;}if(pending!=selected)pump();}));
    }
    void failed(){if(hold(context)==null)pending=null;else if(pending==null)pending=cached(context);}
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key){if(selectionKey(key))handler.post(()->{pending=null;WeeklyStatus state=weeklyStatus(context);if(state.enabled)pauseWeekly(context,state.owner,"SELECTION_CHANGED");});}
    void close(){closed=true;pending=null;preferences.unregisterOnSharedPreferenceChangeListener(this);}
}
