package app.gtdial.lab;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/** One process executor owns additive local storage; no existing activity database is migrated. */
final class ExpansionStore extends SQLiteOpenHelper {
    private static ExpansionStore instance;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile String lastError="";
    private Runnable analysisInvalidator=()->{};
    synchronized void onAnalysisSourceDeleted(Runnable callback){analysisInvalidator=callback;}
    static synchronized ExpansionStore get(Context context) { if(instance==null)instance=new ExpansionStore(context);return instance; }
    private ExpansionStore(Context context) { super(context.getApplicationContext(),"chasovoy_local.db",null,4); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE health_records (id TEXT PRIMARY KEY,device TEXT NOT NULL,dataset TEXT NOT NULL,ts INTEGER NOT NULL,received_ms INTEGER NOT NULL,zone TEXT NOT NULL,evidence TEXT NOT NULL,raw_sha TEXT NOT NULL,page_state TEXT NOT NULL,validity TEXT NOT NULL,coverage TEXT NOT NULL,value REAL,raw_json TEXT NOT NULL,data_json TEXT NOT NULL,UNIQUE(device,dataset,ts,raw_sha))");
        db.execSQL("CREATE INDEX health_period ON health_records(device,dataset,ts)");
        db.execSQL("CREATE TABLE imports (sha TEXT PRIMARY KEY,evidence TEXT NOT NULL,device TEXT NOT NULL,profile TEXT NOT NULL,received_ms INTEGER NOT NULL,state TEXT NOT NULL,error_code TEXT NOT NULL,file_name TEXT NOT NULL)");
        for(String table:new String[]{"workouts","plans","manual_entries","alarm_drafts"})
            db.execSQL("CREATE TABLE "+table+" (id TEXT PRIMARY KEY,sort_ms INTEGER NOT NULL,status TEXT NOT NULL,data_json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE workout_laps (id TEXT PRIMARY KEY,workout_id TEXT NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,sort_ms INTEGER NOT NULL,data_json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE workout_locations (id TEXT PRIMARY KEY,workout_id TEXT NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,sort_ms INTEGER NOT NULL,data_json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE hidden_insights (id TEXT PRIMARY KEY,hidden_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE local_settings (id TEXT PRIMARY KEY,value TEXT NOT NULL)");
        createDetails(db);
        createWorkoutDiary(db);
        createNutritionAndAnalysisTables(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        // Additive: old tables and raw records are retained; only detail bindings gain their own identity.
        if(oldVersion<1||newVersion>4||newVersion<oldVersion)throw new IllegalStateException("LOCAL_SCHEMA_UNSUPPORTED");
        if(oldVersion<2&&newVersion>=2){createDetails(db);try(Cursor c=db.query("health_records",null,"dataset LIKE ?",new String[]{"sports_%"},null,null,null)){while(c.moveToNext()){JSONObject data=parse(s(c,"data_json"));if(!data.has("parentRecordId")||!data.has("parentRawSha256"))continue;ContentValues row=new ContentValues();for(String name:c.getColumnNames()){int col=c.getColumnIndexOrThrow(name);if(c.isNull(col))row.putNull(name);else if(c.getType(col)==Cursor.FIELD_TYPE_INTEGER)row.put(name,c.getLong(col));else if(c.getType(col)==Cursor.FIELD_TYPE_FLOAT)row.put(name,c.getDouble(col));else row.put(name,c.getString(col));}row.put("parent_key",data.optString("parentRecordId")+":"+data.optString("parentRawSha256"));db.insertOrThrow("health_details",null,row);}}}
        if(oldVersion<3&&newVersion>=3)createWorkoutDiary(db);
        if(oldVersion<4&&newVersion>=4)createNutritionAndAnalysisTables(db);
    }
    private static void createNutritionAndAnalysisTables(SQLiteDatabase db){
        db.execSQL("CREATE TABLE nutrition_foods (id TEXT PRIMARY KEY,revision INTEGER NOT NULL,state TEXT NOT NULL,data_json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE nutrition_entries (id TEXT PRIMARY KEY,local_day TEXT NOT NULL,consumed_ms INTEGER NOT NULL,revision INTEGER NOT NULL,state TEXT NOT NULL,data_json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX nutrition_day_time ON nutrition_entries(local_day,consumed_ms,id)");
        db.execSQL("CREATE TABLE nutrition_days (local_day TEXT PRIMARY KEY,revision INTEGER NOT NULL,data_json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE analysis_reports (id TEXT PRIMARY KEY,created_ms INTEGER NOT NULL,status TEXT NOT NULL,data_json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX analysis_created ON analysis_reports(created_ms,id)");
    }
    private static String nutritionKey(String table){if("nutrition_days".equals(table))return "local_day";if("nutrition_foods".equals(table)||"nutrition_entries".equals(table))return "id";throw new IllegalArgumentException("NUTRITION_TABLE");}
    synchronized JSONObject nutritionFind(String table,String id){String key=nutritionKey(table);if("local_day".equals(key))java.time.LocalDate.parse(id);else NutritionModels.id(id);try(Cursor c=getReadableDatabase().query(table,new String[]{"data_json"},key+"=?",new String[]{id},null,null,null,"1")){return c.moveToFirst()?parse(c.getString(0)):null;}}
    synchronized List<JSONObject> nutritionRows(String table,String day){nutritionKey(table);if(day!=null){if(!"nutrition_entries".equals(table))throw new IllegalArgumentException("NUTRITION_DAY_FILTER");java.time.LocalDate.parse(day);}ArrayList<JSONObject> out=new ArrayList<>();String order="nutrition_entries".equals(table)?"consumed_ms DESC,id ASC":"nutrition_days".equals(table)?"local_day DESC":"id ASC";try(Cursor c=getReadableDatabase().query(table,new String[]{"data_json"},day==null?null:"local_day=?",day==null?null:new String[]{day},null,null,order)){while(c.moveToNext()){if(out.size()>=50000)throw new IllegalStateException("NUTRITION_RECORD_LIMIT");out.add(parse(c.getString(0)));}}return out;}
    synchronized void nutritionWrite(String table,JSONObject value,long expectedRevision){String key=nutritionKey(table);if("nutrition_entries".equals(table))NutritionModels.validateEntry(value);else if("nutrition_foods".equals(table))NutritionModels.validateFood(value);else NutritionModels.validateDay(value);long revision=value.optLong("revision");if(expectedRevision>=0&&revision!=expectedRevision+1)throw new IllegalArgumentException("NUTRITION_REVISION_SEQUENCE");ContentValues row=new ContentValues();String id=value.optString("local_day".equals(key)?"localDay":"id");row.put(key,id);row.put("revision",revision);row.put("data_json",value.toString());if(!"nutrition_days".equals(table))row.put("state",value.optString("state"));if("nutrition_entries".equals(table)){row.put("local_day",value.optString("localDay"));row.put("consumed_ms",value.optLong("consumedAt"));}if(expectedRevision<0)getWritableDatabase().insertOrThrow(table,null,row);else if(getWritableDatabase().update(table,row,key+"=? AND revision=?",new String[]{id,Long.toString(expectedRevision)})!=1)throw new IllegalStateException("NUTRITION_REVISION_CHANGED");}
    synchronized List<JSONObject> analysisReports(){ArrayList<JSONObject> out=new ArrayList<>();try(Cursor c=getReadableDatabase().query("analysis_reports",new String[]{"data_json"},null,null,null,null,"created_ms DESC,id ASC")){while(c.moveToNext()){if(out.size()>=50000)throw new IllegalStateException("ANALYSIS_REPORT_LIMIT");out.add(parse(c.getString(0)));}}return out;}
    synchronized int analysisReportCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM analysis_reports",null)){if(!c.moveToFirst())throw new IllegalStateException("ANALYSIS_REPORT_COUNT");return c.getInt(0);}}
    synchronized List<JSONObject> analysisReportsPage(int page){if(page<0||page>333)throw new IllegalArgumentException("REPORT_PAGE");ArrayList<JSONObject> out=new ArrayList<>();try(Cursor c=getReadableDatabase().query("analysis_reports",new String[]{"data_json"},null,null,null,null,"created_ms DESC,id ASC",(page*150)+",150")){while(c.moveToNext())out.add(parse(c.getString(0)));}return out;}
    synchronized JSONObject analysisReport(String id){NutritionModels.id(id);try(Cursor c=getReadableDatabase().query("analysis_reports",new String[]{"data_json"},"id=?",new String[]{id},null,null,null,"1")){return c.moveToFirst()?parse(c.getString(0)):null;}}
    synchronized void saveAnalysisReport(JSONObject report){if(analysisReportCount()>=50000)throw new IllegalStateException("ANALYSIS_REPORT_LIMIT");NutritionModels.id(report.optString("id"));ContentValues row=new ContentValues();row.put("id",report.optString("id"));row.put("created_ms",report.optLong("createdAt"));row.put("status","VALIDATED");row.put("data_json",report.toString());if(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>256*1024)throw new IllegalArgumentException("REPORT_SIZE");getWritableDatabase().insertOrThrow("analysis_reports",null,row);}
    synchronized void deleteAnalysisDomain(String domain){if(!AnalysisSnapshot.DOMAINS.contains(domain))throw new IllegalArgumentException("ANALYSIS_DOMAIN");for(JSONObject report:analysisReports()){org.json.JSONArray domains=report.optJSONArray("requestedDomains");if(domains==null)continue;for(int i=0;i<domains.length();i++)if(domain.equals(domains.optString(i))){getWritableDatabase().delete("analysis_reports","id=?",new String[]{report.optString("id")});break;}}}
    private static void createWorkoutDiary(SQLiteDatabase db){
        for(String table:new String[]{"workout_segments","workout_observations","strength_entries"}){
            db.execSQL("CREATE TABLE "+table+" (id TEXT PRIMARY KEY,workout_id TEXT NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,sort_ms INTEGER NOT NULL,status TEXT NOT NULL,revision INTEGER NOT NULL,data_json TEXT NOT NULL)");
            db.execSQL("CREATE INDEX "+table+"_parent ON "+table+"(workout_id,sort_ms,id)");
        }
        db.execSQL("CREATE TABLE strength_sets (id TEXT PRIMARY KEY,entry_id TEXT NOT NULL REFERENCES strength_entries(id) ON DELETE CASCADE,sort_ms INTEGER NOT NULL,status TEXT NOT NULL,revision INTEGER NOT NULL,data_json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX strength_sets_parent ON strength_sets(entry_id,sort_ms,id)");
        db.execSQL("CREATE TABLE custom_exercises (id TEXT PRIMARY KEY,sort_ms INTEGER NOT NULL,status TEXT NOT NULL,data_json TEXT NOT NULL)");
    }
    private static void createDetails(SQLiteDatabase db){db.execSQL("CREATE TABLE health_details (id TEXT PRIMARY KEY,device TEXT NOT NULL,dataset TEXT NOT NULL,ts INTEGER NOT NULL,received_ms INTEGER NOT NULL,zone TEXT NOT NULL,evidence TEXT NOT NULL,raw_sha TEXT NOT NULL,page_state TEXT NOT NULL,validity TEXT NOT NULL,coverage TEXT NOT NULL,value REAL,raw_json TEXT NOT NULL,data_json TEXT NOT NULL,parent_key TEXT NOT NULL,UNIQUE(device,dataset,ts,raw_sha,parent_key))");db.execSQL("CREATE INDEX details_parent ON health_details(device,dataset,parent_key,ts)");}
    private static final String ALARM_PENDING="alarm.pending.";
    synchronized void saveAlarmDraft(JSONObject edited){
        AlarmEditorUi.validate(edited);JSONObject value=ExpansionModels.copy(edited),current=find("alarm_drafts",edited.optString("id"));
        // Local edits cannot drop a VERIFIED binding delivered while the editor was open.
        value.remove("watchBinding");if(current!=null&&current.optJSONObject("watchBinding")!=null)ExpansionModels.put(value,"watchBinding",ExpansionModels.copy(current.optJSONObject("watchBinding")));
        ExpansionModels.put(value,"state","LOCAL_DRAFT");save("alarm_drafts",value,System.currentTimeMillis(),"LOCAL_DRAFT");
    }
    synchronized void beginAlarmTransfer(String operationId,AlarmTransferPlan plan){
        ExpansionModels.requireId(operationId);if(plan==null||!DeviceFeatureGate.alarmWriteAllowed(plan.action))throw new IllegalArgumentException("ALARM_ACTION_GATE");
        transaction(()->{
            if(!setting(ALARM_PENDING+operationId).isEmpty())throw new IllegalStateException("ALARM_OPERATION_REPLAY");
            List<JSONObject> previous=alarmOperations();if(previous.size()>=1000)throw new IllegalStateException("ALARM_OPERATION_LIMIT");
            for(JSONObject old:previous){
                if(!plan.device.equals(old.optString("device")))continue;
                boolean unsettled="PENDING".equals(old.optString("state"))||"STOPPED_UNCERTAIN".equals(old.optString("state"));
                if(unsettled&&!(plan.action==AlarmTransferPlan.Action.RESTORE&&plan.restoredOperationId.equals(old.optString("operationId"))))throw new IllegalStateException("ALARM_PREVIOUS_UNCERTAIN");
                if(plan.action==AlarmTransferPlan.Action.CREATE&&plan.draftId.equals(old.optString("draftId"))&&"CREATE".equals(old.optString("action"))&&"VERIFIED".equals(old.optString("state")))throw new IllegalStateException("ALARM_CREATE_ALREADY_VERIFIED");
            }
            if(plan.action!=AlarmTransferPlan.Action.RESTORE){
                JSONObject draft=find("alarm_drafts",plan.draftId);
                if(draft==null||!plan.draftHash.equals(ExpansionModels.alarmDraftHash(draft)))throw new IllegalStateException("ALARM_DRAFT_CHANGED");
                JSONObject binding=draft.optJSONObject("watchBinding");
                if(plan.action==AlarmTransferPlan.Action.CREATE){if(binding!=null)throw new IllegalStateException("ALARM_ALREADY_BOUND");}
                else if(!bindingMatches(binding,plan)||!plan.baseHash.equals(binding.optString("snapshotHash")))throw new IllegalStateException("ALARM_BINDING_CHANGED");
            }else{
                JSONObject parent=alarmOperation(plan.restoredOperationId);
                if(parent==null||!plan.device.equals(parent.optString("device"))||!plan.baseHash.equals(parent.optString("expectedHash"))||!plan.expectedHash.equals(parent.optString("baseHash")))throw new IllegalStateException("ALARM_RESTORE_NOT_OWN");
            }
            JSONObject pending=new JSONObject();ExpansionModels.put(pending,"operationId",operationId);ExpansionModels.put(pending,"device",plan.device);
            ExpansionModels.put(pending,"action",plan.action.name());ExpansionModels.put(pending,"draftId",plan.draftId);ExpansionModels.put(pending,"draftHash",plan.draftHash);
            ExpansionModels.put(pending,"index",plan.index);ExpansionModels.put(pending,"alarmId",plan.alarmId);ExpansionModels.put(pending,"baseHash",plan.baseHash);ExpansionModels.put(pending,"expectedHash",plan.expectedHash);
            ExpansionModels.put(pending,"restoredOperationId",plan.restoredOperationId);ExpansionModels.put(pending,"createdAt",System.currentTimeMillis());ExpansionModels.put(pending,"state","PENDING");ExpansionModels.put(pending,"phase","BEFORE_GET");
            setSetting(ALARM_PENDING+operationId,pending.toString());
        });
    }
    synchronized void alarmBackupReady(String operationId,AlarmTransferPlan plan,java.io.File file){
        transaction(()->{JSONObject pending=alarmOperation(operationId);requireAlarmOperation(pending,plan);
            if(!"PENDING".equals(pending.optString("state")))throw new IllegalStateException("ALARM_PENDING_CLOSED");
            ExpansionModels.put(pending,"backupFile",file.getName());ExpansionModels.put(pending,"phase","SET_PREPARED");setSetting(ALARM_PENDING+operationId,pending.toString());});
    }
    synchronized void requireOwnAlarmBackup(AlarmProtocol.Backup backup,java.io.File file){
        JSONObject pending=alarmOperation(backup.operationId);
        if(pending==null||!file.getName().equals(pending.optString("backupFile"))||!backup.original.device.equals(pending.optString("device"))||
                !backup.original.hash.equals(pending.optString("baseHash"))||!backup.expectedHash.equals(pending.optString("expectedHash"))||
                !backup.draftId.equals(pending.optString("draftId"))||!backup.draftHash.equals(pending.optString("draftHash"))||
                backup.index!=pending.optInt("index",-1)||backup.alarmId!=pending.optInt("alarmId",-1))throw new IllegalArgumentException("ALARM_BACKUP_REGISTRY_MISMATCH");
    }
    synchronized void finishAlarmTransfer(AlarmProtocol.Result result){
        if(result.plan==null)return;
        transaction(()->{
            JSONObject pending=alarmOperation(result.operationId);if(pending==null)return;requireAlarmOperation(pending,result.plan);
            // A terminal record is immutable; duplicate callbacks do not bind, remove or restore twice.
            if(!"PENDING".equals(pending.optString("state")))return;
            if("VERIFIED".equals(result.state)){
                if(result.evidence==null||result.snapshot==null||!result.snapshot.editable||!result.plan.expectedHash.equals(result.snapshot.hash)||!result.plan.proposed.same(result.snapshot))throw new IllegalStateException("ALARM_BINDING_REQUIRES_READBACK");
                JSONObject current=find("alarm_drafts",result.plan.draftId);
                if(current!=null){
                    if(result.plan.action==AlarmTransferPlan.Action.DELETE){
                        if(bindingMatches(current.optJSONObject("watchBinding"),result.plan)){
                            if(result.plan.draftHash.equals(ExpansionModels.alarmDraftHash(current)))getWritableDatabase().delete("alarm_drafts","id=?",new String[]{result.plan.draftId});
                            else{current.remove("watchBinding");ExpansionModels.put(current,"state","LOCAL_DRAFT");save("alarm_drafts",current,System.currentTimeMillis(),"LOCAL_DRAFT");}
                        }
                    }else{
                        AlarmProtocol.Entry remote=null;for(AlarmProtocol.Entry entry:result.snapshot.entries)if(entry.index==result.plan.index&&entry.alarmId==result.plan.alarmId)remote=entry;
                        if(remote!=null){JSONObject binding=new JSONObject();ExpansionModels.put(binding,"device",result.device);ExpansionModels.put(binding,"index",remote.index);ExpansionModels.put(binding,"alarmId",remote.alarmId);ExpansionModels.put(binding,"verifiedAt",result.receivedAt);ExpansionModels.put(binding,"snapshotHash",result.snapshot.hash);ExpansionModels.put(binding,"verifiedDraftHash",result.plan.draftHash);ExpansionModels.put(current,"watchBinding",binding);}
                        else if(result.plan.action==AlarmTransferPlan.Action.RESTORE&&bindingMatches(current.optJSONObject("watchBinding"),result.plan))current.remove("watchBinding");
                        boolean unchanged=result.plan.draftHash.equals(ExpansionModels.alarmDraftHash(current));
                        String state=remote!=null&&unchanged&&result.plan.action!=AlarmTransferPlan.Action.RESTORE?"SENT":"LOCAL_DRAFT";
                        ExpansionModels.put(current,"state",state);save("alarm_drafts",current,System.currentTimeMillis(),state);
                    }
                }
                if(result.plan.action==AlarmTransferPlan.Action.DELETE){for(JSONObject previous:alarmOperations())if(result.plan.device.equals(previous.optString("device"))&&result.plan.draftId.equals(previous.optString("draftId"))&&result.plan.index==previous.optInt("index",-1)&&result.plan.alarmId==previous.optInt("alarmId",-1)&&"CREATE".equals(previous.optString("action"))&&"VERIFIED".equals(previous.optString("state"))){ExpansionModels.put(previous,"state","DELETED");setSetting(ALARM_PENDING+previous.optString("operationId"),previous.toString());}}
                if(result.plan.action==AlarmTransferPlan.Action.RESTORE){JSONObject parent=alarmOperation(result.plan.restoredOperationId);if(parent==null)throw new IllegalStateException("ALARM_RESTORE_PARENT");ExpansionModels.put(parent,"state","RESTORED");ExpansionModels.put(parent,"restoredBy",result.operationId);setSetting(ALARM_PENDING+result.plan.restoredOperationId,parent.toString());}
                advanceUnchangedAlarmBindings(result);
            }
            ExpansionModels.put(pending,"state",result.state);ExpansionModels.put(pending,"finishedAt",result.receivedAt);ExpansionModels.put(pending,"errorCode",result.error);setSetting(ALARM_PENDING+result.operationId,pending.toString());
        });
    }
    synchronized java.util.Map<String,String> alarmTransferStates(){
        java.util.Map<String,String> states=new java.util.HashMap<>();for(JSONObject op:alarmOperations()){String state=op.optString("state");if("PENDING".equals(state)||"STOPPED_UNCERTAIN".equals(state))states.put(op.optString("draftId"),state);}return states;
    }
    private void advanceUnchangedAlarmBindings(AlarmProtocol.Result result){
        for(JSONObject draft:list("alarm_drafts")){
            if(result.plan.draftId.equals(draft.optString("id")))continue;
            JSONObject binding=draft.optJSONObject("watchBinding");
            if(binding==null||!result.device.equals(binding.optString("device"))||!result.plan.baseHash.equals(binding.optString("snapshotHash"))||binding.optLong("verifiedAt")<=0)continue;
            AlarmProtocol.Entry before=null,after=null;int index=binding.optInt("index",-1),id=binding.optInt("alarmId",-1);
            for(AlarmProtocol.Entry entry:result.plan.original.entries)if(entry.index==index&&entry.alarmId==id)before=entry;
            for(AlarmProtocol.Entry entry:result.plan.proposed.entries)if(entry.index==index&&entry.alarmId==id)after=entry;
            if(before==null||!before.same(after))continue;
            // Only a VERIFIED own mutation proves that an existing bound entry remained unchanged.
            ExpansionModels.put(binding,"snapshotHash",result.snapshot.hash);ExpansionModels.put(binding,"verifiedAt",result.receivedAt);
            save("alarm_drafts",draft,System.currentTimeMillis(),draft.optString("state","LOCAL_DRAFT"));
        }
    }
    private List<JSONObject> alarmOperations(){
        ArrayList<JSONObject> result=new ArrayList<>();try(Cursor c=getReadableDatabase().query("local_settings",new String[]{"value"},"id LIKE ?",new String[]{ALARM_PENDING+"%"},null,null,null,"1001")){while(c.moveToNext())result.add(parse(c.getString(0)));}return result;
    }
    private JSONObject alarmOperation(String id){ExpansionModels.requireId(id);String saved=setting(ALARM_PENDING+id);return saved.isEmpty()?null:parse(saved);}
    private static void requireAlarmOperation(JSONObject pending,AlarmTransferPlan plan){
        if(pending==null||!plan.action.name().equals(pending.optString("action"))||!plan.device.equals(pending.optString("device"))||!plan.draftId.equals(pending.optString("draftId"))||!plan.draftHash.equals(pending.optString("draftHash"))||plan.index!=pending.optInt("index",-1)||plan.alarmId!=pending.optInt("alarmId",-1)||!plan.baseHash.equals(pending.optString("baseHash"))||!plan.expectedHash.equals(pending.optString("expectedHash")))throw new IllegalStateException("ALARM_PENDING_PLAN_MISMATCH");
    }
    private static boolean bindingMatches(JSONObject binding,AlarmTransferPlan plan){return binding!=null&&plan.device.equals(binding.optString("device"))&&plan.index==binding.optInt("index",-1)&&plan.alarmId==binding.optInt("alarmId",-1)&&binding.optLong("verifiedAt")>0;}
    String error() { return lastError; }
    void execute(Runnable task) { executor.execute(task); }
    <T> void submit(Callable<T> task,BiConsumer<T,String> done) {
        executor.execute(()->{
            T value=null;String error="";
            try { value=task.call();lastError=""; }
            catch(Exception e) { String code=e.getMessage();error=e.getClass().getSimpleName()+(code!=null&&code.matches("[A-Z][A-Z0-9_]{1,80}")?":"+code:"");lastError=error; }
            T result=value;String failure=error;main.post(()->done.accept(result,failure));
        });
    }
    synchronized void save(String table,JSONObject object,long sort,String status) {
        requireTable(table);ExpansionModels.requireId(object.optString("id"));
        if(object.toString().length()>64*1024)throw new IllegalArgumentException("LOCAL_RECORD_SIZE");
        ContentValues row=new ContentValues();row.put("id",object.optString("id"));row.put("sort_ms",sort);
        row.put("status",status);row.put("data_json",object.toString());
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try { if(db.update(table,row,"id=?",new String[]{object.optString("id")})==0&&db.insert(table,null,row)==-1)throw new IllegalStateException("LOCAL_SAVE_FAILED");db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    synchronized JSONObject find(String table,String id) {
        requireTable(table);ExpansionModels.requireId(id);
        try(Cursor c=getReadableDatabase().query(table,new String[]{"data_json"},"id=?",new String[]{id},null,null,null,"1")) {return c.moveToFirst()?parse(c.getString(0)):null;}
    }
    synchronized List<JSONObject> list(String table) {
        requireTable(table);ArrayList<JSONObject> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query(table,new String[]{"data_json"},null,null,null,null,"sort_ms DESC,id","4096")) {while(c.moveToNext())result.add(parse(c.getString(0)));}
        return Collections.unmodifiableList(result);
    }
    /** Reports must fail at their explicit bound instead of silently exporting a prefix. */
    synchronized List<JSONObject> reportRows(String table){requireTable(table);ArrayList<JSONObject> rows=new ArrayList<>();try(Cursor c=getReadableDatabase().query(table,new String[]{"data_json"},null,null,null,null,"sort_ms DESC,id")){while(c.moveToNext()){if(rows.size()>=50000)throw new IllegalStateException("REPORT_RECORD_LIMIT");rows.add(parse(c.getString(0)));}}return rows;}
    synchronized void remove(String table,String id) {requireTable(table);ExpansionModels.requireId(id);transaction(()->{if("workouts".equals(table))unlinkWorkout(id);if("plans".equals(table))for(JSONObject r:list("workouts"))if(id.equals(r.opt("planId"))){ExpansionModels.put(r,"planId",null);save("workouts",r,r.optLong("startedAt"),r.optString("status"));}getWritableDatabase().delete(table,"id=?",new String[]{id});});}
    /** Confirmed permanent source removal, with fresh identity and transactional report invalidation. */
    synchronized void deletePhoneWorkout(String id,long startedAt){transaction(()->{JSONObject fresh=find("workouts",id);if(fresh==null||fresh.optLong("startedAt")!=startedAt||!ExpansionModels.PHONE.equals(fresh.optString("source"))||WorkoutRecorder.isActive(fresh)||!"SAVED".equals(fresh.optString("status")))throw new IllegalStateException("WORKOUT_DELETE_CHANGED");analysisInvalidator.run();deleteAffectedReports(new String[]{"activity","strength","heart"},null,startedAt,null);unlinkWorkout(id);if(getWritableDatabase().delete("workouts","id=?",new String[]{id})!=1)throw new IllegalStateException("WORKOUT_DELETE_CHANGED");});}
    synchronized void deleteWatchWorkout(String device,String id,long recordId,long startedAt,String rawSha){if(device==null||device.isEmpty()||recordId<0||startedAt<0||startedAt>253402300799L||rawSha==null||!rawSha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("WATCH_WORKOUT_IDENTITY");ExpansionModels.requireId(id);transaction(()->{ExpansionModels.HealthRecord exact=null;List<ExpansionModels.HealthRecord> revisions=healthForDeletion(device);for(ExpansionModels.HealthRecord r:revisions)if(r.validity.startsWith("VALID")&&id.equals(r.id)&&recordId==r.data.optLong("recordId",-1)&&startedAt==r.data.optLong("startTime",-1)&&rawSha.equals(r.rawSha))exact=r;if(exact==null)throw new IllegalStateException("WATCH_WORKOUT_DELETE_CHANGED");analysisInvalidator.run();deleteAffectedReports(new String[]{"activity","heart","spo2"},null,Math.multiplyExact(startedAt,1000),device);setSetting(sportsTombstone(device,recordId,startedAt),"1");for(ExpansionModels.HealthRecord r:revisions)if(recordId==r.data.optLong("recordId",-1)&&startedAt==r.data.optLong("startTime",-1)){String parent=recordId+":"+r.rawSha;setSetting(sportsParentTombstone(device,parent),"1");getWritableDatabase().delete("health_details","device=? AND parent_key=?",new String[]{device,parent});getWritableDatabase().delete("health_records","id=? AND device=? AND dataset=?",new String[]{r.id,device,"sports"});}});}
    private List<ExpansionModels.HealthRecord> healthForDeletion(String device){ArrayList<ExpansionModels.HealthRecord> rows=new ArrayList<>();try(Cursor c=getReadableDatabase().query("health_records",null,"device=? AND dataset=?",new String[]{device,"sports"},null,null,null)){while(c.moveToNext()){if(rows.size()>=50000)throw new IllegalStateException("WORKOUT_DELETE_LIMIT");rows.add(new ExpansionModels.HealthRecord(s(c,"id"),s(c,"device"),s(c,"dataset"),s(c,"evidence"),s(c,"raw_sha"),l(c,"ts"),l(c,"received_ms"),s(c,"zone"),s(c,"page_state"),s(c,"validity"),s(c,"coverage"),null,s(c,"raw_json"),parse(s(c,"data_json"))));}}return rows;}
    private static String sportsTombstone(String device,long record,long start){return "sports_deleted_"+tombstoneHash(device+"|"+record+"|"+start);}
    private static String sportsParentTombstone(String device,String parent){return "sports_parent_deleted_"+tombstoneHash(device+"|"+parent);}
    private static String tombstoneHash(String identity){try{return HealthEvidenceImporter.sha(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException("WATCH_WORKOUT_TOMBSTONE_HASH");}}
    synchronized void deleteNutritionEntry(String id,long revision){transaction(()->{JSONObject fresh=nutritionFind("nutrition_entries",id);if(fresh==null||fresh.optLong("revision")!=revision)throw new IllegalStateException("NUTRITION_REVISION_CHANGED");analysisInvalidator.run();deleteAffectedReports(new String[]{"nutrition"},fresh.optString("localDay"),0,null);if(getWritableDatabase().delete("nutrition_entries","id=? AND revision=?",new String[]{id,Long.toString(revision)})!=1)throw new IllegalStateException("NUTRITION_REVISION_CHANGED");});}
    synchronized void deleteNutritionFood(String id,long revision){transaction(()->{if(getWritableDatabase().delete("nutrition_foods","id=? AND revision=?",new String[]{id,Long.toString(revision)})!=1)throw new IllegalStateException("NUTRITION_REVISION_CHANGED");});}
    private void deleteAffectedReports(String[] domains,String localDay,long timestamp,String device){for(JSONObject report:analysisReports()){boolean affected=false;org.json.JSONArray requested=report.optJSONArray("requestedDomains");if(requested==null)affected=true;else for(String domain:domains)for(int i=0;i<requested.length();i++)if(domain.equals(requested.optString(i)))affected=true;if(!affected)continue;try{JSONObject snapshot=report.optJSONObject("snapshot");AnalysisSnapshot.Selection selection=AnalysisSnapshot.Selection.parse(snapshot.optJSONObject("selection"));if(device!=null&&!device.equals(selection.device))continue;java.time.LocalDate day=localDay==null?java.time.Instant.ofEpochMilli(timestamp).atZone(selection.zone).toLocalDate():java.time.LocalDate.parse(localDay);if(day.isBefore(selection.start())||day.isAfter(selection.end))continue;}catch(RuntimeException e){/* Malformed affected report cannot be retained as valid evidence. */}getWritableDatabase().delete("analysis_reports","id=?",new String[]{report.optString("id")});}}
    synchronized void unlinkWorkout(String id){for(JSONObject p:reportRows("plans"))if(id.equals(p.opt("workoutId"))){ExpansionModels.put(p,"workoutId",null);save("plans",p,p.optLong("sortMillis"),p.optString("status"));}}
    synchronized void retainBefore(long cutoff){
        analysisInvalidator.run();transaction(()->{for(String domain:new String[]{"activity","sleep","heart","spo2","strength","weight"})deleteAnalysisDomain(domain);getWritableDatabase().delete("health_records","ts<?",new String[]{Long.toString(cutoff/1000)});getWritableDatabase().delete("health_details","ts<?",new String[]{Long.toString(cutoff/1000)});
            for(String table:new String[]{"workouts","plans","manual_entries"})for(JSONObject r:list(table)){long at="workouts".equals(table)?r.optLong("startedAt"):"plans".equals(table)?r.optLong("sortMillis"):r.optLong("timestamp");if(at<cutoff&&!("workouts".equals(table)&&WorkoutRecorder.isActive(r)))remove(table,r.optString("id"));}});
    }
    synchronized void transaction(Runnable work) { SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{work.run();db.setTransactionSuccessful();}finally{db.endTransaction();} }
    private static String diaryParent(String table){if("strength_sets".equals(table))return "entry_id";if("strength_entries".equals(table)||"workout_segments".equals(table)||"workout_observations".equals(table))return "workout_id";throw new IllegalArgumentException("DIARY_TABLE");}
    synchronized JSONObject diaryFind(String table,String id){diaryParent(table);ExpansionModels.requireId(id);try(Cursor c=getReadableDatabase().query(table,new String[]{"data_json"},"id=?",new String[]{id},null,null,null,"1")){return c.moveToFirst()?parse(c.getString(0)):null;}}
    synchronized List<JSONObject> diaryRows(String table,String parent){String column=diaryParent(table);ExpansionModels.requireId(parent);ArrayList<JSONObject> result=new ArrayList<>();try(Cursor c=getReadableDatabase().query(table,new String[]{"data_json"},column+"=?",new String[]{parent},null,null,"sort_ms ASC,id ASC")){while(c.moveToNext()){if(result.size()>=20000)throw new IllegalStateException("DIARY_ROW_LIMIT");result.add(parse(c.getString(0)));}}return result;}
    /** Insert and update are explicit; a stale UI revision must never overwrite a newer draft. */
    synchronized void diaryWrite(String table,String parent,JSONObject value,long expectedRevision){String column=diaryParent(table);ExpansionModels.requireId(parent);ExpansionModels.requireId(value.optString("id"));if(value.toString().length()>64*1024)throw new IllegalArgumentException("DIARY_RECORD_SIZE");ContentValues row=new ContentValues();row.put("id",value.optString("id"));row.put(column,parent);row.put("sort_ms",value.optLong("position",value.optLong("receivedAt",value.optLong("startElapsed"))));row.put("status",value.optString("state","OPEN"));row.put("revision",value.optLong("revision"));row.put("data_json",value.toString());SQLiteDatabase db=getWritableDatabase();if(expectedRevision<0)db.insertOrThrow(table,null,row);else if(db.update(table,row,"id=? AND "+column+"=? AND revision=?",new String[]{value.optString("id"),parent,Long.toString(expectedRevision)})!=1)throw new IllegalStateException("DIARY_REVISION_CHANGED");}
    synchronized List<ExpansionModels.HealthRecord> healthForEvidence(String device,String dataset,String evidence){ArrayList<ExpansionModels.HealthRecord> out=new ArrayList<>();try(Cursor c=getReadableDatabase().query("health_records",null,"device=? AND dataset=? AND evidence=?",new String[]{device,dataset,evidence},null,null,"ts ASC")){while(c.moveToNext()){if(out.size()>=20000)throw new IllegalStateException("HEALTH_EVIDENCE_LIMIT");out.add(new ExpansionModels.HealthRecord(s(c,"id"),s(c,"device"),s(c,"dataset"),s(c,"evidence"),s(c,"raw_sha"),l(c,"ts"),l(c,"received_ms"),s(c,"zone"),s(c,"page_state"),s(c,"validity"),s(c,"coverage"),c.isNull(c.getColumnIndexOrThrow("value"))?null:c.getDouble(c.getColumnIndexOrThrow("value")),s(c,"raw_json"),parse(s(c,"data_json"))));}}return out;}
    synchronized void clear(String section) {
        if("health".equals(section)||"workouts".equals(section)||"manual_entries".equals(section))analysisInvalidator.run();
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            if("health".equals(section)){for(String domain:new String[]{"activity","sleep","heart","spo2"})deleteAnalysisDomain(domain);db.delete("health_records",null,null);db.delete("health_details",null,null);db.delete("imports",null,null);setSetting("health_import_suppressed","1");}
            else if("workouts".equals(section)){deleteAnalysisDomain("activity");deleteAnalysisDomain("strength");deleteAnalysisDomain("heart");for(JSONObject r:list("workouts"))unlinkWorkout(r.optString("id"));db.delete("workouts",null,null);}
            else if("plans".equals(section)){for(JSONObject r:list("workouts"))if(!r.isNull("planId")){ExpansionModels.put(r,"planId",null);save("workouts",r,r.optLong("startedAt"),r.optString("status"));}db.delete("plans",null,null);}
            else {requireTable(section);if("manual_entries".equals(section))deleteAnalysisDomain("weight");db.delete(section,null,null);}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    synchronized void child(String table,String workoutId,JSONObject value,long sort) {
        if(!"workout_laps".equals(table)&&!"workout_locations".equals(table))throw new IllegalArgumentException("LOCAL_CHILD_TABLE");
        ExpansionModels.requireId(workoutId);ContentValues row=new ContentValues();row.put("id",value.optString("id"));
        row.put("workout_id",workoutId);row.put("sort_ms",sort);row.put("data_json",value.toString());
        if(getWritableDatabase().insertWithOnConflict(table,null,row,SQLiteDatabase.CONFLICT_IGNORE)==-1)throw new IllegalStateException("CHILD_SAVE_FAILED");
    }
    synchronized List<JSONObject> children(String table,String workoutId) {
        if(!"workout_laps".equals(table)&&!"workout_locations".equals(table))throw new IllegalArgumentException("LOCAL_CHILD_TABLE");
        ExpansionModels.requireId(workoutId);ArrayList<JSONObject> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query(table,new String[]{"data_json"},"workout_id=?",new String[]{workoutId},null,null,"sort_ms ASC","20000")){while(c.moveToNext())result.add(parse(c.getString(0)));}
        return result;
    }
    synchronized boolean imported(String sha) {
        try(Cursor c=getReadableDatabase().query("imports",new String[]{"sha"},"sha=?",new String[]{sha},null,null,null,"1")){return c.moveToFirst();}
    }
    synchronized boolean healthImportSuppressed(){return "1".equals(setting("health_import_suppressed"));}
    synchronized void restoreHealthImport(){setSetting("health_import_suppressed","0");}
    synchronized void suppressHealthImport(){setSetting("health_import_suppressed","1");}
    synchronized long healthDeletionCutoff(){String saved=setting("health_deleted_at_ms");return saved.isEmpty()?0:Long.parseLong(saved);}
    synchronized void clearMeasurements(long deletedAt){
        if(deletedAt<=0)throw new IllegalArgumentException("MEASUREMENT_DELETE_TIME");
        analysisInvalidator.run();
        transaction(()->{
            // Retain the import index as tombstones; old saved evidence must never repopulate deleted data.
            setSetting("health_import_suppressed","1");
            setSetting("health_deleted_at_ms",Long.toString(Math.max(deletedAt,healthDeletionCutoff())));
            getWritableDatabase().delete("health_records",null,null);
            getWritableDatabase().delete("health_details",null,null);
            for(JSONObject entry:manualMeasurements())getWritableDatabase().delete("manual_entries","id=?",new String[]{entry.optString("id")});
            getWritableDatabase().delete("hidden_insights",null,null);
            for(String domain:new String[]{"activity","sleep","heart","spo2","weight"})deleteAnalysisDomain(domain);
        });
    }
    synchronized List<JSONObject> manualMeasurements(){
        ArrayList<JSONObject> values=new ArrayList<>();try(Cursor c=getReadableDatabase().query("manual_entries",new String[]{"data_json"},null,null,null,null,"sort_ms ASC,id ASC")){
            while(c.moveToNext()){JSONObject entry=parse(c.getString(0));if("NOTE".equals(entry.optString("kind")))continue;if(values.size()>=50000)throw new IllegalStateException("MANUAL_MEASUREMENT_LIMIT");values.add(entry);}
        }return values;
    }
    synchronized List<ExpansionModels.HealthRecord> measurementsForExport(){
        ArrayList<ExpansionModels.HealthRecord> values=new ArrayList<>();
        for(String table:new String[]{"health_records","health_details"})
            try(Cursor c=getReadableDatabase().query(table,null,null,null,null,null,"ts ASC,received_ms ASC")){
                while(c.moveToNext()){
                    if(values.size()>=50000)throw new IllegalStateException("MEASUREMENT_EXPORT_LIMIT");
                    values.add(new ExpansionModels.HealthRecord(s(c,"id"),s(c,"device"),s(c,"dataset"),s(c,"evidence"),s(c,"raw_sha"),l(c,"ts"),l(c,"received_ms"),s(c,"zone"),s(c,"page_state"),s(c,"validity"),s(c,"coverage"),c.isNull(c.getColumnIndexOrThrow("value"))?null:c.getDouble(c.getColumnIndexOrThrow("value")),s(c,"raw_json"),parse(s(c,"data_json"))));
                }
            }
        return values;
    }
    synchronized List<ExpansionModels.HealthRecord> healthForReport(String device,String dataset,long fromSeconds,long toSeconds){
        ArrayList<ExpansionModels.HealthRecord> values=new ArrayList<>();for(ExpansionModels.HealthRecord record:measurementsForExport())if(device.equals(record.device)&&dataset.equals(record.dataset))values.add(record);
        List<ExpansionModels.HealthRecord> latest=revisions(values);latest.removeIf(r->"sleep".equals(dataset)&&r.data.has("enter")?!(r.data.optLong("enter")<toSeconds&&r.data.optLong("exit")>fromSeconds):r.tsSeconds<fromSeconds||r.tsSeconds>=toSeconds);return latest;
    }
    /** Dedicated namespace: arbitrary setting keys cannot be written by diary callers. */
    synchronized JSONObject templateNote(String variantId){
        validateVariantId(variantId);String saved=setting("strength_template_note_"+variantId);
        if(!saved.isEmpty())return parse(saved);
        JSONObject row=new JSONObject();ExpansionModels.put(row,"variantId",variantId);ExpansionModels.put(row,"note","");ExpansionModels.put(row,"revision",0);ExpansionModels.put(row,"updatedAt",0);return row;
    }
    synchronized JSONObject saveTemplateNote(String variantId,String note,long expected){
        validateVariantId(variantId);String text=ExpansionModels.text(note,2000);JSONObject[] result=new JSONObject[1];transaction(()->{
            JSONObject row=templateNote(variantId);if(row.optLong("revision")!=expected)throw new IllegalStateException("DIARY_REVISION_CHANGED");
            if(!text.equals(row.optString("note"))){ExpansionModels.put(row,"note",text);ExpansionModels.put(row,"revision",expected+1);ExpansionModels.put(row,"updatedAt",System.currentTimeMillis());setSetting("strength_template_note_"+variantId,row.toString());}result[0]=row;
        });return result[0];
    }
    synchronized List<JSONObject> templateNotes(){ArrayList<JSONObject> rows=new ArrayList<>();try(Cursor c=getReadableDatabase().query("local_settings",new String[]{"value"},"id LIKE ?",new String[]{"strength_template_note_%"},null,null,"id ASC")){while(c.moveToNext()){if(rows.size()>=50000)throw new IllegalStateException("REPORT_RECORD_LIMIT");rows.add(parse(c.getString(0)));}}return rows;}
    private void validateVariantId(String id){if(id==null||id.length()>120||!id.matches("[A-Za-z0-9_-]+")||(StrengthCatalog.find(id)==null&&find("custom_exercises",id)==null))throw new IllegalArgumentException("EXERCISE_MISSING");}
    private String setting(String id){try(Cursor c=getReadableDatabase().query("local_settings",new String[]{"value"},"id=?",new String[]{id},null,null,null,"1")){return c.moveToFirst()?c.getString(0):"";}}
    private void setSetting(String id,String value){ContentValues row=new ContentValues();row.put("id",id);row.put("value",value);if(getWritableDatabase().insertWithOnConflict("local_settings",null,row,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IllegalStateException("SETTING_SAVE_FAILED");}
    synchronized java.util.Set<String> importedFileNames(){java.util.Set<String> names=new java.util.HashSet<>();try(Cursor c=getReadableDatabase().query("imports",new String[]{"file_name"},null,null,null,null,null,"20000")){while(c.moveToNext())names.add(c.getString(0));}return names;}
    synchronized void importPage(String fileSha,String evidence,String device,String profile,long received,String state,String error,String file,List<ExpansionModels.HealthRecord> records) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            // Bind every suppressed parent before details, regardless of page record order.
            for(ExpansionModels.HealthRecord r:records)if("sports".equals(r.dataset)&&"1".equals(setting(sportsTombstone(r.device,r.data.optLong("recordId",-1),r.data.optLong("startTime",-1)))))setSetting(sportsParentTombstone(r.device,r.data.optString("recordId")+":"+r.rawSha),"1");
            for(ExpansionModels.HealthRecord r:records) {
                boolean detail=r.dataset.startsWith("sports_");String table=detail?"health_details":"health_records";String parent=detail?r.data.optString("parentRecordId")+":"+r.data.optString("parentRawSha256"):"";
                if("sports".equals(r.dataset)&&"1".equals(setting(sportsTombstone(r.device,r.data.optLong("recordId",-1),r.data.optLong("startTime",-1))))){setSetting(sportsParentTombstone(r.device,r.data.optString("recordId")+":"+r.rawSha),"1");continue;}if(detail&&"1".equals(setting(sportsParentTombstone(r.device,parent))))continue;
                ContentValues v=new ContentValues();v.put("id",r.id);v.put("device",r.device);v.put("dataset",r.dataset);v.put("ts",r.tsSeconds);
                v.put("received_ms",r.receivedAt);v.put("zone",r.zone);v.put("evidence",r.evidenceId);v.put("raw_sha",r.rawSha);
                v.put("page_state",r.pageState);v.put("validity",r.validity);v.put("coverage",r.coverage);if(r.value!=null)v.put("value",r.value);
                v.put("raw_json",r.rawJson);v.put("data_json",r.data.toString());
                if(detail)v.put("parent_key",parent);
                if(db.insertWithOnConflict(table,null,v,SQLiteDatabase.CONFLICT_IGNORE)==-1) {
                    try(Cursor c=db.query(table,new String[]{"id"},"device=? AND dataset=? AND ts=? AND raw_sha=?"+(detail?" AND parent_key=?":""),detail?new String[]{r.device,r.dataset,Long.toString(r.tsSeconds),r.rawSha,parent}:new String[]{r.device,r.dataset,Long.toString(r.tsSeconds),r.rawSha},null,null,null,"1")) {
                        if(!c.moveToFirst())throw new IllegalStateException("HEALTH_SAVE_FAILED");
                    }
                }
            }
            ContentValues v=new ContentValues();v.put("sha",fileSha);v.put("evidence",evidence);v.put("device",device);v.put("profile",profile);
            v.put("received_ms",received);v.put("state",state);v.put("error_code",error);v.put("file_name",file);
            db.insertOrThrow("imports",null,v);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    synchronized List<String> devices() {
        ArrayList<String> values=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query(true,"health_records",new String[]{"device"},null,null,null,null,"device",null)){while(c.moveToNext())values.add(c.getString(0));}
        try(Cursor c=getReadableDatabase().query(true,"health_details",new String[]{"device"},null,null,null,null,"device",null)){while(c.moveToNext())if(!values.contains(c.getString(0)))values.add(c.getString(0));}
        return values;
    }
    private static List<ExpansionModels.HealthRecord> revisions(ArrayList<ExpansionModels.HealthRecord> values) {
        // Revisions stay in storage, but only the newest usable natural-key record enters charts/reports.
        java.util.LinkedHashMap<String,ExpansionModels.HealthRecord> current=new java.util.LinkedHashMap<>();
        for(ExpansionModels.HealthRecord record:values){String key=record.dataset+":"+(record.dataset.startsWith("sports_")?record.data.optString("parentRecordId")+":"+record.data.optString("parentRawSha256")+":"+record.tsSeconds:"sports".equals(record.dataset)?record.data.optString("recordId",Long.toString(record.tsSeconds)):Long.toString(record.tsSeconds));ExpansionModels.HealthRecord old=current.get(key);boolean usable=record.validity.startsWith("VALID");if(old==null||usable&&!old.validity.startsWith("VALID")||usable==old.validity.startsWith("VALID")&&record.receivedAt>=old.receivedAt)current.put(key,record);}
        values=new ArrayList<>(current.values());values.sort(java.util.Comparator.comparingLong(r->r.tsSeconds));return values;
    }
    synchronized List<ExpansionModels.HealthRecord> health(String device,String dataset,long fromSeconds,long toSeconds) {
        return health(device,dataset,fromSeconds,toSeconds,20000,0);
    }
    /** Read-only paging, newest records first before returning chart order. Revisions precede the bound. */
    synchronized List<ExpansionModels.HealthRecord> health(String device,String dataset,long fromSeconds,long toSeconds,int limit,int offset) {
        if(limit<1||limit>20000||offset<0||offset>50000)throw new IllegalArgumentException("HEALTH_PAGE_BOUNDS");
        boolean sleep="sleep".equals(dataset),sports="sports".equals(dataset),detail=dataset.startsWith("sports_");
        // Saved sports recordId is a validated integer. Use core SQLite string/CAST functions,
        // not JSON1 (which is unavailable on some supported Android versions).
        String key=sports?"CASE WHEN instr(data_json,'\"recordId\":')>0 THEN 'record:'||CAST(substr(data_json,instr(data_json,'\"recordId\":')+11) AS INTEGER) ELSE 'ts:'||ts END":detail?"parent_key||':'||ts":"CAST(ts AS TEXT)";
        java.util.Comparator<ExpansionModels.HealthRecord> order=java.util.Comparator.comparingLong((ExpansionModels.HealthRecord r)->r.tsSeconds).thenComparingLong(r->r.receivedAt).thenComparing(r->r.id);
        java.util.PriorityQueue<ExpansionModels.HealthRecord> newest=new java.util.PriorityQueue<>(order);
        String selection=(sleep||sports)?"device=? AND dataset=?":"device=? AND dataset=? AND ts>=? AND ts<?";
        String[] args=(sleep||sports)?new String[]{device,dataset}:new String[]{device,dataset,Long.toString(fromSeconds),Long.toString(toSeconds)};
        String previous=null;
        String ordering=(sports||detail?"natural_key ASC":"ts DESC")+",CASE WHEN validity LIKE 'VALID%' THEN 1 ELSE 0 END DESC,received_ms DESC,id DESC";
        try(Cursor c=getReadableDatabase().query(detail?"health_details":"health_records",new String[]{"*",key+" AS natural_key"},selection,args,null,null,ordering)){
            while(c.moveToNext()){
                String natural=s(c,"natural_key");if(natural.equals(previous))continue;previous=natural;
                JSONObject data=parse(s(c,"data_json"));long ts=l(c,"ts");
                // Filtering the winning revision cannot resurrect a superseded interval.
                if(sleep?!(data.has("enter")?data.optLong("enter")<toSeconds&&data.optLong("exit")>fromSeconds:ts>=fromSeconds&&ts<toSeconds):ts<fromSeconds||ts>=toSeconds)continue;
                ExpansionModels.HealthRecord record=new ExpansionModels.HealthRecord(s(c,"id"),s(c,"device"),s(c,"dataset"),s(c,"evidence"),s(c,"raw_sha"),ts,l(c,"received_ms"),s(c,"zone"),s(c,"page_state"),s(c,"validity"),s(c,"coverage"),c.isNull(c.getColumnIndexOrThrow("value"))?null:c.getDouble(c.getColumnIndexOrThrow("value")),s(c,"raw_json"),data);
                if(newest.size()<limit+offset)newest.add(record);else if(order.compare(record,newest.peek())>0){newest.poll();newest.add(record);}
                if(!sports&&!detail&&newest.size()>=limit+offset)break;
            }
        }
        ArrayList<ExpansionModels.HealthRecord> values=new ArrayList<>(newest);values.sort(order.reversed());
        if(offset>=values.size())return new ArrayList<>();values=new ArrayList<>(values.subList(offset,values.size()));values.sort(order);return values;
    }
    synchronized List<JSONObject> imports() {
        ArrayList<JSONObject> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("imports",null,null,null,null,null,"received_ms DESC","256")) {
            while(c.moveToNext()){JSONObject o=new JSONObject();for(String name:c.getColumnNames())ExpansionModels.put(o,name,c.getString(c.getColumnIndexOrThrow(name)));out.add(o);}
        }return out;
    }
    synchronized boolean hidden(String id) {try(Cursor c=getReadableDatabase().query("hidden_insights",new String[]{"id"},"id=?",new String[]{id},null,null,null)){return c.moveToFirst();}}
    synchronized void hide(String id,boolean hide) {if(hide){ContentValues v=new ContentValues();v.put("id",id);v.put("hidden_ms",System.currentTimeMillis());if(getWritableDatabase().insertWithOnConflict("hidden_insights",null,v,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IllegalStateException("INSIGHT_HIDE_SAVE_FAILED");}else getWritableDatabase().delete("hidden_insights","id=?",new String[]{id});}
    static JSONObject parse(String json) {try{return new JSONObject(json);}catch(Exception e){throw new IllegalStateException("LOCAL_RECORD_INVALID",e);}}
    private static String s(Cursor c,String key) {return c.getString(c.getColumnIndexOrThrow(key));}
    private static long l(Cursor c,String key) {return c.getLong(c.getColumnIndexOrThrow(key));}
    private static void requireTable(String table) {if(!"workouts".equals(table)&&!"plans".equals(table)&&!"manual_entries".equals(table)&&!"alarm_drafts".equals(table)&&!"custom_exercises".equals(table))throw new IllegalArgumentException("LOCAL_TABLE");}
}

