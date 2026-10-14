package app.gtdial.lab;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** One immutable mutation of an exact, known GT01 alarm array. No plan unlocks the gate. */
final class AlarmTransferPlan {
    enum Action { CREATE, UPDATE, DELETE, RESTORE }
    final Action action;
    final String device,baseHash,expectedHash,draftId,draftHash,restoredOperationId;
    final long sourceReceivedAt;
    final int index,alarmId;
    final AlarmProtocol.Snapshot original,proposed;
    private final byte[] body;

    private AlarmTransferPlan(Action action,AlarmProtocol.Snapshot original,AlarmProtocol.Snapshot proposed,
            String draftId,String draftHash,int index,int alarmId,String restoredOperationId){
        this.action=action;device=original.device;baseHash=original.hash;expectedHash=proposed.hash;
        sourceReceivedAt=original.receivedAt;this.draftId=draftId;this.draftHash=draftHash;
        this.index=index;this.alarmId=alarmId;this.original=original;this.proposed=proposed;
        this.restoredOperationId=restoredOperationId;
        JSONObject values=new JSONObject(),request=new JSONObject();
        ExpansionModels.put(values,"alarms",proposed.wireArray());ExpansionModels.put(request,"values",values);
        body=request.toString().getBytes(StandardCharsets.UTF_8);
        if(original.same(proposed))throw new IllegalArgumentException("ALARM_NO_CHANGE");
    }
    static AlarmTransferPlan create(AlarmProtocol.Snapshot source,JSONObject draft){
        requireSource(source);AlarmEditorUi.validate(draft);
        if(draft.optJSONObject("watchBinding")!=null)throw new IllegalArgumentException("ALARM_ALREADY_BOUND");
        if(source.entries.size()>=3)throw new IllegalArgumentException("ALARM_CAPACITY");
        Set<Integer> occupied=new HashSet<>();for(AlarmProtocol.Entry entry:source.entries)occupied.add(entry.alarmId);
        int id=0;while(id<3&&occupied.contains(id))id++;if(id==3)throw new IllegalArgumentException("ALARM_CAPACITY");
        int index=nextIndex(source);JSONArray alarms=source.wireArray();JSONObject entry=new JSONObject();
        ExpansionModels.put(entry,"index",index);ExpansionModels.put(entry,"alarmId",id);
        ExpansionModels.put(entry,"name","");applyFields(entry,draft);alarms.put(entry);
        return plan(Action.CREATE,source,alarms,draft,index,id);
    }
    static AlarmTransferPlan update(AlarmProtocol.Snapshot source,JSONObject draft){
        requireSource(source);AlarmEditorUi.validate(draft);AlarmProtocol.Entry target=boundEntry(source,draft);
        JSONArray alarms=source.wireArray();boolean found=false;
        for(int i=0;i<alarms.length();i++){
            JSONObject entry=alarms.optJSONObject(i);
            if(matches(entry,target.index,target.alarmId)){applyFields(entry,draft);found=true;}
        }
        if(!found)throw new IllegalArgumentException("ALARM_BINDING_MISSING");
        return plan(Action.UPDATE,source,alarms,draft,target.index,target.alarmId);
    }
    static AlarmTransferPlan delete(AlarmProtocol.Snapshot source,JSONObject draft){
        requireSource(source);AlarmEditorUi.validate(draft);AlarmProtocol.Entry target=boundEntry(source,draft);
        JSONArray old=source.wireArray(),alarms=new JSONArray();int removed=0;
        for(int i=0;i<old.length();i++){JSONObject entry=old.optJSONObject(i);if(matches(entry,target.index,target.alarmId))removed++;else alarms.put(entry);}
        if(removed!=1)throw new IllegalArgumentException("ALARM_BINDING_MISSING");
        return plan(Action.DELETE,source,alarms,draft,target.index,target.alarmId);
    }
    static AlarmTransferPlan restore(Context context,AlarmProtocol.Snapshot source,File ownBackup)throws Exception{
        requireSource(source);AlarmProtocol.Backup backup=AlarmProtocol.ownBackup(context,source.device,ownBackup);
        if(!source.hash.equals(backup.expectedHash))throw new IllegalArgumentException("ALARM_RESTORE_CURRENT_CHANGED");
        requireSource(backup.original);
        return new AlarmTransferPlan(Action.RESTORE,source,backup.original,backup.draftId,backup.draftHash,
                backup.index,backup.alarmId,backup.operationId);
    }
    private static AlarmTransferPlan plan(Action action,AlarmProtocol.Snapshot source,JSONArray alarms,JSONObject draft,int index,int id){
        JSONObject object=new JSONObject();ExpansionModels.put(object,"alarms",alarms);
        AlarmProtocol.Snapshot proposed=AlarmProtocol.parse(source.device,object.toString().getBytes(StandardCharsets.UTF_8),source.receivedAt);
        requireSource(proposed);
        return new AlarmTransferPlan(action,source,proposed,draft.optString("id"),ExpansionModels.alarmDraftHash(draft),index,id,"");
    }
    private static void requireSource(AlarmProtocol.Snapshot source){
        if(source==null||!source.editable||source.entries.size()>3||source.device==null||!source.device.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))throw new IllegalArgumentException("TRUSTED_ALARM_SNAPSHOT_REQUIRED");
        for(AlarmProtocol.Entry entry:source.entries)if(!entry.known||entry.alarmId<0||entry.alarmId>2)throw new IllegalArgumentException("GT01_ALARM_SCHEMA_REQUIRED");
    }
    private static int nextIndex(AlarmProtocol.Snapshot source){
        if(source.entries.isEmpty())return 1;
        if(source.entries.size()==1)return increment(source.entries.get(0).index);
        int previous=source.entries.get(0).index;
        for(int i=1;i<source.entries.size();i++){int next=source.entries.get(i).index;if((long)next-previous>1)return increment(previous);previous=next;}
        return increment(previous);
    }
    private static int increment(int value){if(value==Integer.MAX_VALUE)throw new IllegalArgumentException("ALARM_INDEX_OVERFLOW");return value+1;}
    private static AlarmProtocol.Entry boundEntry(AlarmProtocol.Snapshot source,JSONObject draft){
        JSONObject binding=draft.optJSONObject("watchBinding");
        if(binding==null||!source.device.equals(binding.optString("device"))||binding.optLong("verifiedAt")<=0)throw new IllegalArgumentException("ALARM_VERIFIED_BINDING_REQUIRED");
        if(!source.hash.equals(binding.optString("snapshotHash")))throw new IllegalArgumentException("ALARM_BOUND_SNAPSHOT_CHANGED");
        return source.byPair(binding.optInt("index",-1),binding.optInt("alarmId",-1));
    }
    private static boolean matches(JSONObject entry,int index,int id){return entry!=null&&entry.optInt("index",-1)==index&&entry.optInt("alarmId",-1)==id;}
    private static void applyFields(JSONObject entry,JSONObject draft){
        ExpansionModels.put(entry,"hour",draft.optInt("hour"));ExpansionModels.put(entry,"minute",draft.optInt("minute"));ExpansionModels.put(entry,"enable",draft.optBoolean("enabled")?1:0);
        Set<Integer> requested=daySet(draft.optJSONArray("weekdays")),existing=daySet(entry.optJSONArray("cycle"));
        // A repeated save must not reorder an unchanged official cycle.
        if(entry.has("cycle")&&requested.equals(existing))return;
        JSONArray cycle=new JSONArray();for(int day:new int[]{7,1,2,3,4,5,6})if(requested.contains(day))cycle.put(day);
        if(cycle.length()==0)cycle.put(0);ExpansionModels.put(entry,"cycle",cycle);
    }
    static Set<Integer> daySet(JSONArray values){Set<Integer> days=new HashSet<>();if(values!=null)for(int i=0;i<values.length();i++){int day=values.optInt(i);if(day>=1&&day<=7)days.add(day);}return days;}
    byte[] body(){return body.clone();}
    int wireSize(){return 13+AlarmProtocol.SET.getBytes(StandardCharsets.UTF_8).length+2+body.length;}
    String preview(){
        AlarmProtocol.Entry before=entry(original),after=entry(proposed);
        String actionName=action==Action.CREATE?"Добавить на часы":action==Action.UPDATE?"Изменить на часах":action==Action.DELETE?"Удалить с часов":"Восстановить сохранённое расписание";
        if(action==Action.RESTORE)return actionName+". Будет восстановлен полный список из собственной резервной копии.";
        return actionName+"\nБыло: "+describe(before)+"\nБудет: "+describe(after)+"\nНазвание хранится в приложении. Остальные будильники сохраняются.";
    }
    private AlarmProtocol.Entry entry(AlarmProtocol.Snapshot snapshot){for(AlarmProtocol.Entry entry:snapshot.entries)if(entry.index==index&&entry.alarmId==alarmId)return entry;return null;}
    private static String describe(AlarmProtocol.Entry entry){
        if(entry==null)return "нет записи";
        JSONObject value=new JSONObject();ExpansionModels.put(value,"hour",entry.hour);ExpansionModels.put(value,"minute",entry.minute);
        JSONArray days=new JSONArray();for(int day:new int[]{1,2,3,4,5,6,7})if(daySet(entry.wire().optJSONArray("cycle")).contains(day))days.put(day);
        ExpansionModels.put(value,"weekdays",days);return AlarmEditorUi.label(value)+" · "+(entry.enable==1?"включён":"выключен");
    }
}
