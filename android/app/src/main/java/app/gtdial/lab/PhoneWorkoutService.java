package app.gtdial.lab;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;

/** GPS is a separately chosen phone feature. No BLE dependency or watch heart-rate claim. */
public final class PhoneWorkoutService extends Service implements LocationListener {
    private static volatile String ownedId="";
    static boolean owns(String id){return !id.isEmpty()&&id.equals(ownedId);}
    static final String START="app.gtdial.lab.PHONE_GPS_START",STOP="app.gtdial.lab.PHONE_GPS_STOP",PAUSE="app.gtdial.lab.PHONE_GPS_PAUSE",RESUME="app.gtdial.lab.PHONE_GPS_RESUME";
    private static final String CHANNEL="phone_workout";
    private static final int NOTIFICATION=27;
    private final Handler main=new Handler(Looper.getMainLooper());
    private ExpansionStore store;
    private WorkoutRecorder recorder;
    private LocationManager locations;
    private String id="";
    private Location last;
    private boolean receiving,ending,destroyed;
    private int generation;
    private final Runnable timeout=()->failGps("LOCATION_TIMEOUT");
    @Override public void onCreate(){super.onCreate();store=ExpansionStore.get(this);recorder=new WorkoutRecorder(this,store);locations=(LocationManager)getSystemService(LOCATION_SERVICE);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"Часовой — тренировка телефона",NotificationManager.IMPORTANCE_LOW));}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        final int token=++generation;
        if(intent==null){stopSelf();return START_NOT_STICKY;}String action=intent.getAction();
        if(STOP.equals(action)){ending=true;stopUpdates();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
        String requested=intent.getStringExtra("workoutId");if(requested==null||!requested.matches("[0-9a-f-]{36}")){stopSelf();return START_NOT_STICKY;}id=requested;
        ownedId=id;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){failGps("PERMISSION_DENIED");return START_NOT_STICKY;}
        try{if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification(false),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);else startForeground(NOTIFICATION,notification(false));}
        catch(SecurityException|IllegalStateException e){failGps("FOREGROUND_START_REJECTED");return START_NOT_STICKY;}
        final String target=id;
        store.submit(()->{
            JSONObject current=recorder.current();if(current==null||!target.equals(current.optString("id")))throw new IllegalStateException("WORKOUT_MISSING");
            if(PAUSE.equals(action))return recorder.pause(target);if(RESUME.equals(action))return recorder.resume(target);return current;
        },(record,error)->{
            if(destroyed||ending||token!=generation||!target.equals(id))return;
            if(!error.isEmpty()||record==null){failGps("WORKOUT_STATE");return;}
            boolean paused=!"RUNNING".equals(record.optString("status"));updateNotification(paused);
            if(paused){stopUpdates();store.execute(()->recorder.gpsState(target,"PAUSED_ROUTE_GAP"));}else startUpdates();
        });return START_NOT_STICKY;
    }
    private void startUpdates(){
        stopUpdates();last=null;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){failGps("PERMISSION_DENIED");return;}
        String target=id;try{if(locations==null||!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)){failGps("PROVIDER_DISABLED");return;}locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,5000,3,this,Looper.getMainLooper());receiving=true;main.postDelayed(timeout,60000);store.execute(()->recorder.gpsState(target,"WAITING_FIX"));}catch(SecurityException|IllegalArgumentException e){failGps("LOCATION_REQUEST_FAILED");}
    }
    private void stopUpdates(){main.removeCallbacks(timeout);try{if(locations!=null)locations.removeUpdates(this);}catch(SecurityException e){OperationJournal.get(this).stage(java.util.UUID.randomUUID().toString(),"phone_workout","gps_teardown","FAILED","PARTIAL","PERMISSION_REVOKED",0,0,0);}receiving=false;last=null;}
    private void failGps(String reason){stopUpdates();ending=true;++generation;String target=id;if(target.equals(ownedId))ownedId="";if(!target.isEmpty())store.execute(()->{try{recorder.gpsState(target,"PARTIAL_"+reason);}catch(RuntimeException ignored){}});stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onLocationChanged(Location location){
        if(!receiving||destroyed||ending||location==null)return;String target=id;final int token=generation;long age=(SystemClock.elapsedRealtimeNanos()-location.getElapsedRealtimeNanos())/1000000;
        if(!Double.isFinite(location.getLatitude())||!Double.isFinite(location.getLongitude())||Math.abs(location.getLatitude())>90||Math.abs(location.getLongitude())>180||!location.hasAccuracy()||!Float.isFinite(location.getAccuracy())||location.getAccuracy()<0||location.getAccuracy()>50||age<0||age>30000){store.execute(()->recorder.gpsReject(target,"STALE_OR_ACCURACY"));return;}
        main.removeCallbacks(timeout);main.postDelayed(timeout,120000);
        Double increment=null;
        if(last!=null){long delta=(location.getElapsedRealtimeNanos()-last.getElapsedRealtimeNanos())/1000000;if(delta<=0){store.execute(()->recorder.gpsReject(target,"NONMONOTONIC_FIX"));return;}if(delta<=30000){float meters=last.distanceTo(location);if(!Float.isFinite(meters)||meters/(delta/1000.0)>80){store.execute(()->recorder.gpsReject(target,"IMPLAUSIBLE_JUMP"));last=null;return;}increment=(double)meters;}}
        last=new Location(location);JSONObject fix=ExpansionModels.record(ExpansionModels.PHONE);ExpansionModels.put(fix,"latitude",location.getLatitude());ExpansionModels.put(fix,"longitude",location.getLongitude());ExpansionModels.put(fix,"accuracyMeters",location.getAccuracy());ExpansionModels.put(fix,"elapsedRealtime",location.getElapsedRealtimeNanos()/1000000);ExpansionModels.put(fix,"timestamp",location.getTime());
        Double distance=increment;store.submit(()->{recorder.gpsFix(target,fix,distance);return true;},(ok,error)->{if(!destroyed&&!ending&&token==generation&&!error.isEmpty())failGps("PERSIST_FAILED");});
    }
    @Override public void onProviderDisabled(String provider){if(LocationManager.GPS_PROVIDER.equals(provider)&&receiving)failGps("PROVIDER_DISABLED");}
    @Override public void onProviderEnabled(String provider){ }
    @Override public void onStatusChanged(String provider,int status,android.os.Bundle extras){ }
    private Notification notification(boolean paused){
        Intent open=new Intent(this,MainActivity.class).putExtra("phoneWorkout",true);PendingIntent launch=PendingIntent.getActivity(this,270,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent toggle=new Intent(this,PhoneWorkoutService.class).setAction(paused?RESUME:PAUSE).putExtra("workoutId",id);PendingIntent control=PendingIntent.getService(this,271,toggle,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent finish=new Intent(this,MainActivity.class).putExtra("phoneWorkoutFinish",true);PendingIntent finishUi=PendingIntent.getActivity(this,272,finish,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("Часовой — занятие телефона").setContentText(paused?"Пауза · GPS остановлен":"GPS телефона · маршрут может быть неполным").setContentIntent(launch).setOngoing(true).addAction(new Notification.Action.Builder(null,paused?"Продолжить":"Пауза",control).build()).addAction(new Notification.Action.Builder(null,"Завершить…",finishUi).build()).build();
    }
    private void updateNotification(boolean paused){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification(paused));}
    @Override public void onDestroy(){destroyed=true;++generation;stopUpdates();String target=id;if(target.equals(ownedId))ownedId="";if(!ending&&!target.isEmpty())store.execute(()->{try{recorder.gpsState(target,"PARTIAL_PROCESS_OR_SERVICE_STOP");}catch(RuntimeException ignored){}});super.onDestroy();}
}
