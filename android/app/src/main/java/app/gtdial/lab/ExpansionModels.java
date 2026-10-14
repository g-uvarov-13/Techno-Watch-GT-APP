package app.gtdial.lab;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/** Local records keep their original clock, source and interpretation independently. */
final class ExpansionModels {
    static final String WATCH="WATCH_TSDB", PHONE="PHONE_WORKOUT", MANUAL="MANUAL";
    static JSONObject record(String source) {
        JSONObject value=new JSONObject();
        put(value,"id",UUID.randomUUID().toString()); put(value,"source",source);
        put(value,"receivedAtMillis",System.currentTimeMillis());
        put(value,"zoneIdAtImport",ZoneId.systemDefault().getId()); put(value,"schema",1);
        return value;
    }
    static void put(JSONObject object,String key,Object value) {
        try { object.put(key,value==null?JSONObject.NULL:value); }
        catch(JSONException e) { throw new IllegalArgumentException("LOCAL_JSON",e); }
    }
    static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value.toString()); }
        catch(JSONException e) { throw new IllegalArgumentException("LOCAL_JSON",e); }
    }
    static String text(String value,int limit) {
        if(value==null)return "";
        String clean=value.replace('\u0000',' ').trim();
        if(clean.length()>limit)throw new IllegalArgumentException("TEXT_TOO_LONG");
        return clean;
    }
    static void requireId(String value) {
        if(value==null||!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("INVALID_ID");
    }
    static long start(LocalDate date,ZoneId zone) { return date.atStartOfDay(zone).toInstant().toEpochMilli(); }
    static String date(long millis,String zone) { return Instant.ofEpochMilli(millis).atZone(ZoneId.of(zone)).toLocalDate().toString(); }

    static final class HealthRecord {
        final String id,device,dataset,evidenceId,rawSha,pageState,validity,coverage,zone,rawJson;
        final long tsSeconds,receivedAt;
        final Double value;
        final JSONObject data;
        HealthRecord(String id,String device,String dataset,String evidenceId,String rawSha,long ts,long receivedAt,
                String zone,String pageState,String validity,String coverage,Double value,String rawJson,JSONObject data) {
            this.id=id;this.device=device;this.dataset=dataset;this.evidenceId=evidenceId;this.rawSha=rawSha;
            tsSeconds=ts;this.receivedAt=receivedAt;this.zone=zone;this.pageState=pageState;
            this.validity=validity;this.coverage=coverage;this.value=value;this.rawJson=rawJson;this.data=data;
        }
        JSONObject json(boolean includeDevice) {
            JSONObject out=new JSONObject();put(out,"id",id);put(out,"source",WATCH);put(out,"dataset",dataset);
            put(out,"timestamp",tsSeconds);put(out,"timestampUnit","SECONDS");put(out,"receivedAtMillis",receivedAt);
            put(out,"zoneIdAtImport",zone);put(out,"evidenceId",evidenceId);put(out,"rawSha256",rawSha);
            put(out,"pageState",pageState);put(out,"recordValidity",validity);put(out,"datasetCoverage",coverage);
            put(out,"value",value);put(out,"data",data);if(includeDevice)put(out,"sourceDevice",device);return out;
        }
    }
    static JSONArray array(java.util.List<JSONObject> records) { JSONArray out=new JSONArray();for(JSONObject r:records)out.put(r);return out; }
    static String alarmDraftHash(JSONObject draft) {
        AlarmEditorUi.validate(draft);
        JSONArray fields=new JSONArray().put(draft.optInt("hour")).put(draft.optInt("minute"))
                .put(draft.optBoolean("enabled")).put(draft.optString("label"));
        java.util.Set<Integer> days=new java.util.TreeSet<>();JSONArray selected=draft.optJSONArray("weekdays");
        for(int i=0;i<selected.length();i++)days.add(selected.optInt(i));
        JSONArray cycle=new JSONArray();for(int day:days)cycle.put(day);fields.put(cycle);
        try{return HealthEvidenceImporter.sha(fields.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        catch(Exception e){throw new IllegalStateException("ALARM_DRAFT_HASH",e);}
    }
    private ExpansionModels() { }
}
