package app.gtdial.lab;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONObject;
import java.util.List;

/** Persistent monotonic timer; an unknown reboot interval is never counted as exercise. */
final class WorkoutRecorder {
    private static final String UNKNOWN_BOOT_PROCESS=java.util.UUID.randomUUID().toString();
    static final String PROCESS_ID=java.util.UUID.randomUUID().toString();
    private final Context context;
    private final ExpansionStore store;
    private final OperationJournal journal;
    WorkoutRecorder(Context context,ExpansionStore store){this.context=context.getApplicationContext();this.store=store;journal=OperationJournal.get(context);}
    JSONObject current() {
        for(JSONObject record:store.list("workouts"))if(isActive(record)){
            boolean metricRecovery=!PROCESS_ID.equals(record.optString("metricProcessId"))||!boot().equals(record.optString("bootIdentity"));
            if(metricRecovery)store.transaction(()->{closeSegment(record,"PROCESS_OR_BOOT_GAP",false);resetBaseline(record);if(!boot().equals(record.optString("bootIdentity")))StrengthDiary.interruptRest(record);ExpansionModels.put(record,"metricProcessId",PROCESS_ID);ExpansionModels.put(record,"metricState","PARTIAL_PROCESS_GAP");save(record);});
            boolean sameProcess=PROCESS_ID.equals(record.optString("gpsProcessId"));
            if(record.optBoolean("gpsRequested")&&(!sameProcess||!PhoneWorkoutService.owns(record.optString("id")))&&!(sameProcess&&"WAITING_PERMISSION_OR_PROVIDER".equals(record.optString("gpsState")))&&!(record.optString("gpsState").startsWith("PARTIAL_")||"NOT_REQUESTED".equals(record.optString("gpsState")))){ExpansionModels.put(record,"gpsState","PARTIAL_PROCESS_GAP");save(record);log("gps_recovery","PARTIAL","PROCESS_OR_SERVICE_GAP");}
            if(!boot().equals(record.optString("bootIdentity"))||record.optLong("elapsedRealtimeAnchor")>SystemClock.elapsedRealtime()) {
                if(!"RECOVERY_REQUIRED".equals(record.optString("status"))) {
                    ExpansionModels.put(record,"status","RECOVERY_REQUIRED");ExpansionModels.put(record,"gpsState",record.optBoolean("gpsRequested")?"REBOOT_GAP":"NOT_REQUESTED");
                    StrengthDiary.interruptRest(record);
                    save(record);log("recovery","BLOCKED","BOOT_OR_ANCHOR_CHANGED");
                }
            }else if(metricRecovery&&"RUNNING".equals(record.optString("status")))store.transaction(()->{openSegment(record,System.currentTimeMillis(),SystemClock.elapsedRealtime());save(record);});
            return record;
        }return null;
    }
    JSONObject start(String typeId,Integer durationMinutes,boolean gps,String planId) {
        if(WorkoutCatalog.find(typeId)==null||"rest".equals(typeId))throw new IllegalArgumentException("WORKOUT_TYPE");
        if(current()!=null)throw new IllegalStateException("WORKOUT_ALREADY_ACTIVE");
        if(durationMinutes!=null&&(durationMinutes<1||durationMinutes>1440))throw new IllegalArgumentException("WORKOUT_GOAL");
        JSONObject r=ExpansionModels.record(ExpansionModels.PHONE);long now=SystemClock.elapsedRealtime();
        ExpansionModels.put(r,"typeId",typeId);ExpansionModels.put(r,"status","RUNNING");ExpansionModels.put(r,"startedAt",System.currentTimeMillis());
        ExpansionModels.put(r,"elapsedActiveMillis",0);ExpansionModels.put(r,"accumulatedPauseMillis",0);ExpansionModels.put(r,"elapsedRealtimeAnchor",now);
        ExpansionModels.put(r,"bootIdentity",boot());ExpansionModels.put(r,"goalMinutes",durationMinutes);ExpansionModels.put(r,"planId",planId);
        ExpansionModels.put(r,"gpsRequested",gps);ExpansionModels.put(r,"gpsState",gps?"WAITING_PERMISSION_OR_PROVIDER":"NOT_REQUESTED");
        ExpansionModels.put(r,"gpsProcessId",PROCESS_ID);
        ExpansionModels.put(r,"distanceMeters",null);ExpansionModels.put(r,"heartRate",null);ExpansionModels.put(r,"calories",null);
        ExpansionModels.put(r,"lapCount",0);ExpansionModels.put(r,"gpsAccepted",0);ExpansionModels.put(r,"gpsRejected",0);ExpansionModels.put(r,"note","");
        ExpansionModels.put(r,"zoneIdAtStart",java.time.ZoneId.systemDefault().getId());ExpansionModels.put(r,"localDateAtStart",ExpansionModels.date(r.optLong("startedAt"),r.optString("zoneIdAtStart")));
        ExpansionModels.put(r,"stepsObserved",null);ExpansionModels.put(r,"stepsCoverageMillis",0);ExpansionModels.put(r,"heartSampleCount",0);ExpansionModels.put(r,"heartAverage",null);ExpansionModels.put(r,"heartMax",null);ExpansionModels.put(r,"heartLast",null);ExpansionModels.put(r,"heartLastMeasuredAt",null);
        ExpansionModels.put(r,"metricState","NO_OBSERVATIONS");ExpansionModels.put(r,"metricDevice","");ExpansionModels.put(r,"metricProcessId",PROCESS_ID);ExpansionModels.put(r,"metricObservationCount",0);ExpansionModels.put(r,"restSeconds",90);StrengthDiary.clearRest(r);
        store.transaction(()->{save(r);openSegment(r,r.optLong("startedAt"),now);save(r);});log("start","SUCCESS","");return r;
    }
    JSONObject pause(String id) {
        JSONObject r=require(id);if("PAUSED".equals(r.optString("status")))return r;
        if(!"RUNNING".equals(r.optString("status")))throw new IllegalStateException("WORKOUT_NOT_RUNNING");ensureBoot(r);
        checkpoint(r);store.transaction(()->{closeSegment(r,"PAUSE",true);resetBaseline(r);StrengthDiary.clearRest(r);ExpansionModels.put(r,"status","PAUSED");ExpansionModels.put(r,"pauseAnchor",SystemClock.elapsedRealtime());save(r);});log("pause","SUCCESS","");return r;
    }
    JSONObject resume(String id) {
        JSONObject r=require(id);if("RUNNING".equals(r.optString("status")))return r;
        boolean recovering="RECOVERY_REQUIRED".equals(r.optString("status"));
        if(!recovering&&!"PAUSED".equals(r.optString("status")))throw new IllegalStateException("WORKOUT_NOT_PAUSED");
        long now=SystemClock.elapsedRealtime();
        if(!recovering){ensureBoot(r);ExpansionModels.put(r,"accumulatedPauseMillis",r.optLong("accumulatedPauseMillis")+Math.max(0,now-r.optLong("pauseAnchor",now)));}
        else {ExpansionModels.put(r,"recoveryGapUnknown",true);ExpansionModels.put(r,"gpsState",r.optBoolean("gpsRequested")?"RECOVERY_ROUTE_GAP":"NOT_REQUESTED");}
        store.transaction(()->{closeSegment(r,"RESUME_BOUNDARY",false);resetBaseline(r);StrengthDiary.clearRest(r);ExpansionModels.put(r,"metricProcessId",PROCESS_ID);ExpansionModels.put(r,"bootIdentity",boot());ExpansionModels.put(r,"elapsedRealtimeAnchor",now);ExpansionModels.put(r,"status","RUNNING");openSegment(r,System.currentTimeMillis(),now);save(r);});log("resume","SUCCESS",recovering?"UNKNOWN_INTERVAL_EXCLUDED":"");return r;
    }
    JSONObject lap(String id) {
        JSONObject r=require(id);ensureBoot(r);if(!"RUNNING".equals(r.optString("status")))throw new IllegalStateException("LAP_REQUIRES_RUNNING");
        if(r.optInt("lapCount")>=2000)throw new IllegalStateException("LAP_LIMIT");
        checkpoint(r);JSONObject lap=ExpansionModels.record(ExpansionModels.PHONE);int count=r.optInt("lapCount")+1;
        ExpansionModels.put(lap,"number",count);ExpansionModels.put(lap,"activeMillis",r.optLong("elapsedActiveMillis"));ExpansionModels.put(lap,"recordedAt",System.currentTimeMillis());
        long previous=r.optLong("lastLapActiveMillis");ExpansionModels.put(lap,"intervalMillis",r.optLong("elapsedActiveMillis")-previous);
        ExpansionModels.put(r,"lapCount",count);ExpansionModels.put(r,"lastLapActiveMillis",r.optLong("elapsedActiveMillis"));
        store.transaction(()->{save(r);store.child("workout_laps",id,lap,lap.optLong("activeMillis"));});log("lap","SUCCESS","");return r;
    }
    JSONObject finish(String id,String note) {
        JSONObject r=require(id);if("SAVED".equals(r.optString("status")))return r;
        if(!isActive(r))throw new IllegalStateException("WORKOUT_TERMINAL");
        boolean reliable=boot().equals(r.optString("bootIdentity"))&&r.optLong("elapsedRealtimeAnchor")<=SystemClock.elapsedRealtime();
        if("RUNNING".equals(r.optString("status"))&&reliable)checkpoint(r);
        if(!reliable||"RECOVERY_REQUIRED".equals(r.optString("status")))ExpansionModels.put(r,"recoveryGapUnknown",true);
        if("PAUSED".equals(r.optString("status"))&&reliable)ExpansionModels.put(r,"accumulatedPauseMillis",r.optLong("accumulatedPauseMillis")+Math.max(0,SystemClock.elapsedRealtime()-r.optLong("pauseAnchor",SystemClock.elapsedRealtime())));
        ExpansionModels.put(r,"status","SAVED");ExpansionModels.put(r,"endedAt",System.currentTimeMillis());ExpansionModels.put(r,"note",ExpansionModels.text(note,2000));
        if(r.optBoolean("gpsRequested"))ExpansionModels.put(r,"gpsState",r.optInt("gpsAccepted")>0?"PARTIAL_ROUTE":"NO_VALID_FIX");
        store.transaction(()->{
            closeSegment(r,"FINISH",reliable&&PROCESS_ID.equals(r.optString("metricProcessId")));resetBaseline(r);StrengthDiary.clearRest(r);
            save(r);Object rawPlan=r.opt("planId");String plan=rawPlan instanceof String?(String)rawPlan:"";if(!plan.isEmpty()){
                JSONObject p=store.find("plans",plan);if(p!=null){ExpansionModels.put(p,"status","DONE");ExpansionModels.put(p,"workoutId",id);store.save("plans",p,p.optLong("sortMillis"),"DONE");}
            }
        });log("finish","SUCCESS","");return r;
    }
    void note(String id,String note){JSONObject r=require(id);ExpansionModels.put(r,"note",ExpansionModels.text(note,2000));save(r);log("note_edit","SUCCESS","");}
    void checkpoint(String id){JSONObject r=require(id);if("RUNNING".equals(r.optString("status"))&&boot().equals(r.optString("bootIdentity"))){checkpoint(r);save(r);}}
    long activeMillis(JSONObject r){long base=r.optLong("elapsedActiveMillis");if("RUNNING".equals(r.optString("status"))&&boot().equals(r.optString("bootIdentity")))return base+Math.max(0,SystemClock.elapsedRealtime()-r.optLong("elapsedRealtimeAnchor"));return base;}
    void gpsState(String id,String state){JSONObject r=require(id);if(!isActive(r))return;ExpansionModels.put(r,"gpsState",state);ExpansionModels.put(r,"gpsProcessId",PROCESS_ID);save(r);log("gps_state","SUCCESS",state);}
    void gpsReject(String id,String reason){JSONObject r=require(id);if(!isActive(r))return;ExpansionModels.put(r,"gpsRejected",r.optInt("gpsRejected")+1);save(r);log("gps_rejected","PARTIAL",reason);}
    void gpsFix(String id,JSONObject fix,Double distanceIncrement) {
        JSONObject r=require(id);if(!"RUNNING".equals(r.optString("status")))return;ensureBoot(r);
        if(r.optInt("gpsAccepted")>=20000)throw new IllegalStateException("GPS_POINT_LIMIT");
        int accepted=r.optInt("gpsAccepted")+1;ExpansionModels.put(r,"gpsAccepted",accepted);ExpansionModels.put(r,"gpsState","RECORDING_PARTIAL");
        if(distanceIncrement!=null){double previous=r.isNull("distanceMeters")?0:r.optDouble("distanceMeters");ExpansionModels.put(r,"distanceMeters",previous+distanceIncrement);}
        checkpoint(r);store.transaction(()->{save(r);store.child("workout_locations",id,fix,fix.optLong("elapsedRealtime"));});
        if(accepted==1||accepted%20==0)log("gps_accepted","SUCCESS","");
    }
    static boolean isActive(JSONObject r){String status=r.optString("status");return "RUNNING".equals(status)||"PAUSED".equals(status)||"RECOVERY_REQUIRED".equals(status);}
    private JSONObject require(String id){JSONObject r=store.find("workouts",id);if(r==null)throw new IllegalArgumentException("WORKOUT_MISSING");if(!ExpansionModels.PHONE.equals(r.optString("source")))throw new IllegalStateException("PHONE_WORKOUT_REQUIRED");return r;}
    private void checkpoint(JSONObject r){ExpansionModels.put(r,"elapsedActiveMillis",activeMillis(r));ExpansionModels.put(r,"elapsedRealtimeAnchor",SystemClock.elapsedRealtime());}
    private void save(JSONObject r){store.save("workouts",r,r.optLong("startedAt"),r.optString("status"));}
    private void ensureBoot(JSONObject r){if(!boot().equals(r.optString("bootIdentity"))||r.optLong("elapsedRealtimeAnchor")>SystemClock.elapsedRealtime())throw new IllegalStateException("RECOVERY_REQUIRED");}
    /** Consumes an already saved successful snapshot. This path never requests watch data. */
    void observeActivity(String device,ActivitySnapshot snapshot,long receivedElapsed,String zoneId){
        if(snapshot==null||device==null||device.isEmpty())return;
        java.time.ZoneId.of(zoneId);
        store.transaction(()->{
            JSONObject workout=observationWorkout(device,snapshot.receivedAt,receivedElapsed);if(workout==null)return;
            JSONObject segment=observationSegment(workout,snapshot.receivedAt,receivedElapsed);if(segment==null)return;
            String day=ExpansionModels.date(snapshot.receivedAt,zoneId);JSONObject old=workout.optJSONObject("stepsBaseline");
            if(old!=null&&segment.optString("id").equals(old.optString("segmentId"))&&receivedElapsed<=old.optLong("receivedElapsed"))return;
            JSONObject row=observation(workout,segment,"ACTIVITY",device,snapshot.receivedAt,receivedElapsed);
            ExpansionModels.put(row,"phoneDay",day);ExpansionModels.put(row,"zoneId",zoneId);ExpansionModels.put(row,"stepsCumulative",snapshot.steps);ExpansionModels.put(row,"calorieRaw",snapshot.calorie);ExpansionModels.put(row,"distanceRaw",snapshot.distance);
            String state="BASELINE";Long delta=null;long coverage=0;
            if(old!=null&&device.equals(old.optString("device"))&&day.equals(old.optString("phoneDay"))&&zoneId.equals(old.optString("zoneId"))&&segment.optString("id").equals(old.optString("segmentId"))&&PROCESS_ID.equals(old.optString("processId"))&&boot().equals(old.optString("bootIdentity"))){
                long interval=receivedElapsed-old.optLong("receivedElapsed");
                if(interval>0&&interval<=600000&&snapshot.steps>=old.optLong("stepsCumulative")){delta=snapshot.steps-old.optLong("stepsCumulative");coverage=interval;state="DELTA";}
                else state=snapshot.steps<old.optLong("stepsCumulative")?"COUNTER_RESET":"INTERVAL_GAP";
            }
            ExpansionModels.put(row,"stepsDelta",delta);ExpansionModels.put(row,"coverageMillis",coverage);ExpansionModels.put(row,"state",state);
            if(!writeObservation(workout,row))return;
            if(delta!=null){ExpansionModels.put(workout,"stepsObserved",(workout.isNull("stepsObserved")?0:workout.optLong("stepsObserved"))+delta);ExpansionModels.put(workout,"stepsCoverageMillis",workout.optLong("stepsCoverageMillis")+coverage);}
            ExpansionModels.put(workout,"stepsBaseline",row);ExpansionModels.put(workout,"metricState","PARTIAL_OBSERVATIONS");touchSegment(segment,snapshot.receivedAt,receivedElapsed);save(workout);
        });
    }
    /** Only validated imported heart records, with acquisition time inside the active segment. */
    void observeHeart(ExpansionModels.HealthRecord record,long receivedElapsed){
        if(record==null||!"heart".equals(record.dataset)||!record.validity.startsWith("VALID")||record.value==null||!Double.isFinite(record.value)||record.value<20||record.value>300||record.tsSeconds<=0||record.tsSeconds>Long.MAX_VALUE/1000)return;
        long measuredAt=record.tsSeconds*1000;if(measuredAt>record.receivedAt)return;
        store.transaction(()->{
            JSONObject workout=observationWorkout(record.device,record.receivedAt,receivedElapsed);if(workout==null)return;
            JSONObject segment=observationSegment(workout,record.receivedAt,receivedElapsed);if(segment==null||measuredAt<segment.optLong("startAt")||measuredAt>record.receivedAt)return;
            boolean conflict=false;
            for(JSONObject old:store.diaryRows("workout_observations",workout.optString("id"))){
                if(!"HEART".equals(old.optString("kind"))||!record.device.equals(old.optString("device"))||measuredAt!=old.optLong("measuredAt"))continue;
                if(record.rawSha.equals(old.optString("rawSha256"))||Double.compare(record.value,old.optDouble("value"))==0)return;
                conflict=true;if(!"CONFLICT".equals(old.optString("state"))){long revision=old.optLong("revision");ExpansionModels.put(old,"state","CONFLICT");ExpansionModels.put(old,"quality","CONFLICT");ExpansionModels.put(old,"revision",revision+1);store.diaryWrite("workout_observations",workout.optString("id"),old,revision);}
            }
            JSONObject row=observation(workout,segment,"HEART",record.device,record.receivedAt,receivedElapsed);
            ExpansionModels.put(row,"measuredAt",measuredAt);ExpansionModels.put(row,"value",record.value);ExpansionModels.put(row,"unit","bpm");ExpansionModels.put(row,"rawSha256",record.rawSha);ExpansionModels.put(row,"evidenceId",record.evidenceId);ExpansionModels.put(row,"healthRecordId",record.id);ExpansionModels.put(row,"zoneId",record.zone);ExpansionModels.put(row,"state",conflict?"CONFLICT":"ACCEPTED");ExpansionModels.put(row,"quality",conflict?"CONFLICT":"VALID");
            if(!writeObservation(workout,row)){if(conflict){heartSummary(workout);save(workout);}return;}
            heartSummary(workout);ExpansionModels.put(workout,"metricState",conflict?"PARTIAL_HEART_CONFLICT":"PARTIAL_OBSERVATIONS");touchSegment(segment,record.receivedAt,receivedElapsed);save(workout);
        });
    }
    void observationGap(String reason){
        store.transaction(()->{for(JSONObject workout:store.list("workouts"))if(isActive(workout)){
            closeSegment(workout,ExpansionModels.text(reason,120),PROCESS_ID.equals(workout.optString("metricProcessId"))&&boot().equals(workout.optString("bootIdentity")));
            resetBaseline(workout);ExpansionModels.put(workout,"metricState","PARTIAL_GAP");ExpansionModels.put(workout,"metricGapReason",ExpansionModels.text(reason,120));save(workout);
        }});
    }
    private JSONObject observationWorkout(String device,long receivedAt,long receivedElapsed){
        if(receivedAt<=0||receivedElapsed<0||receivedElapsed>SystemClock.elapsedRealtime())return null;
        for(JSONObject workout:store.list("workouts")){
            if(!"RUNNING".equals(workout.optString("status"))||!ExpansionModels.PHONE.equals(workout.optString("source")))continue;
            if(!boot().equals(workout.optString("bootIdentity"))||!PROCESS_ID.equals(workout.optString("metricProcessId"))||receivedAt<workout.optLong("startedAt"))return null;
            if(workout.optBoolean("metricLimitReached"))return null;
            String bound=workout.optString("metricDevice");if(!bound.isEmpty()&&!device.equals(bound))return null;
            return workout;
        }return null;
    }
    private JSONObject observationSegment(JSONObject workout,long receivedAt,long receivedElapsed){
        JSONObject segment=currentSegment(workout);
        if(segment==null){openSegment(workout,receivedAt,receivedElapsed);save(workout);segment=currentSegment(workout);}
        if(segment==null||receivedElapsed<segment.optLong("startElapsed")||receivedAt<segment.optLong("startAt")||!PROCESS_ID.equals(segment.optString("processId"))||!boot().equals(segment.optString("bootIdentity")))return null;
        return segment;
    }
    private JSONObject observation(JSONObject workout,JSONObject segment,String kind,String device,long receivedAt,long receivedElapsed){
        JSONObject row=ExpansionModels.record(ExpansionModels.WATCH);ExpansionModels.put(row,"workoutId",workout.optString("id"));ExpansionModels.put(row,"segmentId",segment.optString("id"));ExpansionModels.put(row,"kind",kind);ExpansionModels.put(row,"device",device);ExpansionModels.put(row,"receivedAt",receivedAt);ExpansionModels.put(row,"receivedElapsed",receivedElapsed);ExpansionModels.put(row,"processId",PROCESS_ID);ExpansionModels.put(row,"bootIdentity",boot());ExpansionModels.put(row,"revision",0);return row;
    }
    private boolean writeObservation(JSONObject workout,JSONObject row){
        int count=workout.optInt("metricObservationCount");if(count>=20000){ExpansionModels.put(workout,"metricState","LIMIT_REACHED");ExpansionModels.put(workout,"metricLimitReached",true);resetBaseline(workout);save(workout);return false;}
        store.diaryWrite("workout_observations",workout.optString("id"),row,-1);ExpansionModels.put(workout,"metricObservationCount",count+1);ExpansionModels.put(workout,"metricDevice",row.optString("device"));return true;
    }
    private void heartSummary(JSONObject workout){
        int count=0;double sum=0,max=0,last=0;long lastAt=0;
        for(JSONObject row:store.diaryRows("workout_observations",workout.optString("id"))){
            if(!"HEART".equals(row.optString("kind"))||!"ACCEPTED".equals(row.optString("state")))continue;
            double value=row.optDouble("value");count++;sum+=value;max=Math.max(max,value);long at=row.optLong("measuredAt");if(at>lastAt){lastAt=at;last=value;}
        }
        ExpansionModels.put(workout,"heartSampleCount",count);ExpansionModels.put(workout,"heartAverage",count==0?null:sum/count);ExpansionModels.put(workout,"heartMax",count==0?null:max);ExpansionModels.put(workout,"heartLast",count==0?null:last);ExpansionModels.put(workout,"heartLastMeasuredAt",count==0?null:lastAt);
    }
    private JSONObject currentSegment(JSONObject workout){String id=workout.optString("metricSegmentId");if(id.isEmpty())return null;JSONObject row=store.diaryFind("workout_segments",id);return row!=null&&"OPEN".equals(row.optString("state"))?row:null;}
    private void openSegment(JSONObject workout,long at,long elapsed){
        if(currentSegment(workout)!=null||workout.optBoolean("metricLimitReached"))return;
        if(store.diaryRows("workout_segments",workout.optString("id")).size()>=128){ExpansionModels.put(workout,"metricState","LIMIT_REACHED");ExpansionModels.put(workout,"metricLimitReached",true);resetBaseline(workout);save(workout);return;}
        JSONObject row=ExpansionModels.record(ExpansionModels.PHONE);ExpansionModels.put(row,"workoutId",workout.optString("id"));ExpansionModels.put(row,"state","OPEN");ExpansionModels.put(row,"revision",0);ExpansionModels.put(row,"startAt",at);ExpansionModels.put(row,"startElapsed",elapsed);ExpansionModels.put(row,"lastAt",at);ExpansionModels.put(row,"lastElapsed",elapsed);ExpansionModels.put(row,"bootIdentity",boot());ExpansionModels.put(row,"processId",PROCESS_ID);ExpansionModels.put(row,"endAt",null);ExpansionModels.put(row,"endElapsed",null);
        store.diaryWrite("workout_segments",workout.optString("id"),row,-1);ExpansionModels.put(workout,"metricSegmentId",row.optString("id"));ExpansionModels.put(workout,"metricProcessId",PROCESS_ID);
    }
    private void touchSegment(JSONObject segment,long at,long elapsed){long revision=segment.optLong("revision");ExpansionModels.put(segment,"lastAt",Math.max(segment.optLong("lastAt"),at));ExpansionModels.put(segment,"lastElapsed",Math.max(segment.optLong("lastElapsed"),elapsed));ExpansionModels.put(segment,"revision",revision+1);store.diaryWrite("workout_segments",segment.optString("workoutId"),segment,revision);}
    private void closeSegment(JSONObject workout,String reason,boolean reliable){
        JSONObject row=currentSegment(workout);if(row==null)return;long revision=row.optLong("revision");
        boolean known=reliable&&boot().equals(row.optString("bootIdentity"))&&PROCESS_ID.equals(row.optString("processId"));
        ExpansionModels.put(row,"state","CLOSED");ExpansionModels.put(row,"endAt",known?System.currentTimeMillis():row.optLong("lastAt"));ExpansionModels.put(row,"endElapsed",known?SystemClock.elapsedRealtime():row.optLong("lastElapsed"));ExpansionModels.put(row,"endReason",reason);ExpansionModels.put(row,"unknownGapAfter",!known);ExpansionModels.put(row,"revision",revision+1);store.diaryWrite("workout_segments",workout.optString("id"),row,revision);ExpansionModels.put(workout,"metricSegmentId","");
    }
    private static void resetBaseline(JSONObject workout){workout.remove("stepsBaseline");}
    private String boot(){return bootIdentity(context);}
    static String bootIdentity(Context context){try{return "boot:"+Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT);}catch(Exception e){return "unverified-boot-process:"+UNKNOWN_BOOT_PROCESS;}}
    private void log(String action,String outcome,String error){String op=journal.begin("phone_workout",action,"PHONE","");journal.stage(op,"phone_workout",action,"PERSISTED",outcome,error,1,0,0);}
    static String duration(long millis){long seconds=Math.max(0,millis/1000);return String.format(java.util.Locale.getDefault(),"%02d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60);}
}
