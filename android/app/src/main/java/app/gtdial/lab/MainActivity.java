package app.gtdial.lab;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.res.Configuration;
import android.content.Intent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import androidx.core.app.NotificationManagerCompat;
import org.json.JSONArray;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
/** View owner only: the BLE session survives tabs, themes and language changes. */
public final class MainActivity extends Activity implements BleWatchSession.Listener {
    static final int PERMISSION=100, IMAGE=101, WEATHER_LOCATION=102, MAX_SOURCE=16*1024*1024, MAX_LOG=18000;
    BleWatchSession watch;
    private boolean directInstallUiPending;
    private final java.util.ArrayList<Button> localDirectInstallButtons=new java.util.ArrayList<>();
    private final Map<Button,TextView> directInstallReasons=new HashMap<>();
    private TextView actionFeedback;
    private String visibleActionFeedback="",weatherActionFeedback="";
    DiagnosticLog journal;
    private DiagnosticLog temporaryJournal;
    DialRepository repo;
    private DialProjectRepository projectRepo;
    private CompiledDialArtifactStore compiledArtifacts;
    private DialEditorController dialEditor;
    private int projectGalleryGeneration;
    private static final int PROJECT_DOCUMENT=104;
    private boolean projectImportBusy;
    BundledDynamicDialRepository bundledRepo;
    AppPreferences prefs;
    ExecutorService worker;
    LinearLayout root,pages,gallery;
    TextView status,device,connectionState,connectionDetail,imageInfo,log,activityText,currentLabel;
    ImageView preview,currentImage;
    Button connect,probe,album,weatherRead,stage,backupShare,activityRead,dynamicInstall,dynamicRecovery,stagingAcquisition,stagingShare,stagingMetadataShare;
    private WeatherReadProtocol.Result lastWeatherState;
    Button topPosition,bottomPosition,steps,active,heart,weather;
    TextView[] colorTiles;
    String[] colorNames;
    int selectedPosition=1, selectedColor=Color.WHITE;
    byte[] selected;
    Bitmap base;
    File backup;
    File stagingEvidence,stagingMetadata;
    Button fragmentRecovery;
    boolean ready,connected,destroyed;
    long lastActivityAt;
    int generation;
    private int imagePickerGeneration;
    private AlertDialog photoDialog;
    String editingId;
    private UiKit ui;
    private final Map<String,Bundle> screenStates=new HashMap<>();
    private String renderedScreenKey,skipCaptureScreen;
    private final Runnable backgroundRedraw=new Runnable(){public void run(){if(!destroyed)rebuildUi();}};
    private void requestBackgroundRedraw(){journalUiHandler.removeCallbacks(backgroundRedraw);journalUiHandler.postDelayed(backgroundRedraw,32);}
    private int activeTab=2;
    private final Map<String,Boolean> settingsSections=new HashMap<>();
    private boolean activityHistoryMode,activityHistoryDaySelected;
    private List<String> activityHistoryDays=Collections.emptyList();
    private String latestStatus="", latestDevice="", latestActivity="";
    private String latestConnectionMessage="";
    private ActivitySnapshot latestActivitySnapshot;
    private boolean watchFound,activityReadFailed,activityReadStarted;
    private CurrentDialSnapshot latestCurrentDial;
    private final Handler journalUiHandler=new Handler(Looper.getMainLooper());
    private boolean journalRefreshPending;
    private DialInstallProgress latestDialProgress;
    private AlertDialog dialProgressDialog;
    private TextView dialProgressTitle,dialProgressStage,dialProgressBytes,dialProgressElapsed,dialProgressDetail,dialProgressSteps;
    private ProgressBar dialProgressBar;
    private Button dialProgressButton;
    private Button pendingRecoveryButton;
    private boolean dialProgressHidden;
    private final Runnable dialProgressTick=new Runnable(){public void run(){if(destroyed||dialProgressDialog==null)return;renderDialProgress();if(latestDialProgress!=null&&!latestDialProgress.terminal())journalUiHandler.postDelayed(this,500);}};
    private boolean bound;
    private boolean serviceRequested;
    private boolean awaitingNotificationPermission;
    private PhoneCityLocator cityLocator;
    private boolean weatherFetchRunning;
    private String selectedBundledId;
    private ActivityHistoryStore activityHistory;
    private String historyDeviceAddress="",historyDay=java.time.LocalDate.now().toString(),historyLoadedKey="",historyError="";
    private ActivityHistoryEntry historyEntry;
    private List<ActivityHistoryEntry> historyObservations=Collections.emptyList();
    private int historyGeneration;
    private boolean historyLoading;
    private TsdbReadProtocol.Result sleepHistoryResult;
    private TsdbReadProtocol.Result latestHeartResult;
    private ExpansionUiController expansion;
    private WatchSessionService.LocalBinder sessionBinder;
    private boolean appForeground;
    private boolean pendingScan;private boolean healthReadPending,connectionPending;private long healthRequestedAt;private String healthPendingProfile="";private String connectionUiState="";
    private final ServiceConnection sessionConnection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,android.os.IBinder raw){WatchSessionService.LocalBinder b=(WatchSessionService.LocalBinder)raw;sessionBinder=b;b.phoneWeatherUiActive(appForeground);watch=b.session();if(temporaryJournal!=null){temporaryJournal.close();temporaryJournal=null;}journal=b.journal();activityHistory=b.history();historyDeviceAddress=b.activityDeviceAddress()==null?"":b.activityDeviceAddress();b.attach(MainActivity.this);bound=true;rebuildUi();if(pendingScan){pendingScan=false;watch.startScan();}expansion.refresh(true);}
        @Override public void onServiceDisconnected(ComponentName name){watch=null;ready=false;connected=false;watchFound=false;sessionBinder=null;if(!destroyed)rebuildUi();}
    };
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        WeatherDeliveryController.initializeAutomaticPolicy(this);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::navigateBack);
        prefs=new AppPreferences(this);
        if(state!=null){Bundle savedSettings=state.getBundle("ui.settings.sections");if(savedSettings!=null)for(String key:savedSettings.keySet())settingsSections.put(key,savedSettings.getBoolean(key));Bundle savedScreens=state.getBundle("ui.screens");if(savedScreens!=null)for(String key:savedScreens.keySet()){Bundle value=savedScreens.getBundle(key);if(value!=null)screenStates.put(key,value);}}
        worker=Executors.newSingleThreadExecutor();temporaryJournal=new DiagnosticLog(this);journal=temporaryJournal;
        expansion=new ExpansionUiController(this,prefs,new ExpansionUiController.Host(){
            public void redraw(){requestBackgroundRedraw();}
            public void tab(int tab){if(expansion!=null)expansion.tabChanged(activeTab,tab);activeTab=tab;rebuildUi();}
            public String device(){return historyDeviceAddress.isEmpty()?prefs.savedDevice():historyDeviceAddress;}
            public boolean ready(){return MainActivity.this.ready&&watch!=null;}
            public void share(File file,String type){MainActivity.this.share(file,type);}
            public void exportDiagnostics(){MainActivity.this.exportDiagnostics();}
            public void weatherDiagnostics(){MainActivity.this.showWeatherSettings();}
            public void clearWorkoutDraft(){screenStates.keySet().removeIf(key->key.startsWith("5:prepare:"));skipCaptureScreen=renderedScreenKey;}
            public void readActivity(){if(activityReadStarted||expansion.measurementsBusy())return;if(watch!=null&&ready){activityReadStarted=true;expansion.readFeedback(tr("Обновляю шаги…","Refreshing steps…"),true);watch.runActivitySnapshot();}else toast(tr("Подключите часы в Настройках","Connect watch in Settings"));}
            public void readHeart(){confirmHealth(false);}
            public void readSpo2(){confirmHealthProfile("SPO2_PAGE");}
            public void readSleep(){confirmHealth(true);}
            public void readSports(){if(!DeviceFeatureGate.sportsAllowed()){toast(tx("Получение тренировок с часов пока недоступно"));return;}if(watch==null||!ready){dependency(tx("Подключите выбранное устройство и дождитесь готовности."));return;}ui.dialogBuilder().setTitle(tx("Одна порция тренировок часов")).setMessage(tx("Один запрос sports_record за 7 дней, максимум 5 записей. Без подробных графиков, синхронизации, повторов и записи. После результата соединение закроется. Выполняйте только в согласованном физическом опыте.")).setNegativeButton(tx("Отмена"),null).setPositiveButton(tx("Прочитать один раз"),(d,w)->{if(watch!=null&&ready)watch.runSportsPage();else dependency(tx("Соединение изменилось: этот запрос не был отправлен."));}).show();}
            public void readAlarms(){if(!DeviceFeatureGate.alarmReadAllowed()){toast(tx("Получение будильников с часов пока недоступно"));return;}if(watch==null||!ready){toast(tr("Подключите часы в Настройках","Connect watch in Settings"));return;}toast(tx("Получаю расписание часов…"));watch.runAlarmRead();}
            public void writeAlarm(AlarmTransferPlan plan){if(plan==null||!DeviceFeatureGate.alarmWriteAllowed(plan.action)){toast(tx("Отправка будильников пока недоступна"));return;}if(watch==null||!ready){toast(tr("Подключите часы в Настройках","Connect watch in Settings"));return;}toast(tx("Передаю изменение…"));watch.runAlarmWrite(plan);}
        });expansion.restore(state);if(state!=null){healthReadPending=state.getBoolean("health.pending");healthRequestedAt=state.getLong("health.requestedAt");healthPendingProfile=state.getString("health.profile","");if(healthPendingProfile.isEmpty())healthReadPending=false;}
        if(state!=null){activeTab=state.getInt("tab",2);if(activeTab==1||activeTab==4||activeTab<0||activeTab>6)activeTab=3;selectedPosition=state.getInt("position",1);selectedColor=state.getInt("color",Color.WHITE);editingId=state.getString("editingId");selectedBundledId=state.getString("bundledDetail");selectedProjectId=state.getString("projectDetail");myDialCategory=state.getBoolean("myDialCategory");falloutFolder=state.getBoolean("falloutFolder");myDialPage=Math.max(0,state.getInt("myDialPage"));try{historyDay=java.time.LocalDate.parse(state.getString("historyDay",historyDay)).toString();}catch(RuntimeException ignored){historyDay=java.time.LocalDate.now().toString();}}
        if(state!=null){activityHistoryMode=state.getBoolean("activityHistoryMode");activityHistoryDaySelected=state.getBoolean("activityHistoryDaySelected");}
        if(!activityHistoryMode)historyDay=java.time.LocalDate.now().toString();
        applyLocale(prefs.language());
        repo=new DialRepository(this);
        projectRepo=new DialProjectRepository(this);
        compiledArtifacts=new CompiledDialArtifactStore(this);
        dialEditor=new DialEditorController(this,projectRepo,this::refreshGallery);
        try{bundledRepo=new BundledDynamicDialRepository(this);}catch(IOException e){bundledRepo=null;}
        rebuildUi();expansion.refresh(true);expansion.intent(getIntent());
        dialEditor.restore(state,prefs.theme());
        if(hasRequiredPermissions())connectSessionService();
        else requestPermissions(Build.VERSION.SDK_INT>=31?new String[]{"android.permission.BLUETOOTH_SCAN","android.permission.BLUETOOTH_CONNECT"}:new String[]{"android.permission.ACCESS_FINE_LOCATION"},PERMISSION);
    }
    @Override protected void onResume(){
        super.onResume();
        appForeground=true;if(sessionBinder!=null)sessionBinder.phoneWeatherUiActive(true);
        if(expansion!=null)expansion.resume();
        boolean access=NotificationManagerCompat.getEnabledListenerPackages(this).contains(getPackageName());
        if(awaitingNotificationPermission){
            awaitingNotificationPermission=false;
            if(access){
                prefs.notificationForwardingEnabled(true);
                if(root!=null&&activeTab==3)rebuildUi();
                if(prefs.notificationPackages().isEmpty())chooseNotificationApps();
                return;
            }
        }
        if(!access&&prefs.notificationForwardingEnabled())prefs.notificationForwardingEnabled(false);
        if(root!=null&&activeTab==3)rebuildUi();
    }
    private boolean hasRequiredPermissions(){return Build.VERSION.SDK_INT>=31?checkSelfPermission("android.permission.BLUETOOTH_SCAN")==PackageManager.PERMISSION_GRANTED&&checkSelfPermission("android.permission.BLUETOOTH_CONNECT")==PackageManager.PERMISSION_GRANTED:checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")==PackageManager.PERMISSION_GRANTED;}
    private void connectSessionService(){if(serviceRequested)return;Intent service=new Intent(this,WatchSessionService.class);try{if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)startForegroundService(service);else startService(service);if(!bindService(service,sessionConnection,Context.BIND_AUTO_CREATE)){latestStatus=tr("Не удалось подключить службу часов","Could not bind watch service");toast(latestStatus);return;}serviceRequested=true;}catch(SecurityException|IllegalStateException e){latestStatus=tr("Не удалось запустить службу часов: ","Could not start watch service: ")+e.getClass().getSimpleName();toast(latestStatus);}}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);if(requestCode==PERMISSION){if(hasRequiredPermissions())connectSessionService();else{pendingScan=false;onState(tr("Без Bluetooth доступны местные данные, тренировки, планы и отчёты.","Local data, workouts, plans and reports remain available without Bluetooth."));}}else if(requestCode==WEATHER_LOCATION){if(checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION")==PackageManager.PERMISSION_GRANTED)detectWeatherCity();else onState(tr("Без разрешения на местоположение выберите город вручную.","Without location access, choose a city manually."));}else if(expansion!=null)expansion.permissionResult(requestCode);}
    @Override protected void onPause(){if(dialEditor!=null)dialEditor.checkpoint();appForeground=false;if(sessionBinder!=null)sessionBinder.phoneWeatherUiActive(false);if(expansion!=null)expansion.pause();super.onPause();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(expansion!=null)expansion.intent(intent);}
    /** Replaces only views; it does not recreate the Activity or close the watch session. */
    private void rebuildUi(){
        journalUiHandler.removeCallbacks(backgroundRedraw);
        captureScreenState();
        localDirectInstallButtons.clear();directInstallReasons.clear();
        ui=new UiKit(this,prefs.theme());
        Window window=getWindow();
        window.setStatusBarColor(ui.surface);
        window.setNavigationBarColor(ui.surface);
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M){
            int flags="arctic".equals(prefs.theme())?View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR:0;
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O&&"arctic".equals(prefs.theme()))flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
        }
        setContentView(build());
        renderedScreenKey=screenKey();restoreScreenState();
        if(status!=null)status.setText(latestStatus);
        refreshWeatherSettings();
    }
    private String screenKey(){return activeTab+":"+(activeTab==0?myDialCategory+":"+falloutFolder+":"+selectedBundledId+":"+selectedProjectId:expansion==null?"":expansion.screenKey(activeTab));}
    private void captureScreenState(){
        if(pages==null||renderedScreenKey==null)return;if(renderedScreenKey.equals(skipCaptureScreen)){skipCaptureScreen=null;return;}View visible=null;
        for(int i=0;i<pages.getChildCount();i++)if(pages.getChildAt(i).getVisibility()==View.VISIBLE){visible=pages.getChildAt(i);break;}
        if(!(visible instanceof ScrollView))return;Bundle state=new Bundle();state.putInt("scroll",visible.getScrollY());captureFields(visible,"",state);screenStates.put(renderedScreenKey,state);
    }
    private void captureFields(View view,String path,Bundle state){if("openrouter.secret".equals(view.getTag()))return;
        Object tag=view.getTag();String key=tag instanceof String?(String)tag:path;
        if(tag instanceof String&&key.startsWith("draft.")){
            if(view instanceof EditText)state.putString(key,((EditText)view).getText().toString());
            else if(view instanceof CheckBox)state.putBoolean(key,((CheckBox)view).isChecked());
            else state.putInt(key,view.getVisibility());
        }
        if(tag instanceof String&&key.startsWith("draft.")&&view.hasFocus()&&view instanceof EditText){state.putString("focus",key);state.putInt("selection",((EditText)view).getSelectionStart());state.putInt("selectionEnd",((EditText)view).getSelectionEnd());}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)captureFields(group.getChildAt(i),path+"/"+i,state);}
    }
    private void restoreFields(View view,String path,Bundle state,boolean focus){
        Object tag=view.getTag();String key=tag instanceof String?(String)tag:path;
        if(tag instanceof String&&key.startsWith("draft.")&&state.containsKey(key)){
            if(view instanceof EditText){EditText edit=(EditText)view;String saved=state.getString(key,"");if(!edit.getText().toString().equals(saved))edit.setText(saved);}
            else if(view instanceof CheckBox)((CheckBox)view).setChecked(state.getBoolean(key));
            else view.setVisibility(state.getInt(key,View.GONE));
        }
        if(focus&&key.equals(state.getString("focus"))&&view instanceof EditText){EditText edit=(EditText)view;edit.requestFocus();edit.setSelection(Math.max(0,Math.min(edit.length(),state.getInt("selection"))),Math.max(0,Math.min(edit.length(),state.getInt("selectionEnd",state.getInt("selection")))));}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)restoreFields(group.getChildAt(i),path+"/"+i,state,focus);}
    }
    private void restoreScreenState(){
        Bundle state=screenStates.get(renderedScreenKey);if(state==null||pages==null)return;LinearLayout expected=pages;String expectedKey=renderedScreenKey;
        for(int i=0;i<pages.getChildCount();i++)if(pages.getChildAt(i).getVisibility()==View.VISIBLE&&pages.getChildAt(i) instanceof ScrollView){ScrollView scroll=(ScrollView)pages.getChildAt(i);boolean allowFocus=!(dialEditor!=null&&dialEditor.isOpen()||photoDialog!=null&&photoDialog.isShowing()||weatherSettingsDialog!=null&&weatherSettingsDialog.isShowing());restoreFields(scroll,"",state,allowFocus);scroll.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){if(scroll.getViewTreeObserver().isAlive())scroll.getViewTreeObserver().removeOnPreDrawListener(this);if(!destroyed&&pages==expected&&expectedKey.equals(screenKey()))scroll.scrollTo(0,state.getInt("scroll"));return true;}});break;}
    }
    private View build(){
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.surface);
        root.setPadding(ui.dp(16),0,ui.dp(16),0);
        root.setOnApplyWindowInsetsListener((v,i)->{
            v.setPadding(ui.dp(16),i.getSystemWindowInsetTop()+ui.dp(10),ui.dp(16),i.getSystemWindowInsetBottom()+ui.dp(8));
            return i;
        }
        );
        root.requestApplyInsets();
        status=null;device=null;connect=null;log=null;dynamicInstall=null;connectionDetail=null;
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);brand.setMinimumHeight(ui.dp(44));
        ImageView eye=new ImageView(this);eye.setImageDrawable(brandDrawable());eye.setContentDescription(tr("Часовой — око с часовыми стрелками","Chasovoy — eye with clock hands"));brand.addView(eye,new LinearLayout.LayoutParams(ui.dp(40),ui.dp(30)));
        TextView title=ui.label(tr("Часовой","Chasovoy"),20,ui.text);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1);titleParams.setMargins(ui.dp(9),0,ui.dp(8),0);brand.addView(title,titleParams);
        connectionState=ui.label("",11,ui.muted);connectionState.setSingleLine(true);brand.addView(connectionState);updateConnectionState();root.addView(brand,ui.fillWrap(0,6));
        actionFeedback=ui.label(tx(visibleActionFeedback),13,ui.text);actionFeedback.setVisibility(visibleActionFeedback.isEmpty()?View.GONE:View.VISIBLE);actionFeedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(actionFeedback,ui.fillWrap(4,6));
        dialProgressButton=secondaryButton(tr("Ход установки циферблата","Watch face installation progress"));
        dialProgressButton.setTextSize(13);dialProgressButton.setSingleLine(true);dialProgressButton.setEllipsize(android.text.TextUtils.TruncateAt.END);dialProgressButton.setPadding(ui.dp(12),0,ui.dp(12),0);dialProgressButton.setOnClickListener(v->{dialProgressHidden=false;showDialInstallProgress();});
        updateDialProgressButton();LinearLayout.LayoutParams statusParams=ui.fillWrap(0,6);statusParams.height=ui.dp(48);root.addView(dialProgressButton,statusParams);
        pages=new LinearLayout(this);
        pages.setOrientation(LinearLayout.VERTICAL);
        addPage(0,this::dials);
        addPage(2,p->expansion.buildActivity(p,ui));
        addPage(3,this::settings);
        addPage(4,this::logging);
        addPage(5,p->expansion.buildWorkouts(p,ui));
        addPage(6,p->expansion.buildNutrition(p,ui));
        root.addView(pages,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(tabBar(),ui.fillWrap(8,0));
        show(activeTab);
        return root;
    }
    private LinearLayout tabBar(){
        LinearLayout tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(4));ui.card(tabs);
        String[] names={tr("Активность","Activity"),tr("Тренировки","Workouts"),tr("Питание","Food"),tr("Часы","Faces"),tr("Настройки","Settings")};
        int[] tabIds={2,5,6,0,3};
        UiIcons.Kind[] icons={UiIcons.Kind.ACTIVITY,UiIcons.Kind.DUMBBELL,UiIcons.Kind.FOOD,UiIcons.Kind.WATCH,UiIcons.Kind.SETTINGS};
        for(int i=0;i<tabIds.length;i++){
            final int tab=tabIds[i];boolean chosen=tab==activeTab||(tab==3&&(activeTab==1||activeTab==4));
            LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER);
            item.setPadding(ui.dp(2),ui.dp(4),ui.dp(2),ui.dp(4));item.setMinimumHeight(ui.dp(64));
            ImageView symbol=new ImageView(this);symbol.setImageDrawable(UiIcons.drawable(icons[i],chosen?ui.accentInk:ui.muted));
            symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(symbol,new LinearLayout.LayoutParams(ui.dp(22),ui.dp(22)));
            TextView label=ui.label(names[i],12,chosen?ui.accentInk:ui.muted);label.setGravity(Gravity.CENTER);label.setSingleLine(true);label.setHorizontallyScrolling(false);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setTextSize(12);
            if(chosen)label.setTypeface(Typeface.DEFAULT,Typeface.BOLD);item.addView(label,new LinearLayout.LayoutParams(-1,-2));
            View indicator=new View(this);ui.underline(indicator,chosen);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(ui.dp(24),ui.dp(3));ip.setMargins(0,ui.dp(4),0,0);item.addView(indicator,ip);
            item.setContentDescription(names[i]+(chosen?tr(", выбрано",", selected"):""));item.setSelected(chosen);ui.touchTarget(item);
            item.setOnClickListener(v->{expansion.tabChanged(activeTab,tab);activeTab=tab;rebuildUi();});tabs.addView(item,ui.weighted(1));
        }
        return tabs;
    }
    private android.graphics.drawable.Drawable brandDrawable(){
        int style="midnight-blue".equals(prefs.theme())?R.style.BrandMidnight:"arctic".equals(prefs.theme())?R.style.BrandArctic:"olive".equals(prefs.theme())?R.style.BrandOlive:R.style.BrandGraphite;
        return new android.view.ContextThemeWrapper(this,style).getDrawable(R.drawable.brand_eye);
    }
    private interface PageBuilder{
        void build(LinearLayout page);
    }
    private void addPage(int index,PageBuilder builder){
        ScrollView scroll=new ScrollView(this);
        scroll.setTag(index);
        scroll.setFillViewport(true);
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0,ui.dp(10),0,ui.dp(18));
        if(index==activeTab)builder.build(page);
        scroll.addView(page,new ScrollView.LayoutParams(-1,-2));
        pages.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
    }
    private boolean myDialCategory;
    private boolean falloutFolder;
    private boolean isFalloutDial(BundledDynamicDial dial){
        return "dial3000".equals(dial.id)||"dial3000walk".equals(dial.id)||dial.id.startsWith("pip");
    }
    private int myDialPage;
    private String selectedProjectId;
    private void dials(LinearLayout p){
        dynamicInstall=null;pendingRecoveryButton=null;
        currentImage=null;currentLabel=null;gallery=null;
        if(selectedProjectId!=null){
            Button back=textButton(tr("Мои циферблаты","My watch faces"));back.setOnClickListener(v->{selectedProjectId=null;rebuildUi();});p.addView(back,ui.fillWrap(0,8));
            gallery=p;refreshProjectGallery(p);return;
        }
        if(selectedBundledId!=null&&bundledRepo!=null){
            for(BundledDynamicDial dial:bundledRepo.list())if(selectedBundledId.equals(dial.id)){bundledDialDetails(p,dial);return;}
            selectedBundledId=null;
        }
        p.addView(section(tr("ТЕКУЩИЙ ЦИФЕРБЛАТ","CURRENT DIAL")));
        LinearLayout current=card();
        currentImage=new ImageView(this);
        currentImage.setMinimumHeight(ui.dp(140));
        currentImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        currentImage.setBackgroundColor(Color.BLACK);
        current.addView(currentImage,new LinearLayout.LayoutParams(-1,ui.dp(150)));
        currentLabel=ui.label(tr("Текущий циферблат ещё не определён","Current dial is not identified yet"),13,ui.muted);
        current.addView(currentLabel,ui.fillWrap(8,0));
        restoreCurrent();
        Button refreshCurrent=secondaryButton(tr("ОБНОВИТЬ С ЧАСОВ","REFRESH FROM WATCH"));setEnabledState(refreshCurrent,ready&&watch!=null);
        refreshCurrent.setOnClickListener(v->{if(watch!=null&&ready)watch.refreshCurrentDialNow();else dependency("Подключите часы и дождитесь готовности для чтения текущего циферблата.");});
        current.addView(refreshCurrent,ui.fillWrap(8,0));
        p.addView(current,ui.fillWrap(6,16));
        LinearLayout categories=new LinearLayout(this);categories.setOrientation(LinearLayout.HORIZONTAL);
        Button presets=choiceButton(tr("Предустановленные","Preset faces"));updateChoice(presets,!myDialCategory);
        Button mine=choiceButton(tr("Мои циферблаты","My watch faces"));updateChoice(mine,myDialCategory);
        presets.setOnClickListener(v->{myDialCategory=false;rebuildUi();});mine.setOnClickListener(v->{myDialCategory=true;rebuildUi();});
        categories.addView(presets,ui.weighted(1));LinearLayout.LayoutParams cp=ui.weighted(1);cp.setMargins(ui.dp(8),0,0,0);categories.addView(mine,cp);p.addView(categories,ui.fillWrap(0,12));
        if(!myDialCategory){
        if(bundledRepo==null)p.addView(note(tr("Встроенный пакет не прошёл проверку целостности.","Bundled package failed integrity verification.")),ui.fillWrap(6,14));
        else {
            if(falloutFolder){
                Button back=textButton(tr("Предустановленные","Preset faces"));back.setOnClickListener(v->{falloutFolder=false;rebuildUi();});p.addView(back,ui.fillWrap(0,8));
                p.addView(section(tr("ЧЕЛОВЕЧЕК","CHARACTER")));
            }else{
                Button folder=secondaryButton(tr("Человечек","Character"));folder.setOnClickListener(v->{falloutFolder=true;rebuildUi();});p.addView(folder,ui.fillWrap(6,8));
            }
            LinearLayout row=null;int index=0;
            for(BundledDynamicDial dial:bundledRepo.list()){
            if("pipboy3000".equals(dial.id))continue;
            if(isFalloutDial(dial)!=falloutFolder)continue;
            if(index%2==0){row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);p.addView(row,ui.fillWrap(6,8));}
            LinearLayout bundled=card(); ImageView image=new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setBackgroundColor(Color.BLACK);
            try(InputStream in=getAssets().open(dial.previewAsset)){image.setImageBitmap(BitmapFactory.decodeStream(in));}catch(IOException e){journal.append("Bundled preview unavailable: "+e.getMessage());}
            bundled.addView(image,new LinearLayout.LayoutParams(-1,ui.dp(130))); TextView name=ui.label(dial.name,14,ui.text); name.setTypeface(Typeface.DEFAULT,Typeface.BOLD); name.setMinLines(2); name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END); bundled.addView(name,ui.fillWrap(8,3));
            Button open=secondaryButton(tr("ОТКРЫТЬ","OPEN"));open.setContentDescription(dial.name+tr(": выбрать оформление",": choose appearance"));
            View.OnClickListener showDetail=v->{selectedBundledId=dial.id;rebuildUi();};open.setOnClickListener(showDetail);image.setOnClickListener(showDetail);bundled.addView(open,ui.fillWrap(6,0));
            LinearLayout.LayoutParams item=ui.weighted(1);item.height=LinearLayout.LayoutParams.MATCH_PARENT;if(index%2==1)item.setMargins(ui.dp(8),0,0,0);row.addView(bundled,item);index++;
            }
            // Match the card height policy: a WRAP_CONTENT empty neighbour collapses an odd row.
            if(index%2==1&&row!=null){LinearLayout.LayoutParams empty=ui.weighted(1);empty.height=LinearLayout.LayoutParams.MATCH_PARENT;row.addView(new View(this),empty);}
        }
        return;
        }
        p.addView(section(tr("МОИ ЦИФЕРБЛАТЫ","MY WATCH FACES")));
        gallery=new LinearLayout(this);
        gallery.setOrientation(LinearLayout.VERTICAL);
        p.addView(gallery,ui.fillWrap(6,8));
        refreshGallery();
        Button create=primaryButton(tr("+ СОЗДАТЬ ЦИФЕРБЛАТ","+ CREATE DIAL"));
        create.setOnClickListener(v->dialEditor.create(prefs.theme()));
        p.addView(create,ui.fillWrap(0,8));
        Button importProject=secondaryButton(tr("ИМПОРТИРОВАТЬ ПРОЕКТ .gtdial","IMPORT .gtdial PROJECT"));
        importProject.setEnabled(!projectImportBusy);importProject.setOnClickListener(v->selectProjectArchive());p.addView(importProject,ui.fillWrap(0,8));
        Button albumCreate=secondaryButton(tr("+ ФОТОЦИФЕРБЛАТ","+ PHOTO WATCH FACE"));
        albumCreate.setOnClickListener(v->openEditor(null));p.addView(albumCreate,ui.fillWrap(0,8));
    }
    private void bundledDialDetails(LinearLayout p,BundledDynamicDial dial){
        Button back=textButton((isFalloutDial(dial)?tr("Человечек","Character"):tr("Предустановленные","Preset faces")));back.setOnClickListener(v->{selectedBundledId=null;rebuildUi();});p.addView(back,ui.fillWrap(0,8));
        p.addView(ui.label(dial.name,24,ui.text),ui.fillWrap(0,12));
        LinearLayout detail=card();ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackgroundColor(Color.BLACK);image.setContentDescription(dial.name);
        try(InputStream in=getAssets().open(dial.previewAsset)){image.setImageBitmap(BitmapFactory.decodeStream(in));}catch(IOException e){journal.append("Bundled preview unavailable: "+e.getMessage());}
        detail.addView(image,new LinearLayout.LayoutParams(-1,ui.dp(250)));
        if(dial.palettes.length==6){detail.addView(section(tr("ЦВЕТ ОФОРМЛЕНИЯ","APPEARANCE COLOR")),ui.fillWrap(12,4));detail.addView(paletteControls(dial,image),ui.fillWrap(4,8));}
        if(dial.displayFields.length>0)detail.addView(note(tr("Показатели: ","Metrics: ")+friendlyFields(dial.displayFields)),ui.fillWrap(8,8));
        if(dial.physicalInstallAllowed()){
            Button install=primaryButton(tr("УСТАНОВИТЬ ЦИФЕРБЛАТ","INSTALL WATCH FACE"));dynamicInstall=install;setEnabledState(install,directInstallEligible());install.setOnClickListener(v->confirmDynamicInstall(dial,install));detail.addView(install);bindDirectInstallReason(detail,install);
        }else detail.addView(note(tr("Установка этой модели пока недоступна.","Installation of this model is not available yet.")),ui.fillWrap(6,0));
        p.addView(detail,ui.fillWrap(0,12));
    }
    private String tx(String text){return UiText.choose("en".equals(prefs.language()),text);}
    private String currentDialName(String catalogId){
        if(bundledRepo!=null)for(BundledDynamicDial dial:bundledRepo.list())if(dial.id.equals(catalogId))return dial.name;
        if("dial3000walk v1".equals(catalogId))return tr("Шагающий человечек · прежняя версия","Walking character · previous version");
        return tr("Сохранённый циферблат","Saved watch face");
    }
    private String friendlyFields(String[] fields){List<String> names=new ArrayList<>();for(String field:fields){switch(field){case "time":names.add(tr("Время","Time"));break;case "date":names.add(tr("Дата","Date"));break;case "steps":names.add(tr("Шаги","Steps"));break;case "calories":names.add(tr("Калории","Calories"));break;case "heartRate":names.add(tr("Пульс","Heart rate"));break;case "battery":names.add(tr("Батарея","Battery"));break;case "weather":names.add(tr("Погода","Weather"));break;case "temperature":names.add(tr("Температура","Temperature"));break;case "distance":names.add(tr("Расстояние","Distance"));break;default:names.add(field);}}return android.text.TextUtils.join(" · ",names);}
    private void restoreCurrent(){
        if(latestCurrentDial!=null){
            if(latestCurrentDial.isLocal()){
                CurrentDialSnapshot snapshot=latestCurrentDial;ImageView target=currentImage;
                currentLabel.setText(snapshot.catalogId+tr("\nПредпросмотр с примерными значениями","\nPreview with example values"));target.setImageDrawable(null);
                worker.execute(()->{try{Bitmap preview=compiledArtifacts.preview(snapshot.localArtifactSha256);runOnUiThread(()->{if(destroyed||latestCurrentDial!=snapshot||currentImage!=target){preview.recycle();return;}target.setImageBitmap(preview);});}catch(Exception|OutOfMemoryError e){runOnUiThread(()->{if(!destroyed&&latestCurrentDial==snapshot&&currentImage==target&&currentLabel!=null)currentLabel.setText(tr("Циферблат определён; сохранённый предпросмотр недоступен","Watch face verified; stored preview unavailable"));});}});return;
            }
            if(latestCurrentDial.isKnown()){currentLabel.setText(currentDialName(latestCurrentDial.catalogId));try(InputStream in=getAssets().open(latestCurrentDial.previewAsset)){currentImage.setImageBitmap(BitmapFactory.decodeStream(in));}catch(IOException ignored){}}
            else {currentImage.setImageDrawable(null);currentLabel.setText(tr("Циферблат на часах неизвестен или данные устарели","Watch face is unknown or stale"));}
            return;
        }
        String id=prefs.currentDial();
        for(DialRepository.Dial d:repo.list())if(d.id.equals(id)){
            currentImage.setImageURI(Uri.fromFile(d.image));
            currentLabel.setText(tr("Последний успешно применённый: ","Last successfully applied: ")+d.name);
            return;
        }
    }
    private void refreshGallery(){
        if(gallery==null)return;
        if(selectedProjectId!=null){refreshProjectGallery(gallery);return;}
        final LinearLayout target=gallery;target.removeAllViews();int request=++projectGalleryGeneration;
        if(dialEditor!=null&&dialEditor.hasDraft()){
            Button resume=secondaryButton(tr("Продолжить черновик","Continue draft"));resume.setOnClickListener(v->dialEditor.resume(prefs.theme()));target.addView(resume,ui.fillWrap(0,8));
        }
        worker.execute(()->{List<DialProjectRepository.Revision> projects=projectRepo.list();List<DialRepository.Dial> photos=repo.list();runOnUiThread(()->{
            if(destroyed||request!=projectGalleryGeneration||gallery!=target||selectedProjectId!=null)return;
            int total=projects.size()+photos.size(),pageSize=6,pageCount=Math.max(1,(total+pageSize-1)/pageSize);
            myDialPage=Math.min(myDialPage,pageCount-1);
            if(total==0){target.addView(note(tr("Создайте свой первый циферблат","Create your first watch face")),ui.fillWrap(8,8));return;}
            LinearLayout row=null;
            for(int i=myDialPage*pageSize;i<Math.min(total,(myDialPage+1)*pageSize);i++){
                int col=(i-myDialPage*pageSize)%2;
                if(col==0){row=new LinearLayout(this);target.addView(row,ui.fillWrap(0,8));}
                LinearLayout tile=card();ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackgroundColor(Color.BLACK);
                String name;Runnable open;
                if(i<projects.size()){
                    DialProjectRepository.Revision r=projects.get(i);name=r.project.name;image.setImageURI(Uri.fromFile(r.preview));open=()->{selectedProjectId=r.project.id;rebuildUi();};
                    tile.addView(image,new LinearLayout.LayoutParams(-1,ui.dp(130)));tile.addView(note(tr("Созданный проект","Created project")),ui.fillWrap(6,2));
                }else{
                    DialRepository.Dial photo=photos.get(i-projects.size());name=photo.name;image.setImageURI(Uri.fromFile(photo.image));open=()->showPhotoDetails(photo);
                    tile.addView(image,new LinearLayout.LayoutParams(-1,ui.dp(130)));tile.addView(note(tr("Фотоциферблат","Photo watch face")),ui.fillWrap(6,2));
                }
                TextView title=ui.label(name,15,ui.text);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setMinLines(2);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);tile.addView(title,ui.fillWrap(4,4));
                Button button=secondaryButton(tr("Открыть","Open"));button.setOnClickListener(v->open.run());image.setContentDescription(name);image.setOnClickListener(v->open.run());tile.addView(button);
                LinearLayout.LayoutParams cell=ui.weighted(1);cell.height=-1;if(col==1)cell.setMargins(ui.dp(8),0,0,0);row.addView(tile,cell);
            }
            if(total%2==1&&myDialPage==pageCount-1&&row!=null)row.addView(new View(this),ui.weighted(1));
            if(pageCount>1){LinearLayout nav=new LinearLayout(this);Button prev=secondaryButton(tr("Назад","Previous"));prev.setContentDescription(tr("Предыдущая страница","Previous page"));setEnabledState(prev,myDialPage>0);prev.setOnClickListener(v->{myDialPage--;refreshGallery();});nav.addView(prev,ui.weightedGap(1,0,8));
                TextView count=ui.label((myDialPage+1)+" / "+pageCount,14,ui.muted);count.setGravity(Gravity.CENTER);nav.addView(count,new LinearLayout.LayoutParams(ui.dp(80),ui.dp(48)));
                Button next=secondaryButton(tr("Далее","Next"));next.setContentDescription(tr("Следующая страница","Next page"));setEnabledState(next,myDialPage+1<pageCount);next.setOnClickListener(v->{myDialPage++;refreshGallery();});nav.addView(next,ui.weightedGap(1,8,0));target.addView(nav,ui.fillWrap(4,8));}
        });});
    }
    private void showPhotoDetails(DialRepository.Dial photo){
        LinearLayout content=card();ImageView image=new ImageView(this);image.setImageURI(Uri.fromFile(photo.image));image.setScaleType(ImageView.ScaleType.FIT_CENTER);content.addView(image,new LinearLayout.LayoutParams(-1,ui.dp(180)));
        Button edit=secondaryButton(tr("Редактировать","Edit"));edit.setOnClickListener(v->openEditor(photo));content.addView(edit,ui.fillWrap(8,8));
        Button upload=primaryButton(tr("Установить на часы","Install on watch"));setEnabledState(upload,photo.uploadable());upload.setOnClickListener(v->loadForUpload(photo));content.addView(upload);
        Button delete=textButton(tr("Удалить с телефона","Delete from phone"));content.addView(delete,ui.fillWrap(8,0));
        AlertDialog details=ui.dialogBuilder().setTitle(photo.name).setView(content).setNegativeButton(tr("Закрыть","Close"),null).create();delete.setOnClickListener(v->{details.dismiss();confirmDelete(photo);});edit.setOnClickListener(v->{details.dismiss();openEditor(photo);});upload.setOnClickListener(v->{details.dismiss();loadForUpload(photo);});details.show();ui.dialog(details);
    }
    private void confirmDeleteProject(DialProjectRepository.Revision revision){
        if(!dialEditor.deletionReady()){toast(tr("Сначала закройте редактор и дождитесь подготовки фотографии","Close the editor and wait for image preparation"));return;}
        if(localInstallPreparationPending||latestDialProgress!=null&&!latestDialProgress.terminal()){toast(tr("Дождитесь окончания установки","Wait until installation finishes"));return;}
        ui.dialogBuilder().setTitle(tr("Удалить проект?","Delete project?"))
            .setMessage(revision.project.name+tr("\n\nПроект и его история исчезнут из галереи. Установленный на часах циферблат сохранится.","\n\nThe project and its history will be removed from the gallery. The installed watch face will remain."))
            .setNegativeButton(tr("Отмена","Cancel"),null).setPositiveButton(tr("Удалить","Delete"),(d,w)->worker.execute(()->{
                try{projectRepo.delete(revision.project.id);runOnUiThread(()->{if(destroyed)return;dialEditor.discardDeletedProject(revision.project.id);selectedProjectId=null;myDialCategory=true;journal.append("DIAL_EDITOR project deleted id="+revision.project.id);rebuildUi();toast(tr("Проект удалён","Project deleted"));});}
                catch(Exception e){runOnUiThread(()->{if(!destroyed)toast(tr("Не удалось удалить проект: ","Could not delete project: ")+e.getMessage());});}
            })).show();
    }
    private void refreshProjectGallery(LinearLayout target){
        if(projectRepo==null||worker==null)return;int request=++projectGalleryGeneration;
        worker.execute(()->{List<DialProjectRepository.Revision> projects=projectRepo.list();runOnUiThread(()->{
            if(destroyed||request!=projectGalleryGeneration||gallery!=target)return;
            target.removeAllViews();Button back=textButton(tr("Мои циферблаты","My watch faces"));back.setOnClickListener(v->{selectedProjectId=null;rebuildUi();});target.addView(back,ui.fillWrap(0,8));
            for(DialProjectRepository.Revision revision:projects){
                if(!revision.project.id.equals(selectedProjectId))continue;
                LinearLayout item=card();ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setImageURI(Uri.fromFile(revision.preview));item.addView(image,new LinearLayout.LayoutParams(-1,ui.dp(180)));
                LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
                heading.addView(ui.label(revision.project.name,17,ui.text),ui.weighted(1));
                Button more=textButton("⋮");more.setContentDescription(tr("Действия с циферблатом","Watch face actions"));heading.addView(more,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));item.addView(heading);
                TextView preparation=note(revision.project.elements.size()+tr(" показателей · сохранено"," metrics · saved"));item.addView(preparation,ui.fillWrap(4,6));
                Button prepare=primaryButton(tr("ПОДГОТОВИТЬ ДЛЯ ЧАСОВ","PREPARE FOR WATCH"));
                Button export=textButton(tr("Поделиться готовым .dial","Share prepared .dial"));export.setVisibility(View.GONE);
                Button install=primaryButton(tr("УСТАНОВИТЬ ЦИФЕРБЛАТ","INSTALL WATCH FACE"));install.setVisibility(View.GONE);
                localDirectInstallButtons.add(install);setEnabledState(install,directInstallEligible());
                CompiledDialArtifactStore.Artifact[] artifact={null};
                install.setOnClickListener(v->{if(artifact[0]!=null)confirmLocalDynamicInstall(artifact[0],install);});
                prepare.setOnClickListener(v->prepareProject(revision,artifact,preparation,prepare,export,install));
                export.setOnClickListener(v->{if(artifact[0]!=null)exportPrepared(artifact[0].verified.sha256,export);});
                worker.execute(()->{try{CompiledDialArtifactStore.Artifact saved=compiledArtifacts.find(revision.project);runOnUiThread(()->{
                    if(destroyed||request!=projectGalleryGeneration||gallery!=target)return;if(saved!=null){artifact[0]=saved;journal.append("DIAL_EDITOR gallery artifact verified "+localPackageSummary(DynamicDialPackage.local(saved)));showPrepared(saved,preparation,prepare,export,install);}
                });}catch(Exception|OutOfMemoryError e){journal.append("DIAL_EDITOR gallery artifact refused project="+revision.project.id+" type="+e.getClass().getSimpleName()+" reason="+e.getMessage());runOnUiThread(()->{if(!destroyed&&request==projectGalleryGeneration)preparation.setText(tr("Сохранённая сборка недоступна: ","Saved build unavailable: ")+e.getMessage());});}});
                item.addView(prepare,ui.fillWrap(8,8));item.addView(install,ui.fillWrap(0,8));bindDirectInstallReason(item,install);item.addView(export,ui.fillWrap(0,8));
                Button edit=secondaryButton(tr("РЕДАКТИРОВАТЬ","EDIT"));edit.setOnClickListener(v->dialEditor.edit(revision,prefs.theme()));item.addView(edit);
                Button exportProject=textButton(tr("Поделиться проектом","Share project"));
                more.setOnClickListener(v->{
                    List<String> names=new ArrayList<>();List<Integer> actions=new ArrayList<>();
                    if(prepare.isEnabled()){names.add(tr("Подготовить для часов","Prepare for watch"));actions.add(1);}
                    if(exportProject.isEnabled()){names.add(tr("Поделиться проектом .gtdial","Share .gtdial project"));actions.add(2);}
                    if(artifact[0]!=null&&export.isEnabled()){names.add(tr("Поделиться готовым .dial","Share prepared .dial"));actions.add(3);names.add(tr("Архив для проверки .zip","Verification archive .zip"));actions.add(6);}
                    names.add(tr("История изменений","Version history"));actions.add(4);
                    if(artifact[0]!=null&&install.isEnabled()){names.add(tr("Установить на часы","Install on watch"));actions.add(5);}
                    names.add(tr("Удалить проект","Delete project"));actions.add(7);
                    ui.dialogBuilder().setTitle(revision.project.name).setItems(names.toArray(new String[0]),(d,index)->{
                        switch(actions.get(index)){
                            case 1:prepare.performClick();break;
                            case 2:exportProjectArchive(revision,exportProject);break;
                            case 3:export.performClick();break;
                            case 4:showProjectRevisions(revision.project.id);break;
                            case 5:install.performClick();break;
                            case 6:if(artifact[0]!=null)exportPreparedEvidence(artifact[0].verified.sha256,more);break;
                            case 7:confirmDeleteProject(revision);break;
                        }
                    }).setNegativeButton(tr("Закрыть","Close"),null).show();});
                target.addView(item,ui.fillWrap(0,10));
            }
        });});
    }
    private void showProjectRevisions(String id){
        worker.execute(()->{List<DialProjectRepository.Revision> revisions=projectRepo.revisions(id);runOnUiThread(()->{
            if(destroyed)return;if(revisions.isEmpty()){toast(tr("Ревизии недоступны","Revisions unavailable"));return;}
            String[] names=new String[revisions.size()];for(int i=0;i<names.length;i++)names[i]=tr("Ревизия ","Revision ")+revisions.get(i).project.revision+" · "+revisions.get(i).project.name;
            ui.dialogBuilder().setTitle(tr("Открыть ревизию","Open revision")).setItems(names,(d,index)->dialEditor.edit(revisions.get(index),prefs.theme())).setNegativeButton(tr("Закрыть","Close"),null).show();
        });});
    }
    private void prepareProject(DialProjectRepository.Revision revision,CompiledDialArtifactStore.Artifact[] artifact,TextView state,Button prepare,Button export,Button install){
        journal.append("DIAL_EDITOR prepare requested project="+revision.project.id+" revision="+revision.project.revision+" nodes="+revision.project.elements.size());
        install.setEnabled(false);prepare.setEnabled(false);export.setEnabled(false);state.setText(tr("Подготовка и проверка пакета…","Preparing and verifying package…"));
        worker.execute(()->{try{
            CompiledDialArtifactStore.Artifact built=compiledArtifacts.find(revision.project);
            journal.append("DIAL_EDITOR find existing="+(built!=null)+" project="+revision.project.id);
            if(built==null)built=compiledArtifacts.prepare(revision);CompiledDialArtifactStore.Artifact result=built;
            journal.append("DIAL_EDITOR prepare verified "+localPackageSummary(DynamicDialPackage.local(result)));
            runOnUiThread(()->{if(destroyed)return;artifact[0]=result;showPrepared(result,state,prepare,export,install);toast(tr("Пакет подготовлен и проверен; можно устанавливать","Package prepared and verified; ready to install"));});
        }catch(Exception|OutOfMemoryError e){runOnUiThread(()->{if(destroyed)return;prepare.setEnabled(true);export.setEnabled(artifact[0]!=null);
            journal.append("DIAL_EDITOR prepare failed type="+e.getClass().getSimpleName()+" reason="+e.getMessage());
            state.setText(tr("Подготовка остановлена: ","Preparation stopped: ")+(e instanceof OutOfMemoryError?tr("недостаточно памяти; выберите меньшую фотографию","not enough memory; choose a smaller photo"):e.getMessage()));});}});
    }
    private void showPrepared(CompiledDialArtifactStore.Artifact artifact,TextView state,Button prepare,Button export,Button install){
        install.setVisibility(View.VISIBLE);setEnabledState(install,directInstallEligible());updateDirectInstallButtons();
        state.setText(tr("Пакет проверен. Можно установить вместо прежнего циферблата.","Package verified. Ready to replace the previous watch face."));
        prepare.setEnabled(true);prepare.setText(tr("ПРОВЕРИТЬ ПАКЕТ","VERIFY PACKAGE"));export.setEnabled(true);export.setVisibility(View.VISIBLE);
    }
    private void exportPrepared(String sha,Button export){
        export.setEnabled(false);worker.execute(()->{try{File file=compiledArtifacts.export(sha);runOnUiThread(()->{if(destroyed)return;export.setEnabled(true);share(file,"application/octet-stream");});}
            catch(Exception|OutOfMemoryError e){runOnUiThread(()->{if(destroyed)return;export.setEnabled(true);toast(tr("Не удалось подготовить копию пакета: ","Could not prepare package copy: ")+e.getMessage());});}});
    }
    private static String localPackageSummary(DynamicDialPackage pkg){
        return "origin="+pkg.origin+" identity="+pkg.internalPackageId+" alias="+pkg.alias+" bytes="+pkg.packageSize
            +" packageSha256="+pkg.packageSha256+" profileSha256="+pkg.profileSha256+" snapshotSha256="+pkg.snapshotSha256
            +" artifactSha256="+pkg.artifactSha256+" project="+pkg.projectId;
    }
    private void exportPreparedEvidence(String sha,Button action){
        action.setEnabled(false);worker.execute(()->{try{
            File file=compiledArtifacts.exportEvidence(sha);
            runOnUiThread(()->{if(destroyed)return;action.setEnabled(true);share(file,"application/zip");});
        }catch(Exception|OutOfMemoryError e){runOnUiThread(()->{if(destroyed)return;action.setEnabled(true);
            toast(e instanceof OutOfMemoryError?tr("Недостаточно памяти для архива проверки","Not enough memory for the verification archive"):
                tr("Архив проверки не подготовлен: ","Verification archive could not be prepared: ")+e.getMessage());});}});
    }
    private void selectProjectArchive(){
        if(projectImportBusy)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent,PROJECT_DOCUMENT);
    }
    private void importProjectArchive(Uri uri){
        if(projectImportBusy||destroyed)return;projectImportBusy=true;toast(tr("Проверка и импорт проекта…","Verifying and importing project…"));
        worker.execute(()->{try{
            DialProjectRepository.Revision imported;
            try(InputStream input=getContentResolver().openInputStream(uri)){if(input==null)throw new IOException("Не удалось открыть проект");imported=projectRepo.importAsNew(DialProjectArchive.read(input));}
            runOnUiThread(()->{projectImportBusy=false;if(destroyed)return;refreshGallery();toast(tr("Проект готов: ","Project ready: ")+imported.project.name);});
        }catch(Exception|OutOfMemoryError error){runOnUiThread(()->{projectImportBusy=false;if(destroyed)return;toast(tr("Импорт остановлен: ","Import stopped: ")+(error instanceof OutOfMemoryError?tr("недостаточно памяти","not enough memory"):error.getMessage()));});}});
    }
    private void exportProjectArchive(DialProjectRepository.Revision revision,Button button){
        button.setEnabled(false);worker.execute(()->{try{
            File file=DialProjectArchive.export(this,revision);runOnUiThread(()->{if(destroyed)return;button.setEnabled(true);share(file,"application/zip");});
        }catch(Exception|OutOfMemoryError error){runOnUiThread(()->{if(destroyed)return;button.setEnabled(true);toast(tr("Экспорт остановлен: ","Export stopped: ")+(error instanceof OutOfMemoryError?tr("недостаточно памяти","not enough memory"):error.getMessage()));});}});
    }
    private void openEditor(DialRepository.Dial d){
        if(photoDialog!=null)photoDialog.dismiss();generation++;
        editingId=d==null?null:d.id;
        selectedPosition=d==null?1:d.position;
        selectedColor=d==null?Color.WHITE:d.color;
        selected=null;
        if(base!=null&&!base.isRecycled())base.recycle();
        base=null;
        if(d!=null)try{
            selected=read(d.image);
            base=decodeLegacyPhoto(selected);
        }catch(Exception|OutOfMemoryError e){
            toast(e.getMessage());
        }
        LinearLayout form=new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(ui.dp(20),ui.dp(4),ui.dp(20),0);
        imageInfo=note(selected==null?tr("Выберите изображение","Choose image"):tr("Изображение загружено","Image loaded"));
        form.addView(imageInfo,ui.fillWrap(0,8));
        Button choose=secondaryButton(tr("ВЫБРАТЬ ИЗОБРАЖЕНИЕ","CHOOSE IMAGE"));
        choose.setOnClickListener(v->selectImage());
        form.addView(choose,ui.fillWrap(0,8));
        LinearLayout previewCard=card();
        previewCard.addView(ui.mono(tr("ПРЕДПРОСМОТР","PREVIEW")),ui.fillWrap(0,6));
        preview=new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(Color.BLACK);
        previewCard.addView(preview,new LinearLayout.LayoutParams(-1,ui.dp(240)));
        form.addView(previewCard,ui.fillWrap(0,12));
        form.addView(ui.mono(tr("РАСПОЛОЖЕНИЕ","POSITION")),ui.fillWrap(0,6));
        form.addView(positionControl(),ui.fillWrap(0,12));
        form.addView(ui.mono(tr("ЦВЕТ ТЕКСТА","TEXT COLOR")),ui.fillWrap(0,6));
        form.addView(colorPalette(),ui.fillWrap(0,12));
        form.addView(ui.mono(tr("ЛОКАЛЬНЫЙ МАКЕТ","LOCAL LAYOUT")),ui.fillWrap(0,6));
        form.addView(placementControls(d),ui.fillWrap(0,0));
        render();
        ScrollView photoScroll=new ScrollView(this);photoScroll.addView(form);
        AlertDialog dialog=ui.dialogBuilder().setTitle(d==null?tr("Новый циферблат","New dial"):tr("Изменить циферблат","Edit dial")).setView(photoScroll).setNegativeButton(tr("Отмена","Cancel"),null).setPositiveButton(tr("Сохранить","Save"),null).create();
        photoDialog=dialog;dialog.setOnDismissListener(x->{generation++;if(photoDialog==dialog){photoDialog=null;preview=null;imageInfo=null;if(base!=null&&!base.isRecycled())base.recycle();base=null;}});
        dialog.setOnShowListener(x->{ui.dialog(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->saveDial(dialog,d));});
        dialog.show();
    }
    private LinearLayout paletteControls(BundledDynamicDial dial,ImageView image){
        LinearLayout block=new LinearLayout(this);block.setOrientation(LinearLayout.VERTICAL);
        String stored=prefs.palette(dial.id);BundledDynamicDial.Palette initial=dial.palette(stored.isEmpty()?dial.defaultPaletteId:stored);
        final Button[] buttons=new Button[6];
        int columns=getResources().getConfiguration().fontScale>1.25f?1:2;
        for(int rowIndex=0;rowIndex<(6+columns-1)/columns;rowIndex++){
            LinearLayout row=new LinearLayout(this);
            for(int column=0;column<columns;column++){
                int i=rowIndex*columns+column;final int paletteIndex=i;BundledDynamicDial.Palette palette=dial.palettes[i];
                Button button=choiceButton(palette.name.replace("-","-\n"));button.setContentDescription(palette.name);buttons[i]=button;
                android.graphics.drawable.GradientDrawable dot=new android.graphics.drawable.GradientDrawable();dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);dot.setColor(Color.parseColor(palette.primary));dot.setStroke(ui.dp(1),ui.border);dot.setBounds(0,0,ui.dp(14),ui.dp(14));button.setCompoundDrawables(dot,null,null,null);button.setCompoundDrawablePadding(ui.dp(8));
                updateChoice(button,palette.id.equals(initial.id));
                button.setOnClickListener(v->{prefs.palette(dial.id,palette.id);applyPalette(dial,image,palette);for(int x=0;x<buttons.length;x++)if(buttons[x]!=null)updateChoice(buttons[x],x==paletteIndex);});
                row.addView(button,ui.weightedGap(1,column==0?0:4,column==columns-1?0:4));
            }
            block.addView(row,ui.fillWrap(4,4));
        }
        applyPalette(dial,image,initial);return block;
    }
    private void applyPalette(BundledDynamicDial dial,ImageView image,BundledDynamicDial.Palette palette){
        String preview=("dial3000".equals(dial.id)||"dial3000walk".equals(dial.id)||(dial.id.startsWith("pip")&&!dial.id.startsWith("pipboy")))?"dynamic_dials/"+dial.id+"/"+palette.id+"/preview.png":dial.previewAsset;
        try(InputStream in=getAssets().open(preview)){
            android.graphics.Bitmap source=BitmapFactory.decodeStream(in);
            if(source==null)throw new IOException("Preview decode failed");
            if(("dial3000".equals(dial.id)||"dial3000walk".equals(dial.id)||(dial.id.startsWith("pip")&&!dial.id.startsWith("pipboy")))){image.clearColorFilter();image.setBackgroundColor(Color.BLACK);image.setImageBitmap(source);image.setContentDescription(palette.name);return;}
            int width=source.getWidth(),height=source.getHeight();
            int[] pixels=new int[width*height];source.getPixels(pixels,0,width,0,0,width,height);
            int background=Color.parseColor(palette.background),primary=Color.parseColor(palette.primary);
            for(int i=0;i<pixels.length;i++)pixels[i]=PalettePixelMapper.map(pixels[i],background,primary);
            android.graphics.Bitmap colored=android.graphics.Bitmap.createBitmap(pixels,width,height,android.graphics.Bitmap.Config.ARGB_8888);
            image.clearColorFilter();image.setBackgroundColor(background);image.setImageBitmap(colored);
            image.setContentDescription(palette.name);
        }catch(IOException e){journal.append("Palette preview unavailable: "+e.getMessage());}
    }
    private void confirmLocalDynamicInstall(CompiledDialArtifactStore.Artifact artifact,Button button){
        if(!directInstallEligible()){onState(tr("Подключите часы и дождитесь окончания текущей установки","Connect the watch and wait for the current installation to finish"));return;}
        DynamicDialPackage selected=DynamicDialPackage.local(artifact);
        String[] fields=new String[artifact.snapshot.project.elements.size()];for(int i=0;i<fields.length;i++)fields[i]=DialRuntimeProfile.field(artifact.snapshot.project.elements.get(i).id).label;
        directInstallUiPending=true;pendingLocalInstallButton=button;updateDirectInstallButtons();
        watch.installLocalDynamicDial(selected,artifact.snapshot.project.name,fields);
    }
    private void confirmPendingLocalRecovery(Button button){
        if(!ready||sessionBinder==null){toast(tr("Подключите часы и дождитесь готовности","Connect the watch and wait until ready"));return;}
        if(localInstallPreparationPending)return;
        localInstallPreparationPending=true;pendingLocalInstallButton=button;button.setEnabled(false);
        sessionBinder.preparePendingLocalRecovery();
    }
    private boolean localInstallPreparationPending;
    private Button pendingLocalInstallButton;
    private DynamicDialAuthorization.PreparedLocal pendingLocalPreparation;
    private AlertDialog localInstallDialog;
    @Override public void onLocalDialPrepared(DynamicDialAuthorization.PreparedLocal prepared){
        runOnUiThread(()->{
            if(destroyed||!localInstallPreparationPending||sessionBinder==null||!ready){if(sessionBinder!=null)sessionBinder.cancelLocalDynamicDial(prepared);return;}
            dismissDialProgress();
            localInstallPreparationPending=false;pendingLocalPreparation=prepared;
            journal.append("DIAL_UI confirmation shown operationId="+prepared.operationId+" sha256="+prepared.packageSha256+" snapshotSha256="+prepared.snapshotSha256);
            LinearLayout content=new LinearLayout(ui.dialogContext());content.setOrientation(LinearLayout.VERTICAL);content.setPadding(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(8));
            boolean verifyPending="VERIFY_PENDING".equals(prepared.mode);
            content.addView(ui.label(prepared.projectName,18,ui.text));
            content.addView(note(tr("Часы: ","Watch: ")+prepared.deviceAddress));
            content.addView(note(verifyPending?tr("Приложение прочитает уже установленный пакет и подтвердит результат. Повторной передачи, установки или переключения циферблата не будет. Оставьте часы подключёнными до окончания проверки.","The app will read the installed package and verify the result. No upload, installation or face switch will occur. Keep the watch connected until verification ends."):tr("Отключите MyHealth. Прежний циферблат можно оставить выбранным на часах. Сохранённая проверенная копия ускоряет подготовку; новый файл проверяется чтением после передачи. При ошибке установка остановится без повтора.","Disconnect MyHealth. You can keep the previous face selected. A verified saved copy speeds up preparation; the new file is read back after upload. Any error stops installation without retry.")));
            ScrollView scroll=new ScrollView(ui.dialogContext());scroll.addView(content);
            boolean[] submitted={false};
            localInstallDialog=ui.dialogBuilder().setTitle(verifyPending?tr("Проверить прошлую установку?","Check previous installation?"):tr("Установить циферблат?","Install watch face?"))
                .setView(scroll).setNegativeButton(tr("ОТМЕНА","CANCEL"),null)
                .setPositiveButton(verifyPending?tr("ПРОВЕРИТЬ","VERIFY"):tr("УСТАНОВИТЬ","INSTALL"),(d,w)->{
                    try{
                        if(sessionBinder==null||!ready)throw new IllegalStateException("CONNECTION_NOT_READY");
                        DynamicDialAuthorization.LocalConsent consent=DynamicDialAuthorization.confirmLocal(prepared);
                        submitted[0]=true;pendingLocalPreparation=null;
                        journal.append("DIAL_UI confirmation accepted operationId="+prepared.operationId+" sha256="+prepared.packageSha256);
                        sessionBinder.installConfirmedLocalDynamicDial(consent);
                    }catch(Exception e){journal.append("DIAL_UI confirmation refused operationId="+prepared.operationId+" type="+e.getClass().getSimpleName());onState(tr("Подтверждение устарело или соединение изменилось. Установка не начата.","Confirmation expired or the connection changed. Installation did not start."));}
                }).create();
            localInstallDialog.setOnDismissListener(d->{localInstallDialog=null;if(!submitted[0])cancelLocalInstallConfirmation();});localInstallDialog.show();
        });
    }
    private void cancelLocalInstallConfirmation(){
        DynamicDialAuthorization.PreparedLocal prepared=pendingLocalPreparation;pendingLocalPreparation=null;localInstallPreparationPending=false;
        if(prepared!=null){journal.append("DIAL_UI confirmation cancelled operationId="+prepared.operationId);if(sessionBinder!=null)sessionBinder.cancelLocalDynamicDial(prepared);}
        if(pendingLocalInstallButton!=null){pendingLocalInstallButton.setEnabled(true);pendingLocalInstallButton=null;}
    }
    @Override public void onDialInstallProgress(DialInstallProgress progress){
        runOnUiThread(()->{
            if(destroyed||progress==null)return;
            boolean newOperation=latestDialProgress==null||latestDialProgress.startedAtMs!=progress.startedAtMs;
            latestDialProgress=progress;if(newOperation)dialProgressHidden=false;
            if(progress.direct){directInstallUiPending=!progress.terminal();updateDirectInstallButtons();}
            updateDialProgressButton();
            if("COMPLETE".equals(progress.phase)||"CANCELLED".equals(progress.phase)){dialProgressHidden=true;dismissDialProgress();updateDialProgressButton();return;}
            if("CONFIRM".equals(progress.phase)){dismissDialProgress();return;}
            if("CANCELLED".equals(progress.phase)){dismissDialProgress();return;}
            if(!dialProgressHidden||progress.terminal())showDialInstallProgress();else renderDialProgress();
        });
    }
    private void showDialInstallProgress(){
        if(destroyed||latestDialProgress==null)return;
        if(dialProgressDialog==null){
            LinearLayout content=new LinearLayout(ui.dialogContext());content.setOrientation(LinearLayout.VERTICAL);content.setPadding(ui.dp(18),ui.dp(12),ui.dp(18),ui.dp(8));
            dialProgressTitle=ui.label("",19,ui.text);content.addView(dialProgressTitle,ui.fillWrap(4,10));
            dialProgressStage=ui.label("",14,ui.muted);content.addView(dialProgressStage);
            dialProgressBar=new ProgressBar(ui.dialogContext(),null,android.R.attr.progressBarStyleHorizontal);dialProgressBar.setMax(1000);ui.progress(dialProgressBar);content.addView(dialProgressBar,ui.fillWrap(8,8));
            dialProgressBytes=ui.label("",15,ui.text);content.addView(dialProgressBytes);
            dialProgressElapsed=ui.label("",13,ui.muted);content.addView(dialProgressElapsed,ui.fillWrap(6,12));
            dialProgressSteps=ui.label("",14,ui.muted);content.addView(dialProgressSteps,ui.fillWrap(0,12));
            dialProgressDetail=ui.label("",15,ui.text);content.addView(dialProgressDetail,ui.fillWrap(4,12));
            ScrollView scroll=new ScrollView(ui.dialogContext());scroll.addView(content);
            dialProgressDialog=ui.dialogBuilder().setTitle(tr("Установка циферблата","Watch face installation")).setView(scroll)
                .setPositiveButton(tr("Свернуть","Minimize"),(d,w)->{dialProgressHidden=true;updateDialProgressButton();})
                .setNegativeButton(tr("Подключение","Connection"),(d,w)->{dialProgressHidden=true;expansion.tabChanged(activeTab,3);settingsSections.put("watch",true);activeTab=3;rebuildUi();})
                .setNeutralButton(tr("Экспорт диагностики","Export diagnostics"),(d,w)->{dialProgressHidden=true;exportDiagnostics();}).create();
            dialProgressDialog.setCanceledOnTouchOutside(false);
            dialProgressDialog.setOnCancelListener(d->dialProgressHidden=true);
            dialProgressDialog.setOnDismissListener(d->{dialProgressDialog=null;journalUiHandler.removeCallbacks(dialProgressTick);});
            dialProgressDialog.show();
        }
        renderDialProgress();journalUiHandler.removeCallbacks(dialProgressTick);
        if(!latestDialProgress.terminal())journalUiHandler.postDelayed(dialProgressTick,500);
    }
    private void dismissDialProgress(){if(dialProgressDialog!=null)dialProgressDialog.dismiss();}
    private void updateDialProgressButton(){if(dialProgressButton==null)return;DialInstallProgress p=latestDialProgress;
        boolean visible=p!=null&&!"COMPLETE".equals(p.phase)&&!"CANCELLED".equals(p.phase)&&(!p.terminal()||activeTab==0&&!dialProgressHidden);
        dialProgressButton.setVisibility(visible?View.VISIBLE:View.GONE);if(visible)dialProgressButton.setText(p.title(!"en".equals(prefs.language()))+tr(" · Подробнее"," · Details"));}
    private void renderDialProgress(){
        if(dialProgressDialog==null||latestDialProgress==null)return;DialInstallProgress p=latestDialProgress;boolean ru=!"en".equals(prefs.language());
        dialProgressTitle.setText(p.title(ru));
        dialProgressStage.setText(p.stage()==0?tr("Подготовка на телефоне","Preparation on phone"):"COMPLETE".equals(p.phase)?tr("Все этапы завершены","All stages completed"):(p.terminal()?tr("Остановлено на этапе ","Stopped at stage "):tr("Этап ","Stage "))+p.stage()+tr(" из 4"," of 4"));
        boolean bytes=p.total>0&&!p.terminal();dialProgressBar.setIndeterminate(!bytes&&!p.terminal());
        dialProgressBar.setProgress("COMPLETE".equals(p.phase)?1000:bytes?(int)Math.min(1000,p.completed*1000/p.total):0);
        dialProgressBytes.setText(bytes?String.format(java.util.Locale.ROOT,"%d%% · %.1f / %.1f KB",Math.min(100,p.completed*100/p.total),p.completed/1024.0,p.total/1024.0):p.terminal()?"":tr("Ожидаю завершения текущего этапа…","Waiting for the current stage…"));
        long seconds=Math.max(0,((p.terminal()?p.updatedAtMs:SystemClock.elapsedRealtime())-p.startedAtMs)/1000);
        dialProgressElapsed.setText(tr("Прошло: ","Elapsed: ")+String.format(java.util.Locale.ROOT,"%d:%02d",seconds/60,seconds%60));
        boolean recovery=p.activePhase.startsWith("RECOVERY_")||"RECOVERY_VERIFIED".equals(p.error);
        dialProgressDialog.setTitle(recovery?tr("Подтверждение установки","Installation verification"):tr("Установка циферблата","Watch face installation"));
        String[] stages=p.direct?(ru?new String[]{"Соединение","Передача файла","Установка","Применение и текущий циферблат"}:new String[]{"Connection","File upload","Install","Apply and current face"}):recovery?(ru?new String[]{"Проверка часов","Чтение установленного пакета","Сверка результата","Сохранение подтверждения"}:new String[]{"Watch checks","Read installed package","Verify result","Save confirmation"}):(ru?new String[]{"Подготовка","Передача файла","Проверка файла","Применение"}:new String[]{"Preparation","File upload","File verification","Apply"});
        StringBuilder lines=new StringBuilder();for(int i=0;i<stages.length;i++){boolean done="COMPLETE".equals(p.phase)||i+1<p.stage();lines.append(i+1).append(". ").append(stages[i]).append(done?tr(" — готово"," — done"):i+1==p.stage()?p.terminal()?tr(" — остановлено"," — stopped"):tr(" — сейчас"," — current"):"").append('\n');}
        dialProgressSteps.setText(lines.toString().trim());dialProgressDetail.setText(p.detail(ru));
        dialProgressDialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(p.terminal()?tr("Закрыть","Close"):tr("Свернуть","Minimize"));
        dialProgressDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(p.terminal());
        dialProgressDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(p.terminal());
    }
    private void confirmDynamicInstall(BundledDynamicDial dial,Button installButton){
        if(!directInstallEligible()){onState(tr("Подключите часы и дождитесь окончания текущей установки","Connect the watch and wait for the current installation to finish"));return;}
        String selectedPalette=prefs.palette(dial.id);if(selectedPalette.isEmpty())selectedPalette=dial.defaultPaletteId;
        if(!dial.physicalInstallAllowed(selectedPalette)){onState(tr("Для выбранного циферблата и цвета проверенный пакет недоступен","A verified package is unavailable for this watch face and color"));return;}
        final BundledDynamicDial.VerifiedPackage verified;
        try{verified=bundledRepo.packageForInstall(dial,selectedPalette,line->{journal.append(line);onLog(line);});}catch(IOException e){onState(tr("Пакет не прошёл проверку","Package verification failed"));onLog("DIAL local verification failed: "+e.getMessage());return;}
        directInstallUiPending=true;updateDirectInstallButtons();watch.installBundledDynamicDial(verified);
    }
    private LinearLayout positionControl(){
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        topPosition=choiceButton(tr("СВЕРХУ","TOP"));
        bottomPosition=choiceButton(tr("СНИЗУ","BOTTOM"));
        topPosition.setOnClickListener(v->{selectedPosition=1;updatePositionChoices();render();});
        bottomPosition.setOnClickListener(v->{selectedPosition=2;updatePositionChoices();render();});
        row.addView(topPosition,ui.weighted(1));
        LinearLayout.LayoutParams bottomParams=ui.weighted(1);bottomParams.setMargins(ui.dp(6),0,0,0);
        row.addView(bottomPosition,bottomParams);
        updatePositionChoices();
        return row;
    }
    private LinearLayout colorPalette(){
        String[] names={tr("Белый","White"),tr("Чёрный","Black"),tr("Красный","Red"),tr("Оранжевый","Orange"),tr("Жёлтый","Yellow"),tr("Зелёный","Green"),tr("Циан","Cyan"),tr("Индиго","Indigo"),tr("Фиолетовый","Purple"),tr("Розовый","Pink")};
        int[] colors={0xffffffff,0xff000000,0xffe53935,0xffff8c00,0xffffd600,0xff43a047,0xff00bcd4,0xff3f51b5,0xff8e24aa,0xffe91e63};
        LinearLayout palette=new LinearLayout(this);
        palette.setOrientation(LinearLayout.VERTICAL);
        colorTiles=new TextView[colors.length];
        colorNames=names;
        // Reserve dialog/window insets before budgeting 48dp targets and 8dp gaps.
        int availableDp=Math.round(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density)-96;
        int columns=Math.max(1,Math.min(5,(availableDp+8)/56));
        for(int row=0;row<(colors.length+columns-1)/columns;row++){
            LinearLayout line=new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for(int column=0;column<columns;column++){
                int index=row*columns+column;
                if(index>=colors.length){line.addView(new View(this),ui.weightedGap(1,column==0?0:8,0));continue;}
                TextView tile=ui.label("",1,colors[index]);
                tile.setContentDescription(names[index]);
                ui.touchTarget(tile);
                tile.setOnClickListener(v->{selectedColor=colors[index];updateColorTiles();render();});
                colorTiles[index]=tile;
                LinearLayout.LayoutParams params=ui.weighted(1);
                if(column>0)params.setMargins(ui.dp(8),0,0,0);
                line.addView(tile,params);
            }
            LinearLayout.LayoutParams lineParams=new LinearLayout.LayoutParams(-1,ui.dp(48));lineParams.setMargins(0,0,0,ui.dp(8));palette.addView(line,lineParams);
        }
        updateColorTiles();
        return palette;
    }
    private LinearLayout placementControls(DialRepository.Dial d){
        LinearLayout group=new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        steps=choiceButton(tr("ШАГИ","STEPS"));
        active=choiceButton(tr("АКТИВНОСТЬ","ACTIVITY"));
        heart=choiceButton(tr("ПУЛЬС","HEART RATE"));
        weather=choiceButton(tr("ПОГОДА","WEATHER"));
        Button[] buttons={steps,active,heart,weather};
        String[] keys={"steps","activity","heart","weather"};
        for(int i=0;i<buttons.length;i++){
            final Button button=buttons[i];
            boolean checked=d!=null&&hasPlacement(d.placements,keys[i]);
            updateChoice(button,checked);
            button.setOnClickListener(v->{button.setSelected(!button.isSelected());updateChoice(button,button.isSelected());});
            group.addView(button,ui.fillWrap(0,i==buttons.length-1?0:6));
        }
        return group;
    }
    private boolean hasPlacement(JSONArray placements,String key){
        for(int i=0;i<placements.length();i++)if(key.equals(placements.optString(i)))return true;
        return false;
    }
    private void updatePositionChoices(){
        updateChoice(topPosition,selectedPosition==1);
        updateChoice(bottomPosition,selectedPosition==2);
    }
    private void updateColorTiles(){
        for(int i=0;i<colorTiles.length;i++){
            TextView tile=colorTiles[i];
            int color=tile.getCurrentTextColor();
            boolean selected=color==selectedColor;
            ui.swatch(tile,color,selected);
            tile.setSelected(selected);
            tile.setContentDescription(colorNames[i]+(selected?tr(", выбрано",", selected"):""));
        }
    }
    private void saveDial(AlertDialog dialog,DialRepository.Dial old){
        try{
            if(selected==null)throw new IOException(tr("Сначала выберите изображение","Choose an image first"));
            EditText name=new EditText(this);
            name.setText(old==null?tr("Мой циферблат","My dial"):old.name);
            ui.dialogBuilder().setTitle(tr("Название","Name")).setView(name).setNegativeButton(tr("Отмена","Cancel"),null).setPositiveButton(tr("Сохранить","Save"),(x,w)->{
                try{
                    JSONArray a=new JSONArray();
                    if(steps.isSelected())a.put("steps");
                    if(active.isSelected())a.put("activity");
                    if(heart.isSelected())a.put("heart");
                    if(weather.isSelected())a.put("weather");
                    repo.save(old==null?UUID.randomUUID().toString():old.id,selected,name.getText().toString(),selectedPosition,selectedColor,a);
                    dialog.dismiss();
                    refreshGallery();
                    toast(tr("Сохранено","Saved"));
                }
                catch(Exception e){
                    toast(e.getMessage());
                }
            }
            ).show();
        }
        catch(Exception e){
            toast(e.getMessage());
        }
    }
    private void loadForUpload(DialRepository.Dial d){
        try{
            selected=read(d.image);
            base=BitmapFactory.decodeByteArray(selected,0,selected.length);
            editingId=d.id;
            confirmStage(d.position,d.color,d.name);
        }
        catch(Exception e){
            toast(e.getMessage());
        }
    }
    private void confirmDelete(DialRepository.Dial d){
        ui.dialogBuilder().setTitle(tr("Удалить из телефона?","Delete from phone?")).setMessage(d.name).setNegativeButton(tr("Отмена","Cancel"),null).setPositiveButton(tr("Удалить","Delete"),(x,w)->{
            try{
                repo.delete(d.id);
                refreshGallery();
            }
            catch(Exception e){
                toast(e.getMessage());
            }
        }
        ).show();
    }
    private void debug(LinearLayout p){connectionControls(p);}
    private void connectionControls(LinearLayout p){
        p.addView(ui.section(tr("Подключение","Connection")),ui.fillWrap(12,8));
        LinearLayout c=card();String name=prefs.savedDeviceName().isEmpty()?"TECNO Watch GT":prefs.savedDeviceName();
        c.addView(ui.label(name,16,ui.text));connectionDetail=ui.label(connected?tr("Часы подключены","Watch connected"):tr("Часы не подключены","Watch disconnected"),13,ui.muted);c.addView(connectionDetail,ui.fillWrap(5,6));
        LinearLayout actions=new LinearLayout(this);
        Button scan=secondaryButton(tr("Найти часы","Find watch"));scan.setOnClickListener(v->{connectionUiState="Поиск часов…";updateConnectionState();pendingScan=true;if(ensurePermissions()&&watch!=null){pendingScan=false;watch.startScan();}});actions.addView(scan,ui.weighted(1));
        connect=connected||reconnectWaiting()?secondaryButton(tr("Отключить","Disconnect")):primaryButton(tr("Подключить","Connect"));setEnabledState(connect,connected||reconnectWaiting()?watch!=null:watchFound&&watch!=null);connect.setOnClickListener(v->{if(watch==null)return;if(connected||reconnectWaiting())watch.disconnect();else{connectionPending=true;connectionUiState="Подключение…";updateConnectionState();watch.connectFoundWatch();}});LinearLayout.LayoutParams connectLayout=ui.weighted(1);connectLayout.setMargins(ui.dp(8),0,0,0);actions.addView(connect,connectLayout);c.addView(actions,ui.fillWrap(3,0));
        status=ui.label(connectionUiState,12,ui.muted);status.setMaxLines(3);status.setEllipsize(android.text.TextUtils.TruncateAt.END);if(!connectionUiState.isEmpty())c.addView(status,ui.fillWrap(8,0));p.addView(c,ui.fillWrap(0,10));
        Button export=secondaryButton(tr("Экспортировать диагностику","Export diagnostics"));export.setOnClickListener(v->exportDiagnostics());p.addView(export,ui.fillWrap(0,12));
        Button clearLogs=secondaryButton(tr("Очистить логи","Clear logs"));clearLogs.setOnClickListener(v->confirmClearLogs());p.addView(clearLogs,ui.fillWrap(0,8));
        p.addView(note(tr("Хранятся четыре последних журнала подключения. Данные установки и резервные копии сохраняются отдельно.","The four latest connection logs are retained. Installation evidence and backups are stored separately.")),ui.fillWrap(0,8));
    }
    private boolean diagnosticsBusy;
    private void confirmClearLogs(){
        if(diagnosticsBusy){toast(tr("Дождитесь окончания работы с диагностикой","Wait for the diagnostics operation to finish"));return;}
        ui.dialogBuilder().setTitle(tr("Очистить логи?","Clear logs?"))
            .setMessage(tr("Будут удалены журналы подключения, журнал операций и сохранённые архивы диагностики в приложении. Проекты, показатели, резервные копии и журналы установки останутся. Запись новых логов продолжится.","Connection logs, the operation journal and diagnostic archives stored in the app will be removed. Projects, measurements, backups and installation journals are kept. New logs will continue to be recorded."))
            .setNegativeButton(tr("ОТМЕНА","CANCEL"),null).setPositiveButton(tr("ОЧИСТИТЬ","CLEAR"),(d,w)->clearLogs()).show();
    }
    private void clearLogs(){
        if(diagnosticsBusy)return;diagnosticsBusy=true;DiagnosticLog current=journal;
        worker.execute(()->{try{
            if(current==null)throw new IOException("LOG_UNAVAILABLE");
            long bytes=current.clear()+OperationJournal.get(this).clear();
            bytes+=DiagnosticFiles.clear(new File(getFilesDir(),"reports"),DiagnosticFiles.REPORT);
            current.append(tr("Обычные диагностические логи очищены","Ordinary diagnostic logs cleared"));
            final long removed=bytes;runOnUiThread(()->{diagnosticsBusy=false;if(destroyed)return;if(log!=null)log.setText(current.tail());toast(String.format(java.util.Locale.ROOT,tr("Логи очищены. Освобождено %.1f МБ","Logs cleared. Freed %.1f MB"),removed/1048576.0));});
        }catch(Exception e){runOnUiThread(()->{diagnosticsBusy=false;if(!destroyed)toast(tr("Не все логи удалось очистить. Данные установки сохранены.","Some logs could not be cleared. Installation evidence is kept."));});}});
    }
    private void exportDiagnostics(){
        if(diagnosticsBusy){toast(tr("Дождитесь окончания работы с диагностикой","Wait for the diagnostics operation to finish"));return;}diagnosticsBusy=true;
        toast(tr("Готовлю отчёт…","Preparing report…"));DiagnosticLog current=journal;OperationJournal operations=OperationJournal.get(this);String operation=operations.begin("diagnostics","export","LOCAL",prefs.savedDevice());
        worker.execute(()->{
            try{boolean flushed=current!=null&&current.flush(5000);File file=ReportExporter.diagnostics(this,current,flushed);operations.stage(operation,"diagnostics","export","COMPLETE","SUCCESS","",1,file.length(),0);runOnUiThread(()->{diagnosticsBusy=false;if(!destroyed)share(file,"application/zip");});}
            catch(Exception e){operations.stage(operation,"diagnostics","export","FAILED","FAILED",e.getClass().getSimpleName(),0,0,0);runOnUiThread(()->{diagnosticsBusy=false;if(!destroyed)toast(tr("Не удалось создать отчёт. Попробуйте ещё раз.","Could not create report. Try again."));});}
        });
    }
    private void activity(LinearLayout p){
        activityRead=null;
        if(!activityHistoryMode&&!historyDay.equals(java.time.LocalDate.now().toString())){historyDay=java.time.LocalDate.now().toString();invalidateHistory();}
        loadActivityHistory();
        p.addView(ui.heading(tr("Активность","Activity")),ui.fillWrap(0,12));
        LinearLayout modes=new LinearLayout(this);
        Button todayMode=secondaryButton(tr("Сегодня","Today")),historyMode=secondaryButton(tr("История","History"));
        if(!activityHistoryMode)ui.selectedCard(todayMode);else ui.selectedCard(historyMode);
        todayMode.setOnClickListener(v->{activityHistoryMode=false;activityHistoryDaySelected=false;historyDay=java.time.LocalDate.now().toString();invalidateHistory();rebuildUi();});
        historyMode.setOnClickListener(v->{activityHistoryMode=true;activityHistoryDaySelected=false;invalidateHistory();rebuildUi();});
        modes.addView(todayMode,ui.weighted(1));LinearLayout.LayoutParams modeParams=ui.weighted(1);modeParams.setMargins(ui.dp(8),0,0,0);modes.addView(historyMode,modeParams);p.addView(modes,ui.fillWrap(0,12));
        if(activityHistoryMode&&activityHistoryDaySelected){Button allDays=textButton(tr("Все дни","All days"));allDays.setOnClickListener(v->{activityHistoryDaySelected=false;rebuildUi();});p.addView(allDays,ui.fillWrap(0,8));}
        Button day=secondaryButton(historyDay+tr(" · Выбрать день"," · Choose day"));
        day.setOnClickListener(v->{java.time.LocalDate selectedDay=java.time.LocalDate.parse(historyDay);android.app.DatePickerDialog picker=new android.app.DatePickerDialog(ui.dialogContext(),(dialog,year,month,date)->{activityHistoryDaySelected=true;historyDay=java.time.LocalDate.of(year,month+1,date).toString();invalidateHistory();rebuildUi();},selectedDay.getYear(),selectedDay.getMonthValue()-1,selectedDay.getDayOfMonth());picker.getDatePicker().setMaxDate(System.currentTimeMillis());picker.show();ui.dialog(picker);});
        if(activityHistoryMode)p.addView(day,ui.fillWrap(0,8));else p.addView(note(historyDay),ui.fillWrap(0,8));
        if(activityHistoryMode&&!activityHistoryDaySelected){
            if(!historyError.isEmpty())p.addView(note(historyError),ui.fillWrap(0,8));
            if(activityHistoryDays.isEmpty())p.addView(note(historyLoading?tr("Загружаю историю…","Loading history…"):tr("История пока пуста. Получите показатели с часов на вкладке «Сегодня».","History is empty. Read watch activity in Today.")),ui.fillWrap(8,12));
            for(String date:activityHistoryDays){Button savedDay=secondaryButton(date+tr(" · Открыть показатели"," · Open activity"));savedDay.setOnClickListener(v->{historyDay=date;activityHistoryDaySelected=true;invalidateHistory();rebuildUi();});p.addView(savedDay,ui.fillWrap(0,8));}
            return;
        }
        boolean today=historyDay.equals(java.time.LocalDate.now().toString());
        if(historyEntry!=null){
            historyActivityCards(p,historyEntry);
        }else if(today&&latestActivitySnapshot!=null){
            activitySuccess(p,latestActivitySnapshot);
        }else if(today&&activityReadFailed){
            LinearLayout failure=card();
            failure.addView(ui.label(tr("Ошибка чтения. Переподключите часы.","Read failed. Reconnect the watch."),18,ui.accent));
            failure.addView(note(tr("Запрос уже завершён. Для новой попытки переподключите часы.","The request has finished. Reconnect the watch before another attempt.")),ui.fillWrap(8,0));
            p.addView(failure,ui.fillWrap(6,8));
        }else{
            LinearLayout empty=card();
            activityText=ui.label(historyLoading?tr("Загружаю историю…","Loading history…"):tr("За этот день пока нет записей","No observations for this day yet"),20,ui.text);
            empty.addView(activityText);
            empty.addView(note(tr("История начнёт сохраняться после обновления приложения. Пропущенные дни не заполняются нулями.","History starts accumulating after this update. Missing days are not filled with zeros.")),ui.fillWrap(8,0));
            p.addView(empty,ui.fillWrap(6,8));
        }
        if(activityReadFailed&&(historyEntry!=null||latestActivitySnapshot!=null))p.addView(note(tr("Последнее чтение не удалось. Показаны ранее сохранённые данные.","Last read failed. Previously saved data is shown.")),ui.fillWrap(0,8));
        if(!historyError.isEmpty())p.addView(note(historyError),ui.fillWrap(0,8));
        activityRead=primaryButton(tr("ПРОЧИТАТЬ С ЧАСОВ","READ FROM WATCH"));
        setEnabledState(activityRead,!activityReadStarted);
        activityRead.setOnClickListener(v->{
            activityReadStarted=true;
            setEnabledState(activityRead,false);
            watch.runActivitySnapshot();
        }
        );
        if(today)p.addView(activityRead,ui.fillWrap(0,20));
        p.addView(section(tr("ПУЛЬС · СОН · ТРЕНИРОВКИ","HEART · SLEEP · WORKOUTS")));
        LinearLayout heartCard=card();heartCard.addView(ui.mono(tr("ПОСЛЕДНИЙ СОХРАНЁННЫЙ ПУЛЬС","LAST SAVED HEART RATE")));
        if(latestHeartResult!=null&&!"FAILED".equals(latestHeartResult.state)&&!latestHeartResult.records.isEmpty()){
            HealthHistoryRecord record=latestHeartResult.records.get(0);
            heartCard.addView(ui.label(record.heartRateAvailable()?String.valueOf(record.heartRateRaw):tr("Нет пригодного измерения","No usable measurement"),24,ui.text));
            heartCard.addView(note(tr("Запись часов: ","Watch record: ")+java.time.Instant.ofEpochSecond(record.ts).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm:ss"))));
        }else heartCard.addView(note(latestHeartResult==null?tr("Ещё не прочитано","Not read yet"):latestHeartResult.detail));
        Button readHeart=secondaryButton(tr("ПРОЧИТАТЬ ПОСЛЕДНЮЮ ЗАПИСЬ","READ LAST RECORD"));setEnabledState(readHeart,ready);readHeart.setOnClickListener(v->ui.dialogBuilder().setTitle(tr("Чтение пульса","Heart rate read")).setMessage(tr("Один экспериментальный запрос последней сохранённой записи за сегодня. Не запускает измерение. После результата часы отключатся; повторов нет. Продолжайте после отдельного согласования опыта.","One experimental query for today's last saved record. Does not start measurement. Disconnects after the result, no retries. Continue after separate approval.")).setNegativeButton(tr("ОТМЕНА","CANCEL"),null).setPositiveButton(tr("ПРОЧИТАТЬ ОДИН РАЗ","READ ONCE"),(d,w)->watch.runLatestHeartRate()).show());heartCard.addView(readHeart);
        if(latestHeartResult!=null){heartCard.addView(note(tr("Получено телефоном: ","Received on phone: ")+new SimpleDateFormat("dd.MM HH:mm:ss",Locale.getDefault()).format(new Date(latestHeartResult.receivedAt))+" · "+latestHeartResult.state));if(latestHeartResult.evidenceFile!=null){Button report=textButton(tr("Поделиться ответом пульса","Share heart response"));report.setOnClickListener(v->share(latestHeartResult.evidenceFile,"application/json"));heartCard.addView(report);}}
        p.addView(heartCard,ui.fillWrap(6,6));
        LinearLayout sleepCard=card();sleepCard.addView(ui.mono(tr("СОН","SLEEP")));sleepCard.addView(note(sleepHistoryResult==null?tr("Данные можно прочитать одним ручным запросом ниже.","Read one page with the manual action below."):sleepHistoryResult.evidenceFile!=null?tr("Ответ часов сохранён. График появится после проверки формата стадий сна.","Watch response saved. Graphs require the sleep-stage format to be verified."):tr("Ответ не сохранён: отправьте общий журнал ошибки.","Response not saved: share the general error log.")));p.addView(sleepCard,ui.fillWrap(0,6));
        Button sleepPage=secondaryButton(tr("ПРОБНОЕ ЧТЕНИЕ ИСТОРИИ СНА","EXPERIMENTAL SLEEP HISTORY READ"));setEnabledState(sleepPage,ready);
        sleepPage.setOnClickListener(v->{long to=System.currentTimeMillis()/1000L,from=to-TsdbReadProtocol.MAX_RANGE_SECONDS;
            ui.dialogBuilder().setTitle(tr("История сна за 7 суток","Seven-day sleep history"))
                    .setMessage(tr("Будет прочитана история сна за фиксированные последние 7 суток: до 8 порций по 5 записей. Каждая порция сохраняется и обрабатывается до следующего запроса. При ошибке чтение останавливается без повтора; уже сохранённые записи остаются. Порядок записей не предполагается. Продолжайте только после отдельного согласования этого эксперимента.","Reads a fixed seven-day sleep range, up to eight pages of five records. Each page is saved and imported before the next request. An error stops without retry; earlier saved records remain. Record order is not assumed. Continue only after separate approval of this experiment."))
                    .setNegativeButton(tr("ОТМЕНА","CANCEL"),null).setPositiveButton(tr("ПРОЧИТАТЬ ОДИН РАЗ","READ ONCE"),(d,w)->watch.runSleepHistoryPage(from,to)).show();});
        p.addView(sleepPage,ui.fillWrap(0,8));
        if(sleepHistoryResult!=null){
            p.addView(note(sleepHistoryResult.detail),ui.fillWrap(0,4));
            p.addView(note(tr("Записей в последней порции: ","Records in the last page: ")+sleepHistoryResult.records.size()+" · "+("FAILED".equals(sleepHistoryResult.state)?tr("чтение остановлено","read stopped"):"PARTIAL".equals(sleepHistoryResult.state)?tr("достигнут предел; история частичная","limit reached; partial history"):tr("порция получена","page received"))),ui.fillWrap(0,4));
            p.addView(note(tr("Ответ часов получен. Для графика требуется разбор сохранённого JSON: время и стадии пока не расшифрованы.","Watch response received. Graphs need the saved JSON decoded; times and stages are not interpreted yet.")),ui.fillWrap(0,4));
            if(sleepHistoryResult.evidenceFile!=null){Button shareSleep=textButton(tr("Поделиться данными чтения сна","Share sleep read data"));shareSleep.setOnClickListener(v->share(sleepHistoryResult.evidenceFile,"application/json"));p.addView(shareSleep,ui.fillWrap(0,8));}
        }
        p.addView(unavailableActivityCard(tr("ТРЕНИРОВКИ","WORKOUTS")),ui.fillWrap(0,0));
    }
    private void invalidateHistory(){activityHistoryDays=Collections.emptyList();historyGeneration++;historyLoading=false;historyLoadedKey="";historyEntry=null;historyObservations=Collections.emptyList();historyError="";}
    private void loadActivityHistory(){
        if(activityHistory==null||worker==null||worker.isShutdown()||historyDeviceAddress.isEmpty())return;
        String key=historyDeviceAddress+"/"+historyDay;
        if(key.equals(historyLoadedKey)||historyLoading)return;
        historyLoading=true;int request=historyGeneration;String address=historyDeviceAddress,date=historyDay;ActivityHistoryStore store=activityHistory;
        worker.execute(()->{ActivityHistoryEntry entry=null;List<ActivityHistoryEntry> observations=Collections.emptyList();List<String> dates=Collections.emptyList();String error="";
            try{entry=store.latestByDay(address,date);observations=store.observationsForDay(address,date);dates=store.observedDays(address);}catch(RuntimeException e){error=tr("Не удалось открыть сохранённую историю","Could not open saved history");}
            ActivityHistoryEntry result=entry;List<ActivityHistoryEntry> points=observations;List<String> days=dates;String failure=error;
            runOnUiThread(()->{if(destroyed||request!=historyGeneration)return;historyLoading=false;historyLoadedKey=key;historyEntry=result;historyObservations=points;activityHistoryDays=days;historyError=failure;if(activeTab==2)rebuildUi();});
        });
    }
    private void historyActivityCards(LinearLayout p,ActivityHistoryEntry entry){
        LinearLayout metrics=new LinearLayout(this);metrics.setOrientation(LinearLayout.HORIZONTAL);
        Button steps=secondaryButton(tr("ШАГИ\n","STEPS\n")+entry.steps);steps.setOnClickListener(v->showActivityHistoryMetric("steps"));metrics.addView(steps,ui.weighted(1));
        Button calorie=secondaryButton(tr("КАЛОРИИ\n","CALORIE\n")+entry.calorieRaw);calorie.setOnClickListener(v->showActivityHistoryMetric("calorie"));LinearLayout.LayoutParams cp=ui.weighted(1);cp.setMargins(ui.dp(8),0,0,0);metrics.addView(calorie,cp);p.addView(metrics,ui.fillWrap(0,8));
        Button distance=secondaryButton(tr("РАССТОЯНИЕ\n","DISTANCE\n")+entry.distanceRaw);distance.setOnClickListener(v->showActivityHistoryMetric("distance"));p.addView(distance,ui.fillWrap(0,8));
        String receipt=java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.of(entry.phoneZoneId)).format(java.time.Instant.ofEpochMilli(entry.receivedAt));
        p.addView(note(tr("Последнее наблюдение: ","Last observation: ")+receipt+" · "+entry.phoneZoneId),ui.fillWrap(0,4));
        p.addView(note(tr("День и время телефона. Это наблюдения, а не полный итог суток. Единицы калорий и расстояния ещё не подтверждены.","Phone day and time. These are observations, not complete day totals. Calorie and distance units are not confirmed yet.")),ui.fillWrap(0,14));
    }
    private void showActivityHistoryMetric(String metric){
        if(historyObservations.isEmpty())return;
        List<ActivityHistoryEntry> records=new ArrayList<>(historyObservations);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(8));content.setBackgroundColor(ui.panel);
        TextView selected=new TextView(this);selected.setTextColor(ui.text);
        ActivityHistoryChartView chart=new ActivityHistoryChartView(this,records,metric,ui,entry->{String time=java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.of(entry.phoneZoneId)).format(java.time.Instant.ofEpochMilli(entry.receivedAt));selected.setText(time+" · "+ActivityHistoryChartView.value(entry,metric));});
        content.addView(chart,new LinearLayout.LayoutParams(-1,ui.dp(230)));content.addView(selected);content.addView(note(tr("Нажмите на точку для времени и значения. Разрывы чтения и уменьшение счётчика не соединяются линией.","Tap a point for time and value. Reading gaps and counter drops are not connected.")),ui.fillWrap(8,0));
        ui.dialogBuilder().setTitle(("steps".equals(metric)?tr("Шаги","Steps"):"calorie".equals(metric)?tr("Калории · исходные значения","Calories · raw values"):tr("Расстояние · исходные значения","Distance · raw values"))+" · "+historyDay).setView(content).setPositiveButton(tr("ЗАКРЫТЬ","CLOSE"),null).show();
    }
    private void activitySuccess(LinearLayout page,ActivitySnapshot snapshot){
        LinearLayout stepsCard=card();
        stepsCard.addView(ui.mono(tr("ПОДТВЕРЖДЁННОЕ ПОЛЕ","CONFIRMED FIELD")),ui.fillWrap(0,4));
        stepsCard.addView(ui.label(tr("ШАГИ","STEPS"),13,ui.muted));
        stepsCard.addView(ui.label(String.valueOf(snapshot.steps),32,ui.text));
        String receipt=new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date(snapshot.receivedAt));
        stepsCard.addView(note(tr("Получено: ","Received: ")+receipt),ui.fillWrap(6,0));
        page.addView(stepsCard,ui.fillWrap(6,8));
        LinearLayout rawRow=new LinearLayout(this);
        rawRow.setOrientation(LinearLayout.HORIZONTAL);
        rawRow.addView(rawMetricCard(tr("КАЛОРИИ","CALORIE"),snapshot.calorie),ui.weighted(1));
        LinearLayout.LayoutParams distanceParams=ui.weighted(1);
        distanceParams.setMargins(ui.dp(8),0,0,0);
        rawRow.addView(rawMetricCard(tr("ДИСТАНЦИЯ","DISTANCE"),snapshot.distance),distanceParams);
        page.addView(rawRow,ui.fillWrap(0,8));
    }
    private LinearLayout rawMetricCard(String title,int value){
        LinearLayout card=card();
        card.addView(ui.mono(title),ui.fillWrap(0,4));
        card.addView(ui.label(String.valueOf(value),24,ui.text));
        card.addView(note(tr("единица не подтверждена","unit not confirmed")),ui.fillWrap(6,0));
        return card;
    }
    private LinearLayout unavailableActivityCard(String title){
        LinearLayout card=card();
        card.addView(ui.mono(title),ui.fillWrap(0,3));
        TextView dash=ui.label("—",24,ui.muted);
        card.addView(dash);
        card.addView(note(tr("Данные не запрашиваются: поле пока не подтверждено.","Not requested: this field is not confirmed yet.")),ui.fillWrap(4,0));
        return card;
    }
    private void settings(LinearLayout p){
        if(expansion.buildSettings(p,ui))return;
        p.addView(ui.heading(tr("Настройки","Settings")),ui.fillWrap(0,12));
        LinearLayout profileSettings=ui.settingDisclosure(p,tr("Профиль и цели","Profile and goals"),tr("Личные параметры и карточки","Personal details and activity cards"),UiIcons.Kind.USER,"profile",settingsSections);expansion.settingsLinks(profileSettings,ui,"profile");
        LinearLayout watchSettings=ui.settingDisclosure(p,tr("Часы и уведомления","Watch and notifications"),tr("Подключение, будильники и погода","Connection, alarms and weather"),UiIcons.Kind.WATCH,"watch",settingsSections);
        expansion.settingsLinks(watchSettings,ui,"watch");connectionControls(watchSettings);
        watchSettings.addView(section(tr("УВЕДОМЛЕНИЯ","NOTIFICATIONS")));
        LinearLayout notificationCard=card();
        boolean access=NotificationManagerCompat.getEnabledListenerPackages(this).contains(getPackageName());
        android.widget.Switch forwarding=new android.widget.Switch(this);
        forwarding.setText(tr("Пересылать уведомления на часы","Forward notifications to watch"));
        forwarding.setTextColor(ui.text);ui.toggle(forwarding);
        forwarding.setChecked(access&&prefs.notificationForwardingEnabled());
        forwarding.setOnCheckedChangeListener((button,checked)->{
            if(checked&&!NotificationManagerCompat.getEnabledListenerPackages(this).contains(getPackageName())){
                button.setChecked(false);
                awaitingNotificationPermission=true;
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                return;
            }
            prefs.notificationForwardingEnabled(checked);
            if(checked&&prefs.notificationPackages().isEmpty())chooseNotificationApps();
            else rebuildUi();
        });
        notificationCard.addView(forwarding);
        int count=prefs.notificationPackages().size();
        notificationCard.addView(note(!access?tr("Шаг 1: если Android пишет «Настройки с ограниченным доступом», откройте сведения о «Часовой» в настройках приложений, нажмите ⋮ «Разрешить настройки с ограниченным доступом». Затем вернитесь сюда и разрешите доступ к уведомлениям.","Step 1: if Android shows Restricted settings, open Chasovoy app info, tap ⋮ Allow restricted settings. Then return here and grant notification access."):
                !prefs.notificationForwardingEnabled()?tr("Шаг 2: включите пересылку, затем выберите приложения.","Step 2: enable forwarding, then choose apps."):
                count==0?tr("Шаг 3: выберите приложения. Пока список пуст, пересылка не работает.","Step 3: choose apps. Forwarding is inactive while the list is empty."):
                tr("Включено. Выбрано приложений: ","Enabled. Selected apps: ")+count),ui.fillWrap(6,4));
        android.content.SharedPreferences notificationState=getSharedPreferences("gt_dial_preferences",MODE_PRIVATE);
        long capturedAt=notificationState.getLong("notification.last-captured-at",0L);
        long acknowledgedAt=notificationState.getLong("notification.last-ack-at",0L);
        String statusLine=capturedAt==0?tr("Полученных уведомлений пока нет.","No selected notification captured yet."):
                tr("Последнее получено: ","Last captured: ")+new SimpleDateFormat("dd.MM HH:mm:ss",Locale.getDefault()).format(new Date(capturedAt))+
                " · "+notificationState.getString("notification.last-captured-package","");
        notificationCard.addView(note(statusLine),ui.fillWrap(4,0));
        if(acknowledgedAt>0)notificationCard.addView(note(tr("Последняя команда подтверждена часами: ","Last watch command acknowledged: ")+new SimpleDateFormat("dd.MM HH:mm:ss",Locale.getDefault()).format(new Date(acknowledgedAt))),ui.fillWrap(4,0));
        Button setup=secondaryButton(!access?tr("РАЗРЕШИТЬ ДОСТУП В ANDROID","GRANT ACCESS IN ANDROID"):tr("ВЫБРАТЬ ПРИЛОЖЕНИЯ","CHOOSE APPS"));
        setup.setOnClickListener(v->{if(!NotificationManagerCompat.getEnabledListenerPackages(this).contains(getPackageName())){awaitingNotificationPermission=true;startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));}else chooseNotificationApps();});
        notificationCard.addView(setup,ui.fillWrap(8,0));
        watchSettings.addView(notificationCard,ui.fillWrap(6,12));
        LinearLayout assistantSettings=ui.settingDisclosure(p,tr("Личный помощник","Personal assistant"),tr("OpenRouter, питание и разбор показателей","OpenRouter, nutrition and analysis"),UiIcons.Kind.SPARKLES,"assistant",settingsSections);expansion.settingsLinks(assistantSettings,ui,"assistant");
        LinearLayout appearanceSettings=ui.settingDisclosure(p,tr("Оформление","Appearance"),tr("Тема и язык интерфейса","Theme and language"),UiIcons.Kind.PALETTE,"appearance",settingsSections);
        appearanceSettings.addView(section(tr("ОФОРМЛЕНИЕ","APPEARANCE")));
        LinearLayout tiles=new LinearLayout(this);
        tiles.setOrientation(LinearLayout.VERTICAL);
        addThemeTile(tiles,"default",tr("ГРАФИТ","GRAPHITE"));
        addThemeTile(tiles,"midnight-blue",tr("ПОЛНОЧЬ","MIDNIGHT"));
        addThemeTile(tiles,"arctic",tr("АРКТИКА","ARCTIC"));
        addThemeTile(tiles,"olive",tr("ОЛИВА","OLIVE"));
        appearanceSettings.addView(tiles);
        appearanceSettings.addView(section(tr("ЯЗЫК","LANGUAGE")),ui.fillWrap(20,4));
        LinearLayout languages=new LinearLayout(this);
        languages.setOrientation(LinearLayout.HORIZONTAL);
        languages.addView(languageTile("ru","РУССКИЙ"),ui.weighted(1));
        LinearLayout.LayoutParams english=ui.weighted(1);
        english.setMargins(ui.dp(8),0,0,0);
        languages.addView(languageTile("en","ENGLISH"),english);
        appearanceSettings.addView(languages);
        LinearLayout dataSettings=ui.settingDisclosure(p,tr("Данные и приватность","Data and privacy"),tr("Экспорт и управление данными","Export and data controls"),UiIcons.Kind.SHIELD,"data",settingsSections);expansion.settingsLinks(dataSettings,ui,"data");
        p.addView(section(tr("ПРИЛОЖЕНИЕ","APPLICATION")),ui.fillWrap(20,4));
        p.addView(note(tr("Версия ","Version ")+BuildConfig.VERSION_NAME));
    }
    private AlertDialog weatherSettingsDialog;
    private AlertDialog weatherDiagnosticsDialog;
    private LinearLayout weatherDiagnosticsContent;
    private ScrollView weatherDiagnosticsScroll;
    private LinearLayout weatherSettingsContent;
    private ScrollView weatherSettingsScroll;
    private void showWeatherSettings(){
        if(weatherSettingsDialog!=null&&weatherSettingsDialog.isShowing())return;
        weatherSettingsContent=new LinearLayout(ui.dialogContext());weatherSettingsContent.setOrientation(LinearLayout.VERTICAL);
        weatherSettingsContent.setPadding(ui.dp(12),ui.dp(8),ui.dp(12),ui.dp(8));
        weatherSettingsScroll=new ScrollView(ui.dialogContext());weatherSettingsScroll.addView(weatherSettingsContent);
        weatherSettingsDialog=ui.dialogBuilder().setTitle(tr("Погода","Weather"))
            .setView(weatherSettingsScroll).setNegativeButton(tr("Закрыть","Close"),null).create();
        weatherSettingsDialog.setOnDismissListener(d->{weatherSettingsDialog=null;weatherSettingsContent=null;weatherSettingsScroll=null;});
        refreshWeatherSettings();weatherSettingsDialog.show();
    }
    private void refreshWeatherSettings(){
        if(weatherSettingsContent==null)return;
        int scrollY=weatherSettingsScroll==null?0:weatherSettingsScroll.getScrollY();
        weatherSettingsContent.removeAllViews();buildWeatherSettings(weatherSettingsContent);
        if(weatherSettingsScroll!=null)weatherSettingsScroll.post(()->{if(weatherSettingsScroll!=null)weatherSettingsScroll.scrollTo(0,scrollY);});
    }
    private void buildWeatherSettings(LinearLayout p){
        p.addView(section(tr("ПОГОДА","WEATHER")));
        LinearLayout weatherCard=card();
        android.content.SharedPreferences weatherPrefs=getSharedPreferences("gt_dial_preferences",MODE_PRIVATE);
        String city=weatherPrefs.getString("weather.city","");
        long fetchedAt=weatherPrefs.getLong("weather.fetched-at",0L);
        String weatherStatus=city.isEmpty()?tr("Выберите город для получения погоды.","Choose a city to fetch weather."):
                city+" · "+(fetchedAt==0?tr("пока нет данных","no data yet"):
                weatherPrefs.getInt("weather.temperature-c",0)+tr(" °C · обновлено "," °C · refreshed ")+new SimpleDateFormat("dd.MM HH:mm",Locale.getDefault()).format(new Date(fetchedAt)));
        weatherCard.addView(ui.label(weatherStatus,14,ui.text));
        if(!weatherActionFeedback.isEmpty())weatherCard.addView(ui.label(weatherActionFeedback,14,ui.text),ui.fillWrap(6,6));
        WeatherDeliveryController.Delivery phoneSnapshot=WeatherDeliveryController.cached(this);
        if(phoneSnapshot!=null){
            WeatherPayload phonePayload=new WeatherPayload(phoneSnapshot.snapshot);int phoneDays=phonePayload.forecastDays();
            weatherCard.addView(note(phonePayload.readyForWeekly()?tr("На телефоне готов прогноз: сегодня и 6 будущих дней.","Phone forecast ready: today and 6 future days."):tr("Недельный прогноз пока не готов; получено дней: ","Weekly forecast is not ready; received days: ")+phoneDays+"/6"+tr(". Обновите погоду на телефоне.",". Refresh phone weather.")),ui.fillWrap(6,6));
            java.time.format.DateTimeFormatter forecastDate=java.time.format.DateTimeFormatter.ofPattern("EEE dd.MM",Locale.getDefault());
            for(WeatherSnapshot.ForecastDay day:phoneSnapshot.snapshot.forecast){
                weatherCard.addView(ui.label(day.date.format(forecastDate)+"   "+Math.round(day.lowC)+"…"+Math.round(day.highC)+" °C",14,ui.text),ui.fillWrap(3,3));
            }
            if(phoneSnapshot.snapshot.source.startsWith("MET Norway")){
                weatherCard.addView(note(tr("MET Norway · модельный прогноз. Диапазоны взяты из пересекающих день прогнозных интервалов и могут включать соседние часы. Значок соответствует прогнозу около полудня.","MET Norway · model forecast. Ranges come from forecast intervals overlapping the day and may include adjacent hours. The symbol represents the forecast around noon.")),ui.fillWrap(6,6));
                Button attribution=textButton("MET Norway · CC BY 4.0");attribution.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://docs.api.met.no/doc/License"))));weatherCard.addView(attribution);
                weatherCard.addView(note(tr("Модель выпущена: ","Model issued: ")+new SimpleDateFormat("dd.MM HH:mm",Locale.getDefault()).format(new Date(phoneSnapshot.snapshot.modelIssuedAtUnix*1000L))+tr(". Мокрый снег передаётся значком осадков.",". Sleet uses the precipitation symbol.")),ui.fillWrap(6,6));
            }
        }
        long watchVerifiedAt=weatherPrefs.getLong("weather.watch-verified-at",0L);
        String weatherSource=weatherPrefs.getString("weather.source","");
        String providerLabel=weatherSource.startsWith("MET Norway")?"MET Norway":weatherSource.startsWith("Yandex Weather")?tr("Яндекс Погода","Yandex Weather"):weatherSource.startsWith("Open-Meteo")?"Open-Meteo":weatherSource.startsWith("wttr.in")?"wttr.in":tr("пока нет данных","no data yet");
        weatherCard.addView(note(watchVerifiedAt==0||!java.util.Objects.equals(prefs.savedDevice(),weatherPrefs.getString("weather.watch-verified-device",""))?tr("Источник: ","Source: ")+providerLabel+tr(". Подтверждённой записи для этих часов пока нет.",". No verified write for this watch yet."):
                tr("Обновлено на часах: ","Updated on watch: ")+new SimpleDateFormat("dd.MM HH:mm",Locale.getDefault()).format(new Date(watchVerifiedAt))+tr(". Проверьте изображение на часах.",". Check the watch display.")),ui.fillWrap(6,6));
        Button cityButton=secondaryButton(tr("ВЫБРАТЬ ГОРОД","CHOOSE CITY"));
        cityButton.setOnClickListener(v->chooseWeatherCity());
        weatherCard.addView(cityButton,ui.fillWrap(0,6));
        Button locateButton=secondaryButton(tr("ОПРЕДЕЛИТЬ ГОРОД","DETECT CITY"));
        setEnabledState(locateButton,cityLocator==null);
        locateButton.setOnClickListener(v->detectWeatherCity());
        weatherCard.addView(locateButton,ui.fillWrap(0,6));
        Button fetchButton=secondaryButton(tr("ОБНОВИТЬ ПОГОДУ НА ТЕЛЕФОНЕ","REFRESH PHONE WEATHER"));
        setEnabledState(fetchButton,!city.isEmpty()&&!weatherFetchRunning);
        fetchButton.setOnClickListener(v->fetchPhoneWeather());
        weatherCard.addView(fetchButton,ui.fillWrap(0,6));
        WeatherDeliveryController.WeeklyStatus weekly=WeatherDeliveryController.weeklyStatus(this);
        android.widget.Switch automatic=new android.widget.Switch(ui.dialogContext());automatic.setText(tr("Погода на часах","Weather on watch"));automatic.setTextColor(ui.text);automatic.setMinHeight(ui.dp(48));ui.toggle(automatic);
        automatic.setChecked(WeatherDeliveryController.automaticWeeklyEnabled(this));automatic.setEnabled(sessionBinder!=null&&!"IN_FLIGHT".equals(weekly.status));
        automatic.setOnCheckedChangeListener((button,enabled)->{if(sessionBinder==null)return;automatic.setEnabled(false);sessionBinder.setAutomaticWeeklyEnabled(enabled,ok->{if(destroyed)return;toast(ok?(enabled?tr("Автообновление включено","Automatic updates enabled"):tr("Автообновление выключено","Automatic updates disabled")):tr("Режим не изменён. Проверьте состояние в диагностике погоды.","Mode unchanged. Review weather diagnostics."));if(activeTab==3)rebuildUi();});});weatherCard.addView(automatic,ui.fillWrap(4,6));
        String mode=!WeatherDeliveryController.automaticWeeklyEnabled(this)?tr("Автообновление выключено.","Automatic updates disabled."):
            "PAUSED".equals(weekly.status)?weeklyPauseReason(weekly.reason):"IN_FLIGHT".equals(weekly.status)?tr("Обновляю прогноз и проверяю запись…","Updating forecast and verifying storage…"):
            !ready?tr("Ожидаю готовности часов.","Waiting for the watch to be ready."):phoneSnapshot==null||!new WeatherPayload(phoneSnapshot.snapshot).readyForWeekly()?tr("Ожидаю полный прогноз на неделю.","Waiting for a full weekly forecast."):tr("Автообновление: сегодня и шесть будущих дней.","Automatic updates: today and six future days.");
        weatherCard.addView(note(mode),ui.fillWrap(6,6));
        p.addView(weatherCard,ui.fillWrap(6,12));
        Button diagnostics=secondaryButton(tr("ДИАГНОСТИКА ПОГОДЫ","WEATHER DIAGNOSTICS"));
        diagnostics.setOnClickListener(v->showWeatherDiagnostics());p.addView(diagnostics,ui.fillWrap(6,6));
    }
    private void showWeatherDiagnostics(){
        if(weatherDiagnosticsDialog!=null&&weatherDiagnosticsDialog.isShowing())return;
        weatherDiagnosticsContent=new LinearLayout(ui.dialogContext());weatherDiagnosticsContent.setOrientation(LinearLayout.VERTICAL);
        weatherDiagnosticsScroll=new ScrollView(ui.dialogContext());weatherDiagnosticsScroll.addView(weatherDiagnosticsContent);
        weatherDiagnosticsDialog=ui.dialogBuilder().setTitle(tr("Диагностика погоды","Weather diagnostics")).setView(weatherDiagnosticsScroll).setNegativeButton(tr("Закрыть","Close"),null).create();
        weatherDiagnosticsDialog.setOnDismissListener(d->{weatherDiagnosticsDialog=null;weatherDiagnosticsContent=null;weatherDiagnosticsScroll=null;weatherRead=null;});
        refreshWeatherDiagnostics();weatherDiagnosticsDialog.show();
    }
    private void refreshWeatherDiagnostics(){
        if(weatherDiagnosticsContent==null||destroyed)return;
        int scrollY=weatherDiagnosticsScroll.getScrollY();
        weatherDiagnosticsContent.removeAllViews();buildWeatherDiagnostics(weatherDiagnosticsContent);
        weatherDiagnosticsScroll.post(()->{if(weatherDiagnosticsScroll!=null)weatherDiagnosticsScroll.scrollTo(0,scrollY);});
    }
    private void buildWeatherDiagnostics(LinearLayout content){
        LinearLayout weatherCard=card();android.content.SharedPreferences weatherPrefs=getSharedPreferences("gt_dial_preferences",MODE_PRIVATE);
        String city=weatherPrefs.getString("weather.city","");long fetchedAt=weatherPrefs.getLong("weather.fetched-at",0L);
        weatherRead=secondaryButton(tr("ПРОЧИТАТЬ ПОГОДУ ЧАСОВ","READ WATCH WEATHER"));
        setEnabledState(weatherRead,ready);
        weatherRead.setOnClickListener(v->watch.readWeatherState());
        weatherCard.addView(weatherRead,ui.fillWrap(0,6));
        weatherCard.addView(note(watchWeatherSummary()),ui.fillWrap(6,6));
        if(!ready)weatherCard.addView(note(tr("Чтение доступно после готовности соединения. Если обмен остановлен, отключите часы и подключите заново.","Reading is available when the connection is ready. If exchange is stopped, disconnect the watch and connect again.")),ui.fillWrap(6,6));
        if(!weatherActionFeedback.isEmpty())weatherCard.addView(note(weatherActionFeedback),ui.fillWrap(6,6));
        Button sendButton=primaryButton(tr("ОТПРАВИТЬ ПОГОДУ НА ЧАСЫ","SEND WEATHER TO WATCH"));
        WeatherDeliveryController.Hold weatherHold=WeatherDeliveryController.hold(this);
        WeatherDeliveryController.WeeklyStatus weekly=WeatherDeliveryController.weeklyStatus(this);
        String weeklyLabel=!weekly.enabled?tr("Недельное автообновление выключено.","Weekly automatic updates are disabled."):
                "PAUSED".equals(weekly.status)?tr("Недельное автообновление остановлено. После проверки причины его нужно включить вручную.","Weekly automatic updates are paused. Review the cause before enabling them again."):
                "IN_FLIGHT".equals(weekly.status)?tr("Передаю недельный прогноз и проверяю запись…","Transferring the weekly forecast and verifying storage…"):
                tr("Недельное автообновление включено. Передаются только полные 6 будущих дней.","Weekly automatic updates are enabled. Only all 6 future days are transferred.");
        weatherCard.addView(note(weeklyLabel),ui.fillWrap(6,6));
        if(weekly.enabled&&"PAUSED".equals(weekly.status))weatherCard.addView(note(weeklyPauseReason(weekly.reason)),ui.fillWrap(6,6));
        if(weekly.enabled&&weatherHold!=null){
            Button weeklyDisable=textButton(tr("Выключить автообновление и принять погоду часов","Disable automatic updates and accept watch weather"));
            setEnabledState(weeklyDisable,sessionBinder!=null&&!"IN_FLIGHT".equals(weekly.status));
            weeklyDisable.setOnClickListener(v->ui.dialogBuilder().setTitle(tr("Выключить недельное обновление?","Disable weekly updates?"))
                    .setMessage(tr("Вы принимаете текущую погоду часов. Автообновление будет выключено; сохранённые попытки передачи останутся в приложении.","You accept the current watch weather. Automatic updates will be disabled; consumed transfer attempts remain in the app."))
                    .setNegativeButton(tr("ОТМЕНА","CANCEL"),null).setPositiveButton(tr("ВЫКЛЮЧИТЬ","DISABLE"),(d,w)->{if(sessionBinder!=null)sessionBinder.resumeWeatherAfterFullExperiment(weatherHold,ok->{onState(ok?tr("Недельное обновление выключено","Weekly updates disabled"):tr("Не удалось изменить режим: дождитесь завершения операции","Could not change mode: wait for the operation to finish"));if(activeTab==3)rebuildUi();});}).show());
            weatherCard.addView(weeklyDisable,ui.fillWrap(0,6));
        }
        if(!weekly.enabled||"PAUSED".equals(weekly.status)){
            Button weeklyEnable=primaryButton(weekly.enabled?tr("ВОЗОБНОВИТЬ НЕДЕЛЬНОЕ ОБНОВЛЕНИЕ","RESUME WEEKLY UPDATES"):tr("ВКЛЮЧИТЬ НЕДЕЛЬНОЕ АВТООБНОВЛЕНИЕ","ENABLE WEEKLY AUTOMATIC UPDATES"));
            setEnabledState(weeklyEnable,ready&&sessionBinder!=null);
            weeklyEnable.setOnClickListener(v->ui.dialogBuilder().setTitle(tr("Обновлять неделю автоматически?","Update the week automatically?"))
                    .setMessage(tr("Приложение будет отправлять текущую погоду и 6 будущих дней на эти часы. Текущие данные часов станут исходной точкой; последующие подтверждённые обновления будут заменять резервную копию предыдущей погоды. Если данные изменит MyHealth или произойдёт ошибка, обновление остановится. При возобновлении вы принимаете текущее состояние часов заново.","The app will send current weather and 6 future days to this watch. Current watch data becomes the baseline; subsequent verified updates replace the backup of previous weather. Updates stop if MyHealth changes the data or an error occurs. Resuming accepts the current watch state again."))
                    .setNegativeButton(tr("ОТМЕНА","CANCEL"),null).setPositiveButton(tr("ВКЛЮЧИТЬ","ENABLE"),(d,w)->{if(sessionBinder!=null)sessionBinder.enableWeeklyWeather(weatherHold,ok->{onState(ok?tr("Недельное автообновление включено; ожидаю полный прогноз","Weekly updates enabled; waiting for the full forecast"):tr("Не удалось включить: дождитесь свободного соединения и повторите","Could not enable: wait for an idle connection and try again"));if(activeTab==3)rebuildUi();});}).show());
            weatherCard.addView(weeklyEnable,ui.fillWrap(0,6));
        }else{
            Button weeklyPause=secondaryButton(tr("ПРИОСТАНОВИТЬ НЕДЕЛЬНОЕ ОБНОВЛЕНИЕ","PAUSE WEEKLY UPDATES"));
            setEnabledState(weeklyPause,sessionBinder!=null&&!"IN_FLIGHT".equals(weekly.status));
            weeklyPause.setOnClickListener(v->{if(sessionBinder!=null)sessionBinder.pauseWeeklyWeather(ok->{onState(ok?tr("Недельное автообновление на паузе","Weekly updates paused"):tr("Дождитесь завершения текущей операции","Wait for the current operation to finish"));if(activeTab==3)rebuildUi();});});
            weatherCard.addView(weeklyPause,ui.fillWrap(0,6));
        }
        setEnabledState(sendButton,ready&&weatherHold==null&&WeatherDeliveryController.cached(this)!=null);
        if(fetchedAt>0&&weatherPrefs.getLong("weather.provider-time",0)==0)weatherCard.addView(note(tr("Время наблюдения источник не указал. Показано время обновления на телефоне.","The source omitted the observation timestamp. The phone refresh time is shown.")));
        sendButton.setOnClickListener(v->confirmWeatherSend());
        weatherCard.addView(sendButton,ui.fillWrap(0,6));
        if(weatherHold!=null&&!weekly.enabled){
            weatherCard.addView(note(tr("Автоматическая передача погоды приостановлена для проверки прогноза. Данные на телефоне продолжают обновляться. Проверьте страницы на часах, затем примите результат или восстановите сохранённую погоду.","Automatic weather transfer is paused for the forecast check. Phone data continues to refresh. Check the watch pages, then accept the result or restore saved weather.")),ui.fillWrap(6,6));
            Button resumeWeather=secondaryButton(tr("ПРИНЯТЬ И ВЫКЛЮЧИТЬ АВТОПОГОДУ","ACCEPT AND DISABLE AUTOMATIC WEATHER"));
            setEnabledState(resumeWeather,sessionBinder!=null);
            resumeWeather.setOnClickListener(v->ui.dialogBuilder().setTitle(tr("Принять результат проверки?","Accept the result?"))
                    .setMessage(tr("Автообновление будет выключено. Это действие не снимает остановку команд после ошибки; сохранённые попытки не повторяются.","Automatic updates will be disabled. This does not clear the command stop after an error; consumed attempts are never repeated."))
                    .setNegativeButton(tr("ОТМЕНА","CANCEL"),null)
                    .setPositiveButton(tr("ПРИНЯТЬ","ACCEPT"),(d,w)->{if(sessionBinder!=null)sessionBinder.resumeWeatherAfterFullExperiment(weatherHold,ok->{onState(ok?tr("Автоматическая передача погоды выключена","Automatic weather transfer disabled"):tr("Не удалось снять паузу: завершите текущую операцию и повторите","Could not resume: finish the current operation and try again"));if(activeTab==3)rebuildUi();});}).show());
            weatherCard.addView(resumeWeather,ui.fillWrap(0,6));
        }
        Button restoreButton=textButton(tr("Восстановить сохранённую погоду часов","Restore saved watch weather"));
        setEnabledState(restoreButton,ready);
        restoreButton.setOnClickListener(v->ui.dialogBuilder()
                .setTitle(tr("Восстановить погоду?","Restore weather?"))
                .setMessage(tr("Будет восстановлено только ранее сохранённое значение этих же часов, если текущее значение совпадает с последней записью приложения.","Only this watch's saved value will be restored, if its current value matches the app's last write."))
                .setNegativeButton(tr("ОТМЕНА","CANCEL"),null)
                .setPositiveButton(tr("ВОССТАНОВИТЬ","RESTORE"),(d,w)->watch.restoreWeather()).show());
        weatherCard.addView(restoreButton);
        Button export=secondaryButton(tr("ЭКСПОРТИРОВАТЬ ДИАГНОСТИКУ","EXPORT DIAGNOSTICS"));
        export.setOnClickListener(v->exportDiagnostics());weatherCard.addView(export,ui.fillWrap(0,6));
        content.addView(weatherCard);
    }
    private void chooseWeatherCity(){
        if(cityLocator!=null){cityLocator.cancel();cityLocator=null;}
        EditText input=new EditText(this);
        input.setSingleLine(true);
        input.setHint(tr("Город, область или страна","City, region or country"));
        input.setTextColor(ui.text);
        input.setHintTextColor(ui.muted);ui.input(input);
        input.setPadding(ui.dp(18),ui.dp(12),ui.dp(18),ui.dp(12));
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        content.addView(input);
        TextView feedback=new TextView(this);feedback.setTextColor(ui.muted);feedback.setPadding(ui.dp(18),ui.dp(8),ui.dp(18),ui.dp(8));
        content.addView(feedback);
        LinearLayout results=new LinearLayout(this);results.setOrientation(LinearLayout.VERTICAL);content.addView(results);
        AlertDialog dialog=ui.dialogBuilder().setTitle(tr("Выберите город","Choose city")).setView(content)
                .setNegativeButton(tr("ОТМЕНА","CANCEL"),null).create();
        Handler searchHandler=new Handler(Looper.getMainLooper());
        final Runnable[] pending={null};final int[] generation={0};
        input.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                generation[0]++;if(pending[0]!=null)searchHandler.removeCallbacks(pending[0]);results.removeAllViews();
                String query=s.toString().trim();
                if(query.length()<2){feedback.setText(tr("Введите минимум 2 буквы","Enter at least 2 letters"));return;}
                feedback.setText(tr("Ищу…","Searching…"));int request=generation[0];
                pending[0]=()->new Thread(()->{try{
                    List<OpenMeteoCityResolver.City> cities=new OpenMeteoCityResolver().search(query);
                    runOnUiThread(()->{if(!dialog.isShowing()||request!=generation[0])return;
                        feedback.setText(cities.isEmpty()?tr("Город не найден","No city found"):tr("Выберите результат","Select a result"));
                        results.removeAllViews();for(OpenMeteoCityResolver.City city:cities){
                            Button option=secondaryButton(city.label);
                            option.setOnClickListener(v->{getSharedPreferences("gt_dial_preferences",MODE_PRIVATE).edit()
                                    .putString("weather.city",city.label).putString("weather.city-name",city.label.split(",",2)[0])
                                    .putLong("weather.latitude-bits",Double.doubleToRawLongBits(city.latitude))
                                    .putLong("weather.longitude-bits",Double.doubleToRawLongBits(city.longitude))
                                    .remove("weather.fetched-at").remove("weather.watch-verified-at").apply();
                                dialog.dismiss();if(activeTab==3)rebuildUi();fetchPhoneWeather();});results.addView(option);
                        }
                    });
                }catch(IOException e){runOnUiThread(()->{if(dialog.isShowing()&&request==generation[0])feedback.setText(tr("Поиск города: ","City search: ")+e.getMessage());});}},"weather-city-search").start();
                searchHandler.postDelayed(pending[0],350);
            }
            public void afterTextChanged(android.text.Editable e){}
        });
        dialog.setOnDismissListener(d->{generation[0]++;if(pending[0]!=null)searchHandler.removeCallbacks(pending[0]);});
        dialog.show();
    }
    private void detectWeatherCity(){
        if(cityLocator!=null)return;
        if(checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION")!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION","android.permission.ACCESS_COARSE_LOCATION"},WEATHER_LOCATION);return;
        }
        onState(tr("Определяю город…","Detecting city…"));
        cityLocator=new PhoneCityLocator(this,(name,lat,lon,error)->{
            cityLocator=null;
            if(destroyed)return;
            if(error!=null){recordEvent("WEATHER_LOCATION failed reason="+error);onState("disabled".equals(error)?tr("Включите геолокацию телефона или выберите город вручную.","Enable phone location or choose a city manually."):tr("Не удалось определить город. Попробуйте ещё раз или выберите вручную.","Could not detect city. Try again or choose manually."));if(activeTab==3)rebuildUi();return;}
            recordEvent("WEATHER_LOCATION success");String city=name==null||name.isEmpty()?tr("Текущее местоположение","Current location"):name;
            getSharedPreferences("gt_dial_preferences",MODE_PRIVATE).edit().putString("weather.city",city).putString("weather.city-name",city)
                    .putLong("weather.latitude-bits",Double.doubleToRawLongBits(lat)).putLong("weather.longitude-bits",Double.doubleToRawLongBits(lon))
                    .remove("weather.fetched-at").remove("weather.watch-verified-at").apply();
            if(activeTab==3)rebuildUi();fetchPhoneWeather();
        });
        if(activeTab==3)rebuildUi();cityLocator.start();
    }
    private String weatherFailure(Exception e){
        if(e instanceof java.net.SocketTimeoutException||e instanceof java.net.ConnectException||e instanceof java.net.UnknownHostException)
            return tr("Сервис погоды недоступен. Проверьте интернет; попробуйте другую сеть и обновите снова. Сохранённые данные не обновлены.","Weather service unavailable. Check internet, try another network and refresh again. Saved data was not updated.");
        return tr("Не удалось получить погоду. Попробуйте позже. Сохранённые данные не обновлены.","Could not fetch weather. Try later. Saved data was not updated.");
    }
    private void fetchPhoneWeather(){
        if(sessionBinder==null){onState(tr("Дождитесь запуска сервиса часов","Wait for the watch service"));return;}
        sessionBinder.fetchPhoneWeather();
    }
    @Override public void onPhoneWeatherChanged(boolean busy,String message){
        runOnUiThread(()->{if(destroyed)return;weatherFetchRunning=busy;if(message!=null&&!message.isEmpty()){weatherActionFeedback=tx(message);onState(message);}if(activeTab==3)rebuildUi();else refreshWeatherSettings();});
    }
    private WeatherSnapshot savedPhoneWeather(android.content.SharedPreferences p){
        WeatherDeliveryController.Delivery cached=WeatherDeliveryController.cached(this);
        if(cached!=null)return cached.snapshot;
        double latitude=Double.longBitsToDouble(p.getLong("weather.latitude-bits",Double.doubleToRawLongBits(Double.NaN)));
        double longitude=Double.longBitsToDouble(p.getLong("weather.longitude-bits",Double.doubleToRawLongBits(Double.NaN)));
        double minimum=Double.longBitsToDouble(p.getLong("weather.min-bits",Double.doubleToRawLongBits(Double.NaN)));
        double maximum=Double.longBitsToDouble(p.getLong("weather.max-bits",Double.doubleToRawLongBits(Double.NaN)));
        return new WeatherSnapshot(latitude,longitude,Double.longBitsToDouble(p.getLong("weather.temperature-bits",Double.doubleToRawLongBits(Double.NaN))),p.getInt("weather.wmo-code",-1),p.getLong("weather.fetched-at",0L),p.getLong("weather.provider-time",0L),minimum,maximum,p.getString("weather.source","Open-Meteo; city="+p.getString("weather.city-name","")));
    }
    private void confirmWeatherSend(){
        android.content.SharedPreferences p=getSharedPreferences("gt_dial_preferences",MODE_PRIVATE);
        String city=p.getString("weather.city-name","");long fetched=p.getLong("weather.fetched-at",0L);
        if(!ready||fetched==0||city.isEmpty()){onState(tr("Подключите часы и обновите погоду","Connect watch and refresh weather"));return;}
        WeatherSnapshot snapshot=savedPhoneWeather(p);
        if(!new WeatherPayload(snapshot).readyForBle()){onState(tr("Обновите погоду перед отправкой: данные устарели или не поддерживаются","Refresh weather before sending: stale or unsupported data"));return;}
        String sourceNote=snapshot.providerTimeUnix==0?tr("\nВремя наблюдения источник не указал; данные обновлены на телефоне ","\nThe source omitted the observation time; refreshed on phone ")+new SimpleDateFormat("dd.MM HH:mm",Locale.getDefault()).format(new Date(snapshot.fetchedAtMs)):"";
        WeatherPayload payload=new WeatherPayload(snapshot);
        LinearLayout options=new LinearLayout(this);options.setOrientation(LinearLayout.VERTICAL);options.setPadding(ui.dp(20),ui.dp(8),ui.dp(20),ui.dp(8));
        CheckBox forecast=new CheckBox(this);forecast.setTextColor(ui.text);forecast.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.accent));forecast.setMinHeight(ui.dp(48));forecast.setChecked(false);
        forecast.setText(payload.readyForWeekly()?tr("Также передать прогноз на ","Also send forecast for ")+payload.forecastDays()+tr(" дн."," days"):tr("Прогноз по дням пока недоступен","Daily forecast is not available yet"));forecast.setEnabled(payload.readyForWeekly());options.addView(forecast);
        options.addView(note(tr("При выборе прогноза автоматическая передача погоды приостановится до восстановления или принятия результата. После передачи проверьте страницы погоды на часах.","Selecting forecast pauses automatic weather transfer until you restore or accept the result. Check the watch weather pages afterward.")),ui.fillWrap(4,0));
        ui.dialogBuilder().setTitle(tr("Проверка передачи погоды","Weather transfer check"))
                .setMessage(tr("Приложение прочитает и сохранит прежние настройки. Если погода не настроена, сначала включит её и проверит состояние. Затем запишет свежие данные и проверит все поля чтением. Если часы не подтвердят состояние, операция остановится. Источник: ","The app reads and backs up the prior settings. If weather is not initialized, it enables weather and verifies that state first. It then stores fresh weather and reads back every field. Any unexpected state stops the operation. Source: ")+city+" · WMO "+snapshot.wmoCode+sourceNote)
                .setView(options).setNegativeButton(tr("ОТМЕНА","CANCEL"),null)
                .setPositiveButton(tr("ПРОДОЛЖИТЬ","CONTINUE"),(d,w)->watch.sendWeather(snapshot,city,forecast.isChecked())).show();
    }
    private void addThemeTile(LinearLayout parent,String key,String title){
        boolean chosen=key.equals(prefs.theme());
        LinearLayout tile=card();
        if(chosen)ui.selectedCard(tile);
        TextView name=ui.label(title,14,chosen?ui.accentInk:ui.text);
        name.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
        tile.addView(name);
        tile.setOnClickListener(v->{
            if(!key.equals(prefs.theme())){
                prefs.theme(key);
                rebuildUi();
            }
        }
        );
        parent.addView(tile,ui.fillWrap(0,7));
    }
    private View languageTile(String key,String title){
        boolean chosen=key.equals(prefs.language());
        LinearLayout tile=card();
        if(chosen)ui.selectedCard(tile);
        TextView name=ui.label(title,13,chosen?ui.accentInk:ui.text);
        name.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
        tile.addView(name);
        tile.setOnClickListener(v->{
            if(!key.equals(prefs.language())){
                prefs.language(key);
                applyLocale(key);
                rebuildUi();
            }
        }
        );
        return tile;
    }
    private void selectImage(){
        imagePickerGeneration=generation;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,IMAGE);
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==PROJECT_DOCUMENT){if(result==RESULT_OK&&data!=null&&data.getData()!=null)importProjectArchive(data.getData());return;}
        if(req==DialEditorController.IMAGE_REQUEST){if(result==RESULT_OK)dialEditor.imageResult(data);return;}
        if(req>=ExpansionUiController.NUTRITION_EXPORT&&req<=ExpansionUiController.ANALYSIS_EXPORT){expansion.documentResult(req,result,data);return;}
        if((req==ExpansionUiController.HEALTH_DOCUMENT||req==ExpansionUiController.ALARM_DOCUMENT)&&result==RESULT_OK){expansion.documentResult(req,data);return;}
        if(req!=IMAGE||result!=RESULT_OK||data==null||data.getData()==null||photoDialog==null||!photoDialog.isShowing()||imagePickerGeneration!=generation)return;
        Uri uri=data.getData();int g=++generation;AlertDialog expectedEditor=photoDialog;
        if(imageInfo!=null)imageInfo.setText(tr("Подготовка…","Preparing…"));
        worker.execute(()->{
            Bitmap raw=null,fit=null;
            try{
                raw=decodeLegacyPhoto(read(uri));
                fit=Bitmap.createBitmap(466,466,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(fit);c.drawColor(Color.BLACK);
                float scale=Math.max(466f/raw.getWidth(),466f/raw.getHeight()),w=raw.getWidth()*scale,h=raw.getHeight()*scale;
                c.drawBitmap(raw,null,new RectF((466-w)/2,(466-h)/2,(466+w)/2,(466+h)/2),new Paint(3));
                byte[] png=compress(fit);Bitmap prepared=fit;
                runOnUiThread(()->{
                    if(destroyed||g!=generation||photoDialog!=expectedEditor||!expectedEditor.isShowing()){prepared.recycle();return;}
                    selected=png;if(base!=null&&!base.isRecycled())base.recycle();base=prepared;
                    if(imageInfo!=null)imageInfo.setText(tr("PNG готов: ","PNG ready: ")+png.length+" B");render();
                });fit=null;
            }catch(Exception|OutOfMemoryError e){runOnUiThread(()->{if(destroyed||g!=generation||photoDialog!=expectedEditor)return;
                String error=e instanceof OutOfMemoryError?tr("Недостаточно памяти; выберите меньшую фотографию","Not enough memory; choose a smaller photo"):e.getMessage();if(imageInfo!=null)imageInfo.setText(error);toast(error);});}
            finally{if(raw!=null&&!raw.isRecycled())raw.recycle();if(fit!=null&&!fit.isRecycled())fit.recycle();}
        });
    }
    private Bitmap decodeLegacyPhoto(byte[] bytes)throws IOException{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        DialImageNormalizer.checkDimensions(bounds.outWidth,bounds.outHeight);
        BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;options.inSampleSize=1;
        while((long)(bounds.outWidth/options.inSampleSize)*(bounds.outHeight/options.inSampleSize)>2_000_000L)options.inSampleSize*=2;
        Bitmap decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(decoded==null)throw new IOException(tr("Не удалось прочитать изображение","Could not decode image"));return decoded;
    }
    private byte[] compress(Bitmap b)throws IOException{
        int[] px=new int[466*466];
        b.getPixels(px,0,466,0,0,466,466);
        for(int bits=8;
        bits>=3;
        bits--){
            int mask=(255<<(8-bits))&255;
            if(bits<8)for(int i=0;
            i<px.length;
            i++){
                int p=px[i];
                px[i]=(p&0xff000000)|(((p>>>16)&mask)<<16)|(((p>>>8)&mask)<<8)|(p&mask);
            }
            b.setPixels(px,0,466,0,0,466,466);
            ByteArrayOutputStream o=new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG,100,o);
            if(o.size()<=168722)return o.toByteArray();
        }
        throw new IOException(tr("PNG не удалось сжать до лимита часов","PNG exceeds watch limit"));
    }
    private void render(){
        if(preview==null||base==null)return;
        Bitmap b=base.copy(Bitmap.Config.ARGB_8888,true);
        Canvas c=new Canvas(b);
        Paint p=new Paint(1);
        p.setColor(selectedColor);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(72);
        float y=selectedPosition==2?365:105;
        c.drawText(new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()),233,y,p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(26);
        c.drawText(new SimpleDateFormat("d MMMM",Locale.getDefault()).format(new Date()),233,y+38,p);
        preview.setImageBitmap(b);
    }
    private void confirmStage(int pos,int col,String name){
        if(selected==null||!watch.isAlbumStageAvailable()){
            toast(tr("Подключите часы и дождитесь готовности","Connect watch and wait until ready"));
            return;
        }
        ui.dialogBuilder().setTitle(tr("Загрузить на часы?","Upload to watch?")).setMessage(tr("Фотоциферблат будет передан на подключённые часы.","The photo watch face will be uploaded to the connected watch.")).setNegativeButton(tr("Отмена","Cancel"),null).setPositiveButton(tr("Загрузить","Upload"),(d,w)->watch.stageAlbumImage(selected.clone(),"gtdl_"+System.currentTimeMillis()+".png",pos,col)).show();
    }
    private void confirmProbe(){
        ui.dialogBuilder().setTitle(tr("Создать резервную копию?","Create backup?")).setMessage(tr("Только чтение /user, до 128 МиБ.","Read-only /user, up to 128 MiB.")).setNegativeButton(tr("Отмена","Cancel"),null).setPositiveButton(tr("Запустить","Run"),(d,w)->watch.runReadOnlyProtocolProbe()).show();
    }
    private void confirmDynamicRecovery(){
        if(!ready){onState(tr("Сначала подключите часы","Connect the watch first"));return;}
        ui.dialogBuilder().setTitle(tr("Восстановить квитанцию Dial Lab","Recover Dial Lab receipt"))
                .setMessage(tr("Только чтение: приложение сверит текущий animation-циферблат, инвентарь и файл /download/1909202601.dial, сохранит и перечитает локальную резервную копию, затем повторно сверит часы и сохранит квитанцию. Запись, install и apply не выполняются.","Read only: the app verifies the current animation dial, inventory and /download/1909202601.dial, saves and rereads a local backup, then rechecks the watch and saves a receipt. It does not write, install, or apply."))
                .setNegativeButton(tr("ОТМЕНА","CANCEL"),null).setPositiveButton(tr("ПРОВЕРИТЬ","VERIFY"),(d,w)->{setEnabledState(dynamicRecovery,false);watch.recoverCapturedDynamicDialReceipt();}).show();
    }
    private void confirmStagingAcquisition(){
        if(!ready){onState(tr("Сначала подключите часы","Connect the watch first"));return;}
        ui.dialogBuilder().setTitle(tr("Считать staging-файл","Read staging file"))
                .setMessage(tr("Однократно прочитать файл /download/1909202601.dial размером ровно 414720 байт, сохранить копию и сверить текущий циферблат. Файл на часах и квитанция не меняются; после операции соединение закроется.","Read the exact 414720-byte staging file once, save a copy and recheck the current dial. The watch file and receipt stay unchanged; the connection closes afterward."))
                .setNegativeButton(tr("ОТМЕНА","CANCEL"),null).setPositiveButton(tr("СЧИТАТЬ","READ"),(d,w)->{setEnabledState(stagingAcquisition,false);watch.acquireCapturedStaging();}).show();
    }
    private void confirmFragmentRecovery(){
        if(!ready){onState(tr("Сначала подключите часы","Connect the watch first"));return;}
        ui.dialogBuilder().setTitle(tr("Восстановить phosphor","Restore phosphor"))
                .setMessage(tr("Сначала выберите встроенный Bolt01 на самих часах. Приложение сохранит повреждённый staging-файл, заново запишет проверенный phosphor (547548 байт), вычитает его полностью и установит. При ошибке остановится без повтора. Старая повреждённая копия не пригодна для отката.","First select built-in Bolt01 on the watch. The app backs up the captured fragment, writes and fully verifies phosphor (547548 bytes), then installs it. Any failure stops without retry. The malformed fragment cannot be used as a rollback dial."))
                .setNegativeButton(tr("ОТМЕНА","CANCEL"),null).setPositiveButton(tr("ВОССТАНОВИТЬ","RESTORE"),(d,w)->{setEnabledState(fragmentRecovery,false);watch.recoverCapturedFragment();}).show();
    }
    private void share(File f,String type){
        if(f==null||!f.isFile()){
            toast(tr("Файл ещё не готов","File is not ready"));
            return;
        }
        try{
            Uri u=FileProvider.getUriForFile(this,getPackageName()+".files",f);
            Intent i=new Intent(Intent.ACTION_SEND);
            i.setType(type);
            i.putExtra(Intent.EXTRA_STREAM,u);
            i.setClipData(ClipData.newRawUri("GT Dial Lab",u));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i,tr("Отправить","Share")));
        }
        catch(Exception e){
            toast(e.getMessage());
        }
    }
    private boolean ensurePermissions(){
        if(hasRequiredPermissions()){if(watch!=null)return true;connectSessionService();return false;}
        requestPermissions(Build.VERSION.SDK_INT>=31?new String[]{
            "android.permission.BLUETOOTH_SCAN","android.permission.BLUETOOTH_CONNECT"
        }
        :new String[]{
            "android.permission.ACCESS_FINE_LOCATION"
        }
        ,PERMISSION);
        return false;
    }
    @Override public void onState(String s){
        latestStatus=s;runOnUiThread(()->{
            if(s.startsWith("Ищу"))connectionUiState="Поиск часов…";
            else if(s.startsWith("Подключаюсь")||s.contains("ищу сервисы")||s.contains("Повторное подключение")||s.contains("Восстанавливаю подключение")){connectionPending=true;connectionUiState="Подключение…";}
            else if(s.equals("Часы найдены"))connectionUiState="Часы найдены. Нажмите «Подключить».";
            else if(s.startsWith("Ошибка подключения")||s.startsWith("Не удалось прочитать сервисы")||s.startsWith("Ошибка подписки")||s.startsWith("Не удалось создать подключение")||s.startsWith("Запуск поиска сервисов")||s.startsWith("Это не GT01")||s.startsWith("Сервис GT01 неполный")){connectionPending=false;connectionUiState="Не удалось подключиться. Повторите подключение или отправьте диагностику.";}
            else if(s.startsWith("Ошибка сканирования")||s.equals("BLE-сканер недоступен"))connectionUiState="Не удалось найти часы. Повторите поиск.";
            else if(s.contains("Bluetooth выключен"))connectionUiState="Включите Bluetooth на телефоне.";
            else if(s.contains("разрешение Bluetooth")||s.contains("Разрешение Bluetooth"))connectionUiState="Разрешите приложению доступ к Bluetooth.";
            else if(s.equals("Отключено")||s.equals("Часы отключились")){connectionUiState=connectionPending?"Не удалось подключиться. Повторите подключение или отправьте диагностику.":"Часы не подключены";connectionPending=false;}
            if(activityReadStarted&&(s.equals("Другая операция уже выполняется")||s.startsWith("Не удалось безопасно начать чтение активности")||s.startsWith("Подключите часы заново"))){activityReadStarted=false;expansion.readFeedback(tr("Дождитесь готовности часов и повторите обновление.","Wait for watch readiness and refresh again."),false);}
            updateConnectionState();
            String lower=s.toLowerCase(java.util.Locale.ROOT);
            boolean actionable=s.contains("Не удалось")||s.contains("не удалось")||s.contains("Ошибка")||s.contains("не прошёл")||s.contains("недоступ")||s.contains("уже выполняется")||s.contains("не начата")||s.contains("не был отправлен")||lower.contains("failed")||lower.contains("error")||lower.contains("unavailable")||lower.contains("already in progress")||lower.contains("not started")||lower.contains("not sent")||lower.contains("connect");
            if(weatherSettingsDialog!=null&&weatherSettingsDialog.isShowing()&&(actionable||s.contains("город")||s.contains("погод")||s.contains("city")||s.contains("weather"))){weatherActionFeedback=s;refreshWeatherSettings();}
            if(weatherDiagnosticsDialog!=null&&weatherDiagnosticsDialog.isShowing()&&(actionable||lower.contains("погод")||lower.contains("weather"))){weatherActionFeedback=s;refreshWeatherDiagnostics();}
            if(actionable){visibleActionFeedback=s;if(actionFeedback!=null){actionFeedback.setText(UiText.choose("en".equals(prefs.language()),s));actionFeedback.setVisibility(View.VISIBLE);}}
        });
    }
    private void chooseNotificationApps(){
        PackageManager pm=getPackageManager();List<ResolveInfo> apps=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(ResolveInfo item:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0))if(item.activityInfo!=null&&!getPackageName().equals(item.activityInfo.packageName)&&seen.add(item.activityInfo.packageName))apps.add(item);
        if(apps.isEmpty()){toast(tr("Приложения не найдены","No apps found"));return;}
        android.app.AppOpsManager ops=(android.app.AppOpsManager)getSystemService(APP_OPS_SERVICE);
        boolean allowed=ops!=null&&ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),getPackageName())==android.app.AppOpsManager.MODE_ALLOWED;
        Map<String,Long> usage=new HashMap<>();
        if(allowed)try{android.app.usage.UsageStatsManager m=(android.app.usage.UsageStatsManager)getSystemService(USAGE_STATS_SERVICE);long end=System.currentTimeMillis();List<android.app.usage.UsageStats> stats=m==null?null:m.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY,end-7L*86400000,end);if(stats!=null)for(android.app.usage.UsageStats stat:stats)usage.merge(stat.getPackageName(),stat.getTotalTimeInForeground(),Long::sum);}catch(SecurityException ignored){allowed=false;}
        apps.sort((l,r)->{int n=Long.compare(usage.getOrDefault(r.activityInfo.packageName,0L),usage.getOrDefault(l.activityInfo.packageName,0L));return n!=0?n:String.CASE_INSENSITIVE_ORDER.compare(l.loadLabel(pm).toString(),r.loadLabel(pm).toString());});
        Set<String> chosen=prefs.notificationPackages();LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(ui.dp(12),ui.dp(6),ui.dp(12),0);content.setBackgroundColor(ui.panel);
        content.addView(ui.label(allowed?tr("Часто используемые за 7 дней — в начале","Most used over 7 days first"):tr("По алфавиту. Для частоты нужен доступ Android.","Alphabetical. Usage sorting needs Android access."),12,ui.muted));
        ListView list=new ListView(this);list.setBackgroundColor(ui.panel);list.setDivider(new android.graphics.drawable.ColorDrawable(ui.border));list.setDividerHeight(ui.dp(1));BaseAdapter adapter=new BaseAdapter(){
            public int getCount(){return apps.size();}public Object getItem(int i){return apps.get(i);}public long getItemId(int i){return i;}
            public View getView(int i,View old,ViewGroup parent){ResolveInfo app=apps.get(i);LinearLayout row=new LinearLayout(MainActivity.this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(ui.dp(6),ui.dp(8),ui.dp(6),ui.dp(8));row.setMinimumHeight(ui.dp(52));ImageView icon=new ImageView(MainActivity.this);icon.setImageDrawable(app.loadIcon(pm));row.addView(icon,new LinearLayout.LayoutParams(ui.dp(32),ui.dp(32)));TextView title=ui.label(app.loadLabel(pm).toString(),15,ui.text);LinearLayout.LayoutParams name=new LinearLayout.LayoutParams(0,-2,1);name.setMargins(ui.dp(12),0,ui.dp(6),0);row.addView(title,name);CheckBox check=new CheckBox(MainActivity.this);check.setButtonTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ui.accent,ui.muted}));check.setChecked(chosen.contains(app.activityInfo.packageName));check.setClickable(false);check.setFocusable(false);row.addView(check);return row;}
        };list.setAdapter(adapter);list.setOnItemClickListener((parent,v,i,id)->{String pkg=apps.get(i).activityInfo.packageName;if(!chosen.remove(pkg))chosen.add(pkg);adapter.notifyDataSetChanged();});
        LinearLayout bulk=new LinearLayout(this);Button all=textButton(tr("ВЫБРАТЬ ВСЁ","SELECT ALL")),none=textButton(tr("СНЯТЬ ВСЁ","CLEAR ALL"));bulk.addView(all,ui.weighted(1));bulk.addView(none,ui.weighted(1));content.addView(bulk);all.setOnClickListener(v->{for(ResolveInfo app:apps)chosen.add(app.activityInfo.packageName);adapter.notifyDataSetChanged();});none.setOnClickListener(v->{chosen.clear();adapter.notifyDataSetChanged();});
        if(!allowed){Button access=textButton(tr("СОРТИРОВКА ПО ИСПОЛЬЗОВАНИЮ","SORT BY USAGE"));content.addView(access);access.setOnClickListener(v->ui.dialogBuilder().setMessage(tr("Разрешите Часовому статистику использования в Android, затем откройте список заново. Данные остаются на телефоне.","Allow Chasovoy usage access in Android, then reopen this list. Data stays on this phone.")).setNegativeButton(tr("ОТМЕНА","CANCEL"),null).setPositiveButton(tr("НАСТРОЙКИ ANDROID","ANDROID SETTINGS"),(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))).show());}
        content.addView(list,new LinearLayout.LayoutParams(-1,Math.min(ui.dp(360),getResources().getDisplayMetrics().heightPixels/3)));
        TextView title=ui.heading(tr("Приложения для часов","Apps for the watch"));title.setPadding(ui.dp(20),ui.dp(18),ui.dp(20),ui.dp(10));title.setBackgroundColor(ui.panel);
        AlertDialog dialog=ui.dialogBuilder().setCustomTitle(title).setView(content).setNegativeButton(tr("ОТМЕНА","CANCEL"),null).setPositiveButton(tr("СОХРАНИТЬ","SAVE"),(d,w)->{prefs.notificationPackages(chosen);recordEvent("NOTIFICATION_SELECTION saved count="+chosen.size());rebuildUi();}).create();
        dialog.setOnShowListener(d->{if(dialog.getWindow()!=null)dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ui.panel));for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE}){Button button=dialog.getButton(which);button.setTextColor(ui.accentInk);button.setBackgroundColor(ui.panel);}});dialog.show();
    }
    private boolean reconnectWaiting(){return !connected&&"Ожидаю часы; переподключение автоматически".equals(latestConnectionMessage);}
    private void updateConnectionState(){
        boolean stopped=connected&&"Часы подключены; команды остановлены".equals(latestConnectionMessage);
        if(connectionState!=null){connectionState.setText(stopped?tr("Обмен остановлен","Exchange stopped"):reconnectWaiting()?tr("Переподключение","Reconnecting"):connected?tr("Подключено","Connected"):tr("Не подключено","Disconnected"));connectionState.setTextColor(stopped?ui.accent:connected?ui.text:ui.muted);}
        if(connectionDetail!=null)connectionDetail.setText(reconnectWaiting()?tr("Ожидаю часы. Подключение восстановится автоматически, когда они будут рядом.","Waiting for the watch. Connection will resume automatically when it is nearby."):connected?(latestConnectionMessage==null||latestConnectionMessage.isEmpty()?tr("Часы подключены","Watch connected"):tx(latestConnectionMessage)):connectionUiState.isEmpty()?tr("Часы не подключены","Watch disconnected"):UiText.choose("en".equals(prefs.language()),connectionUiState));
        if(connect!=null){connect.setText(connected||reconnectWaiting()?tr("Отключить","Disconnect"):tr("Подключить","Connect"));setEnabledState(connect,connected||reconnectWaiting()?watch!=null:watchFound&&watch!=null&&!connectionPending);}
    }
    private boolean directInstallEligible(){
        return connected&&watch!=null&&!directInstallUiPending
            &&!(latestDialProgress!=null&&latestDialProgress.direct&&!latestDialProgress.terminal());
    }
    private void bindDirectInstallReason(LinearLayout parent,Button button){
        TextView reason=note("");ui.touchTarget(reason);reason.setOnClickListener(v->{if(connected&&latestDialProgress!=null&&!latestDialProgress.terminal())showDialInstallProgress();else{activeTab=3;rebuildUi();}});parent.addView(reason,ui.fillWrap(4,6));directInstallReasons.put(button,reason);updateDirectInstallButtons();
    }
    private void updateDirectInstallButtons(){
        boolean eligible=directInstallEligible();if(dynamicInstall!=null)setEnabledState(dynamicInstall,eligible);
        for(Button button:localDirectInstallButtons)setEnabledState(button,eligible);
        for(Map.Entry<Button,TextView> entry:directInstallReasons.entrySet()){
            entry.getValue().setText(!connected||watch==null?tr("Подключите часы в Настройках","Connect watch in Settings"):tr("Установка уже выполняется. Показать ход","Installation in progress. Show progress"));
            entry.getValue().setVisibility(entry.getKey().getVisibility()==View.VISIBLE&&!eligible?View.VISIBLE:View.GONE);
        }
    }
    @Override public void onConnectionSnapshot(ConnectionSnapshot snapshot){
        runOnUiThread(()->{if(snapshot!=null){connected=snapshot.isConnected();latestConnectionMessage=snapshot.connectionMessage();if(connected||reconnectWaiting()){connectionPending=false;connectionUiState=reconnectWaiting()?tr("Ожидаю часы; переподключение автоматически","Waiting for watch; automatic reconnect"):snapshot.connectionMessage();}updateConnectionState();updateDirectInstallButtons();}});
    }
    @Override public void onLog(String s){
        if(log==null||journalRefreshPending)return;
        journalRefreshPending=true;journalUiHandler.postDelayed(()->{journalRefreshPending=false;if(log!=null)log.setText(journal.tail());},250);
    }
    private void recordEvent(String event){if(journal!=null)journal.append(event);onLog(event);}
    private void logging(LinearLayout p){connectionControls(p);}
    private File latestHealthEvidence(String prefix){
        File[] files=getFilesDir().listFiles((directory,name)->name.startsWith(prefix)&&name.matches("(?:sleep-history|heart-last)-[0-9a-fA-F-]{36}[.]json"));
        File newest=null;if(files!=null)for(File file:files)if(file.isFile()&&file.length()>0&&file.length()<=128*1024&&(newest==null||file.lastModified()>newest.lastModified()))newest=file;
        return newest;
    }
    @Override public void onWatchFound(String n,String a){
        prefs.savedDevice(a);prefs.savedDeviceName(n==null?"GT01":n);
        if(!Objects.equals(historyDeviceAddress,a)){historyDeviceAddress=a==null?"":a;invalidateHistory();latestActivitySnapshot=null;sleepHistoryResult=null;latestHeartResult=null;}
        watchFound=true;
        latestDevice=n==null?"TECNO Watch GT":n;
        runOnUiThread(()->{
            if(device!=null)device.setText(latestDevice);
            if(connect!=null)setEnabledState(connect,true);
        }
        );
    }
    @Override public void onProbeReady(boolean r){
        runOnUiThread(()->{
            if(destroyed)return;ready=r&&watch!=null;
            boolean effective=ready;
            updateConnectionState();
            if(probe!=null)setEnabledState(probe,effective);
            if(album!=null)setEnabledState(album,effective&&watch.isAlbumConfigurationAvailable());
            if(weatherRead!=null)setEnabledState(weatherRead,effective);
            refreshWeatherDiagnostics();
            updateDirectInstallButtons();
            if(pendingRecoveryButton!=null)setEnabledState(pendingRecoveryButton,effective&&!localInstallPreparationPending);
            if(dynamicRecovery!=null)setEnabledState(dynamicRecovery,effective);
            if(stagingAcquisition!=null)setEnabledState(stagingAcquisition,effective);
            if(fragmentRecovery!=null)setEnabledState(fragmentRecovery,effective);
        }
        );
    }
    @Override public void onBackupReady(File f){
        runOnUiThread(()->{
            backup=f;
            if(backupShare!=null)setEnabledState(backupShare,true);
            updateDirectInstallButtons();
            if(dynamicRecovery!=null)setEnabledState(dynamicRecovery,ready);
            if(stagingAcquisition!=null)setEnabledState(stagingAcquisition,ready);
            onState(tr("Резервная копия готова","Backup ready"));
        }
        );
    }
    @Override public void onAlbumFinished(boolean ok){
        onState(ok?tr("Настройки прочитаны","Settings read"):tr("Ошибка чтения настроек","Settings read failed"));
    }
    @Override public void onWeatherReadFinished(boolean ok,WeatherReadProtocol.Result result){
        runOnUiThread(()->{
            if(ok&&result!=null){
                lastWeatherState=result;
                onState(watchWeatherSummary());
                onLog("WEATHER_READ saved device="+result.deviceIdentity+" sha256="+result.sha256+" todayWeatherKind="+("0".equals(result.todayWeather)?"ZERO":"OBJECT")+" unit="+result.unitTemperature+" enable="+result.weatherEnable);
            }else onState(tr("Не удалось прочитать погоду часов; отправьте журнал","Could not read watch weather; share the log"));
            if(activeTab==3)rebuildUi();
        });
    }
    private String weeklyPauseReason(String reason){
        if(reason==null||reason.isEmpty())return tr("Передача остановлена; прочитайте погоду часов перед возобновлением.","Transfer stopped; read watch weather before resuming.");
        reason=reason.toUpperCase(Locale.ROOT);
        if(reason.endsWith("WEATHERENABLE MUST BE INTEGER 0"))return tr("Предыдущая версия отклонила пустую погоду при включённой функции. Это состояние теперь поддерживается. Прочитайте погоду часов, обновите прогноз на телефоне и возобновите недельное обновление.","The previous version refused empty weather while the feature was enabled. This state is now supported. Read watch weather, refresh the phone forecast, then resume weekly updates.");
        if(reason.contains("SELECTION"))return tr("Изменён город или источник. Автообновление остановлено; проверьте новые настройки в диагностике погоды.","The city or source changed. Automatic updates stopped; review the new settings in weather diagnostics.");
        if(reason.contains("EXTERNAL")||reason.contains("BASELINE")||reason.contains("CAS"))return tr("Погода часов отличается от последней записи приложения. Прочитайте её и проверьте, прежде чем принимать новое состояние.","Watch weather differs from the app's last write. Read and review it before accepting the new state.");
        if(reason.contains("STORAGE")||reason.contains("INVALID"))return tr("Не удалось подтвердить сохранённое состояние обновления. Передача заблокирована.","The saved update state could not be verified. Transfer is blocked.");
        if(reason.contains("MANUAL")||reason.contains("USER")||reason.contains("EXPLICIT_PAUSE"))return tr("Обновление приостановлено вручную.","Updates were paused manually.");
        if(reason.contains("INTERRUPTED"))return tr("Предыдущая передача была прервана. Автоматический повтор отключён.","The previous transfer was interrupted. Automatic retry is disabled.");
        if(reason.startsWith("SAFE_PREWRITE:"))return tr("Запись погоды отклонена до отправки. Прочитайте погоду часов и экспортируйте диагностику. Автообновление остаётся на паузе.","Weather write refused before sending. Read watch weather and export diagnostics. Automatic updates remain paused.");
        return tr("Операция остановлена после ошибки или изменения соединения. Прочитайте погоду часов; подробности сохранены в журнале.","The operation stopped after an error or connection change. Read watch weather; details are saved in the journal.");
    }
    private String watchWeatherSummary(){
        WeatherReadProtocol.Result result=lastWeatherState;
        if(result==null||!java.util.Objects.equals(result.deviceIdentity,prefs.savedDevice()))return tr("Погода часов ещё не прочитана в этом сеансе.","Watch weather has not been read in this session.");
        try{
            if("0".equals(result.todayWeather))return "1".equals(result.weatherEnable)?tr("Погода на часах включена, но текущие данные пусты. Обновите прогноз на телефоне; затем можно отправить погоду или возобновить недельное обновление.","Watch weather is enabled but current data is empty. Refresh the phone forecast; then send weather or resume weekly updates."):tr("Погода часов не настроена.","Watch weather is not initialized.");
            org.json.JSONObject today=new org.json.JSONObject(result.todayWeather);
            int days=result.futureDayWeather.startsWith("[")?new org.json.JSONArray(result.futureDayWeather).length():0;
            String updated=new SimpleDateFormat("dd.MM HH:mm",Locale.getDefault()).format(new Date(today.getLong("updateTime")*1000L));
            return tr("Последнее чтение часов: ","Last watch read: ")+today.getInt("currentTemperature")+tr(" °C · данные от "," °C · data from ")+updated+tr(" · будущих дней: "," · future days: ")+days+"/6";
        }catch(Exception unavailable){return tr("Данные часов прочитаны; формат отображения не распознан.","Watch data was read; display format is not recognized.");}
    }
    @Override public void onWeatherSendFinished(boolean ok,String detail){
        runOnUiThread(()->{
            onState(ok?(detail!=null&&detail.startsWith("RESTORED_PAUSED:")?tr("Погода восстановлена; автоматическая передача остаётся на паузе","Weather restored; automatic transfer remains paused"):detail!=null&&detail.contains("restored")?tr("Сохранённая погода восстановлена и подтверждена чтением","Saved weather restored and verified by readback"):detail!=null&&detail.startsWith("FULL_FORECAST:")?tr("Текущая погода и прогноз подтверждены чтением; проверьте страницы на часах","Current weather and forecast verified by readback; check the watch pages"):tr("Текущая погода подтверждена чтением; прогноз по дням не передавался","Current weather verified by readback; daily forecast was not sent")):
                    weatherFailureLabel(detail));
            onLog("WEATHER_SEND success="+ok+" detail="+detail);
            if(activeTab==3)rebuildUi();
        });
    }
    private String weatherFailureLabel(String detail){
        if(detail!=null&&detail.startsWith("SAFE_PREWRITE:")){
            if(detail.endsWith("weatherEnable must be integer 0"))return weeklyPauseReason(detail);
            if(detail.contains("HOLD")||detail.contains("held"))return tr("Передача приостановлена для проверки прогноза: примите результат или восстановите сохранённую погоду.","Transfer is paused for the forecast check: accept the result or restore saved weather.");
            if(detail.contains("DUPLICATE"))return tr("Передача этих данных уже запускалась. Для новой попытки сначала обновите погоду.","A transfer of this snapshot was already attempted. Refresh weather before another attempt.");
            if(detail.contains("busy"))return tr("Передача не начата: часы выполняют другую операцию.","Transfer did not start: another watch operation is running.");
            if(detail.contains("expired")||detail.contains("stale")||detail.contains("selection")||detail.contains("date/zone"))return tr("Передача не начата: обновите погоду для выбранного города.","Transfer did not start: refresh weather for the selected city.");
            return tr("Передача не начата: состояние погоды не прошло проверку. Соединение сохранено; подробности доступны в диагностике.","Transfer did not start: weather state validation failed. Connection preserved; details are available in diagnostics.");
        }
        return tr("Передача погоды остановлена. Экспортируйте диагностику, чтобы выяснить причину.","Weather transfer stopped. Export diagnostics to identify the cause.");
    }
    @Override public void onActivityFinished(boolean ok,ActivitySnapshot a){
        runOnUiThread(()->{boolean explicit=activityReadStarted;activityReadStarted=false;if(ok&&a!=null)prefs.lastSourceAt("activity",a.receivedAt);if(a!=null)latestActivitySnapshot=a;if(ok&&a!=null)lastActivityAt=a.receivedAt;activityReadFailed=!ok;latestActivity=ok?tr("Данные активности получены","Activity data received"):tr("Ошибка чтения. Переподключите часы.","Read failed. Reconnect the watch.");invalidateHistory();updateConnectionState();if(activityRead!=null)setEnabledState(activityRead,false);if(expansion!=null)expansion.activityFinished(explicit,ok);else requestBackgroundRedraw();});
    }

    @Override public void onStageFinished(boolean ok,String name,String alias){
        runOnUiThread(()->{
            onState(ok?tr("Циферблат применён и проверен","Dial applied and verified"):tr("Ошибка загрузки; отправьте журнал","Upload failed; share the log"));
            if(ok&&base!=null&&currentImage!=null){
                currentImage.setImageBitmap(base.copy(Bitmap.Config.ARGB_8888,false));
                if(currentLabel!=null)currentLabel.setText(tr("Последний успешно применённый циферблат","Last successfully applied dial"));
            }
            onLog("STAGE terminal success="+ok+" name="+name+" priorAlias="+alias);
        }
        );
    }
    private String journalText(){
        return journal==null?"":journal.tail();
    }
    @Override public void onCurrentDialSnapshot(CurrentDialSnapshot snapshot){latestCurrentDial=snapshot;runOnUiThread(()->{if(currentImage!=null)restoreCurrent();});}
    private void show(int n){
        if(pages==null)return;
        for(int i=0;
        i<pages.getChildCount();
        i++){
            View page=pages.getChildAt(i);
            // Tab IDs remain stable when a legacy page is removed.
            page.setVisibility(Integer.valueOf(n).equals(page.getTag())?View.VISIBLE:View.GONE);
        }
    }
    private LinearLayout card(){
        LinearLayout v=new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12));
        ui.card(v);
        return v;
    }
    private TextView section(String s){
        return ui.section(s);
    }
    private TextView note(String s){
        return ui.label(s,12,ui.muted);
    }
    private Button primaryButton(String s){
        Button b=baseButton(s);
        ui.buttonText(b,Color.WHITE);
        ui.accent(b);
        return b;
    }
    private Button secondaryButton(String s){
        Button b=baseButton(s);
        ui.buttonText(b,ui.text);
        ui.outline(b);
        return b;
    }
    private Button textButton(String s){
        Button b=baseButton(s);
        ui.buttonText(b,ui.muted);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }
    private Button baseButton(String s){
        Button b=new Button(this);
        b.setText(s);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT,Typeface.NORMAL);
        b.setAllCaps(false);
        b.setMinHeight(ui.dp(48));
        b.setMinimumHeight(ui.dp(48));
        b.setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10));b.setMinWidth(0);b.setMinimumWidth(0);ui.touchTarget(b);
        return b;
    }
    @Override public void onDynamicDialFinished(boolean ok){
        runOnUiThread(()->{if(destroyed)return;localInstallPreparationPending=false;
            directInstallUiPending=false;updateDirectInstallButtons();
            if(!ok&&localInstallDialog!=null)localInstallDialog.dismiss();
            if(pendingLocalInstallButton!=null){setEnabledState(pendingLocalInstallButton,directInstallEligible());pendingLocalInstallButton=null;}
            if(pendingRecoveryButton!=null&&sessionBinder!=null&&!sessionBinder.hasPendingLocalRecovery())pendingRecoveryButton.setVisibility(View.GONE);
            if(latestDialProgress!=null&&latestDialProgress.terminal())onState(latestDialProgress.title(!"en".equals(prefs.language())));
            else onState(ok?tr("Новый циферблат установлен и проверен","New dial installed and verified"):tr("Установка остановлена; отправьте диагностику","Installation stopped; export diagnostics"));onLog("DIAL terminal success="+ok);
        });
    }
    @Override public void onLatestHeartRateFinished(TsdbReadProtocol.Result result){runOnUiThread(()->{if(destroyed)return;latestHeartResult=result;boolean explicit=explicitHealthResult(result,"HEART_LAST");if(explicit)healthReadPending=false;if(result!=null&&result.evidenceFile!=null&&!"FAILED".equals(result.state))prefs.lastSourceAt("heart",result.receivedAt);expansion.acceptFreshHealthResult(result,explicit,healthRequestedAt);});}
    @Override public void onSpo2Finished(TsdbReadProtocol.Result result){runOnUiThread(()->{if(destroyed)return;boolean explicit=explicitHealthResult(result,"SPO2_PAGE");if(explicit)healthReadPending=false;if(result!=null&&result.evidenceFile!=null&&!"FAILED".equals(result.state))prefs.lastSourceAt("spo2",result.receivedAt);expansion.acceptFreshHealthResult(result,explicit,healthRequestedAt);});}
    @Override public void onSleepHistoryFinished(TsdbReadProtocol.Result result){runOnUiThread(()->{if(destroyed)return;sleepHistoryResult=result;boolean explicit=explicitHealthResult(result,"SLEEP_PAGE");if(explicit)healthReadPending=false;if(result!=null&&result.evidenceFile!=null&&!"FAILED".equals(result.state))prefs.lastSourceAt("sleep",result.receivedAt);expansion.acceptFreshHealthResult(result,explicit,healthRequestedAt);if(explicit&&result!=null)onState(ExpansionUiController.sleepReadFeedback(result,"en".equals(prefs.language())));});}
    @Override public void onSportsPageFinished(TsdbReadProtocol.Result result){runOnUiThread(()->{if(destroyed)return;if(result!=null&&result.evidenceFile!=null&&!"FAILED".equals(result.state))prefs.lastSourceAt("sports",result.receivedAt);toast(result==null||"FAILED".equals(result.state)?tx("Не удалось обновить историю часов"):tx("Записи тренировок сохранены"));expansion.acceptFreshHealthResult(result,false,0);});}
    @Override public void onAlarmFinished(AlarmProtocol.Result result){runOnUiThread(()->{if(destroyed)return;if(result!=null&&result.evidence!=null&&("COMPLETE".equals(result.state)||"VERIFIED".equals(result.state)))prefs.lastSourceAt("alarms",result.receivedAt);toast(result!=null&&"VERIFIED".equals(result.state)?result.plan!=null&&result.plan.action==AlarmTransferPlan.Action.DELETE?tx("Будильник удалён с часов"):result.plan!=null&&result.plan.action==AlarmTransferPlan.Action.RESTORE?tx("Расписание часов восстановлено"):tx("Будильник сохранён на часах"):result!=null&&"COMPLETE".equals(result.state)?tx("Будильники часов получены"):result!=null&&"STOPPED_UNCERTAIN".equals(result.state)?tx("Результат отправки не подтверждён. Проверьте часы."):tx("Не удалось обновить будильники"));expansion.refresh(false);});}
    @Override public void onDynamicDialRecoveryFinished(boolean ok){
        runOnUiThread(()->{onState(ok?tr("Квитанция Dial Lab восстановлена после read-only проверки","Dial Lab receipt recovered after read-only verification"):tr("Восстановление квитанции остановлено; отправьте журнал","Receipt recovery stopped; share the log"));onLog("DIAL_RECOVERY terminal success="+ok);if(dynamicRecovery!=null)setEnabledState(dynamicRecovery,ready);});
    }
    @Override public void onStagingAcquisitionFinished(boolean ok){
        runOnUiThread(()->{onState(ok?tr("Копия staging-файла сохранена; соединение закрыто","Staging file saved; connection closed"):tr("Чтение staging остановлено; отправьте журнал","Staging read stopped; share the log"));onLog("STAGING_ACQUISITION terminal success="+ok);if(stagingAcquisition!=null)setEnabledState(stagingAcquisition,false);});
    }
    @Override public void onStagingAcquisitionReady(File binary,File metadata){
        runOnUiThread(()->{stagingEvidence=binary;stagingMetadata=metadata;if(stagingShare!=null)setEnabledState(stagingShare,true);if(stagingMetadataShare!=null)setEnabledState(stagingMetadataShare,true);});
    }
    @Override public void onFragmentRecoveryFinished(boolean ok){
        runOnUiThread(()->{onState(ok?tr("Phosphor восстановлен и проверен; соединение закрыто","Phosphor restored and verified; connection closed"):tr("Историческое восстановление недоступно в публичной версии","Historical recovery is unavailable in the public release"));onLog("FRAGMENT_RECOVERY terminal success="+ok);if(fragmentRecovery!=null)setEnabledState(fragmentRecovery,ready);});
    }
    @Override public void onConnectionReset(){
        runOnUiThread(()->{connected=false;ready=false;if(healthReadPending){healthReadPending=false;expansion.readFeedback(tr("Обновление остановлено. Сохранённые данные доступны.","Refresh stopped. Saved data is available."),false);}if(activityReadStarted){activityReadStarted=false;expansion.readFeedback(tr("Обновление остановлено. Сохранённые данные доступны.","Refresh stopped. Saved data is available."),false);}updateConnectionState();updateDirectInstallButtons();if(dynamicRecovery!=null)setEnabledState(dynamicRecovery,false);if(stagingAcquisition!=null)setEnabledState(stagingAcquisition,false);if(fragmentRecovery!=null)setEnabledState(fragmentRecovery,false);});
    }
    private void setEnabledState(Button button,boolean enabled){
        button.setEnabled(enabled);
        button.setAlpha(enabled?1f:.45f);
    }
    private Button choiceButton(String label){
        Button button=baseButton(label);
        updateChoice(button,false);
        return button;
    }
    private void updateChoice(Button button,boolean selected){
        button.setSelected(selected);
        ui.buttonText(button,selected?ui.accentInk:ui.text);
        if(selected)ui.selectedCard(button);else ui.outline(button);
    }
    private String tr(String ru,String en){
        return "en".equals(prefs.language())?en:ru;
    }
    private void toast(String s){
        Toast.makeText(this,s==null?tr("Ошибка","Error"):s,Toast.LENGTH_LONG).show();
    }
    private void applyLocale(String lang){
        Locale l=new Locale(lang);
        Locale.setDefault(l);
        Configuration c=new Configuration(getResources().getConfiguration());
        c.setLocale(l);
        getResources().updateConfiguration(c,getResources().getDisplayMetrics());
    }
    private byte[] read(Uri u)throws IOException{
        try(InputStream i=getContentResolver().openInputStream(u);
        ByteArrayOutputStream o=new ByteArrayOutputStream()){
            if(i==null)throw new IOException("open");
            byte[] b=new byte[8192];
            for(int n;
            (n=i.read(b))!=-1;
            ){
                o.write(b,0,n);
                if(o.size()>MAX_SOURCE)throw new IOException(tr("Файл больше 16 МиБ","File exceeds 16 MiB"));
            }
            return o.toByteArray();
        }
    }
    private static byte[] read(File f)throws IOException{
        try(InputStream i=new FileInputStream(f);
        ByteArrayOutputStream o=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];
            for(int n;
            (n=i.read(b))!=-1;
            )o.write(b,0,n);
            return o.toByteArray();
        }
    }
    @Override protected void onDestroy(){
        if(dialEditor!=null)dialEditor.destroy();
        destroyed=true;
        dismissDialProgress();journalUiHandler.removeCallbacks(dialProgressTick);
        if(localInstallDialog!=null)localInstallDialog.dismiss();
        cancelLocalInstallConfirmation();
        if(weatherSettingsDialog!=null)weatherSettingsDialog.dismiss();
        if(weatherDiagnosticsDialog!=null)weatherDiagnosticsDialog.dismiss();
        if(expansion!=null)expansion.destroy();
        if(sessionBinder!=null)sessionBinder.detach(this);
        if(cityLocator!=null){cityLocator.cancel();cityLocator=null;}
        generation++;
        if(worker!=null)worker.shutdownNow();
        if(temporaryJournal!=null){temporaryJournal.close();temporaryJournal=null;}
        if(photoDialog!=null)photoDialog.dismiss();
        if(bound){unbindService(sessionConnection);bound=false;}
        if(base!=null&&!base.isRecycled())base.recycle();
        super.onDestroy();
    }
    private void navigateBack(){if(expansion!=null&&expansion.back(activeTab))return;if(activeTab==1||activeTab==4){activeTab=3;rebuildUi();}else if(activeTab==0&&selectedProjectId!=null){selectedProjectId=null;rebuildUi();}else if(activeTab==0&&selectedBundledId!=null){selectedBundledId=null;rebuildUi();}else if(activeTab==0&&!myDialCategory&&falloutFolder){falloutFolder=false;rebuildUi();}else finish();}
    // Legacy phones use this callback; Android 13+ uses OnBackInvokedDispatcher above.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){navigateBack();}
    @Override protected void onSaveInstanceState(Bundle out){captureScreenState();Bundle savedScreens=new Bundle();for(Map.Entry<String,Bundle> entry:screenStates.entrySet())savedScreens.putBundle(entry.getKey(),entry.getValue());out.putBundle("ui.screens",savedScreens);if(dialEditor!=null)dialEditor.saveState(out);Bundle savedSettings=new Bundle();for(Map.Entry<String,Boolean> section:settingsSections.entrySet())savedSettings.putBoolean(section.getKey(),section.getValue());out.putBundle("ui.settings.sections",savedSettings);out.putInt("tab",activeTab);out.putBoolean("health.pending",healthReadPending);out.putLong("health.requestedAt",healthRequestedAt);out.putString("health.profile",healthPendingProfile);out.putInt("position",selectedPosition);out.putInt("color",selectedColor);out.putString("editingId",editingId);out.putString("bundledDetail",selectedBundledId);out.putString("projectDetail",selectedProjectId);out.putBoolean("myDialCategory",myDialCategory);out.putBoolean("falloutFolder",falloutFolder);out.putInt("myDialPage",myDialPage);out.putString("historyDay",historyDay);out.putBoolean("activityHistoryMode",activityHistoryMode);out.putBoolean("activityHistoryDaySelected",activityHistoryDaySelected);if(expansion!=null)expansion.save(out);super.onSaveInstanceState(out);}
    private void dependency(String text){String op=OperationJournal.get(this).begin("device_gate","requested","COMPILE_PROFILE",prefs.savedDevice());OperationJournal.get(this).stage(op,"device_gate","requested","BLOCKED","BLOCKED","DEPENDENCY_REQUIRED",0,0,0);ui.dialogBuilder().setTitle(tx("Условия выполнения")).setMessage(text).setPositiveButton(tx("Закрыть"),null).show();}
    private boolean explicitHealthResult(TsdbReadProtocol.Result result,String profile){return healthReadPending&&profile.equals(healthPendingProfile)&&(result==null||profile.equals(result.profile)&&result.receivedAt>=healthRequestedAt);}
    private void confirmHealth(boolean sleep){confirmHealthProfile(sleep?"SLEEP_PAGE":"HEART_LAST");}
    private void confirmHealthProfile(String profile){
        if(healthReadPending||expansion.measurementsBusy())return;if(watch==null||!ready){toast(tr("Подключите часы в Настройках","Connect the watch in Settings"));return;}
        healthReadPending=true;healthPendingProfile=profile;healthRequestedAt=System.currentTimeMillis();expansion.readFeedback("SLEEP_PAGE".equals(profile)?tr("Обновляю сон…","Refreshing sleep…"):"SPO2_PAGE".equals(profile)?tr("Обновляю SpO₂…","Refreshing SpO₂…"):tr("Обновляю пульс…","Refreshing heart rate…"),true);
        if(sessionBinder!=null)sessionBinder.readFreshHealth(profile,healthRequestedAt);else{healthReadPending=false;expansion.readFeedback(tr("Служба часов недоступна. Подключитесь снова.","Watch service unavailable. Connect again."),false);}
    }

}



