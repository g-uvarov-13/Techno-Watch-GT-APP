package app.gtdial.lab;

import android.Manifest;
import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.ZoneId;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONObject;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Process owner for the long-lived BLE session. The Activity only attaches its view listener. */
public final class WatchSessionService extends Service implements BleWatchSession.Listener {
    static final String ACTION_DISCONNECT="app.gtdial.lab.DISCONNECT";
    static final String ACTION_DRAIN_NOTIFICATIONS="app.gtdial.lab.DRAIN_NOTIFICATIONS";
    private static final String CHANNEL="watch_session";
    private final CopyOnWriteArrayList<BleWatchSession.Listener> listeners=new CopyOnWriteArrayList<>();
    public final class LocalBinder extends Binder {
        BleWatchSession session() { return session; }
        void installLocalDynamicDial(DynamicDialPackage selected){session.installLocalDynamicDial(selected);}
        boolean hasPendingLocalRecovery(){return session.hasPendingLocalRecovery();}
        void preparePendingLocalRecovery(){latestDynamicResult=null;session.preparePendingLocalRecovery();}
        void prepareLocalDynamicDial(String sha){latestDynamicResult=null;session.prepareLocalDynamicDial(sha);}
        void installConfirmedLocalDynamicDial(DynamicDialAuthorization.LocalConsent consent){latestDynamicResult=null;session.installConfirmedLocalDynamicDial(consent);}
        void cancelLocalDynamicDial(DynamicDialAuthorization.PreparedLocal prepared){session.cancelLocalDynamicDial(prepared);}
        DiagnosticLog journal() { return journal; }
        void readFreshHealth(String profile,long requestedAt){
            freshHealthRequest=new HealthEvidenceImporter.FreshRequest(profile,latestAddress==null?"":latestAddress,requestedAt);
            if("SLEEP_PAGE".equals(profile)){long to=System.currentTimeMillis()/1000;session.runSleepHistoryPage(to-TsdbReadProtocol.MAX_RANGE_SECONDS,to);}
            else if("SPO2_PAGE".equals(profile))session.runSpo2Page();else if("HEART_LAST".equals(profile))session.runLatestHeartRate();
        }
        void fetchPhoneWeather(){ui.post(()->{if(phoneWeather!=null)phoneWeather.fetch(true);});}
        boolean phoneWeatherRunning(){return phoneWeather!=null&&phoneWeather.isRunning();}
        void phoneWeatherUiActive(boolean active){ui.post(()->{phoneWeatherUiActive=active;updateWeatherUsage();});}
        void resumeWeatherAfterFullExperiment(WeatherDeliveryController.Hold expected,java.util.function.Consumer<Boolean> done){
            session.resumeWeatherAfterFullExperiment(expected,ok->ui.post(()->{if(ok&&weatherDelivery!=null)weatherDelivery.refreshed();done.accept(ok);}));
        }
        void enableWeeklyWeather(WeatherDeliveryController.Hold expected,java.util.function.Consumer<Boolean> done){
            session.enableWeeklyWeather(expected,ok->ui.post(()->{if(ok&&weatherDelivery!=null)weatherDelivery.refreshed();done.accept(ok);}));
        }
        void pauseWeeklyWeather(java.util.function.Consumer<Boolean> done){session.pauseWeeklyWeather(ok->ui.post(()->done.accept(ok)));}
        void setAutomaticWeeklyEnabled(boolean enabled,java.util.function.Consumer<Boolean> done){session.setAutomaticWeeklyEnabled(enabled,ok->ui.post(()->{if(ok&&weatherDelivery!=null)weatherDelivery.refreshed();done.accept(ok);}));}
        ActivityHistoryStore history() { return activityHistory; }
        String activityDeviceAddress() { return latestAddress; }
        void attach(BleWatchSession.Listener listener) { if(listener!=null){listeners.addIfAbsent(listener);ui.post(()->{listener.onState(latestState);if(latestName!=null)listener.onWatchFound(latestName,latestAddress);listener.onProbeReady(latestReady);listener.onConnectionSnapshot(latestConnection);listener.onPhoneWeatherChanged(phoneWeather!=null&&phoneWeather.isRunning(),latestPhoneWeatherMessage);if(latestBackup!=null)listener.onBackupReady(latestBackup);if(latestAlbumResult!=null)listener.onAlbumFinished(latestAlbumResult);if(latestStageResult!=null)listener.onStageFinished(latestStageResult,latestStageName,latestPriorAlias);if(latestActivityResult!=null)listener.onActivityFinished(latestActivityResult,latestActivity);if(latestWeatherResult!=null)listener.onWeatherReadFinished(latestWeatherResult,latestWeather);if(latestWeatherSendResult!=null)listener.onWeatherSendFinished(latestWeatherSendResult,latestWeatherSendDetail);if(latestDialProgress!=null)listener.onDialInstallProgress(latestDialProgress);if(latestDynamicResult!=null)listener.onDynamicDialFinished(latestDynamicResult);if(latestRecoveryResult!=null)listener.onDynamicDialRecoveryFinished(latestRecoveryResult);if(latestStagingBinary!=null&&latestStagingMetadata!=null)listener.onStagingAcquisitionReady(latestStagingBinary,latestStagingMetadata);if(latestAcquisitionResult!=null)listener.onStagingAcquisitionFinished(latestAcquisitionResult);if(latestFragmentResult!=null)listener.onFragmentRecoveryFinished(latestFragmentResult);if(latestCurrentDial!=null)listener.onCurrentDialSnapshot(latestCurrentDial);if(latestSleepHistory!=null)listener.onSleepHistoryFinished(latestSleepHistory);if(latestHeartRate!=null)listener.onLatestHeartRateFinished(latestHeartRate);if(latestSpo2!=null)listener.onSpo2Finished(latestSpo2);if(latestSports!=null)listener.onSportsPageFinished(latestSports);if(latestAlarms!=null)listener.onAlarmFinished(latestAlarms);});} }
        void detach(BleWatchSession.Listener listener) { listeners.remove(listener); }
    }
    private final IBinder binder = new LocalBinder();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private BleWatchSession session;
    private PhoneWeatherController phoneWeather;
    private WeatherDeliveryController weatherDelivery;
    private boolean phoneWeatherUiActive;
    private void updateWeatherUsage(){if(phoneWeather!=null)phoneWeather.setActive(phoneWeatherUiActive||latestConnection.isConnected());}
    private String latestPhoneWeatherMessage="Погода телефона обновляется автоматически каждые 30 минут";
    private DiagnosticLog journal;
    private volatile HealthEvidenceImporter.FreshRequest freshHealthRequest;
    private ActivityHistoryStore activityHistory;
    private final ExecutorService historyExecutor=Executors.newSingleThreadExecutor();
    private final Object historyLifecycle=new Object();
    private boolean historyStopping;
    private String latestState="Готово к поиску", latestName;
    private volatile String latestAddress;
    private boolean latestReady;
    private File latestBackup;
    private Boolean latestAlbumResult,latestStageResult,latestActivityResult,latestDynamicResult,latestRecoveryResult,latestAcquisitionResult;
    private File latestStagingBinary,latestStagingMetadata;
    private Boolean latestFragmentResult;
    private Boolean latestWeatherResult;
    private Boolean latestWeatherSendResult;
    private String latestWeatherSendDetail;
    private String latestStageName,latestPriorAlias;
    private ActivitySnapshot latestActivity;
    private WeatherReadProtocol.Result latestWeather;
    private CurrentDialSnapshot latestCurrentDial;
    private DialInstallProgress latestDialProgress;
    private TsdbReadProtocol.Result latestSleepHistory,latestHeartRate,latestSpo2;
    private TsdbReadProtocol.Result latestSports;
    private AlarmProtocol.Result latestAlarms;
    private ConnectionSnapshot latestConnection=ConnectionSnapshot.notReady("Готово к поиску");
    private String lastNotification=""; private long lastNotificationAt;

    private final Runnable notificationSignal=()->{if(session!=null)session.onNotificationAvailable();};
    @Override public void onCreate() {
        super.onCreate();
        WeatherDeliveryController.initializeAutomaticPolicy(this);
        journal = new DiagnosticLog(this);
        activityHistory=new ActivityHistoryStore(this);
        session = new BleWatchSession(this, this);
        NotificationSessionBridge.install(notificationSignal);
        createChannel();
        startForeground(7, notification("Watch session ready"));
        weatherDelivery=new WeatherDeliveryController(this,ui,(delivery,accepted)->session.offerWeather(delivery,accepted),this::onLog);
        phoneWeather=new PhoneWeatherController(this,ui,new PhoneWeatherController.Listener(){
            public void changed(boolean busy,String message){latestPhoneWeatherMessage=message;each(l->l.onPhoneWeatherChanged(busy,message));}
            public void snapshotSaved(){weatherDelivery.refreshed();}
            public void log(String message){onLog(message);}
        });
        updateWeatherUsage();
        session.recoverPersistedSession();
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { if(intent!=null&&ACTION_DISCONNECT.equals(intent.getAction()))session.disconnect(); return START_STICKY; }
    @Override public void onDestroy() { if(weatherDelivery!=null)weatherDelivery.close();if(phoneWeather!=null)phoneWeather.close(); NotificationSessionBridge.clear(notificationSignal); if (session != null) session.close(); session = null; listeners.clear(); synchronized(historyLifecycle){historyStopping=true;historyExecutor.execute(activityHistory::close);historyExecutor.shutdown();} if(journal!=null)journal.close();super.onDestroy(); }
    private void createChannel(){if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"Часовой — часы",NotificationManager.IMPORTANCE_LOW));}
    private Notification notification(String text){Intent i=new Intent(this,WatchSessionService.class).setAction(ACTION_DISCONNECT);PendingIntent p=PendingIntent.getService(this,1,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("Часовой").setContentText(text).addAction(new Notification.Action.Builder(null,"Disconnect",p).build()).build();}
    private void each(java.util.function.Consumer<BleWatchSession.Listener> e){ui.post(()->{for(BleWatchSession.Listener l:listeners)e.accept(l);});}
    @Override public void onState(String s){latestState=s;journal.append(s);long now=android.os.SystemClock.elapsedRealtime();boolean terminal=s.contains("Отключ")||s.contains("Ошибка")||s.contains("готов");if((terminal||!s.equals(lastNotification))&&(terminal||now-lastNotificationAt>=1000)){lastNotification=s;lastNotificationAt=now;if(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(7,notification(s));}each(l->l.onState(s));}
    @Override public void onLog(String s){journal.append(s);each(l->l.onLog(s));}
    @Override public void onWatchFound(String n,String a){boolean changed=!java.util.Objects.equals(latestAddress,a);latestName=n;latestAddress=a;AppPreferences preferences=new AppPreferences(this);preferences.savedDevice(a);preferences.savedDeviceName(n==null?"GT01":n);ui.post(()->{if(changed){latestActivity=null;latestActivityResult=null;latestSleepHistory=null;latestHeartRate=null;latestSpo2=null;latestSports=null;latestAlarms=null;}for(BleWatchSession.Listener l:listeners)l.onWatchFound(n,a);});}
    @Override public void onProbeReady(boolean r){latestReady=r;each(l->l.onProbeReady(r));if(r)ui.post(()->{if(weatherDelivery!=null)weatherDelivery.ready();});}
    @Override public void onBackupReady(File f){latestBackup=f;each(l->l.onBackupReady(f));}
    @Override public void onAlbumFinished(boolean ok){latestAlbumResult=ok;each(l->l.onAlbumFinished(ok));}
    @Override public void onStageFinished(boolean ok,String n,String a){latestStageResult=ok;latestStageName=n;latestPriorAlias=a;each(l->l.onStageFinished(ok,n,a));}
    @Override public void onActivityFinished(boolean ok,ActivitySnapshot a){
        final String device=latestAddress;
        final ZoneId phoneZone=ZoneId.systemDefault();
        final long receivedElapsed=android.os.SystemClock.elapsedRealtime();
        synchronized(historyLifecycle){
            if(historyStopping)return;
            historyExecutor.execute(()->{
                if(ok&&a!=null){
                    try{
                        if(device==null||device.isBlank())throw new IllegalStateException("device identity unavailable");
                        activityHistory.record(device,a,phoneZone);
                        ExpansionStore local=ExpansionStore.get(this);local.submit(()->{new WorkoutRecorder(this,local).observeActivity(device,a,receivedElapsed,phoneZone.getId());return true;},(recorded,error)->{if(!error.isEmpty())onLog("WORKOUT_OBSERVATION activity save failed: "+error);});
                        onLog("ACTIVITY_HISTORY save success source=current_snapshot");
                    }catch(RuntimeException e){
                        onLog("ACTIVITY_HISTORY save failed: "+e.getClass().getSimpleName()+": "+e.getMessage());
                        onState("Историю активности не удалось сохранить");
                    }
                }
                ui.post(()->{
                    if(!java.util.Objects.equals(device,latestAddress))return;
                    latestActivityResult=ok;
                    if(ok&&a!=null)latestActivity=a;
                    for(BleWatchSession.Listener l:listeners)l.onActivityFinished(ok,latestActivity);
                });
            });
        }
    }
    @Override public void onSleepHistoryFinished(TsdbReadProtocol.Result result){freshHealthRequest=null;if(result.evidenceFile!=null||result.detail.startsWith("LOCAL_GATE:")){latestSleepHistory=result;each(l->l.onSleepHistoryFinished(result));}else saveHealthEvidence(result,false);}
    @Override public void onSleepHistoryPage(TsdbReadProtocol.Result result,java.util.function.Consumer<TsdbReadProtocol.Result> acknowledged){saveHealthEvidence(result,false,acknowledged);}
    @Override public void onSpo2Finished(TsdbReadProtocol.Result result){if(result.detail.startsWith("LOCAL_GATE:")){freshHealthRequest=null;latestSpo2=result;each(l->l.onSpo2Finished(result));}else saveHealthEvidence(result,false,saved->{TsdbReadProtocol.Result delivered=saved==null?result.saveFailed("Local SpO2 evidence import failed"):saved;if(!java.util.Objects.equals(delivered.deviceAddress,latestAddress))return;latestSpo2=delivered;for(BleWatchSession.Listener listener:listeners)listener.onSpo2Finished(delivered);});}
    @Override public void onLatestHeartRateFinished(TsdbReadProtocol.Result result){if(result.detail.startsWith("LOCAL_GATE:")){freshHealthRequest=null;latestHeartRate=result;each(l->l.onLatestHeartRateFinished(result));}else saveHealthEvidence(result,true);}
    @Override public void onSportsPageFinished(TsdbReadProtocol.Result result){saveHealthEvidence(result,false);}
    @Override public void onAlarmFinished(AlarmProtocol.Result result){
        synchronized(historyLifecycle){
            if(historyStopping)return;
            historyExecutor.execute(()->{
                AlarmProtocol.Result delivered=result;
                try{delivered=result.saved(AlarmProtocol.saveEvidence(this,result));}
                catch(Exception e){delivered=result.saveFailed();OperationJournal.get(this).stage(result.operationId,"alarm_evidence","save","FAILED","FAILED","LOCAL_EVIDENCE",0,0,0);}
                final AlarmProtocol.Result evidenceResult=delivered;
                // This survives Activity recreation and follows the pending save on the storage executor.
                ExpansionStore local=ExpansionStore.get(this);
                local.submit(()->{local.finishAlarmTransfer(evidenceResult);return evidenceResult;},(applied,error)->{
                    AlarmProtocol.Result saved=error.isEmpty()?applied:evidenceResult.saveFailed();
                    if(!error.isEmpty())OperationJournal.get(this).stage(result.operationId,"alarm_binding","save","FAILED","FAILED","LOCAL_BINDING",0,0,0);
                    if(("COMPLETE".equals(saved.state)||"VERIFIED".equals(saved.state))&&saved.snapshot!=null)new AppPreferences(this).lastSourceAt("alarms",saved.receivedAt);
                    if(!java.util.Objects.equals(saved.device,latestAddress))return;latestAlarms=saved;
                    for(BleWatchSession.Listener listener:listeners)listener.onAlarmFinished(saved);
                });
            });
        }
    }
    private void saveHealthEvidence(TsdbReadProtocol.Result result,boolean heart){saveHealthEvidence(result,heart,null);}
    private void saveHealthEvidence(TsdbReadProtocol.Result result,boolean heart,java.util.function.Consumer<TsdbReadProtocol.Result> acknowledged){
        final long receivedElapsed=android.os.SystemClock.elapsedRealtime();
        final HealthEvidenceImporter.FreshRequest fresh=freshHealthRequest;
        if(!"SLEEP_PAGE".equals(result.profile))freshHealthRequest=null;
        synchronized(historyLifecycle){
            if(historyStopping){if(acknowledged!=null)ui.post(()->acknowledged.accept(null));return;}
            historyExecutor.execute(()->{
                TsdbReadProtocol.Result delivered=result;
                try{
                    if(result.receivedAt<=ExpansionStore.get(this).healthDeletionCutoff())throw new IllegalStateException("DELETED_HEALTH_RECEIPT");
                    byte[] raw=result.rawResponse(),request=result.requestBytes(),receivedMcf=result.receivedMcfBytes();
                    JSONObject evidence=new JSONObject().put("deviceAddress",result.deviceAddress).put("profile",result.profile).put("path",result.path)
                            .put("from",result.fromUnix).put("to",result.toUnix).put("index",result.index).put("count",result.requestedCount)
                            .put("_eventId",result.eventId).put("_module","tsdb_query").put("receivedAt",result.receivedAt)
                            .put("zoneIdAtReceipt",ZoneId.systemDefault().getId())
                            .put("state",result.state).put("detail",result.detail).put("rawResponseBase64",Base64.encodeToString(raw,Base64.NO_WRAP))
                            .put("rawResponseSha256",historySha(raw)).put("requestUbjsonBase64",Base64.encodeToString(request,Base64.NO_WRAP))
                            .put("requestSha256",historySha(request)).put("receivedMcfBase64",Base64.encodeToString(receivedMcf,Base64.NO_WRAP))
                            .put("receivedMcfSha256",historySha(receivedMcf));
                    File destination=new File(getFilesDir(),("SPO2_PAGE".equals(result.profile)?"spo2-page-":"SPORTS_PAGE".equals(result.profile)?"sports-page-":heart?"heart-last-":"sleep-history-")+result.eventId+".json");
                    byte[] serialized=evidence.toString().getBytes(StandardCharsets.UTF_8);
                    if(serialized.length>128*1024)throw new IllegalStateException("Health evidence size limit");
                    AtomicFile atomic=new AtomicFile(destination);FileOutputStream stream=null;
                    try{stream=atomic.startWrite();stream.write(serialized);atomic.finishWrite(stream);}
                    catch(Exception e){if(stream!=null)atomic.failWrite(stream);throw e;}
                    // AtomicFile may log a sync/rename failure without throwing. Verify the final file.
                    try(java.io.FileInputStream in=atomic.openRead();java.io.ByteArrayOutputStream verified=new java.io.ByteArrayOutputStream()){
                        byte[] chunk=new byte[4096];for(int n;(n=in.read(chunk))!=-1;){if(verified.size()+n>serialized.length)throw new IllegalStateException("Health evidence readback size");verified.write(chunk,0,n);}
                        if(!java.util.Arrays.equals(serialized,verified.toByteArray()))throw new IllegalStateException("Health evidence readback mismatch");
                    }
                    delivered=result.saved(destination);
                    final TsdbReadProtocol.Result persisted=delivered;
                    ExpansionStore local=ExpansionStore.get(this);local.submit(()->{
                        boolean imported=new HealthEvidenceImporter(this,local).importFile(destination,fresh);
                        if(heart&&imported&&!"FAILED".equals(result.state)){
                            try{WorkoutRecorder recorder=new WorkoutRecorder(this,local);for(ExpansionModels.HealthRecord record:local.healthForEvidence(result.deviceAddress,"heart",result.eventId))recorder.observeHeart(record,receivedElapsed);}
                            catch(RuntimeException e){OperationJournal.get(this).stage(result.eventId,"workout_observation","heart","FAILED","FAILED","LOCAL_OBSERVATION",0,0,0);}
                        }
                        return imported||local.imported(HealthEvidenceImporter.sha(serialized));
                    },(imported,error)->{
                        boolean ok=error.isEmpty()&&Boolean.TRUE.equals(imported);
                        TsdbReadProtocol.Result saved=ok?persisted:result.saveFailed("Local evidence import failed; no retry");
                        if(ok&&!"FAILED".equals(result.state))new AppPreferences(this).lastSourceAt("SPO2_PAGE".equals(result.profile)?"spo2":"SPORTS_PAGE".equals(result.profile)?"sports":heart?"heart":"sleep",result.receivedAt);
                        if(acknowledged!=null)acknowledged.accept(ok?persisted:null);else deliverHealth(saved,heart);
                        if(!error.isEmpty())OperationJournal.get(this).stage(result.eventId,"health_import","service_import","FAILED","FAILED",error,0,0,0);
                    });
                    onLog("HEALTH_EVIDENCE saved profile="+result.profile+" records="+result.records.size()+" rawBytes="+raw.length);
                }catch(Exception e){
                    onLog(result.profile+" evidence save failed: "+e.getClass().getSimpleName()+": "+e.getMessage());
                    onState("SPO2_PAGE".equals(result.profile)?"Не удалось сохранить измерения SpO₂":heart?"Не удалось сохранить исходный ответ пульса":"Не удалось сохранить исходный ответ истории сна");
                    delivered=result.saveFailed("Local evidence save failed; no retry");if(acknowledged!=null)ui.post(()->acknowledged.accept(null));
                }
                if("FAILED".equals(delivered.state)&&delivered.evidenceFile==null&&acknowledged==null){final TsdbReadProtocol.Result failed=delivered;ui.post(()->deliverHealth(failed,heart));}
            });
        }
    }
    private void deliverHealth(TsdbReadProtocol.Result saved,boolean heart){
        if(!java.util.Objects.equals(saved.deviceAddress,latestAddress))return;
        if("SPORTS_PAGE".equals(saved.profile)){latestSports=saved;for(BleWatchSession.Listener l:listeners)l.onSportsPageFinished(saved);}
        else if(heart){latestHeartRate=saved;for(BleWatchSession.Listener l:listeners)l.onLatestHeartRateFinished(saved);}
        else{latestSleepHistory=saved;for(BleWatchSession.Listener l:listeners)l.onSleepHistoryFinished(saved);}
    }
    private static String historySha(byte[] bytes)throws Exception{
        StringBuilder out=new StringBuilder(64);for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.US,"%02x",value&255));return out.toString();
    }
    @Override public void onWeatherReadFinished(boolean ok,WeatherReadProtocol.Result result){latestWeatherResult=ok;latestWeather=result;each(l->l.onWeatherReadFinished(ok,result));}
    @Override public void onWeatherSendFinished(boolean ok,String detail){
        if(ok)getSharedPreferences("gt_dial_preferences",MODE_PRIVATE).edit().putLong("weather.watch-verified-at",System.currentTimeMillis()).putString("weather.watch-verified-device",latestAddress==null?"":latestAddress).apply();
        if(!ok&&(detail==null||!detail.startsWith("SAFE_PREWRITE:")))ui.post(()->{if(weatherDelivery!=null)weatherDelivery.failed();});latestWeatherSendResult=ok;latestWeatherSendDetail=detail;each(l->l.onWeatherSendFinished(ok,detail));
    }
    @Override public void onDialInstallProgress(DialInstallProgress progress){latestDialProgress=progress;if(!progress.terminal())latestDynamicResult=null;each(l->l.onDialInstallProgress(progress));}
    @Override public void onDynamicDialFinished(boolean ok){latestDynamicResult=ok;each(l->l.onDynamicDialFinished(ok));}
    @Override public void onLocalDialPrepared(DynamicDialAuthorization.PreparedLocal prepared){latestDynamicResult=null;each(l->l.onLocalDialPrepared(prepared));}
    @Override public void onDynamicDialRecoveryFinished(boolean ok){latestRecoveryResult=ok;each(l->l.onDynamicDialRecoveryFinished(ok));}
    @Override public void onStagingAcquisitionReady(File binary,File metadata){latestStagingBinary=binary;latestStagingMetadata=metadata;each(l->l.onStagingAcquisitionReady(binary,metadata));}
    @Override public void onStagingAcquisitionFinished(boolean ok){latestAcquisitionResult=ok;each(l->l.onStagingAcquisitionFinished(ok));}
    @Override public void onFragmentRecoveryFinished(boolean ok){latestFragmentResult=ok;each(l->l.onFragmentRecoveryFinished(ok));}
    @Override public void onCurrentDialSnapshot(CurrentDialSnapshot snapshot){latestCurrentDial=snapshot;each(l->l.onCurrentDialSnapshot(snapshot));}
    @Override public void onConnectionSnapshot(ConnectionSnapshot snapshot){
        // ConnectionSnapshot preserves transport readiness while an exclusive operation is busy.
        boolean lost=latestConnection!=null&&latestConnection.isConnected()&&!snapshot.isConnected();latestConnection=snapshot;
        ui.post(this::updateWeatherUsage);
        if(lost){ExpansionStore local=ExpansionStore.get(this);local.submit(()->{new WorkoutRecorder(this,local).observationGap("BLE_DISCONNECTED");return true;},(saved,error)->{if(!error.isEmpty())onLog("WORKOUT_OBSERVATION gap save failed: "+error);});}each(l->l.onConnectionSnapshot(snapshot));
    }
    @Override public void onConnectionReset(){each(BleWatchSession.Listener::onConnectionReset);}
}
