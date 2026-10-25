package app.gtdial.lab;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.HandlerThread;

import java.util.Locale;
import java.util.UUID;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

final class BleWatchSession {
    interface Listener {
        void onState(String state);
        void onLog(String message);
        void onWatchFound(String name, String address);
        void onProbeReady(boolean ready);
        void onBackupReady(File file);
        void onAlbumFinished(boolean success);
        void onStageFinished(boolean success, String stagedName, String priorAlias);
        void onActivityFinished(boolean success, ActivitySnapshot snapshot);
        default void onWeatherReadFinished(boolean success, WeatherReadProtocol.Result result) { }
        default void onPhoneWeatherChanged(boolean busy,String message) { }
        default void onWeatherSendFinished(boolean success, String detail) { }
        default void onDynamicDialFinished(boolean success) { }
        default void onDialInstallProgress(DialInstallProgress progress) { }
        default void onLocalDialPrepared(DynamicDialAuthorization.PreparedLocal prepared) { }
        default void onDynamicDialRecoveryFinished(boolean success) { }
        default void onStagingAcquisitionFinished(boolean success) { }
        default void onStagingAcquisitionReady(File binary,File metadata) { }
        default void onFragmentRecoveryFinished(boolean success) { }
        default void onCurrentDialSnapshot(CurrentDialSnapshot snapshot) { }
        default void onSleepHistoryFinished(TsdbReadProtocol.Result result) { }
        default void onSleepHistoryPage(TsdbReadProtocol.Result result,java.util.function.Consumer<TsdbReadProtocol.Result> acknowledged){acknowledged.accept(null);}
        default void onSpo2Finished(TsdbReadProtocol.Result result) { }
        default void onLatestHeartRateFinished(TsdbReadProtocol.Result result) { }
        default void onSportsPageFinished(TsdbReadProtocol.Result result) { }
        default void onAlarmFinished(AlarmProtocol.Result result) { }
        default void onConnectionSnapshot(ConnectionSnapshot snapshot) { }
        default void onConnectionReset() { }
    }
    void runActivitySnapshot() {
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::runActivitySnapshot);return;}
        if (protocolProbe == null || gatt == null || txCharacteristic == null) { state("Подключите часы заново для чтения активности"); return; }
        if (protocolProbe.isRunning() || weatherReadProtocol!=null&&weatherReadProtocol.isRunning() || weatherSessionProtocol!=null&&weatherSessionProtocol.isRunning() || stageProtocol != null || dynamicDialProtocol != null || (notificationProtocol!=null&&notificationProtocol.isRunning())) { state("Другая операция уже выполняется"); return; }
        if(!beginProtocolOperation("ACTIVITY")){state("Не удалось безопасно начать чтение активности");return;}
        protocolProbe.startActivity(initialReadsComplete);
    }
    void readWeatherState() {
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::readWeatherState);return;}
        if (protocolProbe == null || weatherReadProtocol == null || gatt == null || txCharacteristic == null || !replayReady) { state("Подключите часы и дождитесь готовности"); return; }
        if (weatherReadProtocol.isRunning() || weatherSessionProtocol!=null&&weatherSessionProtocol.isRunning() || protocolProbe.isRunning() || stageProtocol != null || dynamicDialProtocol != null || (notificationProtocol!=null&&notificationProtocol.isRunning())) { state("Другая операция уже выполняется"); return; }
        if(!beginProtocolOperation("WEATHER_GET")){state("Не удалось безопасно начать чтение погоды");return;}
        weatherReadProtocol.start(protocolProbe.reservePacketId());
    }
    void sendWeather(WeatherSnapshot snapshot, String city) { sendWeather(snapshot,city,false); }
    void sendWeather(WeatherSnapshot snapshot,String city,boolean includeForecast){startWeatherSession(snapshot,city,false,includeForecast);}
    void restoreWeather() { startWeatherSession(null,null,true,false); }
    void resumeWeatherAfterFullExperiment(WeatherDeliveryController.Hold expected,java.util.function.Consumer<Boolean> done){mainHandler.post(()->{if(operationRunning()){done.accept(false);return;}boolean released=WeatherDeliveryController.releaseExactHold(context,expected);listener.onLog("WEATHER_FULL_HOLD explicit release="+released+"; session state unchanged");done.accept(released);});}
    void enableWeeklyWeather(WeatherDeliveryController.Hold expected,java.util.function.Consumer<Boolean> done){mainHandler.post(()->{if(!weatherDeliveryReady()){listener.onLog("WEATHER_WEEKLY enable refused=busy_or_not_verified");done.accept(false);return;}String device=gatt.getDevice().getAddress();boolean saved=WeatherDeliveryController.enableWeekly(context,expected,device,WeatherDeliveryController.selectionIdentity(sessionPreferences()));if(saved)saved=WeatherDeliveryController.setAutomaticWeeklyEnabled(context,true);listener.onLog("WEATHER_WEEKLY explicit enable="+saved);done.accept(saved);});}
    void pauseWeeklyWeather(java.util.function.Consumer<Boolean> done){mainHandler.post(()->{if(operationRunning()){done.accept(false);return;}boolean paused=WeatherDeliveryController.setAutomaticWeeklyEnabled(context,false);listener.onLog("WEATHER_WEEKLY explicit pause="+paused);done.accept(paused);});}
    void setAutomaticWeeklyEnabled(boolean enabled,java.util.function.Consumer<Boolean> done){mainHandler.post(()->{boolean saved=WeatherDeliveryController.setAutomaticWeeklyEnabled(context,enabled);listener.onLog("WEATHER_WEEKLY product switch="+enabled+" saved="+saved);done.accept(saved);});}
    void offerWeather(WeatherDeliveryController.Delivery delivery,java.util.function.Consumer<Boolean> accepted){
        mainHandler.post(()->{
            if(!weatherDeliveryReady()||!WeatherDeliveryController.prepareAutomaticWeekly(context,gatt.getDevice().getAddress(),delivery)){listener.onLog("WEATHER_DELIVERY automatic deferred=busy_or_weekly_policy");accepted.accept(false);return;}
            WeatherDeliveryController.WeeklyStatus weekly=WeatherDeliveryController.weeklyStatus(context);String device=gatt.getDevice().getAddress();
            if(!weatherDeliveryReady()||!"READY".equals(weekly.status)||!weekly.matches(device,delivery,WeatherDeliveryController.hold(context))||!delivery.selectionIdentity.equals(WeatherDeliveryController.selectionIdentity(sessionPreferences()))){accepted.accept(false);return;}
            startWeatherSession(delivery.snapshot,delivery.city,false,delivery,true,true);accepted.accept(true);
        });
    }
    private boolean weatherDeliveryReady(){String verified=sessionPreferences().getString("verified_device_address",null);return directInstallRequest==null&&!commandsQuarantined&&actualConnected&&!txLedgerUnreliable&&sessionIdle()&&initialReadsComplete&&replayReady&&gatt!=null&&txCharacteristic!=null&&protocolProbe!=null&&!operationRunning()&&verified!=null&&verified.equals(replayAddress)&&verified.equals(gatt.getDevice().getAddress());}
    private void startWeatherSession(WeatherSnapshot snapshot,String city,boolean restore,boolean includeForecast){
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(()->startWeatherSession(snapshot,city,restore,includeForecast));return;}
        if(!restore&&(WeatherDeliveryController.hold(context)!=null||WeatherDeliveryController.weeklyStatus(context).enabled)){listener.onWeatherSendFinished(false,"SAFE_PREWRITE: FULL_EXPERIMENT_HOLD is active");return;}
        WeatherDeliveryController.Delivery delivery=restore?null:WeatherDeliveryController.cached(context);
        if(!restore&&(delivery==null||!delivery.city.equals(city)||!delivery.snapshotIdentity.equals(WeatherDeliveryController.snapshotIdentity(snapshot)))){listener.onWeatherSendFinished(false,"SAFE_PREWRITE: refresh weather for the current selection");return;}
        if(includeForecast&&(delivery==null||!delivery.snapshot.forecastJson().equals(snapshot.forecastJson()))){listener.onWeatherSendFinished(false,"SAFE_PREWRITE: refresh forecast for current selection");return;}
        startWeatherSession(snapshot,city,restore,delivery,false,includeForecast);
    }
    private void startWeatherSession(WeatherSnapshot snapshot,String city,boolean restore,WeatherDeliveryController.Delivery delivery,boolean automatic,boolean includeForecast) {
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(()->startWeatherSession(snapshot,city,restore,delivery,automatic,includeForecast));return;}
        if(gatt==null||txCharacteristic==null||protocolProbe==null||!replayReady){state("Подключите часы и дождитесь готовности");return;}
        WeatherDeliveryController.Hold activeHold=WeatherDeliveryController.hold(context);
        WeatherDeliveryController.WeeklyStatus weekly=WeatherDeliveryController.weeklyStatus(context);
        boolean weeklyRequested=automatic&&includeForecast&&delivery!=null&&"READY".equals(weekly.status)&&weekly.matches(gatt.getDevice().getAddress(),delivery,activeHold);
        if(weekly.enabled&&!restore&&!weeklyRequested){listener.onWeatherSendFinished(false,"SAFE_PREWRITE: WEEKLY_HOLD blocks this writer");return;}
        if(activeHold!=null&&(!restore&&!weeklyRequested||!activeHold.valid()||!gatt.getDevice().getAddress().equals(activeHold.device))){listener.onWeatherSendFinished(false,"SAFE_PREWRITE: FULL_EXPERIMENT_HOLD blocks this writer");return;}
        if(weatherWriteSessionUsed){state("Эксперимент погоды уже запускался; переподключите часы");return;}
        if(!weatherDeliveryReady()){listener.onWeatherSendFinished(false,"SAFE_PREWRITE: connection busy or not verified");return;}
        if(!restore&&!new WeatherPayload(snapshot).readyForBle()){listener.onLog("WEATHER_DELIVERY automatic="+automatic+" skip=stale_or_invalid_snapshot");listener.onWeatherSendFinished(false,"SAFE_PREWRITE: stale or invalid snapshot");return;}
        if(includeForecast&&(automatic&&!weeklyRequested||!new WeatherPayload(snapshot).readyForWeekly())){listener.onWeatherSendFinished(false,"SAFE_PREWRITE: full forecast unavailable or date/zone changed");return;}
        WeatherDeliveryController.Hold claimedHold=null;WeatherDeliveryController.WeeklyStatus weeklyOwner=null;
        if(!restore){
            if(weeklyRequested){weeklyOwner=WeatherDeliveryController.claimWeekly(context,gatt.getDevice().getAddress(),delivery);if(weeklyOwner==null){listener.onWeatherSendFinished(false,"SAFE_PREWRITE: WEEKLY_HELD_OR_CONSUMED");return;}claimedHold=weeklyOwner.owner;}
            else{WeatherDeliveryController.Claim claim=includeForecast?WeatherDeliveryController.claimForecast(context,gatt.getDevice().getAddress(),delivery):WeatherDeliveryController.claim(context,gatt.getDevice().getAddress(),delivery);listener.onLog("WEATHER_DELIVERY automatic="+automatic+" full="+includeForecast+" claim="+claim.name());if(claim!=WeatherDeliveryController.Claim.CLAIMED){listener.onWeatherSendFinished(false,"SAFE_PREWRITE: "+claim.name());return;}if(includeForecast){claimedHold=WeatherDeliveryController.hold(context);if(!WeatherDeliveryController.matchesForecastClaim(claimedHold,gatt.getDevice().getAddress(),delivery)){listener.onWeatherSendFinished(false,"SAFE_PREWRITE: FULL_EXPERIMENT_HOLD claimed owner differs");return;}}}
        }
        if(!beginProtocolOperation()){if(weeklyOwner!=null)WeatherDeliveryController.pauseWeekly(context,weeklyOwner.owner,"OPERATION_MARKER_SAVE_FAILED");listener.onWeatherSendFinished(false,"SAFE_PREWRITE: operation marker save failed");return;}
        weatherWriteSessionUsed=true;activityPoll.onExclusiveStart();mainHandler.removeCallbacks(activityPollRunnable);
        final BluetoothGatt weatherGatt=gatt;
        weatherFrameWriter=new WeatherFrameWriter(mainHandler,negotiatedMtu,new WeatherFrameWriter.Transport(){
            public boolean ledgerEmpty(){return txLedger.isEmpty();}public long nextGeneration(){return ++weatherTxGeneration;}
            public boolean write(long generation,int chunk,byte[] bytes){return weatherGatt==gatt&&weatherSessionProtocol!=null&&weatherSessionProtocol.allowFrameChunk()&&writeTx(weatherGatt,bytes,generation,chunk);}
            public void complete(){if(weatherSessionProtocol!=null)weatherSessionProtocol.onFrameWritten();}
            public void failed(String reason){if(weatherSessionProtocol!=null)weatherSessionProtocol.cancel(reason);}
        });
        weatherSessionProtocol=new WeatherSessionProtocol(context,gatt.getDevice().getAddress(),negotiatedMtu-3,snapshot,city,restore,includeForecast,claimedHold,new WeatherSessionProtocol.Transport(){
            @Override public boolean write(byte[] frame){return weatherGatt==gatt&&(weatherSessionProtocol.fullMode()?weatherFrameWriter.write(frame):writeTx(weatherGatt,frame));}
            @Override public int nextPacketId(){return protocolProbe.reservePacketId();}
            @Override public int fullFrameLimit(){return txLedgerUnreliable?0:Math.min(2048,16*Math.min(509,negotiatedMtu-5));}
            @Override public void log(String message){listener.onLog(message);}
            @Override public void finished(boolean success,String detail){
                boolean safe=weatherSessionProtocol!=null&&weatherSessionProtocol.safeOutcome();
                boolean cleanupComplete;
                if(!success&&safe&&weatherSessionProtocol.completedReadOnlyRefusal())cleanupComplete=finishCompletedWeatherReadRefusal(weatherGatt,detail);
                else{
                    if(weatherFrameWriter!=null)weatherFrameWriter.close();
                    if(!success&&safe)cleanupComplete=finishLocalRefusal(detail);
                    else{terminalOperation(success);cleanupComplete=success&&sessionIdle()&&replayReady&&!commandsQuarantined;}
                }
                if(cleanupComplete)weatherWriteSessionUsed=false;
                state(success?"Эксперимент погоды завершён":"Эксперимент погоды остановлен");
                listener.onLog("WEATHER_DELIVERY automatic="+automatic+" completed success="+success+" safe="+safe+" cleanupComplete="+cleanupComplete);
                String reportedDetail=detail;
                if(!success&&!cleanupComplete&&detail!=null&&detail.startsWith("SAFE_PREWRITE:")){reportedDetail="SESSION_STOPPED: "+detail;listener.onLog("WEATHER_DELIVERY "+reportedDetail);}
                listener.onWeatherSendFinished(success,reportedDetail);
                if(cleanupComplete){activityPoll.onReady();scheduleActivityPoll(true);requestCurrentDialRefresh(0);mainHandler.post(BleWatchSession.this::maybeSendNotification);}
                else{notificationsPending=false;NotificationForwardingService.clearPending();}
            }
        });
        weatherSessionProtocol.weeklyOwner(weeklyOwner);
        weatherSessionProtocol.start();
    }

    void runSleepHistoryPage(long fromUnix,long toUnix){
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(()->runSleepHistoryPage(fromUnix,toUnix));return;}
        try{TsdbReadProtocol.validateRange(fromUnix,toUnix);}catch(IllegalArgumentException e){state(e.getMessage());listener.onSleepHistoryFinished(TsdbReadProtocol.localSleepFailure(replayAddress,fromUnix,toUnix,"LOCAL_GATE: invalid range"));return;}
        if(!sessionIdle()||!replayReady||!initialReadsComplete||gatt==null||txCharacteristic==null||operationRunning()){
            state("Для истории сна дождитесь чтения циферблата и свободного соединения");listener.onSleepHistoryFinished(TsdbReadProtocol.localSleepFailure(replayAddress,fromUnix,toUnix,"LOCAL_GATE: connection not ready or busy"));return;
        }
        BluetoothGatt active=gatt;
        int sleepPacketId=protocolProbe.reservePacketId();
        if(!beginProtocolOperation("SLEEP_PAGE")){state("Не удалось безопасно начать чтение истории сна");listener.onSleepHistoryFinished(TsdbReadProtocol.localSleepFailure(replayAddress,fromUnix,toUnix,"LOCAL_GATE: operation unavailable"));return;}
        activityPoll.onExclusiveStart();mainHandler.removeCallbacks(activityPollRunnable);
        mainHandler.removeCallbacks(currentDialRefresh);currentDialRefreshState.onDisconnected();
        notificationsPending=false;NotificationForwardingService.clearPending();ready(false);
        sleepHistoryProtocol=new TsdbReadProtocol(active.getDevice().getAddress(),fromUnix,toUnix,negotiatedMtu-3,sleepPacketId,new TsdbReadProtocol.Transport(){
            public boolean write(byte[] frame){return active==gatt&&writeTx(active,frame);}
            public void log(String message){listener.onLog(message);}
            public int nextPacketId(){return protocolProbe.reservePacketId();}
            public void persist(TsdbReadProtocol.Result result,java.util.function.Consumer<TsdbReadProtocol.Result> acknowledged){listener.onSleepHistoryPage(result,acknowledged);}
            public void finished(TsdbReadProtocol.Result result){
                sleepHistoryProtocol=null;terminalOperation(!"FAILED".equals(result.state));
                listener.onSleepHistoryFinished(result);
            }
        });
        sleepHistoryProtocol.start();
    }

    void runLatestHeartRate(){
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::runLatestHeartRate);return;}
        long nowUnix=java.time.Instant.now().getEpochSecond();
        java.time.ZoneId phoneZone=java.time.ZoneId.systemDefault();
        long midnightUnix=java.time.Instant.ofEpochSecond(nowUnix).atZone(phoneZone).toLocalDate().atStartOfDay(phoneZone).toEpochSecond();
        try{TsdbReadProtocol.validateHeartRange(nowUnix,midnightUnix);}catch(IllegalArgumentException e){listener.onLatestHeartRateFinished(TsdbReadProtocol.localHeartFailure(replayAddress,nowUnix,midnightUnix,"LOCAL_GATE: invalid range"));return;}
        if(!sessionIdle()||!replayReady||!initialReadsComplete||gatt==null||txCharacteristic==null||protocolProbe==null||operationRunning()){
            state("Для чтения пульса дождитесь чтения циферблата и свободного соединения");listener.onLatestHeartRateFinished(TsdbReadProtocol.localHeartFailure(replayAddress,nowUnix,midnightUnix,"LOCAL_GATE: connection not ready or busy"));return;
        }
        BluetoothGatt active=gatt;int reservedId=protocolProbe.reservePacketId();
        if(!beginProtocolOperation("HEART_LAST")){state("Не удалось безопасно начать чтение пульса");listener.onLatestHeartRateFinished(TsdbReadProtocol.localHeartFailure(replayAddress,nowUnix,midnightUnix,"LOCAL_GATE: operation unavailable"));return;}
        activityPoll.onExclusiveStart();mainHandler.removeCallbacks(activityPollRunnable);
        mainHandler.removeCallbacks(currentDialRefresh);currentDialRefreshState.onDisconnected();
        notificationsPending=false;NotificationForwardingService.clearPending();ready(false);
        try{sleepHistoryProtocol=new TsdbReadProtocol(TsdbReadProtocol.Profile.HEART_LAST,active.getDevice().getAddress(),nowUnix,midnightUnix,negotiatedMtu-3,reservedId,new TsdbReadProtocol.Transport(){
            public boolean write(byte[] frame){return active==gatt&&writeTx(active,frame);}
            public void log(String message){listener.onLog(message);}
            public void finished(TsdbReadProtocol.Result result){sleepHistoryProtocol=null;terminalOperation(!"FAILED".equals(result.state));listener.onLatestHeartRateFinished(result);}
        });}catch(RuntimeException e){
            sleepHistoryProtocol=null;finishLocalRefusal("Local heart query rejected");listener.onLatestHeartRateFinished(TsdbReadProtocol.localHeartFailure(replayAddress,nowUnix,midnightUnix,"Local heart query rejected: "+e.getMessage()));return;
        }
        sleepHistoryProtocol.start();
    }
    void runSpo2Page(){
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::runSpo2Page);return;}
        long to=java.time.Instant.now().getEpochSecond(),from=to-TsdbReadProtocol.MAX_RANGE_SECONDS;
        if(!sessionIdle()||!replayReady||!initialReadsComplete||gatt==null||txCharacteristic==null||protocolProbe==null||operationRunning()){listener.onSpo2Finished(TsdbReadProtocol.localSpo2Failure(replayAddress,from,to,"LOCAL_GATE: connection not ready or busy"));return;}
        BluetoothGatt active=gatt;int id=protocolProbe.reservePacketId();
        if(!beginProtocolOperation("SPO2_PAGE")){listener.onSpo2Finished(TsdbReadProtocol.localSpo2Failure(replayAddress,from,to,"LOCAL_GATE: operation unavailable"));return;}
        beginExpansionExclusive();
        try{sleepHistoryProtocol=new TsdbReadProtocol(TsdbReadProtocol.Profile.SPO2_PAGE,active.getDevice().getAddress(),from,to,negotiatedMtu-3,id,new TsdbReadProtocol.Transport(){
            public boolean write(byte[] frame){return active==gatt&&writeTx(active,frame);}
            public void log(String message){listener.onLog(message);}
            public void finished(TsdbReadProtocol.Result result){sleepHistoryProtocol=null;terminalOperation(!"FAILED".equals(result.state));listener.onSpo2Finished(result);}
        });sleepHistoryProtocol.start();}catch(RuntimeException e){sleepHistoryProtocol=null;finishLocalRefusal("Local SpO2 request rejected");listener.onSpo2Finished(TsdbReadProtocol.localSpo2Failure(replayAddress,from,to,"Local SpO2 request rejected"));}
    }
    void runSportsPage(){
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::runSportsPage);return;}
        if(!DeviceFeatureGate.sportsAllowed()){state("Новые чтения спорта отключены сборочным профилем");return;}
        if(!sessionIdle()||!replayReady||!initialReadsComplete||gatt==null||txCharacteristic==null||protocolProbe==null||operationRunning()){state("Дождитесь готовности и свободного соединения выбранных часов");return;}
        BluetoothGatt active=gatt;String exact=active.getDevice().getAddress();if(!exact.equals(replayAddress)){state("Identity текущих часов изменилась");return;}int id=protocolProbe.reservePacketId();long to=System.currentTimeMillis()/1000,from=to-TsdbReadProtocol.MAX_RANGE_SECONDS;
        if(!beginProtocolOperation())return;beginExpansionExclusive();
        try{sleepHistoryProtocol=new TsdbReadProtocol(TsdbReadProtocol.Profile.SPORTS_PAGE,exact,from,to,negotiatedMtu-3,id,new TsdbReadProtocol.Transport(){
            public boolean write(byte[] frame){return active==gatt&&writeTx(active,frame);}
            public void log(String message){listener.onLog(message);}
            public void finished(TsdbReadProtocol.Result result){sleepHistoryProtocol=null;terminalOperation("COMPLETE".equals(result.state)||"PARTIAL".equals(result.state));listener.onSportsPageFinished(result);}
        });sleepHistoryProtocol.start();}catch(RuntimeException e){sleepHistoryProtocol=null;finishLocalRefusal("Local sports request rejected");state("Местные условия спорта отклонены: "+e.getClass().getSimpleName());String op=OperationJournal.get(context).begin("sports_transport","start","SPORTS_PAGE",exact);OperationJournal.get(context).stage(op,"sports_transport","start","FAILED","FAILED","LOCAL_VALIDATION",0,0,0);}
    }
    void runAlarmRead(){startAlarm(null);}
    void runAlarmWrite(AlarmTransferPlan plan){if(plan==null){state("Точный план изменения будильника отсутствует");return;}startAlarm(plan);}
    private void startAlarm(AlarmTransferPlan plan){
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(()->startAlarm(plan));return;}
        boolean writing=plan!=null;if(writing?!DeviceFeatureGate.alarmWriteAllowed(plan.action):!DeviceFeatureGate.alarmReadAllowed()){state("Будильники отключены сборочным профилем");return;}
        if(!sessionIdle()||!replayReady||!initialReadsComplete||gatt==null||txCharacteristic==null||protocolProbe==null||operationRunning()){state("Дождитесь готовности и свободного соединения выбранных часов");return;}
        BluetoothGatt active=gatt;McfProtocolProbe probe=protocolProbe;String exact=active.getDevice().getAddress();if(!exact.equals(replayAddress)||writing&&!exact.equals(plan.device)){state("Точный источник будильников не совпадает с текущим устройством");return;}
        if(!beginProtocolOperation())return;beginExpansionExclusive();
        try{alarmProtocol=new AlarmProtocol(context,writing?AlarmProtocol.Mode.WRITE:AlarmProtocol.Mode.READ,exact,negotiatedMtu-3,plan,new AlarmProtocol.Transport(){
            public int reservePacketId(){return active==gatt&&probe==protocolProbe?probe.reservePacketId():-1;}
            public boolean write(byte[] frame){return active==gatt&&probe==protocolProbe&&writeTx(active,frame);}
            public void finished(AlarmProtocol.Result result){alarmProtocol=null;terminalOperation(plan==null&&"COMPLETE".equals(result.state));listener.onAlarmFinished(result);}
        });alarmProtocol.start();}catch(RuntimeException e){alarmProtocol=null;finishLocalRefusal("Local alarm request rejected");state("Местные условия будильника отклонены: "+e.getClass().getSimpleName());}
    }
    private void beginExpansionExclusive(){expansionRxRedacted=true;activityPoll.onExclusiveStart();mainHandler.removeCallbacks(activityPollRunnable);mainHandler.removeCallbacks(currentDialRefresh);currentDialRefreshState.onDisconnected();notificationsPending=false;NotificationForwardingService.clearPending();ready(false);}

    static final UUID WATCH_SERVICE = UUID.fromString("00000800-0000-1000-8000-00805f9b34fb");
    static final UUID WATCH_RX = UUID.fromString("00000813-0000-1000-8000-00805f9b34fb");
    static final UUID WATCH_TX = UUID.fromString("00000814-0000-1000-8000-00805f9b34fb");
    private static final UUID CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long SCAN_TIMEOUT_MS = 15_000L;

    private final Context context;
    private final Listener listener;
    private final HandlerThread sessionThread;
    private final Handler mainHandler;
    private final Runnable scanTimeout = this::onScanTimeout;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private android.bluetooth.BluetoothDevice foundDevice;
    private boolean scanning;
    private BluetoothGattCharacteristic txCharacteristic;
    private McfProtocolProbe protocolProbe;
    private WeatherReadProtocol weatherReadProtocol;
    private TsdbReadProtocol sleepHistoryProtocol;
    private AlarmProtocol alarmProtocol;
    private boolean expansionRxRedacted;
    private boolean initialReadsComplete;
    private boolean rxSubscribed,directHandshakeComplete;
    private WeatherSessionProtocol weatherSessionProtocol;
    private WeatherFrameWriter weatherFrameWriter;
    private long weatherTxGeneration;
    private static final class PendingTx {final BluetoothGatt gatt;final UUID uuid;final long weatherGeneration;final int chunk;PendingTx(BluetoothGatt gatt,UUID uuid,long generation,int chunk){this.gatt=gatt;this.uuid=uuid;weatherGeneration=generation;this.chunk=chunk;}}
    private final java.util.ArrayDeque<PendingTx> txLedger=new java.util.ArrayDeque<>();
    private boolean txLedgerUnreliable;
    private boolean commandsQuarantined,stoppingProtocols,actualConnected;
    private volatile long rxEpoch;
    private boolean operationTxAttempted,operationRxObserved;
    private boolean weatherWriteSessionUsed;
    private boolean albumSessionUsed;
    private AlbumStageProtocol stageProtocol;
    private boolean writeSessionUsed;
    private boolean activitySessionUsed;
    private CurrentDialSnapshot currentDialSnapshot;
    private final Runnable currentDialRefresh=this::refreshCurrentDialIfIdle;
    private final CurrentDialRefreshController currentDialRefreshState=new CurrentDialRefreshController();
    private final ActivityPollController activityPoll=new ActivityPollController();
    private long activityPollGeneration;
    private final Runnable activityPollRunnable=this::pollActivityIfIdle;
    private final OrdinarySyncController ordinarySync;
    private final Runnable ordinarySyncRunnable=this::runOrdinarySync;
    private boolean transportCleanup;
    private void pollActivityIfIdle(){ scheduleOrdinarySync(); }
    private void scheduleOrdinarySync(){
        mainHandler.removeCallbacks(ordinarySyncRunnable);
        if(directInstallRequest!=null)return;
        if(!sessionIdle()||!initialReadsComplete||!replayReady||gatt==null)return;
        String address=sessionPreferences().getString("verified_device_address",null);
        if(address==null||!address.equals(replayAddress))return;
        long delay=operationRunning()?1000L:ordinarySync.nextDelay(address,System.currentTimeMillis());
        listener.onLog("ORDINARY schedule delayMs="+delay);
        mainHandler.postDelayed(ordinarySyncRunnable,Math.max(250L,delay));
    }
    private void runOrdinarySync(){
        if(directInstallRequest!=null)return;
        if(!sessionIdle()||!initialReadsComplete||!replayReady||gatt==null||operationRunning()){scheduleOrdinarySync();return;}
        if(!hasRequiredPermissions()){terminalStopped();closeGatt();return;}
        String address=sessionPreferences().getString("verified_device_address",null);
        if(address==null||!address.equals(replayAddress))return;
        String profile=ordinarySync.due(address,System.currentTimeMillis());
        if("ACTIVITY".equals(profile))runActivitySnapshot();
        else if("SPO2_PAGE".equals(profile))runSpo2Page();
        else if("HEART_LAST".equals(profile))runLatestHeartRate();
        else if("SLEEP_PAGE".equals(profile)){long to=java.time.Instant.now().getEpochSecond();runSleepHistoryPage(to-TsdbReadProtocol.MAX_RANGE_SECONDS,to);}
        else if("WEATHER_GET".equals(profile))readWeatherState();
        scheduleOrdinarySync();
    }
    private int negotiatedMtu;
    private long lastWatchRxElapsed=-1,lastAcceptedTxElapsed=-1;
    private AlbumStageProtocol dynamicDialProtocol;
    private NotificationSessionProtocol notificationProtocol;
    private boolean notificationsPending;
    private boolean dynamicDialSessionUsed;
    private String replayState="Готово к поиску";
    private String replayName, replayAddress;
    private boolean replayReady;
    private ConnectionSnapshot connectionSnapshot=ConnectionSnapshot.notReady("Готово к поиску");
    private boolean manualDisconnect;
    private boolean recoveredFromProcessStart;
    private int reconnectAttempt;
    private boolean closingGatt;
    private String setupPhase="idle";
    private final long[] reconnectDelays={2_000L,5_000L,10_000L,60_000L};
    private Runnable reconnectCleanup;
    private final Runnable reconnectRunnable=this::reconnectIfIdle;
    private final Runnable reconnectTimeout=this::onReconnectTimeout;

    BleWatchSession(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        ordinarySync=new OrdinarySyncController(sessionPreferences());
        sessionThread=new HandlerThread("gt01-session");sessionThread.start();mainHandler=new Handler(sessionThread.getLooper());
    }
    private android.content.SharedPreferences sessionPreferences(){return context.getSharedPreferences("gt_dial_preferences",Context.MODE_PRIVATE);}
    private String operationKind(){return sessionPreferences().getString("session.operation-kind","UNKNOWN");}
    private boolean sessionIdle(){return SessionRecoveryPolicy.IDLE.equals(sessionPreferences().getString("session.marker",SessionRecoveryPolicy.STOPPED));}
    private boolean beginProtocolOperation(){return beginProtocolOperation("PROTECTED");}
    private boolean beginProtocolOperation(String kind){
        if(directInstallRequest!=null)return false;
        if(commandsQuarantined||!actualConnected||txLedgerUnreliable||!sessionIdle()||gatt==null)return false;
        String address=sessionPreferences().getString("verified_device_address",null);
        if(address==null||!address.equals(replayAddress)||!address.equals(gatt.getDevice().getAddress()))return false;
        String marker=SessionRecoveryPolicy.OPERATION;
        if(!sessionPreferences().edit().putString("session.marker",marker).putString("session.operation-kind",kind).commit()){terminalStopped();return false;}
        operationTxAttempted=false;operationRxObserved=false;
        if(SessionRecoveryPolicy.isOrdinary(kind)&&!ordinarySync.attempted(address,kind,System.currentTimeMillis())){terminalStopped();return false;}
        mainHandler.removeCallbacks(ordinarySyncRunnable);
        listener.onLog("ORDINARY attempt classification="+kind);
        return true;
    }
    private boolean terminalIdle(){
        if(manualDisconnect||commandsQuarantined||SessionRecoveryPolicy.STOPPED.equals(sessionPreferences().getString("session.marker",SessionRecoveryPolicy.STOPPED)))return false;
        if(!sessionPreferences().edit().putString("session.marker",SessionRecoveryPolicy.IDLE).putString("session.operation-kind","NONE").commit()){terminalStopped();return false;}return true;
    }
    private void terminalStopped(){
        if(stoppingProtocols)return;stoppingProtocols=true;
        try{
            if(reconnectCleanup!=null){mainHandler.removeCallbacks(reconnectCleanup);reconnectCleanup=null;}
            if(localPreparation!=null)finishLocalPreparation(localPreparation,"CONNECTION_STOPPED",false);
            if(dialInstallProgress!=null&&!dialInstallProgress.terminal()&&(directInstallRequest==null||directInstallStarted))installProgress("FAILED",0,0,"DISCONNECTED",dialInstallProgress.mutationStarted);
            if(!commandsQuarantined){commandsQuarantined=true;rxEpoch++;weatherTxGeneration++;localCurrentGeneration++;}
            txLedgerUnreliable=true;
            if(weatherFrameWriter!=null)weatherFrameWriter.close();weatherFrameWriter=null;
            sessionPreferences().edit().putString("session.marker",SessionRecoveryPolicy.STOPPED).putString("session.operation-kind","NONE").commit();
            ready(false);connectionState(false,actualConnected?"Часы подключены; команды остановлены":"Сессия остановлена");
            mainHandler.removeCallbacks(activityPollRunnable);activityPoll.onDisconnected();
            mainHandler.removeCallbacks(currentDialRefresh);currentDialRefreshState.onDisconnected();
            mainHandler.removeCallbacks(reconnectRunnable);mainHandler.removeCallbacks(reconnectTimeout);mainHandler.removeCallbacks(ordinarySyncRunnable);
            notificationsPending=false;NotificationForwardingService.clearPending();
            if(notificationProtocol!=null&&notificationProtocol.isRunning())notificationProtocol.cancel("Session quarantined");
            if(sleepHistoryProtocol!=null&&sleepHistoryProtocol.isRunning())sleepHistoryProtocol.cancel("Session quarantined");
            if(alarmProtocol!=null&&alarmProtocol.isRunning())alarmProtocol.cancel("Session quarantined");
            if(protocolProbe!=null&&protocolProbe.isRunning())protocolProbe.cancel("Session quarantined");
            if(weatherReadProtocol!=null&&weatherReadProtocol.isRunning())weatherReadProtocol.cancel("Session quarantined");
            if(weatherSessionProtocol!=null&&weatherSessionProtocol.isRunning())weatherSessionProtocol.cancel("Session quarantined");
            if(stageProtocol!=null&&stageProtocol.isRunning())stageProtocol.cancel("Session quarantined");
            if(dynamicDialProtocol!=null&&dynamicDialProtocol.isRunning())dynamicDialProtocol.cancel("Session quarantined");
        }finally{stoppingProtocols=false;}
    }
    private boolean finishLocalRefusal(String reason){
        if(!commandsQuarantined&&actualConnected&&initialReadsComplete&&!operationTxAttempted&&!operationRxObserved&&!txLedgerUnreliable&&txLedger.isEmpty()
                &&SessionRecoveryPolicy.OPERATION.equals(sessionPreferences().getString("session.marker",SessionRecoveryPolicy.STOPPED))&&!operationRunning()){
            listener.onLog("LOCAL_REFUSAL proven preTX/preRX: "+reason);
            if(terminalIdle()){ready(true);scheduleOrdinarySync();return true;}
        }
        terminalStopped();return false;
    }
    private boolean finishCompletedWeatherReadRefusal(BluetoothGatt capturedGatt,String reason){
        String verified=sessionPreferences().getString("verified_device_address",null);
        if(capturedGatt!=null&&capturedGatt==gatt&&actualConnected&&!manualDisconnect&&!transportCleanup&&!stoppingProtocols&&!commandsQuarantined
                &&initialReadsComplete&&replayReady&&verified!=null&&verified.equals(replayAddress)&&verified.equals(capturedGatt.getDevice().getAddress())
                &&!txLedgerUnreliable&&txLedger.isEmpty()&&weatherFrameWriter!=null&&!weatherFrameWriter.active()
                &&SessionRecoveryPolicy.OPERATION.equals(sessionPreferences().getString("session.marker",SessionRecoveryPolicy.STOPPED))&&"PROTECTED".equals(operationKind())
                &&weatherSessionProtocol!=null&&weatherSessionProtocol.completedReadOnlyRefusal()&&!operationRunning()){
            // Inspect the writer before close: closing must never manufacture a drained GET.
            weatherFrameWriter.close();
            listener.onLog("WEATHER_READ_REFUSAL proven completed GET without SET: "+reason);
            if(terminalIdle()){ready(true);scheduleOrdinarySync();return true;}
        }
        terminalStopped();return false;
    }
    private void terminalOperation(boolean success){
        listener.onLog("ORDINARY result success="+success+" classification="+operationKind()+" transportCleanup="+transportCleanup);
        if(transportCleanup||stoppingProtocols)return;
        if(success){if(terminalIdle()&&initialReadsComplete){ready(true);scheduleOrdinarySync();}}else if(!queueFreshReconnect())terminalStopped();
    }
    private void startSetupWatchdog(){setupPhase="connect";mainHandler.removeCallbacks(reconnectTimeout);if(gatt==null){logSetup("GATT creation failed",-1);state("Не удалось создать подключение Bluetooth");setupFailed(-1,true);return;}mainHandler.postDelayed(reconnectTimeout,20_000L);logSetup("started",0);}
    private void logSetup(String outcome,int status){listener.onLog("BLE setup phase="+setupPhase+" outcome="+outcome+" status="+status+" marker="+sessionPreferences().getString("session.marker",SessionRecoveryPolicy.STOPPED)+" attempt="+reconnectAttempt);}
    private void setupFailed(int status,boolean retry){OperationJournal.get(context).connectionFailure(setupPhase,status,false);logSetup("failed",status);if(directInstallRequest!=null)finishDirectInstall(false,"CONNECTION_NOT_READY");else if(!retry||!queueFreshReconnect()){terminalStopped();closeGatt();}}
    void recoverPersistedSession(){
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::recoverPersistedSession);return;}
        android.content.SharedPreferences settings=sessionPreferences();
        String address=settings.getString("verified_device_address",null);
        if(!SessionRecoveryPolicy.mayRecover(settings.getBoolean("session.userEnabled",false),address,settings.getString("session.marker",SessionRecoveryPolicy.STOPPED),settings.getString("session.operation-kind","UNKNOWN"),hasRequiredPermissions()))return;
        reconnectAttempt=Math.max(0,Math.min(3,settings.getInt("session.reconnectAttempt",0)));
        long remaining=Math.max(0,Math.min(60_000L,settings.getLong("session.reconnectNotBefore",0)-System.currentTimeMillis()));
        if(!settings.edit().putString("session.marker",SessionRecoveryPolicy.RECONNECT_WAIT).putString("session.operation-kind","NONE").putInt("session.reconnectAttempt",reconnectAttempt).putLong("session.reconnectNotBefore",System.currentTimeMillis()+remaining).commit()){terminalStopped();return;}
        try{
            BluetoothManager manager=context.getSystemService(BluetoothManager.class); BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            foundDevice=adapter==null?null:adapter.getRemoteDevice(address); manualDisconnect=false; recoveredFromProcessStart=false;
            // Publish the same verified device used for this connection before any activity callback.
            String recoveredName=foundDevice==null?null:foundDevice.getName();
            watchFound(recoveredName==null?"TECNO Watch GT":recoveredName,address);
            publishReconnectWait();
            mainHandler.removeCallbacks(reconnectRunnable);
            mainHandler.postDelayed(reconnectRunnable,remaining);
        }catch(IllegalArgumentException|SecurityException e){terminalStopped();state("Не удалось восстановить подключение");}
    }
    /** The service keeps the session while an Activity is recreated; a new view gets this snapshot. */
    void replay(Listener target) { if(target==null)return; target.onState(replayState); if(replayName!=null)target.onWatchFound(replayName,replayAddress); target.onProbeReady(replayReady); target.onConnectionSnapshot(connectionSnapshot); }
    private void state(String value){replayState=value;connectionSnapshot=connectionSnapshot.withOperationMessage(value);listener.onState(value);listener.onConnectionSnapshot(connectionSnapshot);}
    private void ready(boolean value){value=value&&actualConnected&&!commandsQuarantined;if(value&&!replayReady)OperationJournal.get(context).connectionReady();replayReady=value;connectionSnapshot=connectionSnapshot.withReady(value);listener.onProbeReady(value);listener.onConnectionSnapshot(connectionSnapshot);}
    private void watchFound(String name,String address){replayName=name;replayAddress=address;listener.onWatchFound(name,address);}
    private void connectionState(boolean ready,String message){connectionSnapshot=actualConnected?(ready&&!commandsQuarantined?ConnectionSnapshot.ready(message):ConnectionSnapshot.connected(message)):ConnectionSnapshot.notReady(message);listener.onConnectionSnapshot(connectionSnapshot);}
    private void requestCurrentDialRefresh(long delayMs){if(!sessionIdle())return;currentDialRefreshState.request();mainHandler.removeCallbacks(currentDialRefresh);mainHandler.postDelayed(currentDialRefresh,delayMs);}
    void refreshCurrentDialNow(){
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::refreshCurrentDialNow);return;}
        if(!sessionIdle()||!replayReady||!initialReadsComplete||gatt==null||operationRunning()){
            connectionEvent("dial_refresh","BLOCKED","BLOCKED","SESSION_NOT_IDLE",operationRunning());
            state("Дождитесь готовности и завершения текущей операции для обновления циферблата");return;
        }
        connectionEvent("dial_refresh","REQUESTED","PENDING","",false);requestCurrentDialRefresh(0);
    }
    private void connectionEvent(String action,String stage,String outcome,String error,boolean operating){
        OperationJournal events=OperationJournal.get(context);String id=events.begin("ble_connection",action,"KNOWN_COMMANDS",replayAddress);
        java.util.Map<String,String> metadata=new java.util.LinkedHashMap<>();
        metadata.put("operation",operating?"ACTIVE":"IDLE");
        metadata.put("setupPhase",setupPhase);
        metadata.put("ready",Boolean.toString(replayReady));
        metadata.put("sessionMarker",sessionPreferences().getString("session.marker",SessionRecoveryPolicy.STOPPED));
        metadata.put("reconnectAttempt",Integer.toString(reconnectAttempt));
        events.event(id,"","ble_connection",action,stage,outcome,error,"KNOWN_COMMANDS",replayAddress,0,0,0,metadata);
    }
    private void refreshCurrentDialIfIdle(){
        if(directInstallRequest!=null)return;
        if(!sessionIdle())return;
        if(protocolProbe==null||gatt==null||txCharacteristic==null){currentDialRefreshState.onDisconnected();return;}
        if(protocolProbe.isRunning()||weatherReadProtocol!=null&&weatherReadProtocol.isRunning()||weatherSessionProtocol!=null&&weatherSessionProtocol.isRunning()||stageProtocol!=null||dynamicDialProtocol!=null||(notificationProtocol!=null&&notificationProtocol.isRunning()))return;
        if(!currentDialRefreshState.takeIfIdle(true))return;
        if(!beginProtocolOperation("CURRENT_DIAL")){state("Не удалось безопасно начать чтение циферблата");return;}
        protocolProbe.startCurrentDial();
    }
    void onNotificationAvailable(){
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::onNotificationAvailable);return;}
        if(reconnectWaiting()||SessionRecoveryPolicy.STOPPED.equals(sessionPreferences().getString("session.marker",SessionRecoveryPolicy.STOPPED))){notificationsPending=false;NotificationForwardingService.clearPending();return;}
        notificationsPending=true;maybeSendNotification();
    }
    private void maybeSendNotification(){
        if(directInstallRequest!=null)return;
        if(!sessionIdle())return;
        if(!notificationsPending||notificationProtocol==null||notificationProtocol.isFailed()||notificationProtocol.isRunning())return;
        if(gatt==null||txCharacteristic==null||protocolProbe==null){notificationsPending=false;NotificationForwardingService.clearPending();return;}
        if(protocolProbe.isRunning()||weatherReadProtocol!=null&&weatherReadProtocol.isRunning()||weatherSessionProtocol!=null&&weatherSessionProtocol.isRunning()||stageProtocol!=null||dynamicDialProtocol!=null)return;
        android.content.SharedPreferences settings=context.getSharedPreferences("gt_dial_preferences",Context.MODE_PRIVATE);
        if(!settings.getBoolean("notification.forwarding.enabled",false)){notificationsPending=false;NotificationForwardingService.clearPending();return;}
        NotificationRequest next=NotificationForwardingService.drainPending();
        if(next==null){notificationsPending=false;return;}
        if(!beginProtocolOperation("NOTIFICATION")){notificationsPending=false;state("Не удалось безопасно начать уведомление");return;}
        notificationProtocol.start(next);
    }
    private void scheduleActivityPoll(boolean first){
        mainHandler.removeCallbacks(activityPollRunnable);
        scheduleOrdinarySync();
    }

    boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    void startScan() {
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::startScan);return;}
        if (!hasRequiredPermissions()) {
            state("Нужно разрешение Bluetooth");
            return;
        }
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            state("Bluetooth выключен");
            return;
        }
        stopScan();
        foundDevice = null;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            state("BLE-сканер недоступен");
            return;
        }

        // GT01 does not consistently advertise its 0x0800 control service, so filtering happens
        // by the exact confirmed product name while the GATT service is verified after connection.
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanning = true;
        state("Ищу TECNO Watch GT…");
        listener.onLog("BLE scan started (15 s timeout)");
        scanner.startScan(null, settings, scanCallback);
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
    }

    @SuppressLint("MissingPermission")
    void connectFoundWatch() {
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::connectFoundWatch);return;}
        if (!hasRequiredPermissions() || foundDevice == null) {
            state("Сначала найдите часы");
            if(directInstallRequest!=null)finishDirectInstall(false,"CONNECTION_NOT_READY");
            return;
        }
        android.content.SharedPreferences.Editor selection=sessionPreferences().edit().putBoolean("session.userEnabled",true).putString("session.marker",SessionRecoveryPolicy.manualReconnect()).putString("session.operation-kind","NONE");
        if(!foundDevice.getAddress().equals(sessionPreferences().getString("verified_device_address",null)))selection.remove("verified_device_address");
        if(!selection.commit()){terminalStopped();state("Не удалось сохранить выбранные часы");if(directInstallRequest!=null)finishDirectInstall(false,"CONNECTION_NOT_READY");return;}
        manualDisconnect=false; recoveredFromProcessStart=false; reconnectAttempt=Math.max(0,Math.min(3,sessionPreferences().getInt("session.reconnectAttempt",0))); mainHandler.removeCallbacks(reconnectRunnable);
        if(reconnectCleanup!=null){mainHandler.removeCallbacks(reconnectCleanup);reconnectCleanup=null;}
        stopScan();
        transportCleanup=true;try{closeGatt();}finally{transportCleanup=false;}
        // Old protocol cancellation may call disconnect; reset only after it unwinds.
        manualDisconnect=false;recoveredFromProcessStart=false;
        if(!sessionPreferences().edit().putString("session.marker",SessionRecoveryPolicy.IDLE).putString("session.operation-kind","NONE").commit()){terminalStopped();if(directInstallRequest!=null)finishDirectInstall(false,"CONNECTION_NOT_READY");return;}
        expansionRxRedacted=false;
        state("Подключаюсь…");
        listener.onLog("Connecting to " + foundDevice.getAddress());
        try{OperationJournal.get(context).connectionAttempt();gatt=foundDevice.connectGatt(context,false,gattCallback,android.bluetooth.BluetoothDevice.TRANSPORT_LE);startSetupWatchdog();}
        catch(SecurityException e){terminalStopped();closeGatt();state("Разрешение Bluetooth отозвано");}
        catch(RuntimeException e){setupFailed(-1,true);}
    }

    @SuppressLint("MissingPermission")
    void disconnect() {
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::disconnect);return;}
        if(directInstallRequest!=null)finishDirectInstall(false,"DISCONNECTED");
        listener.onLog("BLE disconnect requested locally; operationActive="+operationRunning()+" marker="+sessionPreferences().getString("session.marker",SessionRecoveryPolicy.STOPPED));
        connectionEvent("local_disconnect","REQUESTED","PENDING","",operationRunning());OperationJournal.get(context).connectionStopped();
        manualDisconnect=true;sessionPreferences().edit().putBoolean("session.userEnabled",false).commit(); terminalStopped(); mainHandler.removeCallbacks(reconnectRunnable);
        activityPoll.onDisconnected(); mainHandler.removeCallbacks(activityPollRunnable);mainHandler.removeCallbacks(ordinarySyncRunnable);
        mainHandler.removeCallbacks(currentDialRefresh);
        currentDialRefreshState.onDisconnected();
        stopScan();
        if (gatt != null) {
            gatt.disconnect();
        }
        closeGatt();
        state("Отключено");
    }

    @SuppressLint("MissingPermission")
    void runReadOnlyProtocolProbe() {
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::runReadOnlyProtocolProbe);return;}
        if (gatt == null || txCharacteristic == null || protocolProbe == null) {
            state("Сначала подключите часы и дождитесь готовности");
            return;
        }
        if (protocolProbe.isRunning() || weatherReadProtocol!=null&&weatherReadProtocol.isRunning() || weatherSessionProtocol!=null&&weatherSessionProtocol.isRunning() || stageProtocol != null || dynamicDialProtocol != null || (notificationProtocol!=null&&notificationProtocol.isRunning())) { state("Другая операция уже выполняется"); return; }
        if(!beginProtocolOperation()){state("Не удалось безопасно начать диагностику");return;}
        protocolProbe.start();
    }

    @SuppressLint("MissingPermission")
    void runAlbumConfigurationRead() {
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::runAlbumConfigurationRead);return;}
        if (gatt == null || txCharacteristic == null || protocolProbe == null) {
            state("Сначала подключите часы и дождитесь готовности");
            return;
        }
        if (albumSessionUsed) {
            state("Настройки фотоциферблата уже читались; переподключите часы");
            listener.onLog("ALBUM rejected: one session per BLE connection");
            return;
        }
        if (protocolProbe.isRunning() || weatherReadProtocol!=null&&weatherReadProtocol.isRunning() || weatherSessionProtocol!=null&&weatherSessionProtocol.isRunning() || stageProtocol != null || dynamicDialProtocol != null || (notificationProtocol!=null&&notificationProtocol.isRunning())) {
            state("Другая диагностическая операция уже выполняется");
            return;
        }
        if(!beginProtocolOperation()){state("Не удалось безопасно начать чтение настроек");return;}
        albumSessionUsed = true;
        protocolProbe.startAlbum();
    }

    boolean isAlbumConfigurationAvailable() {
        return gatt != null && txCharacteristic != null && protocolProbe != null
                && !protocolProbe.isRunning() && (weatherReadProtocol==null||!weatherReadProtocol.isRunning()) && (weatherSessionProtocol==null||!weatherSessionProtocol.isRunning()) && !albumSessionUsed && (notificationProtocol==null||!notificationProtocol.isRunning());
    }

    boolean isAlbumStageAvailable() {
        return gatt != null && txCharacteristic != null && negotiatedMtu >= 512;
    }

    void stageAlbumImage(byte[] image, String basename, int positionIndex, int color) {
        if(Looper.myLooper()!=mainHandler.getLooper()){byte[] copy=image==null?null:image.clone();mainHandler.post(()->stageAlbumImage(copy,basename,positionIndex,color));return;}
        if (!isAlbumStageAvailable() || protocolProbe == null || protocolProbe.isRunning() || weatherReadProtocol!=null&&weatherReadProtocol.isRunning() || weatherSessionProtocol!=null&&weatherSessionProtocol.isRunning() || stageProtocol != null || dynamicDialProtocol != null || (notificationProtocol!=null&&notificationProtocol.isRunning())) {
            state("Часы не готовы или уже выполняется операция"); return;
        }
        if (writeSessionUsed) { state("Запись уже запускалась; переподключите часы"); return; }
        if(!beginProtocolOperation()){state("Не удалось безопасно начать запись");return;}
        writeSessionUsed = true;
        activityPoll.onExclusiveStart(); mainHandler.removeCallbacks(activityPollRunnable);
        BluetoothGatt active = gatt;
        stageProtocol = new AlbumStageProtocol(image, basename, positionIndex, color, new AlbumStageProtocol.Transport() {
            public boolean write(byte[] frame) { return active == gatt && writeTx(active, frame); }
            public void log(String message) { listener.onLog(message); }
            public boolean backup(byte[] bytes, String sha256) { return false; }
            public void terminal(boolean success) {
                String name = success ? stageProtocol.stagedName() : null;
                String alias = success ? stageProtocol.priorAlias() : null;
                stageProtocol = null;
                listener.onStageFinished(success, name, alias);
                terminalOperation(success); if(success)requestCurrentDialRefresh(0);
            }
        }, negotiatedMtu - 3);
        ready(false);
        stageProtocol.start();
    }

    void installBundledDynamicDial(BundledDynamicDial.VerifiedPackage selected) {
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(()->installBundledDynamicDial(selected));return;}
        if(directInstallRequest!=null){state("Установка уже ожидает соединения или выполняется");return;}
        try{
            if(selected==null||selected.variant==null||selected.bytes==null||selected.bytes.length!=selected.variant.packageSize)throw new IllegalArgumentException("package");
            queueDirectInstall(selected.bytes,DynamicDialPackage.bundled(selected.variant),null,null);
        }catch(RuntimeException|OutOfMemoryError e){directInstallRefused("PACKAGE_GATE_FAILED");}
    }

    private void verifyCapturedFragmentEvidence() throws IOException {
        File dir=new File(context.getFilesDir(),"backups/staging-acquisitions");String base="staging-acquisition-"+AlbumStageProtocol.CAPTURED_FRAGMENT_SHA256;
        File binary=new File(dir,base+".dial");if(binary.length()!=414720)throw new IOException("captured staging copy missing");
        byte[] bytes=Files.readAllBytes(binary.toPath());File[] candidates=dir.listFiles((d,n)->n.startsWith(base+"-")&&n.endsWith(".json"));
        if(candidates!=null&&candidates.length<=128)for(File metadata:candidates){
            if(metadata.length()<1||metadata.length()>32768)continue;
            try{AlbumStageProtocol.validateFragmentEvidence(bytes,Files.readAllBytes(metadata.toPath()));return;}catch(Exception ignored){}
        }
        throw new IOException("verified acquisition metadata missing");
    }

    void recoverCapturedFragment(){
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::recoverCapturedFragment);return;}
        listener.onLog("FRAGMENT_RECOVERY_UNAVAILABLE_PUBLIC_RELEASE");
        terminalStopped();
        listener.onFragmentRecoveryFinished(false);
        state("Историческое восстановление недоступно в публичной версии");
    }

    private static final class LocalPreparation {
        final BluetoothGatt gatt;final long epoch;final String address,operationId;volatile String sha;final boolean recovery;
        volatile DialInstallJournal journal;volatile boolean cancelled;
        DynamicDialAuthorization.PreparedLocal prepared;boolean consumed;
        Runnable expiry;
        LocalPreparation(BluetoothGatt gatt,long epoch,String address,String sha,boolean recovery){this.gatt=gatt;this.epoch=epoch;this.address=address;this.sha=sha;this.recovery=recovery;operationId=UUID.randomUUID().toString();}
    }
    private LocalPreparation localPreparation;
    private static final class DirectInstallRequest {
        final String operationId,address,name;final byte[] bytes;final DynamicDialPackage target;final String[] fields;
        final android.bluetooth.BluetoothDevice device;final long queuedAt;
        DirectInstallRequest(android.bluetooth.BluetoothDevice device,byte[] bytes,DynamicDialPackage target,String name,String[] fields){
            this.device=device;address=device.getAddress();operationId=UUID.randomUUID().toString();queuedAt=android.os.SystemClock.elapsedRealtime();
            this.bytes=bytes.clone();this.target=target;this.name=name;this.fields=fields==null?null:fields.clone();
        }
    }
    private DirectInstallRequest directInstallRequest;
    private boolean directInstallStarted,directConnectAttempted,directConnecting;
    private BluetoothGatt directInstallGatt;private long directInstallEpoch;
    private CurrentDialSnapshot directLocalSnapshot;private String directLocalAddress,directLocalPackageId;
    private final Runnable directInstallPump=this::pumpDirectInstall;
    private void directLog(String message){try{listener.onLog(message);}catch(RuntimeException|OutOfMemoryError ignored){/* A diagnostic sink never controls a direct installation. */}}
    private void directInstallRefused(String error){
        if(directInstallRequest!=null){state("Установка уже ожидает соединения или выполняется");return;}
        dialInstallStartedAt=android.os.SystemClock.elapsedRealtime();installProgress("FAILED",0,0,error,false);listener.onDynamicDialFinished(false);
    }
    private void queueDirectInstall(byte[] bytes,DynamicDialPackage target,String name,String[] fields){
        if(directInstallRequest!=null){state("Установка уже ожидает соединения или выполняется");return;}
        android.bluetooth.BluetoothDevice exact=gatt!=null?gatt.getDevice():foundDevice;
        if(exact==null||!hasRequiredPermissions()){directInstallRefused("CONNECTION_NOT_READY");return;}
        directInstallRequest=new DirectInstallRequest(exact,bytes,target,name,fields);
        directInstallStarted=false;directConnectAttempted=false;directConnecting=false;
        dialInstallStartedAt=directInstallRequest.queuedAt;
        mainHandler.removeCallbacks(ordinarySyncRunnable);mainHandler.removeCallbacks(activityPollRunnable);mainHandler.removeCallbacks(currentDialRefresh);mainHandler.removeCallbacks(reconnectRunnable);
        activityPoll.onExclusiveStart();installProgress("PREPARE",0,0,"",false);
        directLog("DIAL DIRECT queued operationId="+directInstallRequest.operationId+" address="+directInstallRequest.address+" target="+target.internalPackageId+"/"+target.alias+" bytes="+bytes.length);
        pumpDirectInstall();
    }
    private boolean directTransportUsable(DirectInstallRequest request){
        return request!=null&&gatt!=null&&request.address.equals(gatt.getDevice().getAddress())&&actualConnected&&txCharacteristic!=null
            &&rxSubscribed&&negotiatedMtu>=512&&!commandsQuarantined&&!txLedgerUnreliable&&!manualDisconnect
            &&!SessionRecoveryPolicy.STOPPED.equals(sessionPreferences().getString("session.marker",SessionRecoveryPolicy.STOPPED));
    }
    @SuppressLint("MissingPermission")
    private void pumpDirectInstall(){
        mainHandler.removeCallbacks(directInstallPump);DirectInstallRequest request=directInstallRequest;
        if(request==null||directInstallStarted||directConnecting)return;
        if(operationRunning()||localPreparation!=null){
            if(android.os.SystemClock.elapsedRealtime()-request.queuedAt>=35_000L){finishDirectInstall(false,"WAIT_TIMEOUT");return;}
            installProgress("PREPARE",0,0,"WAITING_FOR_OPERATION",false);mainHandler.postDelayed(directInstallPump,150L);return;
        }
        if(!directTransportUsable(request)){
            if(directConnectAttempted||!hasRequiredPermissions()){finishDirectInstall(false,"CONNECTION_NOT_READY");return;}
            // The queued user action permits one fresh setup; quarantine is never lifted on the old GATT.
            directConnectAttempted=true;directConnecting=true;foundDevice=request.device;
            installProgress("PREPARE",0,0,"CONNECTING",false);
            try{connectFoundWatch();if(directInstallRequest==request&&gatt==null)finishDirectInstall(false,"CONNECTION_NOT_READY");}
            catch(RuntimeException e){finishDirectInstall(false,"CONNECTION_NOT_READY");}
            return;
        }
        if(!sessionPreferences().edit().putString("session.marker",SessionRecoveryPolicy.OPERATION).putString("session.operation-kind","DIRECT_DYNAMIC_INSTALL").commit()){finishDirectInstall(false,"CONNECTION_NOT_READY");return;}
        directInstallGatt=gatt;directInstallEpoch=rxEpoch;directInstallStarted=true;operationTxAttempted=false;operationRxObserved=false;
        writeSessionUsed=true;dynamicDialSessionUsed=true;ready(false);
        final BluetoothGatt active=gatt;final long epoch=rxEpoch;
        try{
            dynamicDialProtocol=AlbumStageProtocol.directInstall(request.bytes,request.target,directHandshakeComplete,new AlbumStageProtocol.Transport(){
                public boolean write(byte[] frame){return directInstallRequest==request&&active==gatt&&epoch==rxEpoch&&directTransportUsable(request)&&writeTx(active,frame);}
                public void log(String message){directLog(message);}
                public boolean backup(byte[] bytes,String hash){return false;}
                public void progress(String phase,long done,long total,String error,boolean mutated){if(directInstallRequest==request)installProgress(phase,done,total,error,mutated);}
                public void terminal(boolean success){String error=dynamicDialProtocol==null?"PROTOCOL_FAILED":dynamicDialProtocol.terminalError();finishDirectInstall(success,error);}
            },new AlbumStageProtocol.Scheduler(){public void removeCallbacks(Runnable r){mainHandler.removeCallbacks(r);}public void postDelayed(Runnable r,long delay){mainHandler.postDelayed(r,delay);}},negotiatedMtu-3);
            dynamicDialProtocol.start();
        }catch(RuntimeException|OutOfMemoryError e){finishDirectInstall(false,"PROTOCOL_FAILED");}
    }
    private void finishDirectInstall(boolean success,String error){
        DirectInstallRequest request=directInstallRequest;if(request==null)return;
        if(dynamicDialProtocol!=null&&dynamicDialProtocol.isDirectInstall()&&dynamicDialProtocol.isRunning()){dynamicDialProtocol.cancel(error);return;}
        boolean mutated=dialInstallProgress!=null&&dialInstallProgress.mutationStarted;
        boolean healthy=success&&directInstallStarted&&directInstallGatt==gatt&&directInstallEpoch==rxEpoch&&directTransportUsable(request);
        if(!success&&!error.contains(" phase="))error=error+" phase="+(dialInstallProgress==null?"PREPARE":dialInstallProgress.activePhase)+" offset=0";
        if(dynamicDialProtocol!=null&&dynamicDialProtocol.isDirectInstall())dynamicDialProtocol=null;
        mainHandler.removeCallbacks(directInstallPump);mainHandler.removeCallbacks(reconnectTimeout);
        installProgress(success?"COMPLETE":"FAILED",0,0,success?"DIRECT_CONFIRMED":error,mutated);
        directInstallRequest=null;directInstallStarted=false;directConnecting=false;
        if(healthy&&terminalIdle()){
            directHandshakeComplete=true;initialReadsComplete=true;writeSessionUsed=false;dynamicDialSessionUsed=false;
            ready(true);activityPoll.onReady();scheduleActivityPoll(true);requestCurrentDialRefresh(0);scheduleOrdinarySync();
        }else terminalStopped();
        if(success&&request.target.origin==DynamicDialPackage.Origin.LOCAL_PROFILE_BUILD){
            new LocalDialPreviewIndex(context).remember(request.address,request.target.internalPackageId,request.target.alias,request.target.artifactSha256);
            directLocalSnapshot=CurrentDialSnapshot.local(request.target.alias,request.name==null?request.target.projectId:request.name,request.target.artifactSha256,request.fields,System.currentTimeMillis());
            directLocalAddress=request.address;directLocalPackageId=request.target.internalPackageId;currentDialSnapshot=directLocalSnapshot;listener.onCurrentDialSnapshot(currentDialSnapshot);
        }
        directLog("DIAL DIRECT terminal operationId="+request.operationId+" success="+success+" error="+error);
        listener.onDynamicDialFinished(success);
        if(success&&!replayReady)state("Часы подтвердили установку и применение; соединение остановлено");
    }
    private long dialInstallStartedAt;
    private DialInstallProgress dialInstallProgress;
    private void installProgress(String phase,long done,long total,String error,boolean mutated){
        if(dialInstallStartedAt==0)dialInstallStartedAt=android.os.SystemClock.elapsedRealtime();
        String activePhase=("FAILED".equals(phase)||"CANCELLED".equals(phase))&&dialInstallProgress!=null?dialInstallProgress.activePhase:phase;
        dialInstallProgress=new DialInstallProgress(phase,done,total,dialInstallStartedAt,error,mutated,activePhase,directInstallRequest!=null);
        listener.onDialInstallProgress(dialInstallProgress);
    }
    void installLocalDynamicDial(DynamicDialPackage selected) {
        installLocalDynamicDial(selected,selected==null?"":selected.projectId,null);
    }
    void installLocalDynamicDial(DynamicDialPackage selected,String name,String[] fields){
        final String[] copiedFields=fields==null?null:fields.clone();
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(()->installLocalDynamicDial(selected,name,copiedFields));return;}
        if(directInstallRequest!=null){state("Установка уже ожидает соединения или выполняется");return;}
        try{
            if(!DynamicDialAuthorization.localCandidate(selected))throw new IllegalArgumentException("local package");
            byte[] bytes=selected.localBytes();
            if(bytes.length!=selected.packageSize||!hash(bytes).equals(selected.packageSha256))throw new IllegalArgumentException("local bytes");
            queueDirectInstall(bytes,selected,name,copiedFields);
        }catch(RuntimeException|OutOfMemoryError e){directInstallRefused("PACKAGE_GATE_FAILED");}
    }
    boolean hasPendingLocalRecovery(){
        try{String address=gatt!=null?gatt.getDevice().getAddress():foundDevice!=null?foundDevice.getAddress():null;return address!=null&&new DynamicDialOwnershipStore(context).hasPendingLocalRecovery(address);}catch(RuntimeException e){return false;}
    }
    void preparePendingLocalRecovery(){prepareLocalDynamicDial(null,true);}
    void prepareLocalDynamicDial(String sha){prepareLocalDynamicDial(sha,false);}
    private void prepareLocalDynamicDial(String sha,boolean recovery) {
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(()->prepareLocalDynamicDial(sha,recovery));return;}
        if(localPreparation==null){dialInstallStartedAt=android.os.SystemClock.elapsedRealtime();installProgress("PREPARE",0,0,"",false);}
        if(!recovery&&(sha==null||!sha.matches("[0-9a-f]{64}"))||localPreparation!=null||!weatherDeliveryReady()||writeSessionUsed||dynamicDialSessionUsed){state("Дождитесь готовности и свободного соединения выбранных часов");listener.onLog("LOCAL_DIAL PREPARE_GATE_DENIED");if(localPreparation==null)installProgress("FAILED",0,0,"CONNECTION_NOT_READY",false);listener.onDynamicDialFinished(false);return;}
        String address;
        try{address=DynamicDialOwnershipStore.normalizeAddress(gatt.getDevice().getAddress());}catch(IOException e){installProgress("FAILED",0,0,"CONNECTION_NOT_READY",false);listener.onDynamicDialFinished(false);return;}
        if(!beginProtocolOperation("LOCAL_DIAL_PREPARE")){installProgress("FAILED",0,0,"CONNECTION_NOT_READY",false);listener.onDynamicDialFinished(false);return;}
        LocalPreparation pending=new LocalPreparation(gatt,rxEpoch,address,sha,recovery);localPreparation=pending;
        activityPoll.onExclusiveStart();mainHandler.removeCallbacks(activityPollRunnable);mainHandler.removeCallbacks(currentDialRefresh);
        notificationsPending=false;NotificationForwardingService.clearPending();
        pending.expiry=()->finishLocalPreparation(pending,"EXPIRED",true);mainHandler.postDelayed(pending.expiry,DynamicDialAuthorization.LOCAL_CONSENT_MS);
        state("Проверяю сборку циферблата…");
        new Thread(()->{try{
            // An uncertain operation retains its selected artifact and trace; never replace the pointer on this refusal.
            DynamicDialOwnershipStore preparationOwnership=new DynamicDialOwnershipStore(context);
            DynamicDialOwnershipStore.RecoveryContext recoveryContext=null;
            if(recovery){recoveryContext=preparationOwnership.recoveryContext(address);pending.sha=recoveryContext.target.artifactSha256;}else preparationOwnership.requireNoPendingForLocal();
            if(pending.cancelled)throw new IOException("LOCAL_CANCELLED");
            DialInstallJournal journal=new DialInstallJournal(context,pending.operationId,pending.sha,recoveryContext==null?"":recoveryContext.originalOperationId);pending.journal=journal;
            if(pending.cancelled){journal.terminal(false,"CANCELLED","CANCELLED");return;}
            CompiledDialArtifactStore.Artifact artifact=new CompiledDialArtifactStore(context).load(pending.sha,journal,"FULL_VALIDATION_PREPARE");
            DynamicDialAuthorization.PreparedLocal prepared=recoveryContext==null?DynamicDialAuthorization.prepareLocal(artifact,address,pending.operationId):DynamicDialAuthorization.prepareRecovery(artifact,address,pending.operationId,recoveryContext);journal.header(prepared);journal.sync();
            mainHandler.post(()->{
                if(!localPreparationCurrent(pending)){finishLocalPreparation(pending,"CONNECTION_CHANGED",false);return;}
                pending.prepared=prepared;mainHandler.removeCallbacks(pending.expiry);mainHandler.postDelayed(pending.expiry,DynamicDialAuthorization.remainingLocalMillis(prepared));
                state("Сборка проверена; подтвердите установку точного пакета");installProgress("CONFIRM",0,0,"",false);listener.onLocalDialPrepared(prepared);
            });
        }catch(Exception|OutOfMemoryError e){finishPreparationWorkerFailure(pending,"PREPARE_FAILED",e);}},"dial-local-prepare").start();
    }
    private boolean localPreparationCurrent(LocalPreparation p){
        return p!=null&&!p.cancelled&&localPreparation==p&&gatt==p.gatt&&gatt!=null&&rxEpoch==p.epoch&&actualConnected&&!commandsQuarantined&&!txLedgerUnreliable
            &&initialReadsComplete&&replayReady&&txCharacteristic!=null&&protocolProbe!=null&&!operationRunning()&&"LOCAL_DIAL_PREPARE".equals(operationKind())
            &&p.address.equals(gatt.getDevice().getAddress())&&p.address.equals(replayAddress);
    }
    void installConfirmedLocalDynamicDial(DynamicDialAuthorization.LocalConsent consent){
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(()->installConfirmedLocalDynamicDial(consent));return;}
        LocalPreparation pending=localPreparation;
        if(!localPreparationCurrent(pending)||pending.prepared==null||pending.consumed){listener.onLog("LOCAL_DIAL CONSENT_GATE_DENIED");if(pending!=null)finishLocalPreparation(pending,"CONSENT_REJECTED",false);else listener.onDynamicDialFinished(false);return;}
        try{DynamicDialAuthorization.consumeLocal(consent,pending.prepared);pending.consumed=true;pending.journal.event("CONSENT","CONSUMED");pending.journal.sync();}
        catch(Exception|OutOfMemoryError e){finishLocalPreparation(pending,"CONSENT_REJECTED",false);return;}
        installProgress("PREPARE",0,0,"",false);
        new Thread(()->{try{
            CompiledDialArtifactStore.Artifact artifact=new CompiledDialArtifactStore(context).load(pending.sha,pending.journal,"FULL_VALIDATION_EXECUTE");
            DynamicDialAuthorization.LocalExecution execution=DynamicDialAuthorization.validateConsumedLocal(consent,artifact,pending.address,new DynamicDialOwnershipStore(context),new DynamicDialBackupStore(context.getFilesDir()));
            pending.journal.event("EXECUTION","VALIDATED");pending.journal.sync();
            String[] fields=new String[artifact.snapshot.project.elements.size()];for(int i=0;i<fields.length;i++)fields[i]=DialRuntimeProfile.field(artifact.snapshot.project.elements.get(i).id).label;
            mainHandler.post(()->{
                if(!localPreparationCurrent(pending)){finishLocalPreparation(pending,"CONNECTION_CHANGED",false);return;}
                mainHandler.removeCallbacks(pending.expiry);localPreparation=null;
                try{startDynamicDial(execution.target.localBytes(),execution.target,false,execution,pending.journal,fields);}
                catch(Exception|OutOfMemoryError e){stopLocalStart(pending.journal,"EXECUTION_START_FAILED");listener.onDynamicDialFinished(false);}
            });
        }catch(Exception|OutOfMemoryError e){finishPreparationWorkerFailure(pending,"VALIDATION_FAILED",e);}},"dial-local-execute").start();
    }
    void cancelLocalDynamicDial(DynamicDialAuthorization.PreparedLocal prepared){
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(()->cancelLocalDynamicDial(prepared));return;}
        LocalPreparation pending=localPreparation;if(pending==null||pending.prepared!=prepared||pending.consumed)return;
        finishLocalPreparation(pending,"CANCELLED",true);
    }
    private void finishLocalPreparation(LocalPreparation pending,String outcome,boolean release){
        if(pending==null)return;boolean current=localPreparation==pending;pending.cancelled=true;DynamicDialAuthorization.cancelLocal(pending.prepared);mainHandler.removeCallbacks(pending.expiry);
        if(current)localPreparation=null;
        if(pending.journal!=null)try{pending.journal.terminal(false,outcome,outcome);}catch(IOException|OutOfMemoryError e){listener.onLog("LOCAL_DIAL TERMINAL_LOG_FAILED");}
        if(!current)return;
        installProgress("CANCELLED".equals(outcome)||"EXPIRED".equals(outcome)?"CANCELLED":"FAILED",0,0,outcome,false);
        listener.onLog("LOCAL_DIAL "+outcome+" operationId="+pending.operationId);
        if(release&&gatt==pending.gatt&&rxEpoch==pending.epoch&&!commandsQuarantined&&"LOCAL_DIAL_PREPARE".equals(operationKind())){if(terminalIdle()){ready(true);scheduleOrdinarySync();}}
        else terminalStopped();listener.onDynamicDialFinished(false);state(release?"Установка не начата":"Проверка или соединение изменились; установка остановлена");
    }
    private void finishPreparationWorkerFailure(LocalPreparation pending,String error,Throwable cause){
        String code=cause instanceof OutOfMemoryError?"MEMORY_FAILED":cause instanceof IOException?"IO_OR_VALIDATION_FAILED":"VALIDATION_FAILED";
        if(cause instanceof IOException){
            String message=cause.getMessage();
            if(message!=null&&message.startsWith("LOCAL_LOG"))code="LOG_FAILED";
            else for(String fixed:new String[]{"RECOVERY_PENDING_NOT_SOLE","RECOVERY_PENDING_INVALID","RECOVERY_PRIOR_CHANGED","RECOVERY_TARGET_INVALID","RECOVERY_TARGET_CHANGED","RECOVERY_PENDING_CHANGED","RECOVERY_CONTEXT_INVALID","RECOVERY_PREPARATION_CHANGED","BACKUP_UNREADABLE","BACKUP_TUPLE_INVALID","BACKUP_PATH","BACKUP_HASH_MISMATCH","BACKUP_VERIFIED_TUPLE","BACKUP_WITNESS_LIMIT","BACKUP_WITNESS_FAILED","BACKUP_EXISTING_CHANGED","BACKUP_SHORT_WRITE","BACKUP_READBACK","BACKUP_TARGET_EXISTS","BACKUP_DIRECTORY","PRIOR_RECEIPT_REQUIRED","HISTORY_LIMIT","HISTORY_INVALID","HISTORY_CHANGED","HISTORY_PATH"})if(fixed.equals(message)){code=fixed;break;}
            if(!pending.recovery&&"IO_OR_VALIDATION_FAILED".equals(code))try{if(new DynamicDialOwnershipStore(context).pendingMutationPresent())code="PENDING_RECOVERY_REQUIRED";}catch(IOException ignored){/* Preserve the original bounded IO failure. */}
        }
        listener.onLog("LOCAL_DIAL "+error+" error="+code+" operationId="+pending.operationId);
        if(pending.journal!=null)try{pending.journal.terminal(false,"FAILED",code);}catch(IOException|OutOfMemoryError e){listener.onLog("LOCAL_DIAL TERMINAL_LOG_FAILED");}
        final String failureCode=code;mainHandler.post(()->finishLocalPreparation(pending,failureCode,false));
    }
    private void startDynamicDial(BundledDynamicDial.VerifiedPackage selected,boolean fragmentRecovery) {
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(()->startDynamicDial(selected,fragmentRecovery));return;}
        if(selected==null||selected.variant==null||!selected.variant.liveInstallAllowed()||selected.bytes.length!=selected.variant.packageSize||!hash(selected.bytes).equals(selected.variant.packageSha256)){state("Для этой пары циферблата и цвета нет физического допуска к BLE-записи");if(fragmentRecovery)listener.onFragmentRecoveryFinished(false);return;}
        startDynamicDial(selected.bytes,DynamicDialPackage.bundled(selected.variant),fragmentRecovery);
    }
    private void startDynamicDial(byte[] packageBytes,DynamicDialPackage target,boolean fragmentRecovery) {
        startDynamicDial(packageBytes,target,fragmentRecovery,null,null,null);
    }
    private void startDynamicDial(byte[] packageBytes,DynamicDialPackage target,boolean fragmentRecovery,DynamicDialAuthorization.LocalExecution localExecution,DialInstallJournal localJournal,String[] localFields) {
        boolean local=localExecution!=null&&target==localExecution.target&&DynamicDialAuthorization.localCandidate(target)&&!fragmentRecovery;
        final boolean pendingRecovery=local&&localExecution.recovery!=null;
        if(!local){dialInstallStartedAt=android.os.SystemClock.elapsedRealtime();}
        installProgress("CHECK",0,0,"",false);
        if(!(local||DynamicDialAuthorization.physicallyAllowed(target))||packageBytes.length!=target.packageSize||!hash(packageBytes).equals(target.packageSha256)){stopLocalStart(localJournal,"PACKAGE_GATE_FAILED");state("Пакет не допущен к установке");listener.onDynamicDialFinished(false);return;}
        if (gatt == null || txCharacteristic == null || protocolProbe == null || dynamicDialProtocol != null
                || stageProtocol != null || protocolProbe.isRunning() || weatherReadProtocol!=null&&weatherReadProtocol.isRunning() || (weatherSessionProtocol!=null&&weatherSessionProtocol.isRunning()) || (notificationProtocol!=null&&notificationProtocol.isRunning())) {stopLocalStart(localJournal,"CONNECTION_GATE_FAILED");state("Другая операция уже выполняется или часы не готовы");listener.onDynamicDialFinished(false);return; }
        if(writeSessionUsed||dynamicDialSessionUsed){stopLocalStart(localJournal,"SESSION_ALREADY_USED");state("Запись уже запускалась; переподключите часы");if(fragmentRecovery)listener.onFragmentRecoveryFinished(false);if(local)listener.onDynamicDialFinished(false);return;}
        if(local){if(commandsQuarantined||!actualConnected||txLedgerUnreliable||!replayReady||!initialReadsComplete||!"LOCAL_DIAL_PREPARE".equals(operationKind())||!localExecution.prepared.deviceAddress.equals(gatt.getDevice().getAddress())
            ||!sessionPreferences().edit().putString("session.operation-kind",pendingRecovery?"LOCAL_VERIFY_PENDING":"LOCAL_DYNAMIC_INSTALL").commit()){stopLocalStart(localJournal,"EXECUTION_CONNECTION_FAILED");listener.onDynamicDialFinished(false);return;}}
        else if(!beginProtocolOperation()){state("Не удалось безопасно начать запись");if(fragmentRecovery)listener.onFragmentRecoveryFinished(false);return;}
        writeSessionUsed = true;
        dynamicDialSessionUsed = true;
        activityPoll.onExclusiveStart(); mainHandler.removeCallbacks(activityPollRunnable);
        mainHandler.removeCallbacks(currentDialRefresh);notificationsPending=false;NotificationForwardingService.clearPending();
        BluetoothGatt active = gatt;
        long activeEpoch=rxEpoch;
        final String watchAddress;
        final DynamicDialOwnershipStore ownershipStore=new DynamicDialOwnershipStore(context);
        final DynamicDialOwnershipStore.Receipt priorReceipt;
        try{watchAddress=DynamicDialOwnershipStore.normalizeAddress(local?active.getDevice().getAddress():foundDevice.getAddress());priorReceipt=pendingRecovery?localExecution.prior:ownershipStore.load(watchAddress,BundledDynamicDial.REMOTE_PATH);if(local&&!DynamicDialOwnershipStore.fingerprint(priorReceipt).equals(DynamicDialOwnershipStore.fingerprint(localExecution.prior)))throw new IOException("LOCAL_PRIOR_CHANGED");}
        catch(IOException e){stopLocalStart(localJournal,"OWNERSHIP_LOAD_FAILED");state("Предыдущее состояние владения не подтверждено; запись заблокирована");listener.onLog("DIAL ownership receipt refused: "+e.getMessage());terminalStopped();listener.onDynamicDialFinished(false);return;}
        final String[] failureMessage={"Установка остановлена; отправьте отчёт об ошибке"};
        AlbumStageProtocol.Transport operationTransport = new AlbumStageProtocol.Transport() {
            public void progress(String phase,long done,long total,String error,boolean mutated){installProgress(phase,done,total,error,mutated);}
            public boolean write(byte[] frame) { return active==gatt&&(!local||rxEpoch==activeEpoch&&!commandsQuarantined&&!txLedgerUnreliable)&&writeTx(active,frame); }
            public void log(String message) {
                listener.onLog(message);
                if(message.contains("unexpected response id/type"))failureMessage[0]="Установка остановлена: посторонний ответ часов. Отключите MyHealth и другие приложения часов перед новой попыткой.";
                else if(message.contains("select built-in Bolt01"))failureMessage[0]="Для этой установки сначала выберите встроенный Bolt01 на часах.";
                else if(message.contains("SAME_IDENTITY_DIFFERENT_PACKAGE"))failureMessage[0]="Циферблат с тем же ID и alias содержит другой пакет; запись не начата. Отправьте отчёт об ошибке.";
                else if(message.contains("existing hash")||message.contains("existing size")||message.contains("missing receipt"))failureMessage[0]="Предыдущая загрузка не совпадает с подтверждённой копией; запись не начата. Отправьте отчёт об ошибке.";
            }
            public boolean backup(byte[] bytes, String sha256) {
                File dir = new File(context.getFilesDir(), "dynamic-staging-backups");
                File target = new File(dir, "staging-"+hash(bytes)+".dial");
                try {
                    if (!dir.isDirectory()) {if(!dir.mkdirs())return false;try(java.nio.channels.FileChannel parent=java.nio.channels.FileChannel.open(dir.getParentFile().toPath(),java.nio.file.StandardOpenOption.READ)){parent.force(true);}}
                    if (target.exists()) {
                        byte[] prior = Files.readAllBytes(target.toPath());
                        if (prior.length != bytes.length || !hash(prior).equalsIgnoreCase(sha256)) return false;
                    } else {
                        File temporary = File.createTempFile("1909202601-", ".tmp", dir);
                        try {
                            try (FileOutputStream out = new FileOutputStream(temporary, false)) { out.write(bytes); out.getFD().sync(); }
                            byte[] staged = Files.readAllBytes(temporary.toPath());
                            if (staged.length != bytes.length || !hash(staged).equalsIgnoreCase(sha256)) return false;
                            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                        } finally {
                            if(!local&&temporary.exists()&&!temporary.delete())listener.onLog("DIAL backup temporary file retained: "+temporary.getName());
                        }
                    }
                    try(java.nio.channels.FileChannel directory=java.nio.channels.FileChannel.open(dir.toPath(),java.nio.file.StandardOpenOption.READ)){directory.force(true);}
                    byte[] reread = Files.readAllBytes(target.toPath());
                    if (reread.length != bytes.length || !hash(reread).equalsIgnoreCase(sha256)) return false;
                    listener.onLog("DIAL staging backup verified device="+watchAddress+" path="+BundledDynamicDial.REMOTE_PATH+" bytes="+bytes.length+" sha256="+sha256+" local="+target.getName());
                    return true;
                } catch (IOException e) { listener.onLog("DIAL backup failed: " + e.getMessage()); return false; }
            }
            public boolean saveVerifiedBackup(DynamicDialOwnershipStore.Receipt next,byte[] bytes){
                if(!local)return false;try{new DynamicDialBackupStore(context.getFilesDir()).saveVerified(next,bytes);return true;}catch(IOException|OutOfMemoryError e){listener.onLog("LOCAL_DIAL BACKUP_WITNESS_FAILED");return false;}
            }
            public boolean saveRecoveredReceipt(DynamicDialOwnershipStore.RecoveryContext recovery,String exactCurrent,DynamicDialOwnershipStore.Receipt next){
                if(!pendingRecovery||recovery!=localExecution.recovery)return false;
                installProgress("RECOVERY_COMMIT",0,0,"",false);
                try{return ownershipStore.saveAfterVerifiedRecovery(recovery,exactCurrent,next);}catch(IOException|OutOfMemoryError e){listener.onLog("LOCAL_DIAL RECOVERY_RECEIPT_FAILED");return false;}
            }
            public boolean armDynamicMutation(String operationId,DynamicDialOwnershipStore.Receipt prior,DynamicDialPackage descriptor,String backupHash,long backupSize){try{
                if(local){if(descriptor!=localExecution.target||!localExecution.matches(operationId,watchAddress,descriptor))return false;return ownershipStore.armLocalMutation(operationId,watchAddress,BundledDynamicDial.REMOTE_PATH,prior,localExecution,backupHash,backupSize);}
                return ownershipStore.armMutation(operationId,watchAddress,BundledDynamicDial.REMOTE_PATH,prior,descriptor,backupHash,backupSize);
            }catch(IOException e){listener.onLog("DIAL durable mutation arm failed: "+e.getMessage());return false;}}
            public boolean saveMutationReceipt(String operationId,String exactCurrent,DynamicDialOwnershipStore.Receipt expected,DynamicDialOwnershipStore.Receipt next){try{return ownershipStore.saveAfterVerifiedMutation(operationId,exactCurrent,expected,next);}catch(IOException e){listener.onLog("DIAL ownership receipt CAS failed; future writes blocked: "+e.getMessage());return false;}}
            public void terminal(boolean success) {
                String terminalError=dynamicDialProtocol==null?"PROTOCOL_FAILED":dynamicDialProtocol.terminalError();
                boolean unchanged=dynamicDialProtocol!=null&&dynamicDialProtocol.alreadyInstalled();
                boolean mutated=!pendingRecovery&&!unchanged;
                if(success)installProgress("COMPLETE",0,0,unchanged?"ALREADY_INSTALLED":pendingRecovery?"RECOVERY_VERIFIED":"",mutated);
                dynamicDialProtocol=null;
                boolean released=success&&releaseDynamicDialSession(active,activeEpoch,ownershipStore,watchAddress,target);
                if(!released)terminalStopped();
                boolean logFailed=false;
                if(localJournal!=null)try{localJournal.terminal(success,success?"SUCCEEDED":"FAILED",success?"":terminalError);logFailed=localJournal.loggingFailed();}catch(IOException|OutOfMemoryError e){logFailed=true;listener.onLog(success?"PROTOCOL_SUCCESS_LOG_FAILED":"LOCAL_DIAL TERMINAL_LOG_FAILED");}
                if(success&&logFailed&&!unchanged)installProgress("COMPLETE",0,0,"LOG_FAILED",mutated);
                if(success&&local)listener.onCurrentDialSnapshot(CurrentDialSnapshot.local(target.alias,localExecution.prepared.projectName,target.artifactSha256,localFields,System.currentTimeMillis()));
                listener.onDynamicDialFinished(success);
                if(success&&logFailed)state("Циферблат установлен; журнал не удалось полностью сохранить. Повторять установку не нужно.");
                if(success&&unchanged)state("Этот циферблат уже установлен");
                if(success&&!released)state("Циферблат подтверждён; соединение остановлено, так как проверка готовности не пройдена. Повторять установку не нужно.");
                if(!success)state(failureMessage[0]);
            }
        };
        try{dynamicDialProtocol=fragmentRecovery?AlbumStageProtocol.capturedFragmentRecovery(packageBytes,target.bundled,watchAddress,priorReceipt,operationTransport,negotiatedMtu-3):local?new AlbumStageProtocol(localExecution,watchAddress,priorReceipt,operationTransport,negotiatedMtu-3,localJournal):new AlbumStageProtocol(packageBytes,target.bundled,watchAddress,priorReceipt,operationTransport,negotiatedMtu-3,true);}
        catch(IllegalArgumentException e){stopLocalStart(localJournal,local&&"PRIOR_RECEIPT_REQUIRED".equals(e.getMessage())?"PRIOR_RECEIPT_REQUIRED":"PROTOCOL_CONSTRUCTION_FAILED");listener.onLog("DIAL operation refused: "+e.getMessage());terminalStopped();listener.onDynamicDialFinished(false);return;}
        ready(false);
        dynamicDialProtocol.start();
    }
    private boolean releaseDynamicDialSession(BluetoothGatt active,long activeEpoch,DynamicDialOwnershipStore ownershipStore,String watchAddress,DynamicDialPackage target){
        if(active!=gatt||activeEpoch!=rxEpoch||!actualConnected||manualDisconnect||commandsQuarantined||txLedgerUnreliable
                ||!initialReadsComplete||transportCleanup||stoppingProtocols)return false;
        try{
            if(ownershipStore.pendingMutationPresent())return false;
            DynamicDialOwnershipStore.Receipt receipt=ownershipStore.load(watchAddress,BundledDynamicDial.REMOTE_PATH);
            if(receipt==null||!receipt.internalPackageId.equals(target.internalPackageId)||!receipt.alias.equals(target.alias)
                    ||receipt.packageSize!=target.packageSize||!receipt.packageSha256.equals(target.packageSha256))return false;
        }catch(IOException|SecurityException|OutOfMemoryError e){listener.onLog("DIAL terminal release refused: ownership verification failed");return false;}
        terminalOperation(true);
        if(!sessionIdle()||!replayReady||active!=gatt||activeEpoch!=rxEpoch||!actualConnected||commandsQuarantined||txLedgerUnreliable)return false;
        // Re-enable a new user-requested install only after durable success and IDLE/ready.
        writeSessionUsed=false;dynamicDialSessionUsed=false;
        activityPoll.onReady();scheduleActivityPoll(true);requestCurrentDialRefresh(0);scheduleOrdinarySync();
        listener.onLog("DIAL terminal released: verified receipt, no pending mutation, IDLE/ready");return true;
    }
    private void stopLocalStart(DialInstallJournal journal,String error){installProgress("FAILED",0,0,error,false);if(journal==null)return;try{journal.terminal(false,"FAILED",error);}catch(IOException|OutOfMemoryError e){listener.onLog("LOCAL_DIAL TERMINAL_LOG_FAILED");}terminalStopped();}

    void recoverCapturedDynamicDialReceipt() {
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::recoverCapturedDynamicDialReceipt);return;}
        if(gatt==null||txCharacteristic==null||protocolProbe==null||dynamicDialProtocol!=null||stageProtocol!=null||protocolProbe.isRunning()||(weatherReadProtocol!=null&&weatherReadProtocol.isRunning())||(weatherSessionProtocol!=null&&weatherSessionProtocol.isRunning())||(notificationProtocol!=null&&notificationProtocol.isRunning())){state("Часы не готовы или уже выполняется операция");listener.onDynamicDialRecoveryFinished(false);return;}
        final BundledDynamicDial.VerifiedPackage captured;
        try{captured=BundledDynamicDialRepository.capturedAnimationForRecovery(context);}
        catch(IOException e){state("Пакет восстановления не прошёл локальную проверку");listener.onLog("DIAL recovery package refused: "+e.getMessage());listener.onDynamicDialRecoveryFinished(false);return;}
        if(!beginProtocolOperation()){state("Не удалось безопасно начать восстановление receipt");listener.onDynamicDialRecoveryFinished(false);return;}
        activityPoll.onExclusiveStart();mainHandler.removeCallbacks(activityPollRunnable);
        BluetoothGatt active=gatt;final String watchAddress;final DynamicDialOwnershipStore ownershipStore=new DynamicDialOwnershipStore(context);final DynamicDialOwnershipStore.Receipt priorReceipt;
        try{watchAddress=DynamicDialOwnershipStore.normalizeAddress(foundDevice.getAddress());priorReceipt=ownershipStore.load(watchAddress,BundledDynamicDial.REMOTE_PATH);}
        catch(IOException e){state("Состояние владения не удалось прочитать");listener.onLog("DIAL recovery receipt load failed: "+e.getMessage());terminalOperation(false);listener.onDynamicDialRecoveryFinished(false);return;}
        try{dynamicDialProtocol=AlbumStageProtocol.readOnlyDynamicRecovery(captured.bytes,captured.variant,watchAddress,priorReceipt,new AlbumStageProtocol.Transport(){
            public boolean write(byte[] frame){return active==gatt&&writeTx(active,frame);}
            public void log(String message){listener.onLog(message);}
            public boolean backup(byte[] bytes,String sha256){
                File dir=new File(context.getFilesDir(),"dynamic-staging-backups");File target=new File(dir,"staging-"+hash(bytes)+".dial");
                try{if(!dir.isDirectory()&&!dir.mkdirs())return false;if(target.exists()){byte[] prior=Files.readAllBytes(target.toPath());if(prior.length!=bytes.length||!hash(prior).equalsIgnoreCase(sha256))return false;}else{File temporary=File.createTempFile("1909202601-",".tmp",dir);try{try(FileOutputStream out=new FileOutputStream(temporary,false)){out.write(bytes);out.getFD().sync();}byte[] staged=Files.readAllBytes(temporary.toPath());if(staged.length!=bytes.length||!hash(staged).equalsIgnoreCase(sha256))return false;Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);}finally{if(temporary.exists()&&!temporary.delete())listener.onLog("DIAL recovery backup temporary file retained: "+temporary.getName());}}byte[] reread=Files.readAllBytes(target.toPath());if(reread.length!=bytes.length||!hash(reread).equalsIgnoreCase(sha256))return false;listener.onLog("DIAL recovery staging backup verified device="+watchAddress+" path="+BundledDynamicDial.REMOTE_PATH+" bytes="+bytes.length+" sha256="+sha256+" local="+target.getName());return true;}catch(IOException e){listener.onLog("DIAL recovery backup failed: "+e.getMessage());return false;}
            }
            public boolean saveOwnershipReceipt(DynamicDialOwnershipStore.Receipt expected,DynamicDialOwnershipStore.Receipt next){try{return ownershipStore.saveAfterVerifiedCurrent(expected,next);}catch(IOException e){listener.onLog("DIAL recovery receipt save failed: "+e.getMessage());return false;}}
            public void terminal(boolean success){dynamicDialProtocol=null;terminalOperation(success);if(success){ready(true);activityPoll.onReady();scheduleActivityPoll(true);requestCurrentDialRefresh(0);}listener.onDynamicDialRecoveryFinished(success);}
        },new AlbumStageProtocol.Scheduler(){public void removeCallbacks(Runnable r){mainHandler.removeCallbacks(r);}public void postDelayed(Runnable r,long delayMs){mainHandler.postDelayed(r,delayMs);}},negotiatedMtu-3);}catch(IllegalArgumentException e){state("Состояние восстановления отклонено");listener.onLog("DIAL recovery protocol refused: "+e.getMessage());terminalOperation(false);listener.onDynamicDialRecoveryFinished(false);return;}
        ready(false);dynamicDialProtocol.start();
    }

    void acquireCapturedStaging() {
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::acquireCapturedStaging);return;}
        if(gatt==null||txCharacteristic==null||protocolProbe==null||dynamicDialProtocol!=null||stageProtocol!=null||protocolProbe.isRunning()||(weatherReadProtocol!=null&&weatherReadProtocol.isRunning())||(weatherSessionProtocol!=null&&weatherSessionProtocol.isRunning())||(notificationProtocol!=null&&notificationProtocol.isRunning())||writeSessionUsed||dynamicDialSessionUsed){state("Часы не готовы или сессия уже использована");listener.onStagingAcquisitionFinished(false);return;}
        if(!beginProtocolOperation()){state("Не удалось начать чтение staging");listener.onStagingAcquisitionFinished(false);return;}
        writeSessionUsed=true;dynamicDialSessionUsed=true;
        activityPoll.onExclusiveStart();mainHandler.removeCallbacks(activityPollRunnable);mainHandler.removeCallbacks(currentDialRefresh);
        notificationsPending=false;NotificationForwardingService.clearPending();
        BluetoothGatt active=gatt;
        final String watchAddress;final DynamicDialOwnershipStore.Receipt priorReceipt;
        try{watchAddress=DynamicDialOwnershipStore.normalizeAddress(foundDevice.getAddress());priorReceipt=new DynamicDialOwnershipStore(context).load(watchAddress,BundledDynamicDial.REMOTE_PATH);}
        catch(IOException e){listener.onLog("STAGING_ACQUISITION receipt refused: "+e.getMessage());terminalStopped();listener.onStagingAcquisitionFinished(false);return;}
        final File evidenceDir=new File(context.getFilesDir(),"backups/staging-acquisitions");
        final File[] capturedFile=new File[1],metadataFile=new File[1];
        try{dynamicDialProtocol=AlbumStageProtocol.readOnlyStagingAcquisition(watchAddress,priorReceipt,new AlbumStageProtocol.Transport(){
            public boolean write(byte[] frame){return active==gatt&&writeTx(active,frame);}
            public void log(String message){listener.onLog(message);}
            public boolean backup(byte[] bytes,String sha256){
                try{
                    if(bytes.length!=414720||!hash(bytes).equals(sha256))return false;
                    if(!evidenceDir.isDirectory()&&!evidenceDir.mkdirs())return false;
                    File target=new File(evidenceDir,"staging-acquisition-"+sha256+".dial");
                    if(!target.exists()){
                        File temporary=File.createTempFile("staging-acquisition-",".tmp",evidenceDir);
                        try{try(FileOutputStream out=new FileOutputStream(temporary,false)){out.write(bytes);out.getFD().sync();}if(!hash(Files.readAllBytes(temporary.toPath())).equals(sha256))return false;Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);}
                        finally{if(temporary.exists()&&!temporary.delete())listener.onLog("STAGING_ACQUISITION temporary file retained: "+temporary.getName());}
                    }
                    byte[] saved=Files.readAllBytes(target.toPath());if(saved.length!=bytes.length||!hash(saved).equals(sha256))return false;
                    capturedFile[0]=target;listener.onLog("STAGING_ACQUISITION binary saved bytes="+saved.length+" sha256="+sha256+" file="+target.getName());return true;
                }catch(IOException e){listener.onLog("STAGING_ACQUISITION binary save failed: "+e.getMessage());return false;}
            }
            public boolean saveAcquisitionEvidence(String sha256,String currentBefore,String inventoryBefore,String currentAfter,String inventoryAfter){
                if(capturedFile[0]==null||!capturedFile[0].isFile())return false;
                try{
                    JSONObject receipt=null;
                    if(priorReceipt!=null)receipt=new JSONObject().put("id",priorReceipt.internalPackageId).put("alias",priorReceipt.alias).put("size",priorReceipt.packageSize).put("sha256",priorReceipt.packageSha256).put("verifiedAtEpochMs",priorReceipt.verifiedAtEpochMs);
                    JSONObject evidence=new JSONObject().put("schemaVersion",1).put("outcome","verified-acquisition").put("deviceAddress",watchAddress).put("remotePath",BundledDynamicDial.REMOTE_PATH).put("capturedAtEpochMs",System.currentTimeMillis()).put("size",414720).put("sha256",sha256).put("currentBefore",currentBefore).put("inventoryBefore",new org.json.JSONArray(inventoryBefore)).put("currentAfter",currentAfter).put("inventoryAfter",new org.json.JSONArray(inventoryAfter)).put("priorReceipt",receipt==null?JSONObject.NULL:receipt);
                    byte[] data=evidence.toString().getBytes(StandardCharsets.UTF_8);
                    File target=new File(evidenceDir,"staging-acquisition-"+sha256+"-"+System.currentTimeMillis()+".json");
                    File temporary=File.createTempFile("staging-evidence-",".tmp",evidenceDir);
                    try{try(FileOutputStream out=new FileOutputStream(temporary,false)){out.write(data);out.getFD().sync();}if(!java.util.Arrays.equals(data,Files.readAllBytes(temporary.toPath())))return false;Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);}
                    finally{if(temporary.exists()&&!temporary.delete())listener.onLog("STAGING_ACQUISITION metadata temporary file retained: "+temporary.getName());}
                    if(!java.util.Arrays.equals(data,Files.readAllBytes(target.toPath())))return false;
                    metadataFile[0]=target;
                    listener.onLog("STAGING_ACQUISITION evidence saved file="+target.getName()+" receiptChanged=false");return true;
                }catch(Exception e){listener.onLog("STAGING_ACQUISITION metadata save failed: "+e.getMessage());return false;}
            }
            public void terminal(boolean success){dynamicDialProtocol=null;terminalStopped();if(success&&capturedFile[0]!=null&&metadataFile[0]!=null)listener.onStagingAcquisitionReady(capturedFile[0],metadataFile[0]);listener.onStagingAcquisitionFinished(success);}
        },new AlbumStageProtocol.Scheduler(){public void removeCallbacks(Runnable r){mainHandler.removeCallbacks(r);}public void postDelayed(Runnable r,long delayMs){mainHandler.postDelayed(r,delayMs);}},negotiatedMtu-3);}
        catch(IllegalArgumentException e){listener.onLog("STAGING_ACQUISITION refused: "+e.getMessage());terminalStopped();listener.onStagingAcquisitionFinished(false);return;}
        ready(false);dynamicDialProtocol.start();
    }

    private static String hash(byte[] bytes) {
        try { StringBuilder s=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes)) s.append(String.format(Locale.US,"%02x",b&255)); return s.toString(); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }

    @SuppressLint("MissingPermission")
    void close() {
        if(Looper.myLooper()!=mainHandler.getLooper()){mainHandler.post(this::close);return;}
        if(directInstallRequest!=null)finishDirectInstall(false,"DISCONNECTED");
        mainHandler.removeCallbacks(reconnectRunnable);mainHandler.removeCallbacks(ordinarySyncRunnable);
        stopScan();
        String marker=sessionPreferences().getString("session.marker",SessionRecoveryPolicy.STOPPED);
        boolean recoverable=sessionIdle()||SessionRecoveryPolicy.RECONNECT_WAIT.equals(marker)||SessionRecoveryPolicy.OPERATION.equals(marker)&&SessionRecoveryPolicy.isOrdinary(operationKind());
        if(reconnectCleanup!=null){mainHandler.removeCallbacks(reconnectCleanup);reconnectCleanup=null;}
        transportCleanup=recoverable;
        if(!recoverable)terminalStopped();
        closeGatt();transportCleanup=false;
        sessionThread.quitSafely();
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        mainHandler.removeCallbacks(scanTimeout);
        if (scanning && scanner != null) {
            scanner.stopScan(scanCallback);
        }
        scanning = false;
    }

    private void onScanTimeout() {
        if (!scanning) {
            return;
        }
        stopScan();
        if (foundDevice == null) {
            state("Часы не найдены");
            listener.onLog("Scan timed out; wake the watch and keep it near the phone");
        }
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if(closingGatt)return;
        closingGatt=true;actualConnected=false;rxEpoch++;weatherTxGeneration++;localCurrentGeneration++;
        txLedger.clear();if(weatherFrameWriter!=null)weatherFrameWriter.close();weatherFrameWriter=null;
        boolean interruptedOperation=localPreparation!=null||operationRunning();
        if(localPreparation!=null)finishLocalPreparation(localPreparation,"CONNECTION_STOPPED",false);
        mainHandler.removeCallbacks(reconnectTimeout);
        connectionState(false,"Часы не подключены");
        notificationsPending=false;
        if(notificationProtocol!=null&&notificationProtocol.isRunning())notificationProtocol.cancel("BLE connection closed");
        notificationProtocol=null;
        listener.onConnectionReset();
        ready(false);
        albumSessionUsed = false;
        writeSessionUsed = false;
        activitySessionUsed = false;
        weatherWriteSessionUsed = false;
        activityPoll.onDisconnected(); mainHandler.removeCallbacks(activityPollRunnable);mainHandler.removeCallbacks(ordinarySyncRunnable);
        mainHandler.removeCallbacks(currentDialRefresh);
        currentDialRefreshState.onDisconnected();
        dynamicDialSessionUsed = false;
        negotiatedMtu = 0;initialReadsComplete=false;rxSubscribed=false;directHandshakeComplete=false;
        if(sleepHistoryProtocol!=null){TsdbReadProtocol closing=sleepHistoryProtocol;sleepHistoryProtocol=null;if(closing.isRunning())closing.cancel("BLE connection closed");}
        if(alarmProtocol!=null){AlarmProtocol closing=alarmProtocol;alarmProtocol=null;if(closing.isRunning())closing.cancel("BLE connection closed");}
        txCharacteristic = null;
        if (protocolProbe != null&&protocolProbe.isRunning()) protocolProbe.cancel("BLE connection closed");
        if (weatherReadProtocol != null&&weatherReadProtocol.isRunning()) weatherReadProtocol.cancel("BLE connection closed");
        weatherReadProtocol=null;
        if (weatherSessionProtocol != null&&weatherSessionProtocol.isRunning()) weatherSessionProtocol.cancel("BLE connection closed");
        weatherSessionProtocol=null;
        if (stageProtocol != null&&stageProtocol.isRunning()) stageProtocol.cancel("BLE connection closed");
        if (dynamicDialProtocol != null&&dynamicDialProtocol.isRunning()) dynamicDialProtocol.cancel("BLE connection closed");
        dynamicDialProtocol = null;
        stageProtocol = null;
        protocolProbe = null;
        if (gatt != null) {
            gatt.close();
            gatt = null;
        }
        if(!transportCleanup){if(manualDisconnect||interruptedOperation)terminalStopped();else terminalIdle();}
        commandsQuarantined=false;txLedgerUnreliable=false;closingGatt=false;
    }

    private boolean operationRunning(){return (alarmProtocol!=null&&alarmProtocol.isRunning())||(sleepHistoryProtocol!=null&&sleepHistoryProtocol.isRunning())||(protocolProbe!=null&&protocolProbe.isRunning())||(weatherReadProtocol!=null&&weatherReadProtocol.isRunning())||(weatherSessionProtocol!=null&&weatherSessionProtocol.isRunning())||(stageProtocol!=null&&stageProtocol.isRunning())||(dynamicDialProtocol!=null&&dynamicDialProtocol.isRunning())||(notificationProtocol!=null&&notificationProtocol.isRunning());}
    private boolean reconnectWaiting(){return SessionRecoveryPolicy.RECONNECT_WAIT.equals(sessionPreferences().getString("session.marker",SessionRecoveryPolicy.STOPPED));}
    @SuppressLint("MissingPermission")
    private boolean recoveryEligible(){
        String address=sessionPreferences().getString("verified_device_address",null),marker=sessionPreferences().getString("session.marker",SessionRecoveryPolicy.STOPPED);
        boolean knownPush=SessionRecoveryPolicy.OPERATION.equals(marker)&&"NOTIFICATION".equals(operationKind())&&notificationProtocol!=null&&notificationProtocol.canRecoverPushTransport();
        return !manualDisconnect&&directInstallRequest==null&&!commandsQuarantined&&!txLedgerUnreliable&&!transportCleanup&&!stoppingProtocols
                &&sessionPreferences().getBoolean("session.userEnabled",false)&&hasRequiredPermissions()&&address!=null
                &&(knownPush||SessionRecoveryPolicy.mayRecover(true,address,marker,operationKind(),true))
                &&foundDevice!=null&&address.equals(replayAddress)&&address.equals(foundDevice.getAddress())&&(gatt==null||address.equals(gatt.getDevice().getAddress()));
    }
    private boolean persistNextReconnect(){
        long delay=reconnectDelays[Math.max(0,Math.min(3,reconnectAttempt))];reconnectAttempt=Math.min(3,reconnectAttempt+1);
        if(!sessionPreferences().edit().putString("session.marker",SessionRecoveryPolicy.RECONNECT_WAIT).putString("session.operation-kind","NONE")
                .putInt("session.reconnectAttempt",reconnectAttempt).putLong("session.reconnectNotBefore",System.currentTimeMillis()+delay).commit()){terminalStopped();return false;}
        listener.onLog("ORDINARY reconnect schedule delayMs="+delay+" attempt="+reconnectAttempt);return true;
    }
    private void publishReconnectWait(){state("Ожидаю часы; переподключение автоматически");connectionState(false,"Ожидаю часы; переподключение автоматически");}
    /** Retire this transport synchronously; close it only after the protocol RX callback unwinds. */
    @SuppressLint("MissingPermission")
    private boolean queueFreshReconnect(){
        if(reconnectCleanup!=null)return reconnectWaiting();
        try{if(!recoveryEligible())return false;}catch(SecurityException e){terminalStopped();return false;}
        OperationJournal.get(context).connectionUnavailable();
        final BluetoothGatt capturedGatt=gatt;final long capturedEpoch=rxEpoch;final String capturedKind=operationKind();
        commandsQuarantined=true;rxEpoch++;ready(false);
        mainHandler.removeCallbacks(ordinarySyncRunnable);mainHandler.removeCallbacks(activityPollRunnable);mainHandler.removeCallbacks(currentDialRefresh);
        mainHandler.removeCallbacks(reconnectRunnable);mainHandler.removeCallbacks(reconnectTimeout);activityPoll.onDisconnected();currentDialRefreshState.onDisconnected();
        if(!persistNextReconnect())return false;
        notificationsPending=false;NotificationForwardingService.clearPending();
        listener.onLog("ORDINARY fresh GATT recovery classification="+capturedKind+"; failed request is not replayed");
        reconnectCleanup=()->{
            reconnectCleanup=null;
            if(gatt!=capturedGatt||rxEpoch!=capturedEpoch+1)return;
            if(!reconnectWaiting()||manualDisconnect||directInstallRequest!=null
                    ||!sessionPreferences().getBoolean("session.userEnabled",false)||!hasRequiredPermissions()||txLedgerUnreliable){terminalStopped();return;}
            String address=sessionPreferences().getString("verified_device_address",null);
            try{if(address==null||!address.equals(replayAddress)||foundDevice==null||!address.equals(foundDevice.getAddress())
                    ||capturedGatt!=null&&!address.equals(capturedGatt.getDevice().getAddress())){terminalStopped();return;}}
            catch(SecurityException e){terminalStopped();return;}
            transportCleanup=true;try{closeGatt();}finally{transportCleanup=false;}
            if(!reconnectWaiting()||manualDisconnect||directInstallRequest!=null||!sessionPreferences().getBoolean("session.userEnabled",false)){terminalStopped();return;}
            publishReconnectWait();postReconnectDeadline();
        };
        mainHandler.post(reconnectCleanup);return true;
    }
    private void postReconnectDeadline(){
        mainHandler.removeCallbacks(reconnectRunnable);
        long remaining=Math.max(0,Math.min(60_000L,sessionPreferences().getLong("session.reconnectNotBefore",0)-System.currentTimeMillis()));
        mainHandler.postDelayed(reconnectRunnable,remaining);
    }
    @SuppressLint("MissingPermission")
    private void scheduleIdleReconnect(){
        mainHandler.removeCallbacks(reconnectRunnable);
        if(manualDisconnect||directInstallRequest!=null||!reconnectWaiting()||gatt!=null)return;
        if(!hasRequiredPermissions()||commandsQuarantined||txLedgerUnreliable){terminalStopped();return;}
        if(persistNextReconnect()){publishReconnectWait();postReconnectDeadline();}
    }
    @SuppressLint("MissingPermission")
    private void reconnectIfIdle(){
        if(directInstallRequest!=null)return;
        if(!reconnectWaiting()||manualDisconnect||operationRunning()||gatt!=null||reconnectCleanup!=null)return;
        if(!hasRequiredPermissions()){terminalStopped();return;}
        String exact=sessionPreferences().getString("verified_device_address",null);
        if(commandsQuarantined||txLedgerUnreliable||!sessionPreferences().getBoolean("session.userEnabled",false)||exact==null||!exact.equals(replayAddress)){terminalStopped();return;}
        try{
            BluetoothManager manager=context.getSystemService(BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter==null||!adapter.isEnabled()){scheduleIdleReconnect();return;}
            if(foundDevice==null)foundDevice=adapter.getRemoteDevice(exact);
            if(!exact.equals(foundDevice.getAddress())){terminalStopped();return;}
            // Persist a bounded deadline for process restart during this one setup attempt.
            if(!sessionPreferences().edit().putLong("session.reconnectNotBefore",System.currentTimeMillis()+20_000L).commit()){terminalStopped();return;}
            state("Повторное подключение к выбранным часам…");OperationJournal.get(context).connectionAttempt();gatt=foundDevice.connectGatt(context,false,gattCallback,android.bluetooth.BluetoothDevice.TRANSPORT_LE);startSetupWatchdog();
        }catch(SecurityException e){terminalStopped();closeGatt();state("Разрешение Bluetooth отозвано");}
        catch(IllegalArgumentException e){terminalStopped();closeGatt();}
        catch(RuntimeException e){setupFailed(-1,true);}
    }
    private void ordinaryTransportLost(){
        if(directInstallRequest!=null){finishDirectInstall(false,"DISCONNECTED");closeGatt();return;}
        if(reconnectCleanup!=null)return;
        if(!queueFreshReconnect()){terminalStopped();closeGatt();}
    }
    @SuppressLint("MissingPermission")
    private void onReconnectTimeout(){
        if(gatt==null||manualDisconnect||operationRunning())return;
        logSetup("timeout",-1);
        setupFailed(-1,true);
    }

    private boolean isWatchName(String name) {
        return name != null && name.trim().startsWith("TECNO Watch GT");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            String advertisedName = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            String deviceName = result.getDevice().getName();
            String name = advertisedName != null ? advertisedName : deviceName;
            if (!isWatchName(name)) {
                return;
            }
            foundDevice = result.getDevice();
            stopScan();
            watchFound(name.trim(), foundDevice.getAddress());
            state("Часы найдены");
            listener.onLog("Found " + name.trim() + " RSSI=" + result.getRssi());
            if (result.getScanRecord() != null) {
                listener.onLog("Advertisement " + toHex(result.getScanRecord().getBytes()));
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            state("Ошибка сканирования: " + errorCode);
            listener.onLog("BLE scan failed with Android code " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt callbackGatt, int status, int newState) {
            mainHandler.post(() -> handleConnectionStateChange(callbackGatt, status, newState));
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt callbackGatt, int status) {
            mainHandler.post(() -> handleServicesDiscovered(callbackGatt, status));
        }

        @Override
        public void onMtuChanged(BluetoothGatt callbackGatt, int mtu, int status) {
            mainHandler.post(() -> handleMtuChanged(callbackGatt, mtu, status));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt callbackGatt, BluetoothGattDescriptor descriptor, int status) {
            UUID descriptorUuid = descriptor == null ? null : descriptor.getUuid();
            mainHandler.post(() -> handleDescriptorWrite(callbackGatt, descriptorUuid, status));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic) {
            UUID characteristicUuid = characteristic == null ? null : characteristic.getUuid();
            byte[] value = characteristic == null ? null : characteristic.getValue();
            byte[] copy = value == null ? null : value.clone();
            long observedEpoch=rxEpoch;mainHandler.post(() -> handleCharacteristicChanged(callbackGatt, characteristicUuid, copy,observedEpoch));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            UUID characteristicUuid = characteristic == null ? null : characteristic.getUuid();
            byte[] copy = value == null ? null : value.clone();
            long observedEpoch=rxEpoch;mainHandler.post(() -> handleCharacteristicChanged(callbackGatt, characteristicUuid, copy,observedEpoch));
        }
        @Override public void onCharacteristicWrite(BluetoothGatt callbackGatt,BluetoothGattCharacteristic characteristic,int status){UUID uuid=characteristic==null?null:characteristic.getUuid();mainHandler.post(()->handleCharacteristicWritten(callbackGatt,uuid,status));}
    };

    @SuppressLint("MissingPermission")
    private void handleConnectionStateChange(BluetoothGatt callbackGatt, int status, int newState) {
        if (callbackGatt != gatt) return;
        listener.onLog("Connection state=" + newState + " status=" + status);
        logSetup("connection callback",status);
        if(newState==BluetoothProfile.STATE_DISCONNECTED){
            long now=android.os.SystemClock.elapsedRealtime();listener.onLog("BLE_LOSS status="+status+" marker="+sessionPreferences().getString("session.marker","UNKNOWN")+" kind="+operationKind()+" operationRunning="+operationRunning()+" commandsQuarantined="+commandsQuarantined+" txLedgerSize="+txLedger.size()+" rxAgeMs="+(lastWatchRxElapsed<0?"UNKNOWN":Long.toString(Math.max(0,now-lastWatchRxElapsed)))+" txAgeMs="+(lastAcceptedTxElapsed<0?"UNKNOWN":Long.toString(Math.max(0,now-lastAcceptedTxElapsed))));
            boolean wasOperating=operationRunning();if(!manualDisconnect)OperationJournal.get(context).connectionFailure(setupPhase,status,initialReadsComplete);actualConnected=false;
            connectionEvent("unexpected_loss","FAILED","FAILED","GATT_DISCONNECTED_"+status,wasOperating);
            state("Часы отключились");connectionState(false,"Часы отключились");ordinaryTransportLost();return;
        }
        if(newState==BluetoothProfile.STATE_CONNECTED){
            lastWatchRxElapsed=-1;lastAcceptedTxElapsed=-1;
            actualConnected=true;connectionState(false,commandsQuarantined?"Часы подключены; команды остановлены":"Подключено, ищу сервисы…");
            if(commandsQuarantined){listener.onLog("BLE late connected callback; setup remains quarantined");return;}
            if(status!=BluetoothGatt.GATT_SUCCESS){state("Ошибка подключения: "+status);setupFailed(status,true);return;}
            setupPhase="discovery";logSetup("connected",status);state("Подключено, ищу сервисы…");
            if(!hasRequiredPermissions()){terminalStopped();closeGatt();state("Разрешение Bluetooth отозвано");return;}
            discoverServices(callbackGatt);
        }else if(status!=BluetoothGatt.GATT_SUCCESS){state("Ошибка подключения: "+status);setupFailed(status,true);}
    }

    @SuppressLint("MissingPermission")
    private void handleServicesDiscovered(BluetoothGatt callbackGatt, int status) {
        if (callbackGatt != gatt||commandsQuarantined) return;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            state("Не удалось прочитать сервисы: " + status);
            setupFailed(status,true);
            return;
        }
        BluetoothGattService service = callbackGatt.getService(WATCH_SERVICE);
        logGattInventory(callbackGatt);
        if (service == null) {
            state("Это не GT01: сервис 0800 отсутствует");
            listener.onLog("Required service " + WATCH_SERVICE + " was not found");
            setupFailed(-1,false);
            return;
        }
        BluetoothGattCharacteristic rx = service.getCharacteristic(WATCH_RX);
        BluetoothGattCharacteristic tx = service.getCharacteristic(WATCH_TX);
        if (rx == null || tx == null) {
            state("Сервис GT01 неполный");
            listener.onLog("RX 0813 present=" + (rx != null) + ", TX 0814 present=" + (tx != null));
            setupFailed(-1,false);
            return;
        }
        listener.onLog("Verified GT01 service 0800, RX 0813 and TX 0814");
        String address=callbackGatt.getDevice().getAddress(),verified=sessionPreferences().getString("verified_device_address",null);
        if(!address.equals(replayAddress)||foundDevice==null||!address.equals(foundDevice.getAddress())||verified!=null&&!address.equals(verified)) {setupFailed(-1,false);return;}
        if(!sessionPreferences().edit().putString("verified_device_address",address).commit()){setupFailed(-1,false);return;}
        txCharacteristic = tx;
        setupPhase="mtu";logSetup("services verified",status);
        if (!requestMtu(callbackGatt, 512)) {
            listener.onLog("MTU request could not be started");setupFailed(-1,true);
        }
    }

    private void handleMtuChanged(BluetoothGatt callbackGatt, int mtu, int status) {
        if (callbackGatt != gatt||commandsQuarantined) return;
        listener.onLog("MTU changed to " + mtu + " status=" + status);
        if(status!=BluetoothGatt.GATT_SUCCESS){setupFailed(status,true);return;}
        if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu;
        BluetoothGattService service = callbackGatt.getService(WATCH_SERVICE);
        BluetoothGattCharacteristic rx = service == null ? null : service.getCharacteristic(WATCH_RX);
        if (rx == null || !enableRxNotifications(callbackGatt, rx)) {
            setupFailed(status,rx!=null);
            state("Не удалось включить уведомления RX");
        }
    }

    private void handleDescriptorWrite(BluetoothGatt callbackGatt, UUID descriptorUuid, int status) {
        if (callbackGatt != gatt || commandsQuarantined||!sessionIdle()&&!reconnectWaiting()) return;
        if (CLIENT_CONFIG.equals(descriptorUuid) && status == BluetoothGatt.GATT_SUCCESS) {
            if(!sessionPreferences().edit().putString("session.marker",SessionRecoveryPolicy.IDLE).putString("session.operation-kind","NONE").commit()){setupFailed(-1,false);return;}
            rxSubscribed=true;
            mainHandler.removeCallbacks(reconnectTimeout);logSetup("ready",status);
            recoveredFromProcessStart=false;
            state("GT01 готов к диагностике");
            connectionState(true,"GT01 готов к диагностике");
            listener.onLog("RX notifications enabled; no command has been sent to TX 0814");
            protocolProbe = new McfProtocolProbe(context, new McfProtocolProbe.Transport() {
                @Override public boolean write(byte[] frame) { return writeTx(callbackGatt, frame); }
                @Override public void log(String message) { listener.onLog(message); }
                @Override public void finished(boolean success) { terminalOperation(success); if(!success){ready(false);notificationsPending=false;NotificationForwardingService.clearPending();mainHandler.removeCallbacks(activityPollRunnable);mainHandler.removeCallbacks(currentDialRefresh);return;} ready(true); if(currentDialRefreshState.isPending())mainHandler.post(currentDialRefresh);mainHandler.post(BleWatchSession.this::maybeSendNotification); }
                @Override public void backupReady(File file) { listener.onBackupReady(file); }
                @Override public void albumFinished(boolean success) { terminalOperation(success); listener.onAlbumFinished(success); if(success){if(currentDialRefreshState.isPending())mainHandler.post(currentDialRefresh);mainHandler.post(BleWatchSession.this::maybeSendNotification);} }
                @Override public void activityFinished(boolean success, ActivitySnapshot snapshot) {
                    terminalOperation(success); state(success ? "Активность получена" : "Ошибка чтения активности");
                    if (success) listener.onLog("ACTIVITY steps="+snapshot.steps+" receivedAt="+snapshot.receivedAt+" calorie="+snapshot.calorie+" distance="+snapshot.distance);
                    listener.onActivityFinished(success, snapshot);
                    if(!transportCleanup){if(success){activityPoll.onSuccess();scheduleActivityPoll(false);}else activityPoll.onFailure();}
                    if(success){if(currentDialRefreshState.isPending())mainHandler.post(currentDialRefresh);
                    mainHandler.post(BleWatchSession.this::maybeSendNotification);}
                }
                @Override public void currentDialFinished(boolean success,String currentJson,String infoJson){
                    if(success)directHandshakeComplete=true;
                    if(success&&!sessionPreferences().edit().putInt("session.reconnectAttempt",0).putLong("session.reconnectNotBefore",0).putBoolean("session.recoveryConsumed",false).commit()){terminalStopped();return;}
                    terminalOperation(success); if(success){initialReadsComplete=true;reconnectAttempt=0;scheduleOrdinarySync();}CurrentDialSnapshot snapshot=resolveCurrentDial(success,currentJson,infoJson);
                    currentDialSnapshot=snapshot;listener.onCurrentDialSnapshot(snapshot);
                    // Refresh on connect, after known operations or explicit user action; no perpetual handshake loop.
                    if(success){if(activityPoll.nextDelayMs()==0L)scheduleActivityPoll(true);
                    mainHandler.post(BleWatchSession.this::maybeSendNotification);}
                }
            },new McfProtocolProbe.Scheduler(){
                public void removeCallbacks(Runnable r){mainHandler.removeCallbacks(r);}
                public void postDelayed(Runnable r,long delay){mainHandler.postDelayed(r,delay);}
            },WatchBackup::new);
            weatherReadProtocol = new WeatherReadProtocol(context, callbackGatt.getDevice().getAddress(), new WeatherReadProtocol.Transport() {
                @Override public boolean write(byte[] frame) { return callbackGatt == gatt && writeTx(callbackGatt, frame); }
                @Override public void log(String message) { listener.onLog(message); }
                @Override public void finished(boolean success, WeatherReadProtocol.Result result) {
                    terminalOperation(success);
                    state(success ? "Погода часов получена" : "Ошибка чтения погоды часов");
                    listener.onWeatherReadFinished(success, result);
                    if(success){if(currentDialRefreshState.isPending()) mainHandler.post(currentDialRefresh);
                    mainHandler.post(BleWatchSession.this::maybeSendNotification);}
                }
            });
            notificationProtocol=new NotificationSessionProtocol(
                    frame->callbackGatt==gatt&&writeTx(callbackGatt,frame),
                    prior->context.getSharedPreferences("gt_dial_preferences",Context.MODE_PRIVATE).edit().putInt("notification.previous-enable",prior).putLong("notification.previous-enable-at",System.currentTimeMillis()).commit(),
                    listener::onLog,
                    success->{terminalOperation(success);if(!success){notificationsPending=false;NotificationForwardingService.clearPending();state("Уведомления остановлены; отправьте журнал");}else {sessionPreferences().edit().putLong("notification.last-ack-at",System.currentTimeMillis()).apply();listener.onLog("NOTIFICATION watch command acknowledged");mainHandler.post(BleWatchSession.this::maybeSendNotification);}},
                    new NotificationSessionProtocol.Scheduler(){public void schedule(Runnable r,long delay){mainHandler.postDelayed(r,delay);}public void cancel(Runnable r){mainHandler.removeCallbacks(r);}},
                    Math.min(509,Math.max(23,negotiatedMtu-3)));
            if(directInstallRequest!=null){directConnecting=false;pumpDirectInstall();return;}
            ready(true);
            requestCurrentDialRefresh(0);
            activityPoll.onReady();
        } else {
            setupFailed(status,true);
            state("Ошибка подписки RX: " + status);
        }
    }

    private void handleCharacteristicChanged(BluetoothGatt callbackGatt, UUID characteristicUuid, byte[] value,long observedEpoch) {
        if (callbackGatt != gatt||observedEpoch!=rxEpoch) return;
        if(WATCH_RX.equals(characteristicUuid))lastWatchRxElapsed=android.os.SystemClock.elapsedRealtime();
        if(commandsQuarantined){listener.onLog("RX ignored in quarantined session bytes="+(value==null?0:value.length));return;}
        if(SessionRecoveryPolicy.OPERATION.equals(sessionPreferences().getString("session.marker",SessionRecoveryPolicy.STOPPED)))operationRxObserved=true;
        logNotification(characteristicUuid, value);
    }

    @SuppressLint("MissingPermission")
    private void discoverServices(BluetoothGatt callbackGatt) {
        if(commandsQuarantined||callbackGatt!=gatt)return;
        try{if (!callbackGatt.discoverServices()) {
            setupFailed(-1,true);
            state("Запуск поиска сервисов не удался");
        }}catch(SecurityException e){setupFailed(-1,false);}
    }

    @SuppressLint("MissingPermission")
    private boolean writeTx(BluetoothGatt callbackGatt, byte[] value) {
        return writeTx(callbackGatt,value,-1,-1);
    }
    @SuppressLint("MissingPermission")
    private boolean writeTx(BluetoothGatt callbackGatt,byte[] value,long weatherGeneration,int chunk){
        if (commandsQuarantined||!actualConnected||txLedgerUnreliable||callbackGatt != gatt || txCharacteristic == null || SessionRecoveryPolicy.STOPPED.equals(sessionPreferences().getString("session.marker",SessionRecoveryPolicy.STOPPED))) return false;
        UUID uuid=txCharacteristic.getUuid();boolean accepted;operationTxAttempted=true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            accepted=callbackGatt.writeCharacteristic(txCharacteristic, value,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    == android.bluetooth.BluetoothStatusCodes.SUCCESS;
        }else{
        txCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        txCharacteristic.setValue(value);
        accepted=callbackGatt.writeCharacteristic(txCharacteristic);
        }
        if(accepted)lastAcceptedTxElapsed=android.os.SystemClock.elapsedRealtime();
        if(accepted&&!txLedgerUnreliable){if(txLedger.size()>=64)markTxLedgerUnreliable("TX callback ledger capacity reached");else txLedger.addLast(new PendingTx(callbackGatt,uuid,weatherGeneration,chunk));}return accepted;
    }
    private void markTxLedgerUnreliable(String reason){if(!txLedgerUnreliable)listener.onLog("WEATHER_TX_LEDGER unreliable: "+reason+"; commands unavailable until GATT close");txLedgerUnreliable=true;terminalStopped();}
    private void handleCharacteristicWritten(BluetoothGatt callbackGatt,UUID uuid,int status){
        if(callbackGatt!=gatt||commandsQuarantined||txLedgerUnreliable)return;PendingTx pending=txLedger.peekFirst();
        if(pending==null||pending.gatt!=callbackGatt||!pending.uuid.equals(uuid)){markTxLedgerUnreliable("unexpected Android TX callback ownership");return;}
        txLedger.removeFirst();
        if(pending.weatherGeneration<0&&status!=BluetoothGatt.GATT_SUCCESS&&dynamicDialProtocol!=null&&(dynamicDialProtocol.isLocalOperation()||dynamicDialProtocol.isDirectInstall())&&dynamicDialProtocol.isRunning()){dynamicDialProtocol.cancel("ANDROID_TX_FAILED");return;}
        if(pending.weatherGeneration>=0){if(weatherFrameWriter!=null)weatherFrameWriter.written(pending.weatherGeneration,pending.chunk,status);else if(weatherSessionProtocol!=null&&weatherSessionProtocol.isRunning())weatherSessionProtocol.cancel("missing weather callback owner");}
        else if(weatherFrameWriter!=null&&weatherFrameWriter.active()){if(status!=BluetoothGatt.GATT_SUCCESS)weatherFrameWriter.unexpected();else weatherFrameWriter.ledgerDrained();}
        else if(weatherSessionProtocol!=null&&weatherSessionProtocol.fullMode()&&weatherSessionProtocol.isRunning())weatherSessionProtocol.cancel("unexpected ordinary TX owner during manual weather response");
    }

    private void logGattInventory(BluetoothGatt callbackGatt) {
        for (BluetoothGattService service : callbackGatt.getServices()) {
            listener.onLog("GATT service " + service.getUuid());
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                listener.onLog(String.format(Locale.US, "  characteristic %s properties=0x%02X permissions=0x%02X",
                        characteristic.getUuid(), characteristic.getProperties(), characteristic.getPermissions()));
                for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {
                    listener.onLog(String.format(Locale.US, "    descriptor %s permissions=0x%02X",
                            descriptor.getUuid(), descriptor.getPermissions()));
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private boolean requestMtu(BluetoothGatt callbackGatt, int mtu) {
        try{return !commandsQuarantined&&callbackGatt==gatt&&callbackGatt.requestMtu(mtu);}
        catch(SecurityException e){setupFailed(-1,false);return false;}
    }

    @SuppressLint("MissingPermission")
    private boolean enableRxNotifications(BluetoothGatt callbackGatt, BluetoothGattCharacteristic rx) {
        if(commandsQuarantined||callbackGatt!=gatt)return false;
        setupPhase="cccd";logSetup("subscribing",0);
        try{
        if (!callbackGatt.setCharacteristicNotification(rx, true)) {
            return false;
        }
        BluetoothGattDescriptor descriptor = rx.getDescriptor(CLIENT_CONFIG);
        if (descriptor == null) {
            listener.onLog("RX characteristic has no CCC descriptor");
            setupFailed(-1,false);
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return callbackGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    == android.bluetooth.BluetoothStatusCodes.SUCCESS;
        }
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        return callbackGatt.writeDescriptor(descriptor);
        }catch(SecurityException e){setupFailed(-1,false);return false;}
    }

    private void logNotification(UUID uuid, byte[] value) {
        if (!WATCH_RX.equals(uuid)) {
            return;
        }
        byte[] copy = value == null ? null : value.clone();
        if(alarmProtocol!=null&&alarmProtocol.isRunning()){listener.onLog("RX bounded alarm response "+(copy==null?0:copy.length)+" B");alarmProtocol.onChunk(copy);return;}
        if(sleepHistoryProtocol!=null&&sleepHistoryProtocol.isRunning()){
            listener.onLog("RX bounded health response "+(copy==null?0:copy.length)+" B");
            sleepHistoryProtocol.onChunk(copy);return;
        }
        // Privacy changes logging only: later correlated replies must still reach their protocol.
        if(expansionRxRedacted)listener.onLog("RX redacted after bounded expansion operation "+(copy==null?0:copy.length)+" B");
        else if(notificationProtocol!=null&&notificationProtocol.isRunning())listener.onLog("RX notification response " + (copy==null?0:copy.length) + " B");
        else listener.onLog("RX " + (copy == null ? 0 : copy.length) + " B: " + toHex(copy));
        AlbumStageProtocol capturedStage = stageProtocol;
        if (capturedStage != null) {
            if (capturedStage == stageProtocol && capturedStage.isRunning()) capturedStage.onChunk(copy);
        } else if (dynamicDialProtocol != null) {
            AlbumStageProtocol capturedDynamic = dynamicDialProtocol;
            if (capturedDynamic == dynamicDialProtocol && capturedDynamic.isRunning()) capturedDynamic.onChunk(copy);
        } else if(notificationProtocol!=null&&notificationProtocol.isRunning()) {
            notificationProtocol.onChunk(copy);
        } else if(weatherSessionProtocol!=null&&weatherSessionProtocol.isRunning()) {
            weatherSessionProtocol.onChunk(copy);
        } else if(weatherReadProtocol!=null&&weatherReadProtocol.isRunning()) {
            weatherReadProtocol.onChunk(copy);
        } else if (protocolProbe != null) {
            protocolProbe.onChunk(copy);
        }
    }

    private String toHex(byte[] value) {
        if (value == null || value.length == 0) {
            return "<empty>";
        }
        StringBuilder result = new StringBuilder(value.length * 3);
        int displayed = Math.min(value.length, 96);
        for (int i = 0; i < displayed; i++) {
            if (i > 0) result.append(' ');
            result.append(String.format(Locale.US, "%02X", value[i] & 0xFF));
        }
        if (displayed < value.length) result.append(" …");
        return result.toString();
    }
    private int localCurrentGeneration;
    private CurrentDialSnapshot resolveCurrentDial(boolean success,String currentJson,String infoJson){
        final int generation=++localCurrentGeneration;final long expectedEpoch=rxEpoch;
        long now=System.currentTimeMillis();String alias="";
        try{alias=decodeCurrentDialIdentity(currentJson);if(alias.isEmpty())return CurrentDialSnapshot.unknown("",now);
            if(success&&directLocalSnapshot!=null&&gatt!=null&&directLocalAddress.equals(gatt.getDevice().getAddress())
                    &&AlbumStageProtocol.selectedCurrentIdentifierMatches(alias,directLocalPackageId,directLocalSnapshot.alias))
                return CurrentDialSnapshot.local(directLocalSnapshot.alias,directLocalSnapshot.catalogId,directLocalSnapshot.localArtifactSha256,directLocalSnapshot.displayFields,now);
            if("com.gtdial.dial3000".equals(alias)&&containsExactInstalledIdentity(infoJson,"com.gtdial.dial3000","2809202602"))return CurrentDialSnapshot.known("2809202602","dial3000","dynamic_dials/dial3000/preview.png",new String[]{"time","date","steps","calories","heartRate","battery","distance"},now);
            if(("com.gtdial.dial3000anim.violet".equals(alias)||"2909202626".equals(alias))
                    &&containsExactInstalledIdentity(infoJson,"com.gtdial.dial3000anim.violet","2909202626"))
                return CurrentDialSnapshot.known("2909202626","dial3000walk v1","dynamic_dials/previous-v10/dial3000walk/violet-preview.png",new String[]{"time","date","steps","calories","heartRate","battery","distance"},now);
            BundledDynamicDialRepository repository=new BundledDynamicDialRepository(context);
            for(BundledDynamicDial dial:repository.list()){
                BundledDynamicDial.PackageVariant variant=dial.packageVariantByAlias(alias);
                if(variant==null)for(BundledDynamicDial.Palette palette:dial.palettes){BundledDynamicDial.PackageVariant candidate=dial.packageVariant(palette.id);if(candidate!=null&&candidate.internalPackageId.equals(alias)){variant=candidate;break;}}
                if(variant!=null&&containsExactInstalledIdentity(infoJson,variant.internalPackageId,variant.alias)){String preview=("dial3000".equals(dial.id)||"dial3000walk".equals(dial.id)||(dial.id.startsWith("pip")&&!dial.id.startsWith("pipboy")))?"dynamic_dials/"+dial.id+"/"+variant.paletteId+"/preview.png":dial.previewAsset;return CurrentDialSnapshot.known(variant.alias,dial.id,preview,dial.displayFields,now);}
            }
        }catch(Exception ignored){listener.onLog("CURRENT_DIAL unresolved; keeping honest unknown snapshot");}
        if(success&&!commandsQuarantined&&gatt!=null&&foundDevice!=null){
            final BluetoothGatt expectedGatt=gatt;final String address=foundDevice.getAddress(),current=alias;
            new Thread(()->{try{
                org.json.JSONArray previews=new LocalDialPreviewIndex(context).entries(address);
                for(int i=0;i<previews.length();i++){
                    org.json.JSONObject entry=previews.getJSONObject(i);String id=entry.getString("packageId"),savedAlias=entry.getString("alias"),sha=entry.getString("artifact");
                    if(!AlbumStageProtocol.selectedCurrentIdentifierMatches(current,id,savedAlias)||!containsExactInstalledIdentity(infoJson,id,savedAlias))continue;
                    CompiledDialArtifactStore.Artifact stored=new CompiledDialArtifactStore(context).load(sha);
                    DynamicDialPackage target=DynamicDialPackage.local(stored);
                    if(!id.equals(target.internalPackageId)||!savedAlias.equals(target.alias)||!sha.equals(target.artifactSha256))continue;
                    String[] labels=new String[stored.snapshot.project.elements.size()];for(int x=0;x<labels.length;x++)labels[x]=DialRuntimeProfile.field(stored.snapshot.project.elements.get(x).id).label;
                    CurrentDialSnapshot snapshot=CurrentDialSnapshot.local(savedAlias,stored.snapshot.project.name,sha,labels,now);
                    mainHandler.post(()->{if(gatt==expectedGatt&&!commandsQuarantined&&rxEpoch==expectedEpoch&&generation==localCurrentGeneration){currentDialSnapshot=snapshot;listener.onCurrentDialSnapshot(snapshot);}});return;
                }
                DynamicDialOwnershipStore currentOwnership=new DynamicDialOwnershipStore(context);
                DynamicDialOwnershipStore.Receipt receipt=currentOwnership.load(address,BundledDynamicDial.REMOTE_PATH);
                if(!AlbumStageProtocol.verifiedLocalCurrent(current,infoJson,receipt,currentOwnership.knownReceipts(address,BundledDynamicDial.REMOTE_PATH)))return;
                CompiledDialArtifactStore.Artifact artifact=new CompiledDialArtifactStore(context).load(receipt.artifactSha256);
                String[] fields=new String[artifact.snapshot.project.elements.size()];
                for(int i=0;i<fields.length;i++)fields[i]=DialRuntimeProfile.field(artifact.snapshot.project.elements.get(i).id).label;
                CurrentDialSnapshot local=CurrentDialSnapshot.local(receipt.alias,artifact.snapshot.project.name,receipt.artifactSha256,fields,now);
                mainHandler.post(()->{if(gatt==expectedGatt&&!commandsQuarantined&&rxEpoch==expectedEpoch&&generation==localCurrentGeneration)listener.onCurrentDialSnapshot(local);});
            }catch(Exception|OutOfMemoryError e){listener.onLog("CURRENT_DIAL local artifact unresolved: "+e.getMessage());}},"dial-current-artifact").start();
        }
        return CurrentDialSnapshot.unknown(alias,now);
    }
    static String decodeCurrentDialIdentity(String raw)throws org.json.JSONException{
        if(raw==null)return "";
        String value=raw.trim();
        if(value.startsWith("{"))return new org.json.JSONObject(value).optString("alias","").trim();
        if(value.startsWith("\""))return new org.json.JSONTokener(value).nextValue().toString().trim();
        return value;
    }
    private static boolean containsExactInstalledIdentity(String raw,String id,String alias){if(raw==null)return false;try{return containsExactInstalledIdentity(new org.json.JSONTokener(raw).nextValue(),id,alias);}catch(Exception e){return false;}}
    private static boolean containsExactInstalledIdentity(Object value,String id,String alias)throws org.json.JSONException{
        if(value instanceof org.json.JSONObject){org.json.JSONObject o=(org.json.JSONObject)value;if(id.equals(o.optString("id"))&&alias.equals(o.optString("alias")))return true;java.util.Iterator<String> keys=o.keys();while(keys.hasNext())if(containsExactInstalledIdentity(o.get(keys.next()),id,alias))return true;}
        else if(value instanceof org.json.JSONArray){org.json.JSONArray a=(org.json.JSONArray)value;for(int i=0;i<a.length();i++)if(containsExactInstalledIdentity(a.get(i),id,alias))return true;}return false;
    }
}
