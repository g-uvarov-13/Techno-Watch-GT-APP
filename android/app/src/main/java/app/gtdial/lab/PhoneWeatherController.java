package app.gtdial.lab;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Service-owned single network worker; city changes invalidate late results. No BLE writes. */
final class PhoneWeatherController implements SharedPreferences.OnSharedPreferenceChangeListener {
    interface Listener { void changed(boolean busy,String message); void log(String message); default void snapshotSaved(){} }
    private static final long INTERVAL=1_800_000L;
    private final SharedPreferences preferences;
    private final Context context;
    private final Handler handler;
    private final Listener listener;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Runnable tick=()->fetch(false);
    private boolean running,closed;
    private volatile boolean active;
    void setActive(boolean value){Runnable change=()->{if(closed)return;active=value;handler.removeCallbacks(tick);if(value&&!running)handler.post(tick);};if(android.os.Looper.myLooper()==handler.getLooper())change.run();else handler.post(change);}
    private long generation;
    private String trackedIdentity="";
    private void updateIdentity(){String selected=identity();if(!selected.equals(trackedIdentity)){trackedIdentity=selected;generation++;}}
    PhoneWeatherController(Context context,Handler handler,Listener listener){
        this.context=context.getApplicationContext();
        preferences=context.getSharedPreferences("gt_dial_preferences",Context.MODE_PRIVATE);
        this.handler=handler;this.listener=listener;
        preferences.registerOnSharedPreferenceChangeListener(this);handler.post(tick);
    }
    boolean isRunning(){return running;}
    private String identity(){return WeatherDeliveryController.selectionIdentity(preferences);}
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key){
        if(WeatherDeliveryController.selectionKey(key))handler.post(()->{
            if(closed)return;updateIdentity();handler.removeCallbacks(tick);if(!running)handler.post(tick);
        });
    }
    void fetch(boolean manual){
        if(closed||!active)return;
        updateIdentity();
        if(running)return;
        handler.removeCallbacks(tick);
        String city=preferences.getString("weather.city","");
        if(city.isEmpty()){if(manual)listener.changed(false,"Сначала выберите город");return;}
        String identity=identity();String attemptKey="weather.phone-attempt."+identity;
        long now=System.currentTimeMillis(),last=preferences.getLong(attemptKey,0);
        long delay=last==0?0:Math.max(0,Math.min(INTERVAL,last+INTERVAL-now));
        if(!manual&&delay>0){handler.postDelayed(tick,delay);return;}
        double latitude=Double.longBitsToDouble(preferences.getLong("weather.latitude-bits",Double.doubleToRawLongBits(Double.NaN)));
        double longitude=Double.longBitsToDouble(preferences.getLong("weather.longitude-bits",Double.doubleToRawLongBits(Double.NaN)));
        if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||Math.abs(latitude)>90||Math.abs(longitude)>180){listener.changed(false,"Выберите город с известными координатами");return;}
        if(!preferences.edit().putLong(attemptKey,now).commit()){listener.changed(false,"Не удалось сохранить время обновления погоды");return;}
        running=true;long requestGeneration=generation;
        listener.log("PHONE_WEATHER attempt manual="+manual);listener.changed(true,"Обновляю погоду на телефоне…");
        worker.execute(()->{
            WeatherSnapshot result=null;String failure=null;
            // Each refresh attempts each independent host at most once. Keep the last
            // saved snapshot until an entire provider response has been validated.
            String[] providers={"MET Norway","Open-Meteo","wttr.in"};
            for(int index=0;index<providers.length;index++){
                if(Thread.currentThread().isInterrupted())return;
                if(!active)break;
                String provider=providers[index];
                long started=android.os.SystemClock.elapsedRealtime();
                logProvider("PHONE_WEATHER provider="+provider+" attempt");
                try{
                    WeatherSnapshot candidate;
                    if("MET Norway".equals(provider))candidate=new MetNoWeatherProvider(context).fetch(latitude,longitude,city,System.currentTimeMillis());
                    else if("Open-Meteo".equals(provider))candidate=new OpenMeteoWeatherProvider().fetch(latitude,longitude,city,System.currentTimeMillis());
                    else candidate=new WttrWeatherProvider().fetch(latitude,longitude,city,System.currentTimeMillis());
                    if(!new WeatherPayload(candidate).readyForBle())throw new java.io.IOException("invalid or stale weather snapshot");
                    if(result==null)result=candidate;
                    logProvider("PHONE_WEATHER provider="+provider+" success elapsed_ms="+(android.os.SystemClock.elapsedRealtime()-started));
                    if(new WeatherPayload(candidate).readyForWeekly()){result=candidate;break;}
                }catch(Exception e){
                    failure=e.getClass().getSimpleName();
                    logProvider("PHONE_WEATHER provider="+provider+" failed kind="+failure+" elapsed_ms="+(android.os.SystemClock.elapsedRealtime()-started));
                }
            }
            WeatherSnapshot snapshot=result;String error=failure;
            handler.post(()->{
                if(closed)return;
                running=false;
                if(active&&requestGeneration==generation&&identity.equals(identity())){
                    if(snapshot!=null){boolean saved=preferences.edit().putString("weather.snapshot-binding",identity).putLong("weather.temperature-bits",Double.doubleToRawLongBits(snapshot.temperatureC)).putInt("weather.temperature-c",(int)Math.round(snapshot.temperatureC))
                        .putInt("weather.wmo-code",snapshot.wmoCode).putLong("weather.min-bits",Double.doubleToRawLongBits(snapshot.dailyMinC))
                        .putLong("weather.max-bits",Double.doubleToRawLongBits(snapshot.dailyMaxC)).putLong("weather.provider-time",snapshot.providerTimeUnix)
                        .putLong("weather.model-issued-at",snapshot.modelIssuedAtUnix).putLong("weather.forecast-valid-at",snapshot.forecastValidAtUnix).putLong("weather.evaluated-at",snapshot.evaluatedAtMs).putString("weather.range-basis",snapshot.rangeBasis).putString("weather.source",snapshot.source).putLong("weather.fetched-at",snapshot.fetchedAtMs).putString("weather.forecast-json",snapshot.forecastJson()).commit();
                        if(!saved){preferences.edit().remove("weather.snapshot-binding").apply();listener.changed(false,"Не удалось сохранить погоду; передача на часы не начата");handler.postDelayed(tick,INTERVAL);return;}
                        listener.snapshotSaved();listener.log("PHONE_WEATHER result success");listener.changed(false,"Погода телефона: "+snapshot.source+"; "+snapshot.forecast.size()+"/6 будущих дней"+(new WeatherPayload(snapshot).readyForWeekly()?"; недельный источник готов":"; неполная неделя, сохранённая неделя часов не заменяется"));
                    }else{listener.log("PHONE_WEATHER result failed kind="+error);listener.changed(false,"Сервис погоды недоступен; следующее автоматическое обновление через 30 минут");}
                }else listener.changed(false,active?"Настройки погоды изменены; обновляю погоду":"Обновление погоды приостановлено: приложение и часы не активны");
                handler.removeCallbacks(tick);if(active)handler.post(tick);
            });
        });
    }
    private void logProvider(String message){handler.post(()->{if(!closed)listener.log(message);});}
    void close(){closed=true;generation++;preferences.unregisterOnSharedPreferenceChangeListener(this);handler.removeCallbacks(tick);worker.shutdownNow();}
}
