package app.gtdial.lab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.Context;
import android.app.DatePickerDialog;
import android.widget.Switch;
import android.graphics.Typeface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Native local product UI. All storage and exports use the process-wide storage executor. */
final class ExpansionUiController {
    static final int HEALTH_DOCUMENT=110,ALARM_DOCUMENT=111,GPS_PERMISSION=112,NUTRITION_EXPORT=113,NUTRITION_IMPORT=114,NUTRITION_PHOTO=115,ANALYSIS_EXPORT=116;
    interface Host {
        void redraw();void tab(int id);String device();boolean ready();void share(File file,String type);
        void readActivity();void readHeart();void readSpo2();void readSleep();void readSports();void readAlarms();void writeAlarm(AlarmTransferPlan plan);void exportDiagnostics();void weatherDiagnostics();
        void clearWorkoutDraft();
    }
    private static final class Route {
        final String screen,arg;Route(String screen,String arg){this.screen=screen;this.arg=arg;}
    }
    private static final class Data {
        String selectedDevice="";
        List<String> devices=Collections.emptyList();
        List<ActivityHistoryEntry> days=Collections.emptyList(),points=Collections.emptyList(),todayPoints=Collections.emptyList(),latestPoints=Collections.emptyList();
        Map<String,List<ExpansionModels.HealthRecord>> health=new HashMap<>();
        List<JSONObject> workouts=Collections.emptyList(),manual=Collections.emptyList(),alarms=Collections.emptyList();
        JSONObject current;boolean suppressed;Map<String,String> alarmTransfers=Collections.emptyMap();
        Map<String,List<JSONObject>> strengthEntries=new HashMap<>(),strengthSets=new HashMap<>(),workoutObservations=new HashMap<>();
        JSONObject strengthMonth;
        AlarmProtocol.Snapshot watchAlarms;
    }
    private final Activity activity;private final AppPreferences prefs;private final Host host;
    private final ExpansionStore store;private final ActivityHistoryStore activityStore;
    private final WorkoutRecorder recorder;private final HealthEvidenceImporter importer;private final OperationJournal journal;
    private final StrengthDiary strengthDiary;private final StrengthUiController strengthUi;
    private final NutritionUiController nutritionUi;private final AnalysisUiController analysisUi;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<Integer,ArrayDeque<Route>> routes=new HashMap<>();
    private Data data=new Data();private UiKit ui;private LocalDate date=LocalDate.now();private int period=1,loadGeneration;
    private boolean focusHealthWindow;private boolean destroyed,resumed,loading,reloadQueued,queuedScan;private String failure="",device="";
    private String pendingSport,pendingPlan;private Integer pendingGoal;private boolean pendingFinish;
    private TextView activeTimer;private int ticks;private String readFeedback="";private boolean readInProgress,deletingMeasurements;
    private final Runnable ticker=new Runnable(){@Override public void run(){if(!resumed||destroyed)return;if(activeTimer!=null&&data.current!=null)activeTimer.setText(WorkoutRecorder.duration(recorder.activeMillis(data.current)));strengthUi.tick();if(++ticks%30==0&&data.current!=null){String id=data.current.optString("id");store.execute(()->{try{recorder.checkpoint(id);}catch(RuntimeException e){logFailure("checkpoint",e);}});}main.postDelayed(this,1000);}};
    ExpansionUiController(Activity activity,AppPreferences prefs,Host host){
        this.activity=activity;this.prefs=prefs;this.host=host;store=ExpansionStore.get(activity);activityStore=new ActivityHistoryStore(activity);
        recorder=new WorkoutRecorder(activity,store);importer=new HealthEvidenceImporter(activity,store);journal=OperationJournal.get(activity);
        strengthDiary=new StrengthDiary(activity,store);strengthUi=new StrengthUiController(activity,store,()->refresh(false));
        OpenRouterClient router=OpenRouterClient.get(activity);
        nutritionUi=new NutritionUiController(activity,store,router,new NutritionUiController.Host(){public void go(String screen,String arg){ExpansionUiController.this.go(6,screen,arg);}public void redraw(){host.redraw();}public void sourceDeleted(){analysisUi.invalidate();}public void settings(){ExpansionUiController.this.go(3,"openrouter","");}});
        analysisUi=new AnalysisUiController(activity,store,activityStore,router,new AnalysisUiController.Host(){public void go(String screen,String arg){ExpansionUiController.this.go(2,screen,arg);}public void redraw(){host.redraw();}public void settings(){ExpansionUiController.this.go(3,"openrouter","");}public String device(){String exact=host.device();return exact.isEmpty()?device:exact;}});
        device=prefs.evidenceDevice();
        android.content.SharedPreferences saved=activity.getSharedPreferences("gt_dial_preferences",Context.MODE_PRIVATE);
        if(!saved.getBoolean("home.cards.corrective.v1",false)){
            Set<String> cards=saved.contains("home.cards")?prefs.homeCards():new HashSet<>(Arrays.asList("steps","heart","sleep","spo2"));
            cards.remove("wellbeing");cards.add("spo2");prefs.homeCards(cards);
            saved.edit().putBoolean("home.cards.corrective.v1",true).apply();
        }
        if(!saved.getBoolean("home.cards.calorie.v1",false)){
            Set<String> cards=prefs.homeCards();cards.add("calorie");prefs.homeCards(cards);
            saved.edit().putBoolean("home.cards.calorie.v1",true).apply();
        }
    }
    void restore(Bundle state){if(state==null)return;nutritionUi.restore(state);analysisUi.restore(state);readInProgress=state.getBoolean("expansion.read.pending");readFeedback=state.getString("expansion.read.feedback","");try{date=LocalDate.parse(state.getString("expansion.date",date.toString()));period=state.getInt("expansion.period",1);if(period!=1&&period!=7&&period!=0)period=1;
            for(int tab:new int[]{2,5,6,3,4}){String saved=state.getString("expansion.routes."+tab,"[]");JSONArray array=new JSONArray(saved);ArrayDeque<Route> stack=new ArrayDeque<>();for(int i=0;i<array.length()&&i<20;i++){JSONObject r=array.getJSONObject(i);String screen=r.getString("screen");if(allowedRoute(tab,screen)&&safeRouteArgument(screen,r.optString("arg"))&&(!"metric".equals(screen)||Arrays.asList("steps","calorie","heart","sleep","spo2","weight").contains(r.optString("arg"))))stack.add(new Route(screen,r.optString("arg")));}routes.put(tab,stack);}}
        catch(Exception e){routes.clear();date=LocalDate.now();period=1;}}
    // Old Workout subroutes are discarded during restore; diary data and editor IDs remain intact.
    void tabChanged(int previous,int next){if(previous==next)return;if(previous==6)nutritionUi.leave();if(previous==2||previous==3)analysisUi.pause();}
    void save(Bundle state){nutritionUi.save(state);analysisUi.save(state);state.putBoolean("expansion.read.pending",readInProgress);state.putString("expansion.read.feedback",readFeedback);state.putString("expansion.date",date.toString());state.putInt("expansion.period",period);for(int tab:new int[]{2,5,6,3,4}){JSONArray a=new JSONArray();for(Route r:stack(tab)){JSONObject o=new JSONObject();ExpansionModels.put(o,"screen",r.screen);ExpansionModels.put(o,"arg",r.arg);a.put(o);}state.putString("expansion.routes."+tab,a.toString());}}
    void resume(){resumed=true;main.removeCallbacks(ticker);main.post(ticker);refresh(false);}
    void pause(){resumed=false;main.removeCallbacks(ticker);strengthUi.pause();nutritionUi.pause();analysisUi.pause();if(data.current!=null){String id=data.current.optString("id");store.execute(()->{try{recorder.checkpoint(id);}catch(RuntimeException e){logFailure("checkpoint",e);}});}}
    void destroy(){destroyed=true;pause();strengthUi.destroy();nutritionUi.destroy();analysisUi.destroy();store.execute(activityStore::close);}
    void intent(Intent intent){if(intent==null)return;if(intent.getBooleanExtra("phoneWorkout",false)||intent.getBooleanExtra("phoneWorkoutFinish",false)){pendingFinish=intent.getBooleanExtra("phoneWorkoutFinish",false);stack(5).clear();stack(5).add(new Route("active",""));intent.removeExtra("phoneWorkout");intent.removeExtra("phoneWorkoutFinish");host.tab(5);refresh(false);}}
    boolean back(int tab){ArrayDeque<Route> s=stack(tab);Route route=s.peekLast();if(route!=null&&Arrays.asList("nutrition","nutrition_editor","food_search").contains(route.screen))nutritionUi.leave();if(route!=null&&Arrays.asList("analysis","analysis_history","openrouter").contains(route.screen))analysisUi.pause();if(tab==5&&route!=null&&"active".equals(route.screen)&&data.current!=null&&"strength".equals(data.current.optString("typeId"))&&strengthUi.back())return true;if(s.isEmpty())return false;s.removeLast();host.redraw();return true;}
    void refresh(boolean scan){
        if(destroyed)return;if(loading){reloadQueued=true;queuedScan|=scan;return;}loading=true;final int generation=++loadGeneration;
        String chosen=host.device().isEmpty()?device:host.device();if(chosen.isEmpty())chosen=prefs.savedDevice();final String source=chosen;final LocalDate selected=date;final long selectedFrom=ExpansionModels.start(periodStart(),ZoneId.systemDefault())/1000,selectedTo=ExpansionModels.start(periodEnd(),ZoneId.systemDefault())/1000;
        Route workoutRoute=stack(5).peekLast();final String detailWorkout=workoutRoute!=null&&"workout".equals(workoutRoute.screen)?workoutRoute.arg:"";
        final boolean loadStrengthMonth=workoutRoute!=null&&"strength_progress".equals(workoutRoute.screen);
        store.submit(()->{if(scan)importer.scan();Data next=new Data();Set<String> sources=new java.util.LinkedHashSet<>(store.devices());sources.addAll(activityStore.devices());next.devices=new ArrayList<>(sources);next.suppressed=store.healthImportSuppressed();String exact=source;if(exact.isEmpty()&&next.devices.size()==1)exact=next.devices.get(0);next.selectedDevice=exact;
            if(!exact.isEmpty()){ArrayList<ActivityHistoryEntry> daily=new ArrayList<>();for(String day:activityStore.observedDays(exact)){ActivityHistoryEntry r=activityStore.latestByDay(exact,day);if(r!=null)daily.add(r);}next.days=daily;next.points=activityStore.observationsForDay(exact,selected.toString());next.todayPoints=selected.equals(LocalDate.now())?next.points:activityStore.observationsForDay(exact,LocalDate.now().toString());if(!daily.isEmpty())next.latestPoints=activityStore.observationsForDay(exact,daily.get(0).phoneDay);
                for(String dataset:new String[]{"heart","sleep","spo2","sports","sports_graph","sports_spo2","sports_pace","sports_section_run","sports_section_swim"}){List<ExpansionModels.HealthRecord> range=store.health(exact,dataset,selectedFrom,selectedTo);Set<String> ids=new HashSet<>();for(ExpansionModels.HealthRecord record:range)ids.add(record.id);for(ExpansionModels.HealthRecord record:store.health(exact,dataset,0,253402300799L,256,0))if(ids.add(record.id))range.add(record);range.sort(java.util.Comparator.comparingLong(r->r.tsSeconds));next.health.put(dataset,range);}next.watchAlarms=AlarmProtocol.savedSnapshot(activity,exact);}
            next.workouts=store.list("workouts");next.manual=store.list("manual_entries");next.alarms=store.list("alarm_drafts");next.alarmTransfers=store.alarmTransferStates();next.current=recorder.current();
            Set<String> workoutIds=new HashSet<>();if(next.current!=null)workoutIds.add(next.current.optString("id"));if(!detailWorkout.isEmpty())workoutIds.add(detailWorkout);
            for(String workoutId:workoutIds){List<JSONObject> entries=strengthDiary.entries(workoutId);next.strengthEntries.put(workoutId,entries);for(JSONObject entry:entries)next.strengthSets.put(entry.optString("id"),strengthDiary.sets(entry.optString("id")));next.workoutObservations.put(workoutId,store.diaryRows("workout_observations",workoutId));}
            if(loadStrengthMonth)next.strengthMonth=new StrengthProgress(store).month(java.time.YearMonth.from(selected).toString(),ZoneId.systemDefault().getId());
            return next;
        },(next,error)->{if(destroyed)return;loading=false;if(generation!=loadGeneration)return;if(reloadQueued){reloadQueued=false;boolean needed=queuedScan;queuedScan=false;refresh(needed);return;}failure=error;
            if(next!=null){if(!device.equals(next.selectedDevice))analysisUi.invalidate();data=next;device=next.selectedDevice;if(!device.isEmpty())prefs.evidenceDevice(device);}host.redraw();if(pendingFinish&&data.current!=null){pendingFinish=false;finishDialog(data.current);}});
    }
    void buildActivity(LinearLayout page,UiKit kit){ui=kit;build(page,2);}
    void buildWorkouts(LinearLayout page,UiKit kit){ui=kit;build(page,5);}
    void buildNutrition(LinearLayout page,UiKit kit){ui=kit;build(page,6);}
    boolean buildSettings(LinearLayout page,UiKit kit){ui=kit;if(stack(3).isEmpty())return false;build(page,3);return true;}
    void settingsLinks(LinearLayout page,UiKit kit){for(String group:new String[]{"profile","watch","assistant","data"})settingsLinks(page,kit,group);}
    void settingsLinks(LinearLayout page,UiKit kit,String group){
        ui=kit;
        if("profile".equals(group)){
            settingRow(page,tx("Профиль и цели"),tx("Имя, шаги и сон"),UiIcons.Kind.USER,false,()->go(3,"profile",""));
            settingRow(page,tx("Карточки активности"),tx("Показатели на главном экране"),UiIcons.Kind.CHART,false,()->go(3,"cards",""));
        }else if("watch".equals(group)){
            settingRow(page,tx("Будильники"),tx("Расписание в приложении"),UiIcons.Kind.ALARM,false,()->go(3,"alarms",""));
            settingRow(page,tx("Погода"),tx("Город, прогноз и обновление на часах"),UiIcons.Kind.WEATHER,false,host::weatherDiagnostics);
        }else if("assistant".equals(group)){
            settingRow(page,"Личный OpenRouter","Защищённый ключ и бесплатные модели",UiIcons.Kind.SPARKLES,true,()->go(3,"openrouter",""));
            settingRow(page,"Питание и калории","Единый дневник еды",UiIcons.Kind.FOOD,false,()->{go(6,"nutrition","");nutritionUi.refresh();});
            settingRow(page,"Анализ показателей","День, 7 или 30 дней и история оснований",UiIcons.Kind.CHART,false,()->go(2,"analysis",""));
        }else if("data".equals(group))settingRow(page,tx("Данные и приватность"),tx("Экспорт и удаление показателей"),UiIcons.Kind.SHIELD,false,()->go(3,"privacy",""));
    }
    void buildJournal(LinearLayout page,UiKit kit){ui=kit;journal(page);}
    private void build(LinearLayout page,int tab){
        activeTimer=null;
        if(!failure.isEmpty()){
            note(page,tx("Не удалось открыть сохранённые данные."));
            action(page,tx("Обновить"),()->refresh(false));
        }
        if(loading&&data.workouts.isEmpty()&&data.days.isEmpty()&&data.health.isEmpty())note(page,tx("Загрузка…"));
        Route r=stack(tab).peekLast();
        String screen=r==null?(tab==2?"today":tab==5?"workouts":tab==6?"nutrition":"settings"):r.screen;
        if(r!=null){Button back=button(tx("Назад"),()->back(tab));back.setBackgroundColor(android.graphics.Color.TRANSPARENT);back.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);page.addView(back,ui.fillWrap(0,2));}
        String arg=r==null?"":r.arg;
        switch(screen){
            case "today":today(page);break;
            case "metric":metric(page,arg);break;
            case "sleep":sleepDetail(page,arg);break;
            case "workouts":workouts(page);break;
            case "catalog":catalog(page);break;
            case "prepare":prepare(page,arg);break;
            case "active":active(page);break;
            case "workout_history":workoutHistory(page,arg);break;
            case "strength_progress":strengthProgress(page);break;
            case "workout":workoutDetail(page,arg);break;
            case "watch_workout":watchWorkout(page,arg);break;
            case "profile":profile(page);break;
            case "cards":cardSettings(page);break;
            case "privacy":privacy(page);break;
            case "nutrition":case "nutrition_editor":case "food_search":nutritionUi.build(page,ui,screen,arg);break;
            case "analysis":case "analysis_history":case "openrouter":analysisUi.build(page,ui,screen,arg);break;
            case "alarms":alarms(page);break;
            default:stack(tab).clear();if(tab==2)today(page);else if(tab==5)workouts(page);else if(tab==6)nutritionUi.build(page,ui,"nutrition","");else settingsLinks(page,ui);break;
        }
    }
    private ArrayDeque<Route> stack(int tab){return routes.computeIfAbsent(tab,k->new ArrayDeque<>());}
    String screenKey(int tab){Route route=stack(tab).peekLast();return route==null?"home":route.screen+":"+route.arg;}
    private void go(int tab,String screen,String arg){
        Route previous=stack(tab).peekLast();if(previous!=null&&Arrays.asList("nutrition_editor","food_search").contains(previous.screen)&&(!previous.screen.equals(screen)||!previous.arg.equals(arg)))nutritionUi.leave();if(previous!=null&&Arrays.asList("analysis","analysis_history","openrouter").contains(previous.screen)&&!previous.screen.equals(screen))analysisUi.pause();ArrayDeque<Route> s=stack(tab);if(s.size()>=20)s.removeFirst();s.add(new Route(screen,arg));
        if("analysis_history".equals(screen))analysisUi.openHistory(arg);String op=journal.begin("ui","screen_open",screen,device);journal.stage(op,"ui","screen_open","COMPLETE","SUCCESS","",0,0,0);host.tab(tab);
    }
    private static final class CardReading {
        String value="—",unit="",caption="";
        LocalDate day=LocalDate.now();long from,to;
        HealthChartView.Style style=HealthChartView.Style.POINTS;
        List<HealthChartView.Point> points=Collections.emptyList();
    }
    private void today(LinearLayout p){
        LinearLayout title=new LinearLayout(activity);title.setGravity(Gravity.CENTER_VERTICAL);
        title.addView(ui.heading(tx("Активность")),new LinearLayout.LayoutParams(0,-2,1));
        title.addView(ui.label(date.format(DateTimeFormatter.ofPattern("d MMMM",displayLocale())),12,ui.muted));
        p.addView(title,ui.fillWrap(0,8));dateControls(p);periodControls(p);if(!readFeedback.isEmpty())note(p,readFeedback);
        if(data.days.isEmpty()&&data.health.values().stream().allMatch(List::isEmpty)){
            note(p,tr("Сохранённых показателей пока нет.","No saved measurements yet."));
            primary(p,host.ready()?tr("Обновить шаги","Refresh steps"):tr("Подключить часы","Connect watch"),host.ready()?host::readActivity:()->host.tab(3));
        }
        List<String> shown=new ArrayList<>();Set<String> selected=prefs.homeCards();
        for(String metric:new String[]{"steps","calorie","heart","sleep","spo2","weight"})if(selected.contains(metric)&&(!"weight".equals(metric)||hasWeight()))shown.add(metric);
        if(shown.isEmpty())note(p,tx("Выберите показатели в Настройках / Карточки активности."));
        if(period!=1&&shown.remove("steps"))p.addView(activityCard("steps"),ui.fillWrap(0,12));
        int columns=activity.getResources().getConfiguration().fontScale>1.2f||activity.getResources().getConfiguration().screenWidthDp<360?1:2;
        for(int i=0;i<shown.size();i+=columns){
            LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);row.setBaselineAligned(false);
            for(int column=0;column<columns;column++){
                LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(0,-2,1);
                if(column==1)layout.setMargins(ui.dp(10),0,0,0);
                if(i+column>=shown.size()){layout.height=0;row.addView(new View(activity),layout);continue;}
                LinearLayout c=activityCard(shown.get(i+column));
                row.addView(c,layout);
            }
            p.addView(row,ui.fillWrap(0,10));
        }
        TextView source=ui.label(tr("Пропущенные дни не заменяются нулями. Калории часов показаны в исходных единицах, отдельно от еды.","Missing days are not zero. Watch calories use raw units, separate from food."),12,ui.muted);p.addView(source,ui.fillWrap(4,8));
        settingRow(p,"Разбор с ИИ","День, 7 или 30 дней · проверить данные перед отправкой",UiIcons.Kind.SPARKLES,true,()->go(2,"analysis",""));
        settingRow(p,"История разборов","Сохранённые отчёты и основания",UiIcons.Kind.HISTORY,false,()->go(2,"analysis_history",""));
    }
    private LinearLayout activityCard(String metric){
        CardReading reading=periodReading(metric);
        LinearLayout c=column();c.setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(8));c.setMinimumHeight(ui.dp(164));ui.card(c);
        c.addView(ui.iconHeading(metricName(metric),metricIcon(metric)),ui.fillWrap(0,8));
        LinearLayout valueRow=new LinearLayout(activity);valueRow.setOrientation(LinearLayout.VERTICAL);
        TextView value=ui.label(reading.value,26,ui.text);value.setTypeface(Typeface.DEFAULT,Typeface.BOLD);valueRow.addView(value,new LinearLayout.LayoutParams(-1,-2));
        if(!reading.unit.isEmpty()){TextView unit=ui.label(reading.unit,12,ui.muted);LinearLayout.LayoutParams unitLayout=new LinearLayout.LayoutParams(-2,-2);unitLayout.setMargins(0,ui.dp(4),0,0);valueRow.addView(unit,unitLayout);}
        c.addView(valueRow,ui.fillWrap(0,5));
        
        TextView caption=ui.label(reading.caption,11,ui.muted);c.addView(caption,ui.fillWrap(0,4));
        HealthChartView chart=new HealthChartView(activity,ui,reading.style,reading.from,reading.to,unit(metric),reading.points,true);c.addView(chart,new LinearLayout.LayoutParams(-1,ui.dp("steps".equals(metric)&&period!=1?110:60)));
        c.setContentDescription(metricName(metric)+", "+reading.value+" "+reading.unit+", "+reading.caption);
        ui.touchTarget(c);c.setOnClickListener(v->go(2,"metric",metric));
        return c;
    }
    private CardReading periodReading(String metric){
        CardReading result=new CardReading();result.day=date;result.from=ExpansionModels.start(periodStart(),ZoneId.systemDefault());result.to=ExpansionModels.start(periodEnd(),ZoneId.systemDefault());
        result.caption=tr("Нет записей за период","No records in this period");
        result.points="weight".equals(metric)?weightPoints(result.from,result.to):chartPoints(metric,result.from,result.to,period!=1);
        if("steps".equals(metric)||"calorie".equals(metric)){
            result.style=period==1?HealthChartView.Style.LINE:HealthChartView.Style.BARS;
            if(period==1){ActivityHistoryEntry entry=day(date);if(entry!=null){result.value=number("steps".equals(metric)?entry.steps:entry.calorieRaw);result.caption=tr("Последняя запись · ","Last record · ")+shortTime(entry.receivedAt);}}
            else if(!result.points.isEmpty()){double sum=0;for(HealthChartView.Point point:result.points)sum+=point.value;result.value=number(Math.round(sum));result.caption=tr("Дней с записями: ","Recorded days: ")+result.points.size()+" / "+java.time.temporal.ChronoUnit.DAYS.between(periodStart(),periodEnd());}
            result.unit="steps".equals(metric)?tx("шагов"):tr("исх. ед.","raw units");
        }else if("sleep".equals(metric)){
            if(period==1){ExpansionModels.HealthRecord night=latestHealth("sleep",date);if(night!=null){result.value=sleepDuration(night.data.optLong("asleepSeconds"));result.caption=tr("Сохранённый интервал","Saved interval");result.from=night.data.optLong("enter")*1000;result.to=night.data.optLong("exit")*1000;result.points=sleepPoints(night,result.from,result.to);result.style=HealthChartView.Style.SLEEP;}}
            else if(!result.points.isEmpty()){double sum=0;for(HealthChartView.Point point:result.points)sum+=point.value;result.value=sleepDuration(Math.round(sum/result.points.size()*3600));result.caption=tr("Среднее · интервалов: ","Mean · intervals: ")+result.points.size();result.style=HealthChartView.Style.BARS;}
        }else if(!result.points.isEmpty()){
            if("weight".equals(metric)||period==1){HealthChartView.Point last=result.points.get(result.points.size()-1);result.value=format(last.value);result.caption=tr("Последняя запись · ","Last record · ")+shortTime(last.time);}
            else{double sum=0;for(HealthChartView.Point point:result.points)sum+=point.value;result.value=format(sum/result.points.size());result.caption=tr("Среднее · наблюдений: ","Mean · observations: ")+result.points.size();}
            result.unit="heart".equals(metric)?tx("уд/мин"):"spo2".equals(metric)?"%":tx("кг");
        }
        if(period!=1&&("heart".equals(metric)||"spo2".equals(metric)))result.style=HealthChartView.Style.DAILY_RANGE;
        return result;
    }
    private ExpansionModels.HealthRecord latestHealth(String dataset,LocalDate selected){
        ExpansionModels.HealthRecord latest=null;long newest=-1;
        for(ExpansionModels.HealthRecord r:health(dataset)){
            if(!r.validity.startsWith("VALID")||!"sleep".equals(dataset)&&(r.value==null||!Double.isFinite(r.value)))continue;
            long at="sleep".equals(dataset)?r.data.optLong("exit"):r.tsSeconds;
            if(at<=0||"sleep".equals(dataset)&&r.data.optLong("enter")>=at)continue;
            if(selected!=null&&!Instant.ofEpochSecond(at).atZone(ZoneId.systemDefault()).toLocalDate().equals(selected))continue;
            if(at>newest||at==newest&&(latest==null||r.receivedAt>latest.receivedAt)){newest=at;latest=r;}
        }
        return latest;
    }
    private JSONObject latestWeight(){for(JSONObject r:data.manual)if("WEIGHT_KG".equals(r.optString("kind"))&&!r.isNull("value")&&Double.isFinite(r.optDouble("value")))return r;return null;}
    private boolean hasWeight(){return latestWeight()!=null;}
    private void cardSettings(LinearLayout p){
        heading(p,tx("Карточки активности"));
        for(String metric:new String[]{"steps","calorie","heart","sleep","spo2","weight"}){
            if("weight".equals(metric)&&!hasWeight())continue;
            CheckBox box=check(p,metricName(metric),prefs.homeCards().contains(metric));
            box.setOnCheckedChangeListener((b,on)->{Set<String> selected=prefs.homeCards();if(on)selected.add(metric);else selected.remove(metric);prefs.homeCards(selected);event("home_cards_save");});
        }
    }
    private void metric(LinearLayout p,String metric){
        heading(p,metricName(metric));if(!readFeedback.isEmpty())note(p,readFeedback);dateControls(p);periodControls(p);
        long from=ExpansionModels.start(periodStart(),ZoneId.systemDefault()),to=ExpansionModels.start(periodEnd(),ZoneId.systemDefault());
        ExpansionModels.HealthRecord night="sleep".equals(metric)&&period==1?latestHealth("sleep",date):null;
        if(night!=null){from=night.data.optLong("enter")*1000;to=night.data.optLong("exit")*1000;}
        List<HealthChartView.Point> points="weight".equals(metric)?weightPoints(from,to):night!=null?sleepPoints(night,from,to):chartPoints(metric,from,to,period!=1);
        String value="—",caption=tx("За выбранный период записей нет");
        if(!points.isEmpty()){
            if("steps".equals(metric)||"calorie".equals(metric)){double total=0;for(HealthChartView.Point point:points)total=period==1?point.value:total+point.value;value=number(Math.round(total))+("calorie".equals(metric)?"":tx(" шагов"));caption=period==1?tx("Последнее сохранённое значение"):tx("За дни с записями");}
            else if("sleep".equals(metric)){if(night!=null){value=sleepDuration(night.data.optLong("asleepSeconds"));caption=shortTime(from)+" — "+clockTime(to);}else if(period!=1){double total=0;for(HealthChartView.Point point:points)total+=point.value;value=sleepDuration(Math.round(total/points.size()*3600));caption=tx("В среднем по сохранённым интервалам");}}
            else{HealthChartView.Point point=points.get(points.size()-1);value=format(point.value)+" "+unit(metric);caption=("spo2".equals(metric)?tx("Последнее сохранённое измерение · "):tx("Последнее измерение · "))+shortTime(point.time);}
        }
        TextView mainValue=ui.label(value,30,ui.text);mainValue.setTypeface(Typeface.DEFAULT,Typeface.BOLD);p.addView(mainValue,ui.fillWrap(12,4));note(p,caption);
        if("steps".equals(metric)&&period==1&&day(date)!=null){ActivityHistoryEntry e=day(date);ProgressBar progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(prefs.stepsGoal());progress.setProgress(Math.min(prefs.stepsGoal(),e.steps));progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ui.accent));p.addView(progress,ui.fillWrap(4,4));note(p,tx("Цель: ")+number(prefs.stepsGoal())+tx(" шагов"));}
        if(!points.isEmpty()){
            HealthChartView.Style style="sleep".equals(metric)&&period==1?HealthChartView.Style.SLEEP:("steps".equals(metric)||"calorie".equals(metric)||"sleep".equals(metric))&&period!=1?HealthChartView.Style.BARS:("steps".equals(metric)||"calorie".equals(metric))?HealthChartView.Style.LINE:HealthChartView.Style.POINTS;
            if(period!=1&&("heart".equals(metric)||"spo2".equals(metric)))style=HealthChartView.Style.DAILY_RANGE;
            if(period==1&&("heart".equals(metric)||"spo2".equals(metric))){
                p.addView(ui.segmented(new String[]{tr("Весь день","Full day"),tr("Часы с записями","Recorded hours")},focusHealthWindow?1:0,index->{focusHealthWindow=index==1;host.redraw();}),ui.fillWrap(6,6));
                if(focusHealthWindow){long first=points.get(0).time,last=points.get(points.size()-1).time;long pad=Math.max(15*60*1000L,(last-first)/10);from=Math.max(from,first-pad);to=Math.min(to,last+pad);note(p,clockTime(from)+" — "+clockTime(to));}
            }
            LinearLayout graph=card(p);graph.addView(ui.heading(style==HealthChartView.Style.SLEEP?tx("Стадии сна"):style==HealthChartView.Style.DAILY_RANGE?tr("Диапазон по дням","Daily observed range"):tr("Сохранённые измерения","Saved measurements")),ui.fillWrap(0,8));
            if(style==HealthChartView.Style.SLEEP)sleepLegend(graph,points);
            HealthChartView chart=new HealthChartView(activity,ui,style,from,to,"sleep".equals(metric)&&period!=1?tx("ч"):unit(metric),points);TextView selection=ui.label(tx("Нажмите на график"),12,ui.muted);
            if(style==HealthChartView.Style.DAILY_RANGE){
                selection.setText(tr("Минимум — максимум сохранённых значений. Нажмите на день.","Observed minimum — maximum. Tap a day."));
                chart.onDaySelection(at->{date=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate();period=1;focusHealthWindow=true;host.redraw();});
            }else chart.onSelection(selection::setText);
            boolean singleStage=style==HealthChartView.Style.SLEEP&&points.stream().map(point->point.rawStage).distinct().count()==1;
            graph.addView(chart,new LinearLayout.LayoutParams(-1,ui.dp(style==HealthChartView.Style.SLEEP?(singleStage?100:150):220)));graph.addView(selection,ui.fillWrap(6,0));
            LinearLayout actual=ui.disclosure(graph,tr("Все сохранённые значения","All saved values"));
            if(style==HealthChartView.Style.DAILY_RANGE){
                java.util.Map<LocalDate,List<HealthChartView.Point>> groups=new java.util.LinkedHashMap<>();for(HealthChartView.Point point:points)groups.computeIfAbsent(Instant.ofEpochMilli(point.time).atZone(ZoneId.systemDefault()).toLocalDate(),ignored->new ArrayList<>()).add(point);
                for(java.util.Map.Entry<LocalDate,List<HealthChartView.Point>> entry:groups.entrySet()){double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;for(HealthChartView.Point point:entry.getValue()){low=Math.min(low,point.value);high=Math.max(high,point.value);}LocalDate day=entry.getKey();actual.addView(ui.compactRow(day.format(DateTimeFormatter.ofPattern("d MMM",displayLocale())),format(low)+" — "+format(high)+" "+unit(metric)+tr(" · наблюдений: "," · observations: ")+entry.getValue().size(),()->{date=day;period=1;focusHealthWindow=true;host.redraw();}),ui.fillWrap(3,3));}
            }else actual.addView(ui.label(chart.table(),13,ui.text));
            if(style==HealthChartView.Style.SLEEP)sleepSummary(p,points);
        }else note(p,tx("Выберите другую дату или обновите показатель с часов."));
        if(night!=null&&"PARTIAL".equals(night.pageState))note(p,tx("Часы передали часть записей сна."));
        if("heart".equals(metric))refreshAction(p,tx("Обновить пульс"),host::readHeart);
        else if("spo2".equals(metric))refreshAction(p,tx("Обновить SpO₂"),host::readSpo2);
        else if("sleep".equals(metric))refreshAction(p,tx("Обновить сон"),host::readSleep);
        else if("steps".equals(metric)||"calorie".equals(metric))refreshAction(p,tx("Обновить активность"),host::readActivity);
        if("spo2".equals(metric)){note(p,tx("Сохранённые измерения с часов. Автообновление каждые 5 минут; кнопка получает записи, а не запускает измерение."));for(ExpansionModels.HealthRecord record:health("spo2"))if(record.tsSeconds*1000L>=from&&record.tsSeconds*1000L<to&&"PARTIAL".equals(record.pageState)){note(p,tx("Получена часть записей SpO₂; полнота истории не подтверждена."));break;}}
        if("calorie".equals(metric))note(p,tx("Значение калорий передано часами. Единица измерения пока не подтверждена."));
    }
    private List<HealthChartView.Point> activityPoints(String metric,long from,long to,boolean daily){
        List<HealthChartView.Point> points=new ArrayList<>();
        List<ActivityHistoryEntry> values=daily?data.days:from==ExpansionModels.start(LocalDate.now(),ZoneId.systemDefault())?data.todayPoints:from==ExpansionModels.start(date,ZoneId.systemDefault())?data.points:data.latestPoints;
        for(ActivityHistoryEntry e:values){
            long at=daily?ExpansionModels.start(LocalDate.parse(e.phoneDay),ZoneId.of(e.phoneZoneId)):e.receivedAt;
            if(at<from||at>=to)continue;
            long end=daily?ExpansionModels.start(LocalDate.parse(e.phoneDay).plusDays(1),ZoneId.of(e.phoneZoneId)):at;
            int value="calorie".equals(metric)?e.calorieRaw:e.steps;
            points.add(new HealthChartView.Point(at,end,value,shortTime(e.receivedAt)+" · "+number(value)+("calorie".equals(metric)?"":tx(" шагов")),0,false));
        }
        points.sort(java.util.Comparator.comparingLong(v->v.time));
        if(!daily)for(int i=1;i<points.size();i++){HealthChartView.Point a=points.get(i-1),b=points.get(i);if(b.value<a.value)points.set(i,new HealthChartView.Point(b.time,b.end,b.value,b.label,0,true));}
        return points;
    }
    private List<HealthChartView.Point> chartPoints(String metric,long from,long to,boolean daily){
        if("steps".equals(metric)||"calorie".equals(metric))return activityPoints(metric,from,to,daily);
        List<HealthChartView.Point> points=new ArrayList<>();Map<LocalDate,ExpansionModels.HealthRecord> nights=new HashMap<>();
        for(ExpansionModels.HealthRecord r:health(metric)){
            if(!r.validity.startsWith("VALID"))continue;
            if("sleep".equals(metric)){
                if(daily){
                    long exit=r.data.optLong("exit")*1000;if(exit<from||exit>=to)continue;
                    LocalDate night=Instant.ofEpochMilli(exit).atZone(ZoneId.systemDefault()).toLocalDate();ExpansionModels.HealthRecord old=nights.get(night);
                    // Show one actual interval per end date rather than sum overlapping revisions into a fictional night.
                    if(old==null||r.data.optLong("exit")>old.data.optLong("exit")||r.data.optLong("exit")==old.data.optLong("exit")&&r.receivedAt>old.receivedAt)nights.put(night,r);
                }else points.addAll(sleepPoints(r,from,to));
            }else if(r.value!=null&&Double.isFinite(r.value)&&r.tsSeconds*1000>=from&&r.tsSeconds*1000<to){long at=r.tsSeconds*1000;points.add(new HealthChartView.Point(at,at,r.value,shortTime(at)+" · "+format(r.value)+" "+unit(metric),0,true));}
        }
        for(Map.Entry<LocalDate,ExpansionModels.HealthRecord> entry:nights.entrySet()){
            LocalDate day=entry.getKey();ExpansionModels.HealthRecord record=entry.getValue();long at=ExpansionModels.start(day,ZoneId.systemDefault()),end=ExpansionModels.start(day.plusDays(1),ZoneId.systemDefault());
            points.add(new HealthChartView.Point(at,end,record.data.optLong("asleepSeconds")/3600.0,day.format(DateTimeFormatter.ofPattern("d MMMM",displayLocale()))+" · "+sleepDuration(record.data.optLong("asleepSeconds"))+tx(" · сохранённый интервал"),0,false));
        }
        points.sort(java.util.Comparator.comparingLong(v->v.time));return points;
    }
    private List<HealthChartView.Point> weightPoints(long from,long to){
        List<HealthChartView.Point> points=new ArrayList<>();
        for(JSONObject r:data.manual){long at=r.optLong("timestamp");if("WEIGHT_KG".equals(r.optString("kind"))&&at>=from&&at<to&&!r.isNull("value")&&Double.isFinite(r.optDouble("value")))points.add(new HealthChartView.Point(at,at,r.optDouble("value"),shortTime(at)+" · "+format(r.optDouble("value"))+tx(" кг"),0,true));}
        points.sort(java.util.Comparator.comparingLong(v->v.time));return points;
    }
    private void sleepDetail(LinearLayout p,String id){
        ExpansionModels.HealthRecord r=healthById("sleep",id);if(r==null){note(p,tx("Запись недоступна."));return;}
        heading(p,tx("Сон"));long from=r.data.optLong("enter")*1000,to=r.data.optLong("exit")*1000;
        TextView value=ui.label(sleepDuration(r.data.optLong("asleepSeconds")),30,ui.text);p.addView(value,ui.fillWrap(12,4));note(p,shortTime(from)+" — "+clockTime(to));
        List<HealthChartView.Point> points=sleepPoints(r,from,to);LinearLayout graph=card(p);graph.addView(ui.heading(tx("Стадии сна")),ui.fillWrap(0,8));sleepLegend(graph,points);
        HealthChartView chart=new HealthChartView(activity,ui,HealthChartView.Style.SLEEP,from,to,tx("Стадии сна"),points);TextView selection=ui.label(tx("Нажмите на график"),12,ui.muted);chart.onSelection(selection::setText);graph.addView(chart,new LinearLayout.LayoutParams(-1,ui.dp(points.stream().map(point->point.rawStage).distinct().count()==1?100:150)));graph.addView(selection,ui.fillWrap(6,0));sleepSummary(p,points);
        refreshAction(p,tx("Обновить сон"),host::readSleep);
    }
    private List<ExpansionModels.HealthRecord> health(String dataset){return data.health.getOrDefault(dataset,Collections.emptyList());}
    private ExpansionModels.HealthRecord healthById(String dataset,String id){for(ExpansionModels.HealthRecord r:health(dataset))if(r.id.equals(id))return r;return null;}
    private ActivityHistoryEntry day(LocalDate selected){for(ActivityHistoryEntry r:data.days)if(selected.toString().equals(r.phoneDay))return r;return null;}
    private void workouts(LinearLayout p){
        heading(p,tx("Тренировки"));
        if(data.current!=null){LinearLayout current=card(p);ui.selectedCard(current);TextView name=ui.label(WorkoutCatalog.name(data.current),18,ui.text);current.addView(name);activeTimer=ui.label(WorkoutRecorder.duration(recorder.activeMillis(data.current)),30,ui.text);current.addView(activeTimer,ui.fillWrap(6,4));note(current,workoutState(data.current));primary(current,tx("Продолжить тренировку"),()->go(5,"active",""));}
        else primary(p,tx("Начать тренировку"),()->go(5,"catalog",""));
        heading(p,tx("Быстрый старт"));LinearLayout row=null;int count=0;
        for(WorkoutCatalog.Sport sport:WorkoutCatalog.ALL){if(!prefs.favoriteSports().contains(sport.id)||"rest".equals(sport.id))continue;
            if(count%2==0){row=new LinearLayout(activity);row.setBaselineAligned(false);p.addView(row,ui.fillWrap(0,8));}
            LinearLayout item=column();ui.card(item);item.setMinimumHeight(ui.dp(64));item.addView(ui.iconHeading(tx(sport.name),"strength".equals(sport.id)?UiIcons.Kind.DUMBBELL:UiIcons.Kind.ACTIVITY));item.addView(ui.label(tx("Начать занятие"),12,ui.accentInk),ui.fillWrap(4,0));ui.touchTarget(item);item.setOnClickListener(v->go(5,"prepare",sport.id));LinearLayout.LayoutParams lp=ui.weighted(1);if(count%2==1)lp.setMargins(ui.dp(10),0,0,0);row.addView(item,lp);count++;
        }
        if(count%2==1&&row!=null){LinearLayout.LayoutParams empty=ui.weighted(1);empty.height=0;empty.setMargins(ui.dp(8),0,0,0);row.addView(new View(activity),empty);}
        settingRow(p,tx("Выбрать другое занятие"),tx("Поиск и избранное"),()->go(5,"catalog",""));
        heading(p,tx("Силовые тренировки"));
        settingRow(p,tx("Дневник зала"),tx("Упражнения, подходы и веса"),()->go(5,"prepare","strength"));
        settingRow(p,tx("Прогресс в зале"),tx("Результаты по месяцам"),()->{date=LocalDate.now();go(5,"strength_progress","");refresh(false);});
        heading(p,tx("История"));int shown=0;
        for(JSONObject r:data.workouts){if(WorkoutRecorder.isActive(r))continue;settingRow(p,tx(WorkoutCatalog.name(r)),shortTime(r.optLong("startedAt"))+" · "+WorkoutRecorder.duration(r.optLong("elapsedActiveMillis"))+tx(" · телефон"),()->go(5,"workout",r.optString("id")));if(++shown==4)break;}
        if(shown==0)note(p,tx("Завершённые занятия появятся здесь."));
        settingRow(p,tx("Вся история"),tx("Занятия телефона и сохранённые записи часов"),()->{date=LocalDate.now();period=7;refresh(false);go(5,"workout_history","ALL");});
    }
    private void catalog(LinearLayout p){
        heading(p,tx("Выбрать занятие"));EditText query=input(p,tx("Поиск занятия"),"");LinearLayout results=column();results.setPadding(0,0,0,0);results.setBackgroundColor(ui.surface);p.addView(results);
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){renderCatalog(results,s.toString(),"");}public void afterTextChanged(Editable s){}});renderCatalog(results,"","");
    }
    private void renderCatalog(LinearLayout p,String query,String category){
        p.removeAllViews();List<WorkoutCatalog.Sport> found=new ArrayList<>(WorkoutCatalog.search(query,category));String needle=query.toLowerCase(java.util.Locale.ROOT).trim();for(WorkoutCatalog.Sport candidate:WorkoutCatalog.ALL)if(tx(candidate.name).toLowerCase(java.util.Locale.ROOT).contains(needle)&&!found.contains(candidate))found.add(candidate);if(found.isEmpty())note(p,tx("Ничего не найдено"));
        for(WorkoutCatalog.Sport sport:found){if("rest".equals(sport.id))continue;
            LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(ui.dp(12),ui.dp(5),ui.dp(4),ui.dp(5));ui.card(row);
            TextView name=ui.label(tx(sport.name),16,ui.text);name.setMinHeight(ui.dp(48));name.setGravity(Gravity.CENTER_VERTICAL);ui.touchTarget(name);name.setOnClickListener(v->go(5,"prepare",sport.id));row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            CheckBox favorite=new CheckBox(ui.dialogContext());favorite.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.accent));favorite.setChecked(prefs.favoriteSports().contains(sport.id));favorite.setContentDescription(tx(sport.name)+tx(": избранное"));favorite.setMinHeight(ui.dp(48));favorite.setOnCheckedChangeListener((b,on)->{Set<String> favorites=prefs.favoriteSports();if(on)favorites.add(sport.id);else favorites.remove(sport.id);prefs.favoriteSports(favorites);event("favorite_save");});row.addView(favorite);p.addView(row,ui.fillWrap(0,7));
        }
    }
    private void prepare(LinearLayout p,String argument){
        String[] parts=argument.split("\\|",2);String sport=parts[0],plan=parts.length>1?parts[1]:null;WorkoutCatalog.Sport s=WorkoutCatalog.find(sport);if(s==null||"rest".equals(sport)){note(p,tx("Выберите занятие."));return;}
        heading(p,tx(s.name));
        TextView zero=ui.label("00:00:00",44,ui.text);zero.setGravity(Gravity.CENTER);p.addView(zero,ui.fillWrap(28,16));
        LinearLayout options=column();EditText goal=field(options,tx("Цель, минут"),tx("Без цели"),"");goal.setInputType(InputType.TYPE_CLASS_NUMBER);
        goal.setTag("draft.prepare.goal");
        CheckBox gps=s.gps?check(options,tx("Записать маршрут телефоном"),false):null;
        if(gps!=null)gps.setTag("draft.prepare.gps");
        Button start=primary(p,tx("Начать"),()->{try{
            if(data.current!=null){go(5,"active","");return;}
            String value=goal.getText().toString().trim();Integer minutes=value.isEmpty()?null:Integer.valueOf(value);if(minutes!=null&&(minutes<1||minutes>1440))throw new IllegalArgumentException("WORKOUT_GOAL");pendingSport=sport;pendingGoal=minutes;pendingPlan=plan;
            if(gps!=null&&gps.isChecked()&&!hasLocation()){ArrayList<String> permissions=new ArrayList<>(Arrays.asList(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION));if(Build.VERSION.SDK_INT>=33)permissions.add(Manifest.permission.POST_NOTIFICATIONS);activity.requestPermissions(permissions.toArray(new String[0]),GPS_PERMISSION);}else startWorkout(gps!=null&&gps.isChecked());
        }catch(RuntimeException e){goal.setError(tx("От 1 до 1440 минут или пустое поле"));}});
        start.setTextSize(24);start.setMinHeight(ui.dp(80));
        note(p,tx("Телефон сохраняет занятие. Шаги и пульс добавляются при получении данных с часов."));
        quiet(p,tx("Настройки занятия"),()->options.setVisibility(options.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));options.setVisibility(View.GONE);p.addView(options,ui.fillWrap(4,8));
        options.setTag("draft.prepare.options");
    }
    void permissionResult(int request){if(request!=GPS_PERMISSION||pendingSport==null)return;if(hasLocation())startWorkout(true);else confirm(tx("Доступ к GPS не получен"),tx("Начать выбранное занятие с таймером и кругами без маршрута?"),()->startWorkout(false));}
    private boolean hasLocation(){return activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private void startWorkout(boolean gps){String sport=pendingSport,plan=pendingPlan;Integer goal=pendingGoal;if(sport==null)return;pendingSport=null;run("workout_start",()->{JSONObject r=recorder.start(sport,goal,gps,plan);prefs.recentSport(sport);return r;},r->{host.clearWorkoutDraft();if(gps)gps(r,PhoneWorkoutService.START);stack(5).clear();go(5,"active","");});}
    private void active(LinearLayout p){
        if(data.current==null){heading(p,tx("Нет текущего занятия"));primary(p,tx("Выбрать занятие"),()->go(5,"catalog",""));return;}
        JSONObject r=data.current;heading(p,tx(WorkoutCatalog.name(r)));
        LinearLayout timer=card(p);ui.selectedCard(timer);note(timer,workoutState(r));activeTimer=ui.label(WorkoutRecorder.duration(recorder.activeMillis(r)),40,ui.text);activeTimer.setTypeface(Typeface.DEFAULT,Typeface.BOLD);timer.addView(activeTimer,ui.fillWrap(2,8));
        if("RECOVERY_REQUIRED".equals(r.optString("status")))note(p,tx("Запись прервалась после перезапуска телефона. Продолжите с этого момента или сохраните записанное время."));
        LinearLayout metrics=column();workoutMetrics(metrics,r);metrics.setVisibility(View.GONE);quiet(p,tx("Показатели занятия"),()->metrics.setVisibility(metrics.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));p.addView(metrics,ui.fillWrap(0,4));
        if(!r.isNull("distanceMeters"))note(p,tx("GPS-дистанция: ")+distance(r.optDouble("distanceMeters")));else if(r.optBoolean("gpsRequested"))note(p,tx("GPS: ожидание координат"));
        if(r.optInt("lapCount")>0)note(p,tx("Кругов: ")+r.optInt("lapCount"));
        String id=r.optString("id");boolean running="RUNNING".equals(r.optString("status"));
        LinearLayout controls=new LinearLayout(activity);controls.setBaselineAligned(false);
        Button pause=button(running?tx("Пауза"):tx("Продолжить"),()->strengthUi.flush(()->run(running?"pause":"resume",()->running?recorder.pause(id):recorder.resume(id),result->{if(result.optBoolean("gpsRequested")){if(running)stopGps();else gps(result,PhoneWorkoutService.START);}refresh(false);})));controls.addView(pause,ui.weighted(1));
        Button stop=button(tx("Завершить"),()->finishDialog(r));stop.setTextColor(ui.accentInk);LinearLayout.LayoutParams stopParams=ui.weighted(1);stopParams.setMargins(ui.dp(8),0,0,0);controls.addView(stop,stopParams);p.addView(controls,ui.fillWrap(4,16));
        if(running&&!"strength".equals(r.optString("typeId")))quiet(p,tx("Отметить круг"),()->run("lap",()->recorder.lap(id),result->refresh(false)));
        if("strength".equals(r.optString("typeId")))strengthUi.build(p,ui,r,data.strengthEntries.getOrDefault(id,Collections.emptyList()),data.strengthSets);
    }
    private String workoutState(JSONObject workout){return "RUNNING".equals(workout.optString("status"))?tx("Запись телефоном"):"PAUSED".equals(workout.optString("status"))?tx("На паузе"):"SAVED".equals(workout.optString("status"))?tx("Сохранено на телефоне"):tx("Запись прервана");}
    private void finishDialog(JSONObject r){
        ui.dialogBuilder().setTitle(tx("Завершить тренировку?"))
            .setMessage(tx("Время и выполненные подходы сохранятся в истории."))
            .setNegativeButton(tx("Продолжить занятие"),null).setPositiveButton(tx("Завершить"),(dialog,which)->strengthUi.flush(()->run("workout_finish",()->recorder.finish(r.optString("id"),r.optString("note")),saved->{stopGps();stack(5).clear();go(5,"workout",saved.optString("id"));}))).show();
    }
    private void gps(JSONObject workout,String action){try{Intent intent=new Intent(activity,PhoneWorkoutService.class).setAction(action).putExtra("workoutId",workout.optString("id"));if(Build.VERSION.SDK_INT>=26)activity.startForegroundService(intent);else activity.startService(intent);}catch(SecurityException|IllegalStateException e){String id=workout.optString("id");store.execute(()->{try{recorder.gpsState(id,"PARTIAL_FOREGROUND_REJECTED");}catch(RuntimeException error){logFailure("gps_start",error);}});error(tx("GPS не запущен. Таймер сохранён; маршрут неполон."),e);}}
    private void stopGps(){try{activity.stopService(new Intent(activity,PhoneWorkoutService.class));}catch(RuntimeException e){logFailure("gps_stop",e);}}
    private void workoutHistory(LinearLayout p,String source){
        heading(p,tx("История тренировок"));dateControls(p);periodControls(p);
        String selection=source.isEmpty()?"ALL":source.split(":")[0];LinearLayout sources=new LinearLayout(activity);
        String[] keys={"ALL","PHONE","WATCH"},names={tx("Все"),tx("Телефон"),tx("Часы")};
        p.addView(ui.segmented(names,java.util.Arrays.asList(keys).indexOf(selection),index->{stack(5).removeLast();go(5,"workout_history",keys[index]);}),ui.fillWrap(4,10));
        long from=ExpansionModels.start(periodStart(),ZoneId.systemDefault()),to=ExpansionModels.start(periodEnd(),ZoneId.systemDefault());int count=0;
        if(!"WATCH".equals(selection))for(JSONObject r:data.workouts){long at=r.optLong("startedAt");if(at<from||at>=to)continue;settingRow(p,tx(WorkoutCatalog.name(r)),shortTime(at)+" · "+WorkoutRecorder.duration(r.optLong("elapsedActiveMillis"))+tx(" · телефон"),()->go(5,WorkoutRecorder.isActive(r)?"active":"workout",r.optString("id")));count++;}
        if(!"PHONE".equals(selection))for(ExpansionModels.HealthRecord r:health("sports")){if(!r.validity.startsWith("VALID"))continue;long at=r.data.optLong("startTime",r.tsSeconds)*1000;if(at<from||at>=to)continue;settingRow(p,watchWorkoutName(r.data.optInt("workoutType")),shortTime(at)+tx(" · часы"),()->go(5,"watch_workout",r.id));count++;}
        if(count==0)note(p,tx("За этот период занятий нет."));
        if(!"PHONE".equals(selection)){if(DeviceFeatureGate.sportsAllowed())action(p,tx("Обновить историю часов"),host::readSports);else note(p,tx("Получение новых тренировок с часов пока недоступно."));}
    }
    private void workoutDetail(LinearLayout p,String id){
        JSONObject r=find(data.workouts,id);if(r==null){note(p,tx("Запись не найдена."));return;}
        heading(p,tx(WorkoutCatalog.name(r)));note(p,shortTime(r.optLong("startedAt"))+tx(" · запись телефоном"));
        TextView duration=ui.label(WorkoutRecorder.duration(r.optLong("elapsedActiveMillis")),36,ui.text);duration.setTypeface(Typeface.DEFAULT,Typeface.BOLD);p.addView(duration,ui.fillWrap(14,4));note(p,tx("Время занятия"));
        if(r.optLong("accumulatedPauseMillis")>0)note(p,tx("На паузе: ")+WorkoutRecorder.duration(r.optLong("accumulatedPauseMillis")));
        workoutMetrics(p,r);
        if("strength".equals(r.optString("typeId")))strengthUi.history(p,ui,data.strengthEntries.getOrDefault(id,Collections.emptyList()),data.strengthSets);
        workoutHeartChart(p,r,data.workoutObservations.getOrDefault(id,Collections.emptyList()));
        if(!r.isNull("distanceMeters"))note(p,tx("Маршрут телефона: ")+distance(r.optDouble("distanceMeters")));
        if(r.optBoolean("recoveryGapUnknown"))note(p,tx("Перерыв после перезапуска телефона не включён в длительность."));
        if(!r.optString("note").isEmpty())note(p,r.optString("note"));
        if(r.optInt("lapCount")>0)action(p,tx("Круги"),()->run("workout_laps",()->store.children("workout_laps",id),laps->{StringBuilder text=new StringBuilder();for(JSONObject lap:laps)text.append(lap.optInt("number")).append(" · ").append(WorkoutRecorder.duration(lap.optLong("intervalMillis"))).append('\n');message(tx("Круги"),text.toString());}));
        quiet(p,tx("Изменить заметку"),()->{LinearLayout form=column();EditText input=field(form,tx("Заметка"),tx("Необязательно"),r.optString("note"));editDialog(tx("Заметка"),form,()->run("workout_note",()->{recorder.note(id,ExpansionModels.text(input.getText().toString(),2000));return true;},ok->refresh(false)));});
        if(ExpansionModels.PHONE.equals(r.optString("source"))&&"SAVED".equals(r.optString("status")))quiet(p,tx("Удалить тренировку навсегда"),()->confirm(tx("Удалить тренировку?"),tx("Запись, подходы, круги и маршрут будут удалены с телефона без возможности возврата. Связанные разборы ИИ тоже будут удалены."),()->run("phone_workout_delete",()->{store.deletePhoneWorkout(id,r.optLong("startedAt"));return true;},ok->{analysisUi.invalidate();data=new Data();stack(5).clear();go(5,"workout_history","ALL");})));
    }
    private void workoutMetrics(LinearLayout p,JSONObject workout){
        LinearLayout values=card(p);values.addView(ui.section(tx("Показатели занятия")));
        note(values,workout.isNull("stepsObserved")?tx("Шаги · пока нет данных"):tx("Шаги · ")+number(workout.optLong("stepsObserved")));
        int samples=workout.optInt("heartSampleCount");
        note(values,samples==0?tx("Пульс · пока нет измерений"):tx("Пульс · средний ")+format(workout.optDouble("heartAverage"))+tx(" · максимальный ")+workout.optInt("heartMax")+tx(" уд/мин"));
        if(samples>0)note(values,tx("Измерений пульса: ")+samples);
        long coverage=workout.optLong("stepsCoverageMillis");
        if(coverage>0)note(values,tx("Интервалы с данными шагов: ")+WorkoutRecorder.duration(coverage));
        if(workout.optBoolean("metricLimitReached"))note(values,tx("Достигнут предел записей показателей. Таймер занятия продолжает сохраняться."));
        note(values,tx("Учитываются полученные данные внутри занятия. Перерывы в связи могут оставить пропуски."));
    }
    private void workoutHeartChart(LinearLayout p,JSONObject workout,List<JSONObject> observations){
        List<HealthChartView.Point> points=new ArrayList<>();String lastSegment="";
        for(JSONObject row:observations){if(!"HEART".equals(row.optString("kind"))||!"VALID".equals(row.optString("quality")))continue;long at=row.optLong("measuredAt");double value=row.optDouble("value",Double.NaN);if(at<=0||!Double.isFinite(value))continue;String segment=row.optString("segmentId");points.add(new HealthChartView.Point(at,at,value,clockTime(at)+" · "+format(value)+tx(" уд/мин"),0,!lastSegment.isEmpty()&&!lastSegment.equals(segment)));lastSegment=segment;}
        points.sort(java.util.Comparator.comparingLong(point->point.time));if(points.isEmpty())return;
        LinearLayout panel=card(p);panel.addView(ui.section(tx("Пульс во время занятия")));
        HealthChartView chart=new HealthChartView(activity,ui,HealthChartView.Style.POINTS,workout.optLong("startedAt"),Math.max(workout.optLong("endedAt"),points.get(points.size()-1).time+1),tx("уд/мин"),points);panel.addView(chart,new LinearLayout.LayoutParams(-1,ui.dp(210)));note(panel,tx("Каждая точка — сохранённое измерение. Между точками данные не восстанавливаются."));
    }
    private void strengthProgress(LinearLayout p){
        heading(p,tx("Прогресс в зале"));LinearLayout navigation=new LinearLayout(activity);
        navigation.addView(button("‹",()->{date=date.minusMonths(1).withDayOfMonth(1);refresh(false);}),new LinearLayout.LayoutParams(ui.dp(48),-2));
        TextView month=ui.label(date.format(DateTimeFormatter.ofPattern("LLLL yyyy",displayLocale())),18,ui.text);month.setGravity(Gravity.CENTER);month.setMinimumHeight(ui.dp(48));navigation.addView(month,ui.weightedGap(1,8,8));
        Button next=button("›",()->{date=date.plusMonths(1).withDayOfMonth(1);refresh(false);});next.setEnabled(java.time.YearMonth.from(date).isBefore(java.time.YearMonth.now()));navigation.addView(next,new LinearLayout.LayoutParams(ui.dp(48),-2));p.addView(navigation,ui.fillWrap(5,12));
        JSONObject report=data.strengthMonth;if(report==null){note(p,tx("Загружаем записи…"));return;}
        if(java.time.YearMonth.from(date).equals(java.time.YearMonth.now()))note(p,tx("Месяц ещё идёт. Сравнение — за одинаковую прошедшую часть месяцев."));
        LinearLayout totals=card(p);totals.addView(ui.section(tx("Выполненная работа")));note(totals,tx("Тренировок: ")+report.optInt("workouts")+tx(" · рабочих подходов: ")+report.optInt("workingSets"));note(totals,tx("Время занятий: ")+WorkoutRecorder.duration(report.optLong("activeMillis")));
        JSONObject groups=report.optJSONObject("groups");if(groups!=null&&groups.length()>0){LinearLayout distribution=card(p);distribution.addView(ui.section(tx("Рабочие подходы по группам")));List<String> names=new ArrayList<>();java.util.Iterator<String> iterator=groups.keys();int maximum=1;while(iterator.hasNext()){String name=iterator.next();names.add(name);maximum=Math.max(maximum,groups.optInt(name));}Collections.sort(names);for(String name:names){int count=groups.optInt(name);note(distribution,(name.isEmpty()?tx("Без группы"):name)+" · "+count);ProgressBar bar=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(maximum);bar.setProgress(count);bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ui.accent));distribution.addView(bar,new LinearLayout.LayoutParams(-1,ui.dp(10)));}}
        JSONArray exercises=report.optJSONArray("exercises");if(exercises==null||exercises.length()==0){note(p,tx("Запишите выполненные подходы: здесь появится сравнение по упражнениям."));return;}
        for(int i=0;i<exercises.length();i++){JSONObject exercise=exercises.optJSONObject(i);if(exercise==null)continue;LinearLayout panel=card(p);panel.addView(ui.section(progressExerciseName(exercise)));note(panel,tx("Рабочих подходов: ")+exercise.optInt("sets")+tx(" · повторений: ")+exercise.optInt("reps"));JSONObject best=exercise.optJSONObject("bestByReps"),previous=exercise.optJSONObject("previousMonthBestByReps");if(best==null||best.length()==0){note(panel,tx("Недостаточно сопоставимых данных по нагрузке."));continue;}List<Integer> reps=new ArrayList<>();java.util.Iterator<String> keys=best.keys();while(keys.hasNext()){try{reps.add(Integer.valueOf(keys.next()));}catch(NumberFormatException ignored){/* Invalid summary keys are not displayed. */}}Collections.sort(reps);for(int repetitions:reps){String key=Integer.toString(repetitions);double weight=best.optLong(key)/1000.0;String text=repetitions+tx(" повторений · ")+format(weight)+tx(" кг");if(previous!=null&&!previous.isNull(key)){double before=previous.optLong(key)/1000.0;text+=tx(" · раньше ")+format(before)+tx(" кг");}note(panel,text);}if(previous==null||previous.length()==0)note(panel,tx("За предыдущий месяц сопоставимых подходов нет."));if("ASSISTANCE".equals(exercise.optString("loadMode")))note(panel,tx("При одинаковых повторах меньшая помощь тренажёра означает большую нагрузку."));note(panel,tx("Сравнение одинакового упражнения и оборудования при одинаковых повторах."));}
    }
    private void watchWorkout(LinearLayout p,String id){
        ExpansionModels.HealthRecord r=healthById("sports",id);if(r==null||!r.validity.startsWith("VALID")){note(p,tx("Подробности этой записи пока недоступны."));return;}
        heading(p,watchWorkoutName(r.data.optInt("workoutType")));long from=r.data.optLong("startTime")*1000,to=r.data.optLong("endTime")*1000;
        note(p,shortTime(from)+tx(" · запись часов"));TextView duration=ui.label(WorkoutRecorder.duration(Math.max(0,to-from)),36,ui.text);duration.setTypeface(Typeface.DEFAULT,Typeface.BOLD);p.addView(duration,ui.fillWrap(14,4));note(p,tx("Интервал от начала до окончания"));note(p,tx("Подробные показатели этой записи пока недоступны."));
        quiet(p,tx("Удалить запись с телефона"),()->confirm(tx("Удалить локальную запись часов?"),tx("Копия и её подробности будут удалены с телефона без возможности возврата и не появятся при синхронизации. Запись на часах останется. Связанные разборы ИИ тоже будут удалены."),()->run("watch_workout_delete",()->{store.deleteWatchWorkout(r.device,r.id,r.data.optLong("recordId"),r.data.optLong("startTime"),r.rawSha);return true;},ok->{analysisUi.invalidate();data=new Data();stack(5).clear();go(5,"workout_history","ALL");})));
    }
    private void profile(LinearLayout p){
        heading(p,tx("Профиль и цели"));
        LinearLayout personal=card(p);personal.addView(ui.section(tx("Профиль")),ui.fillWrap(0,8));EditText name=field(personal,tx("Имя"),tx("Необязательно"),prefs.profileName());
        LinearLayout goals=card(p);goals.addView(ui.section(tx("Ежедневные цели")),ui.fillWrap(0,8));
        EditText steps=field(goals,tx("Шаги в день"),tx("Шагов"),Integer.toString(prefs.stepsGoal()));steps.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText sleep=field(goals,tx("Сон, минут за ночь"),"",Integer.toString(prefs.sleepGoalMinutes()));sleep.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText schedule=field(goals,tx("Время отхода ко сну"),tx("ЧЧ:ММ"),prefs.sleepSchedule());schedule.setInputType(InputType.TYPE_CLASS_DATETIME|InputType.TYPE_DATETIME_VARIATION_TIME);
        LinearLayout units=card(p);units.addView(ui.section(tx("Единицы")),ui.fillWrap(0,6));CheckBox miles=check(units,tx("Дистанция в милях"), "mi".equals(prefs.distanceUnit()));
        name.setTag("draft.profile.name");steps.setTag("draft.profile.steps");sleep.setTag("draft.profile.sleep");schedule.setTag("draft.profile.schedule");miles.setTag("draft.profile.miles");
        primary(p,tx("Сохранить"),()->{
            name.setError(null);steps.setError(null);sleep.setError(null);schedule.setError(null);
            String n=name.getText().toString().trim(),bedtime=null;Integer stepGoal=null,sleepGoal=null;boolean valid=true;
            if(n.length()>80){name.setError(tr("Не более 80 символов","Up to 80 characters"));valid=false;}
            try{stepGoal=Integer.valueOf(steps.getText().toString().trim());if(stepGoal<100||stepGoal>200000)throw new IllegalArgumentException();}catch(RuntimeException e){steps.setError(tr("От 100 до 200 000 шагов","100 to 200,000 steps"));valid=false;}
            try{sleepGoal=Integer.valueOf(sleep.getText().toString().trim());if(sleepGoal<60||sleepGoal>1440)throw new IllegalArgumentException();}catch(RuntimeException e){sleep.setError(tr("От 60 до 1440 минут","60 to 1440 minutes"));valid=false;}
            try{String value=schedule.getText().toString().trim();if(!value.matches("[0-9]{2}:[0-9]{2}"))throw new IllegalArgumentException();bedtime=LocalTime.parse(value).toString();}catch(RuntimeException e){schedule.setError(tr("Время в формате ЧЧ:ММ","Use HH:MM time"));valid=false;}
            if(!valid)return;
            prefs.profileName(n);prefs.stepsGoal(stepGoal);prefs.sleepGoalMinutes(sleepGoal);prefs.sleepSchedule(bedtime);prefs.distanceUnit(miles.isChecked()?"mi":"km");event("profile_save");android.widget.Toast.makeText(activity,tr("Профиль сохранён","Profile saved"),android.widget.Toast.LENGTH_SHORT).show();
        });
    }
    private void privacy(LinearLayout p){
        heading(p,tx("Данные и приватность"));note(p,tx("Показатели хранятся на телефоне. Экспорт создаёт файл, которым вы можете поделиться."));
        primary(p,tx("Экспортировать показатели"),()->run("measurements_export",()->new ReportExporter(activity,store,activityStore).measurements(),file->host.share(file,"application/json")));
        Button remove=action(p,deletingMeasurements?tx("Удаление…"):tx("Удалить все показатели"),()->{
            if(measurementsBusy())return;
            confirm(tx("Удалить все показатели?"),tx("Будут удалены шаги, пульс, сон, SpO₂ и прежние ручные показатели, их исходные ответы и отчёты в приложении. Старые записи не восстановятся автоматически. Тренировки телефона, будильники и циферблаты сохранятся. Копии вне приложения останутся у получателей."),()->{
                if(measurementsBusy()){android.widget.Toast.makeText(activity,tx("Дождитесь обновления"),android.widget.Toast.LENGTH_SHORT).show();return;}
                analysisUi.invalidate();deletingMeasurements=true;host.redraw();
                run("measurements_delete",()->{long cutoff=System.currentTimeMillis();store.clearMeasurements(cutoff);activityStore.clearMeasurements(cutoff);try{ReportExporter.removeOwnedMeasurements(activity);return true;}catch(java.io.IOException e){logFailure("measurements_delete_files",new IllegalStateException(e));return false;}},ok->{deletingMeasurements=false;readFeedback="";data=new Data();refresh(false);if(ok)android.widget.Toast.makeText(activity,tx("Показатели удалены"),android.widget.Toast.LENGTH_SHORT).show();else message(tx("Удалена часть данных"),tx("Не все исходные файлы удалось удалить. Повторите удаление, чтобы завершить его."));});
            });
        });remove.setEnabled(!measurementsBusy());if(readInProgress)note(p,tx("Дождитесь обновления, прежде чем удалять показатели."));
    }
    private void alarms(LinearLayout p){
        heading(p,tx("Будильники"));note(p,DeviceFeatureGate.alarmWriteAllowed()?tx("Время и повторение можно отправить на часы. Название хранится в приложении."):tx("Сохранены в приложении. Отправка на часы пока недоступна."));
        if(DeviceFeatureGate.alarmReadAllowed())action(p,tx("Получить расписание часов"),host::readAlarms);
        for(JSONObject draft:data.alarms){
            LinearLayout c=card(p),top=new LinearLayout(activity);top.setGravity(Gravity.CENTER_VERTICAL);
            TextView time=ui.label(AlarmEditorUi.time(draft),30,ui.text);time.setTypeface(Typeface.DEFAULT,Typeface.BOLD);top.addView(time,new LinearLayout.LayoutParams(0,-2,1));
            Switch enabled=new Switch(ui.dialogContext());enabled.setChecked(draft.optBoolean("enabled"));enabled.setText("");enabled.setContentDescription(tx("Включён в приложении: ")+AlarmEditorUi.time(draft));enabled.setMinHeight(ui.dp(48));ui.toggle(enabled);enabled.setOnCheckedChangeListener((b,on)->{JSONObject changed=ExpansionModels.copy(draft);ExpansionModels.put(changed,"enabled",on);saveAlarm(changed);});top.addView(enabled);c.addView(top);
            if(!draft.optString("label").isEmpty())c.addView(ui.label(draft.optString("label"),15,ui.text),ui.fillWrap(4,2));
            LinearLayout bottom=new LinearLayout(activity);bottom.setGravity(Gravity.CENTER_VERTICAL);TextView repeat=ui.label(AlarmEditorUi.repeat(draft,"en".equals(prefs.language())),13,ui.muted);bottom.addView(repeat,new LinearLayout.LayoutParams(0,-2,1));
            Button remove=button(tx("Удалить"),()->removeAlarm(draft));remove.setBackgroundColor(android.graphics.Color.TRANSPARENT);remove.setTextColor(ui.muted);bottom.addView(remove);c.addView(bottom);
            String transfer=data.alarmTransfers.getOrDefault(draft.optString("id"),"");
            if("PENDING".equals(transfer))note(c,tx("Отправка не завершена. Проверьте результат перед новым изменением."));
            if("STOPPED_UNCERTAIN".equals(transfer))note(c,tx("Результат отправки не подтверждён. Проверьте часы перед новым изменением."));
            AlarmTransferPlan.Action action=draft.optJSONObject("watchBinding")==null?AlarmTransferPlan.Action.CREATE:AlarmTransferPlan.Action.UPDATE;
            if(DeviceFeatureGate.alarmWriteAllowed(action)){Button send=action(c,action==AlarmTransferPlan.Action.CREATE?tx("Отправить на часы"):tx("Обновить на часах"),()->prepareAlarmTransfer(draft,action));send.setEnabled(transfer.isEmpty());}
            remove.setEnabled(transfer.isEmpty());ui.touchTarget(c);c.setOnClickListener(v->AlarmEditorUi.edit(activity,ui,draft,this::saveAlarm));
        }
        if(data.alarms.isEmpty())note(p,tx("Добавьте время и дни повторения."));primary(p,tx("Добавить будильник"),()->AlarmEditorUi.edit(activity,ui,null,this::saveAlarm));
    }
    private void saveAlarm(JSONObject draft){run("alarm_save",()->{AlarmEditorUi.validate(draft);store.saveAlarmDraft(draft);return true;},ok->refresh(false));}
    private void removeAlarm(JSONObject draft){
        if(draft.optJSONObject("watchBinding")!=null&&DeviceFeatureGate.alarmWriteAllowed(AlarmTransferPlan.Action.DELETE)){prepareAlarmTransfer(draft,AlarmTransferPlan.Action.DELETE);return;}
        confirm(tx("Удалить будильник из приложения?"),AlarmEditorUi.time(draft)+(draft.optJSONObject("watchBinding")==null?"":tx(". Расписание часов сохранится.")),()->run("alarm_delete",()->{store.remove("alarm_drafts",draft.optString("id"));return true;},ok->refresh(false)));
    }
    private void prepareAlarmTransfer(JSONObject draft,AlarmTransferPlan.Action action){
        if(!DeviceFeatureGate.alarmWriteAllowed(action))return;
        if(!host.ready()){android.widget.Toast.makeText(activity,tx("Подключите часы в Настройках"),android.widget.Toast.LENGTH_SHORT).show();return;}
        run("alarm_preview",()->{
            JSONObject saved=store.find("alarm_drafts",draft.optString("id"));AlarmProtocol.Snapshot snapshot=AlarmProtocol.savedSnapshot(activity,host.device());
            if(saved==null)throw new IllegalArgumentException("ALARM_DRAFT_MISSING");
            if(action==AlarmTransferPlan.Action.CREATE)return AlarmTransferPlan.create(snapshot,saved);
            if(action==AlarmTransferPlan.Action.UPDATE)return AlarmTransferPlan.update(snapshot,saved);
            if(action==AlarmTransferPlan.Action.DELETE)return AlarmTransferPlan.delete(snapshot,saved);
            throw new IllegalArgumentException("ALARM_ACTION_UI");
        },plan->confirm(tx("Передать изменение на часы?"),plan.preview(),()->host.writeAlarm(plan)));
    }
    private void journal(LinearLayout p){heading(p,tx("Диагностика"));primary(p,tx("Экспортировать диагностику"),host::exportDiagnostics);}
    void documentResult(int request,Intent result){documentResult(request,Activity.RESULT_OK,result);}
    void documentResult(int request,int result,Intent intent){if(request==ANALYSIS_EXPORT)analysisUi.documentResult(request,result,intent);else nutritionUi.documentResult(request,result,intent);}
    void activityFinished(boolean explicit,boolean ok){if(explicit){readFeedback=ok?tx("Активность обновлена."):tx("Не удалось обновить. Сохранённые данные доступны.");readInProgress=false;}refresh(false);}
    void readFeedback(String text,boolean pending){readFeedback=text;readInProgress=pending;host.redraw();}
    boolean measurementsBusy(){return readInProgress||deletingMeasurements;}
    private void refreshAction(LinearLayout p,String name,Runnable request){Button button=primary(p,readInProgress?tx("Обновление…"):name,request);button.setEnabled(!measurementsBusy());}
    void acceptFreshHealthResult(TsdbReadProtocol.Result result,boolean explicit,long requestedAt){
        if(result==null||result.evidenceFile==null||"FAILED".equals(result.state)){if(explicit)readFeedback(result!=null&&"SLEEP_PAGE".equals(result.profile)?sleepReadFeedback(result,"en".equals(prefs.language())):tx("Не удалось обновить. Сохранённые данные доступны."),false);refresh(false);return;}
        store.submit(()->{
            // A replayed service callback cannot lift deletion suppression; an explicit new request can import its exact file.
            boolean suppressed=store.healthImportSuppressed();if(suppressed&&!explicit)return -1;
            long cutoff=store.healthDeletionCutoff();if(result.receivedAt<=cutoff||explicit&&requestedAt<=cutoff)return -1;
            if(!result.evidenceFile.isFile())return -1;
            {
                importer.importFile(result.evidenceFile,explicit?new HealthEvidenceImporter.FreshRequest(result.profile,result.deviceAddress,requestedAt):null);int usable=0;String dataset="HEART_LAST".equals(result.profile)?"heart":"SLEEP_PAGE".equals(result.profile)?"sleep":"SPO2_PAGE".equals(result.profile)?"spo2":"sports";
                for(ExpansionModels.HealthRecord record:store.healthForEvidence(result.deviceAddress,dataset,result.eventId))if(result.eventId.equals(record.evidenceId)&&record.validity.startsWith("VALID"))usable++;
                return usable;
            }
        },(usable,error)->{if(destroyed)return;if(explicit){if(!error.isEmpty())readFeedback=tx("Не удалось сохранить новые данные. Прежние записи доступны.");else if("SLEEP_PAGE".equals(result.profile)&&result.sleepSummary!=null)readFeedback=sleepReadFeedback(result,"en".equals(prefs.language()));else if("SPO2_PAGE".equals(result.profile))readFeedback="PARTIAL".equals(result.state)?tx("Получена часть записей SpO₂. Сохранённые измерения доступны на графике."):usable!=null&&usable>0?tx("SpO₂ обновлён."):result.records.isEmpty()?tx("За последние 7 дней часы не вернули записей SpO₂."):tx("Записи получены, но подходящих измерений SpO₂ нет.");else readFeedback=usable!=null&&usable>0?("SLEEP_PAGE".equals(result.profile)&&"PARTIAL".equals(result.state)?tx("Сон сохранён. Получена часть записей."):tx("Показатель обновлён.")):tx("Новых измерений нет.");readInProgress=false;}refresh(false);});
    }
    static String sleepReadFeedback(TsdbReadProtocol.Result result){return sleepReadFeedback(result,false);}
    static String sleepReadFeedback(TsdbReadProtocol.Result result,boolean english){
        TsdbReadProtocol.SleepSummary summary=result.sleepSummary;
        if("FAILED".equals(result.state))return summary!=null&&summary.savedRecords>0
                ?UiText.choose(english,"Чтение остановлено. Полученные записи сна сохранены; можно посмотреть график.")
                :UiText.choose(english,"Не удалось обновить сон. Сохранённые данные доступны.");
        if(summary==null)return UiText.choose(english,"Сон сохранён.");
        if(!summary.historyComplete)return UiText.choose(english,"Получена часть данных сна. Сохранено записей: ")+summary.savedRecords+".";
        return summary.usableRecords>0?UiText.choose(english,"Сон обновлён. Сохранено записей: ")+summary.savedRecords+"."
                :summary.savedRecords>0?UiText.choose(english,"Данные получены, но их формат сна пока не поддерживается.")
                :UiText.choose(english,"За последние 7 дней часы не вернули записей сна.");
    }
    private <T> void run(String action,java.util.concurrent.Callable<T> work,Consumer<T> done){String op=journal.begin("local_ui",action,"LOCAL",device);journal.stage(op,"local_ui",action,"STARTED","PENDING","",0,0,0);long start=android.os.SystemClock.elapsedRealtime();store.submit(work,(value,error)->{journal.stage(op,"local_ui",action,error.isEmpty()?"COMPLETE":"FAILED",error.isEmpty()?"SUCCESS":"FAILED",error,1,0,android.os.SystemClock.elapsedRealtime()-start);if(destroyed)return;if(!error.isEmpty()){if("measurements_delete".equals(action))deletingMeasurements=false;refresh(false);message(tx("Действие не завершено"),tx("Не удалось выполнить действие. Попробуйте ещё раз."));return;}done.accept(value);refresh(false);});}
    private void logFailure(String action,RuntimeException e){String op=journal.begin("local_ui",action,"LOCAL","");journal.stage(op,"local_ui",action,"FAILED","FAILED",e.getClass().getSimpleName(),0,0,0);}
    private void error(String message,RuntimeException e){logFailure("ui_action",e);message(tx("Действие не завершено"),message);}
    private static boolean safeRouteArgument(String screen,String arg){if("nutrition_editor".equals(screen)||"food_search".equals(screen)||"analysis_history".equals(screen)){if(arg.isEmpty())return !"nutrition_editor".equals(screen);try{NutritionModels.id(arg);return true;}catch(RuntimeException e){return false;}}return arg.length()<=512;}
    private static boolean allowedRoute(int tab,String screen){
        if(tab==2)return Arrays.asList("metric","sleep","analysis","analysis_history").contains(screen);
        if(tab==6)return Arrays.asList("nutrition","nutrition_editor","food_search").contains(screen);
        if(tab==3)return "profile".equals(screen)||"cards".equals(screen)||"privacy".equals(screen)||"alarms".equals(screen)||"openrouter".equals(screen);
        if(tab==5)return Arrays.asList("catalog","prepare","active","workout_history","workout","watch_workout","strength_progress").contains(screen);
        return false;
    }
    private void dateControls(LinearLayout p){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
        Button previous=button("‹",()->{date=period==0?date.minusMonths(1):date.minusDays(period);event("date_previous");refresh(false);});previous.setContentDescription(tx("Предыдущий период"));
        Button select=button(date.format(DateTimeFormatter.ofPattern("d MMMM yyyy",displayLocale())),()->{
            DatePickerDialog picker=new DatePickerDialog(ui.dialogContext(),(dialog,year,month,day)->{date=LocalDate.of(year,month+1,day);event("date_select");refresh(false);},date.getYear(),date.getMonthValue()-1,date.getDayOfMonth());picker.getDatePicker().setMaxDate(System.currentTimeMillis());picker.show();ui.dialog(picker);
        });
        Button next=button("›",()->{LocalDate nextDate=period==0?date.plusMonths(1):date.plusDays(period);date=nextDate.isAfter(LocalDate.now())?LocalDate.now():nextDate;event("date_next");refresh(false);});next.setContentDescription(tx("Следующий период"));next.setEnabled(!date.equals(LocalDate.now())&&date.isBefore(LocalDate.now()));
        row.addView(previous,new LinearLayout.LayoutParams(ui.dp(48),-2));row.addView(select,ui.weightedGap(1,8,8));row.addView(next,new LinearLayout.LayoutParams(ui.dp(48),-2));p.addView(row,ui.fillWrap(4,6));
    }
    private void periodControls(LinearLayout p){
        p.addView(ui.segmented(new String[]{tx("День"),tx("Неделя"),tx("Месяц")},period==1?0:period==7?1:2,index->{period=new int[]{1,7,0}[index];event("period_select");refresh(false);}),ui.fillWrap(0,8));
    }
    private LinearLayout column(){LinearLayout p=new LinearLayout(activity);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(ui.dp(12),ui.dp(8),ui.dp(12),ui.dp(8));p.setBackgroundColor(ui.panel);return p;}
    private LinearLayout card(LinearLayout parent){LinearLayout c=column();ui.card(c);parent.addView(c,ui.fillWrap(6,10));return c;}
    private ScrollView scroll(LinearLayout content){ScrollView scroll=new ScrollView(activity);scroll.addView(content);return scroll;}
    private void heading(LinearLayout p,String text){p.addView(ui.heading(text),ui.fillWrap(4,10));}
    private java.util.Locale displayLocale(){return "en".equals(prefs.language())?java.util.Locale.ENGLISH:new java.util.Locale("ru");}
    private String watchWorkoutName(int code){
        if(!"en".equals(prefs.language()))return WorkoutTypeDictionary.name(code);
        String value=WorkoutTypeDictionary.all().get(code);if(value==null)return "Workout type "+code;
        int begin=value.lastIndexOf('('),end=value.lastIndexOf(')');
        String name=begin>=0&&end>begin?value.substring(begin+1,end).replaceAll("(?<=[a-z])(?=[A-Z])"," "):value;
        return name+" · code "+code;
    }
    private String progressExerciseName(JSONObject exercise){String name=exercise.optString("name"),key=exercise.optString("key");int separator=key.indexOf('|');String variant=separator<0?key:key.substring(0,separator);return StrengthCatalog.find(variant)==null?name:tx(name);}
    private String tx(String text){return UiText.choose("en".equals(prefs.language()),text);}
    private String tr(String ru,String en){return "en".equals(prefs.language())?en:ru;}
    private void note(LinearLayout p,String text){TextView label=ui.label(text,13,ui.muted);p.addView(label,ui.fillWrap(3,8));}
    private Button button(String text,Runnable clicked){Button b=new Button(ui.dialogContext());b.setAllCaps(false);b.setText(text);ui.buttonText(b,ui.text);b.setTextSize(14);b.setMinHeight(ui.dp(48));b.setMinimumHeight(ui.dp(48));b.setPadding(ui.dp(10),ui.dp(6),ui.dp(10),ui.dp(6));ui.outline(b);ui.touchTarget(b);b.setOnClickListener(v->clicked.run());return b;}
    private Button action(LinearLayout p,String text,Runnable clicked){Button b=button(text,clicked);p.addView(b,ui.fillWrap(4,6));return b;}
    private Button primary(LinearLayout p,String text,Runnable clicked){Button b=action(p,text,clicked);ui.accent(b);ui.buttonText(b,android.graphics.Color.WHITE);return b;}
    private Button quiet(LinearLayout p,String text,Runnable clicked){Button b=action(p,text,clicked);b.setBackgroundColor(android.graphics.Color.TRANSPARENT);b.setTextColor(ui.muted);return b;}
    private void settingRow(LinearLayout p,String name,String detail,Runnable clicked){p.addView(ui.compactRow(name,detail,clicked),ui.fillWrap(0,8));}
    private void settingRow(LinearLayout p,String name,String detail,UiIcons.Kind icon,boolean highlighted,Runnable clicked){p.addView(ui.settingRow(name,detail,icon,highlighted,clicked),ui.fillWrap(0,8));}
    private static UiIcons.Kind metricIcon(String metric){switch(metric){case "steps":return UiIcons.Kind.STEPS;case "calorie":return UiIcons.Kind.FLAME;case "heart":return UiIcons.Kind.HEART;case "sleep":return UiIcons.Kind.MOON;case "spo2":return UiIcons.Kind.DROPLET;case "weight":return UiIcons.Kind.SCALE;default:return UiIcons.Kind.ACTIVITY;}}
    private EditText input(LinearLayout p,String hint,String value){EditText e=new EditText(ui.dialogContext());e.setId(View.generateViewId());e.setTextColor(ui.text);e.setHintTextColor(ui.muted);e.setHint(hint);e.setText(value);e.setTextSize(16);e.setSingleLine(true);e.setMinHeight(ui.dp(48));e.setPadding(ui.dp(12),ui.dp(8),ui.dp(12),ui.dp(8));ui.input(e);p.addView(e,ui.fillWrap(3,10));return e;}
    private EditText field(LinearLayout p,String label,String hint,String value){TextView name=ui.label(label,14,ui.text);p.addView(name,ui.fillWrap(6,3));EditText edit=input(p,hint,value);name.setLabelFor(edit.getId());return edit;}
    private CheckBox check(LinearLayout p,String text,boolean selected){CheckBox b=new CheckBox(ui.dialogContext());b.setText(text);ui.buttonText(b,ui.text);b.setTextSize(15);b.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.accent));b.setChecked(selected);b.setMinHeight(ui.dp(48));p.addView(b,ui.fillWrap(2,4));return b;}
    private void editDialog(String title,LinearLayout form,Runnable save){AlertDialog dialog=ui.dialogBuilder().setTitle(title).setView(scroll(form)).setNegativeButton(tx("Отмена"),null).setPositiveButton(tx("Сохранить"),null).create();dialog.setOnShowListener(v->{ui.dialog(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{try{save.run();dialog.dismiss();}catch(RuntimeException e){error(tx("Проверьте поля: дата, время и числовые границы."),e);}});});dialog.show();}
    private void message(String title,String text){LinearLayout content=column();note(content,text);AlertDialog d=ui.dialogBuilder().setTitle(title).setView(scroll(content)).setPositiveButton(tx("Закрыть"),null).create();d.show();ui.dialog(d);}
    private void confirm(String title,String message,Runnable yes){AlertDialog d=ui.dialogBuilder().setTitle(title).setMessage(message).setNegativeButton(tx("Отмена"),null).setPositiveButton(tx("Подтвердить"),(dialog,which)->yes.run()).create();d.show();ui.dialog(d);}
    private void choose(String title,String[] names,Consumer<Integer> selected){AlertDialog d=ui.dialogBuilder().setTitle(title).setItems(names,(dialog,index)->selected.accept(index)).setNegativeButton(tx("Отмена"),null).create();d.show();ui.dialog(d);}
    private String[] sportNames(boolean all){List<String> names=new ArrayList<>();if(all)names.add(tx("Все виды"));for(WorkoutCatalog.Sport s:WorkoutCatalog.ALL)names.add(tx(s.name));return names.toArray(new String[0]);}
    private static JSONObject find(List<JSONObject> values,String id){for(JSONObject r:values)if(id.equals(r.optString("id")))return r;return null;}


    private List<HealthChartView.Point> sleepPoints(ExpansionModels.HealthRecord r,long from,long to){
        List<HealthChartView.Point> points=new ArrayList<>();JSONArray intervals=r.data.optJSONArray("intervals");
        if(intervals!=null)for(int i=0;i<intervals.length();i++){JSONObject value=intervals.optJSONObject(i);if(value==null)continue;long at=value.optLong("start")*1000,end=value.optLong("end")*1000;int stage=value.optInt("rawStage");if(at<to&&end>from&&end>at)points.add(new HealthChartView.Point(Math.max(from,at),Math.min(to,end),stage,clockTime(at)+" — "+clockTime(end)+" · "+(stage<0||stage>3?tr("Неизвестная стадия (код ","Unknown stage (code ")+stage+")":tx(HealthDatasetParser.stage(stage))),stage,false));}
        points.sort(java.util.Comparator.comparingLong(v->v.time));return points;
    }
    private void sleepLegend(LinearLayout parent,List<HealthChartView.Point> points){
        Set<Integer> codes=new java.util.TreeSet<>();codes.add(0);codes.add(1);codes.add(3);for(HealthChartView.Point point:points)if(point.rawStage<0||point.rawStage>3)codes.add(point.rawStage);
        for(int code:codes){LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);View swatch=new View(activity);ui.swatch(swatch,HealthChartView.stageColor(ui,code),false);swatch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ui.dp(12),ui.dp(12));lp.setMargins(0,0,ui.dp(8),0);row.addView(swatch,lp);row.addView(ui.label(sleepStageName(code),12,ui.text),ui.weighted(1));parent.addView(row,ui.fillWrap(0,5));}
    }
    private String sleepStageName(int code){return code==0?tr("Бодрствование","Awake"):code==1||code==2?tr("Лёгкий сон","Light sleep"):code==3?tr("Глубокий сон","Deep sleep"):tr("Неизвестная стадия (код ","Unknown stage (code ")+code+")";}
    private void sleepSummary(LinearLayout p,List<HealthChartView.Point> points){LinearLayout info=card(p);info.addView(ui.heading(tx("Информация о сне")),ui.fillWrap(0,10));Map<Integer,Long> durations=new java.util.TreeMap<>();for(HealthChartView.Point point:points){int code=point.rawStage==2?1:point.rawStage;durations.put(code,durations.getOrDefault(code,0L)+Math.max(0,point.end-point.time));}for(Map.Entry<Integer,Long> value:durations.entrySet())note(info,sleepStageName(value.getKey())+" · "+sleepDuration(value.getValue()/1000));}
    private LocalDate periodStart(){return period==0?date.withDayOfMonth(1):date.minusDays(period-1);}
    private LocalDate periodEnd(){return period==0?date.withDayOfMonth(1).plusMonths(1):date.plusDays(1);}
    private void event(String action){String op=journal.begin("ui",action,"LOCAL","");journal.stage(op,"ui",action,"COMPLETE","SUCCESS","",0,0,0);}
    private String metricName(String metric){return "steps".equals(metric)?tx("Шаги"):"calorie".equals(metric)?tr("Калории часов","Watch calories"):"heart".equals(metric)?tx("Пульс"):"sleep".equals(metric)?tx("Сон"):"spo2".equals(metric)?"SpO₂":"weight".equals(metric)?tx("Вес"):tx("Показатель");}
    private String unit(String metric){return "steps".equals(metric)?tx("шагов"):"heart".equals(metric)?tx("уд/мин"):"spo2".equals(metric)?"%":"sleep".equals(metric)?tx("Стадии сна"):"weight".equals(metric)?tx("кг"):"";}
    private String shortTime(long millis){return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm",displayLocale()));}
    private static String clockTime(long millis){return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"));}
    private String sleepDuration(long seconds){long minutes=Math.max(0,seconds/60);return minutes/60+tx(" ч ")+minutes%60+tx(" мин");}
    private static String number(long value){return java.text.NumberFormat.getIntegerInstance(java.util.Locale.getDefault()).format(value);}
    private static String format(double value){return value==Math.rint(value)?Long.toString(Math.round(value)):String.format(java.util.Locale.getDefault(),"%.1f",value);}
    private String distance(double meters){return "mi".equals(prefs.distanceUnit())?String.format(java.util.Locale.getDefault(),tx("%.2f мили"),meters/1609.344):String.format(java.util.Locale.getDefault(),tx("%.2f км"),meters/1000);}
}



