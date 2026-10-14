package app.gtdial.lab;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.Map;

/** Official RTT interpretation only; unfamiliar wire shapes remain preserved, never guessed. */
final class HealthDatasetParser {
    static final String METHOD="RTT FitnessComponent: seconds; raw 0 awake, 1/2 light, 3 deep; REM unconfirmed";
    static final class Parsed {
        final String validity;
        final Double value;
        final JSONObject data;
        Parsed(String validity,Double value,JSONObject data){this.validity=validity;this.value=value;this.data=data;}
    }
    static Parsed parse(String dataset,byte[] rawD) {
        JSONObject out=new JSONObject();Object raw=UbjsonCodec.decode(rawD);
        if("sports".equals(dataset))return sports(raw,out);
        if("heart".equals(dataset)||"spo2".equals(dataset)) {
            Long value=decimalInteger(raw);
            if(value==null||value<=0)return new Parsed("UNSUPPORTED_OR_NONPOSITIVE",null,out);
            // These are local plausibility exclusions, never a medical diagnosis or a wire rewrite.
            if("spo2".equals(dataset)&&value>100||"heart".equals(dataset)&&(value<20||value>300)) {
                ExpansionModels.put(out,"errorCode","LOCAL_PLAUSIBILITY_EXCLUSION");return new Parsed("IMPLAUSIBLE_RAW",null,out);
            }
            ExpansionModels.put(out,"unit","heart".equals(dataset)?"bpm":"%");ExpansionModels.put(out,"method","RTT positive integer observation");
            return new Parsed("VALID",value.doubleValue(),out);
        }
        if(!"sleep".equals(dataset))return new Parsed("UNSUPPORTED_DATASET",null,out);
        try {
            JSONObject sleep=raw instanceof String?new JSONObject((String)raw):raw instanceof Map?new JSONObject((Map<?,?>)raw):null;
            if(sleep==null)return new Parsed("UNSUPPORTED_WIRE_SHAPE",null,out);
            long enter=integer(sleep.opt("enter_sleep_stamp")),exit=integer(sleep.opt("exit_sleep_stamp"));
            if(enter<0||exit<=enter||exit>253402300799L||exit-enter>7L*86400)throw new IllegalArgumentException("SLEEP_BOUNDS");
            JSONArray states=sleep.optJSONArray("states");
            if(states==null||states.length()==0||states.length()>4096)throw new IllegalArgumentException("SLEEP_STATES");
            JSONArray intervals=new JSONArray();long previousStamp=-1,asleep=0,awake=0,unknown=0;
            for(int i=0;i<states.length();i++) {
                JSONObject state=states.getJSONObject(i);long stamp=integer(state.opt("timestamp")),code=integer(state.opt("value"));
                if(stamp<0||stamp>=exit||i>0&&(stamp<enter||stamp<=previousStamp)||code<Integer.MIN_VALUE||code>Integer.MAX_VALUE)throw new IllegalArgumentException("SLEEP_STATE_ORDER");
                long begin=i==0?enter:stamp;
                long end=i+1<states.length()?integer(states.getJSONObject(i+1).opt("timestamp")):exit;
                if(end<=begin||end>exit)throw new IllegalArgumentException("SLEEP_INTERVAL");
                JSONObject interval=new JSONObject();ExpansionModels.put(interval,"start",begin);ExpansionModels.put(interval,"end",end);
                ExpansionModels.put(interval,"rawTimestamp",stamp);ExpansionModels.put(interval,"rawStage",code);ExpansionModels.put(interval,"stage",stage(code));intervals.put(interval);
                long duration=end-begin;if(code==0)awake+=duration;else if(code>=1&&code<=3)asleep+=duration;else unknown+=duration;previousStamp=stamp;
            }
            ExpansionModels.put(out,"enter",enter);ExpansionModels.put(out,"exit",exit);ExpansionModels.put(out,"intervals",intervals);
            ExpansionModels.put(out,"asleepSeconds",asleep);ExpansionModels.put(out,"awakeSeconds",awake);ExpansionModels.put(out,"unknownSeconds",unknown);
            ExpansionModels.put(out,"type",sleep.has("type")?sleep.opt("type"):JSONObject.NULL);ExpansionModels.put(out,"method",METHOD);
            ExpansionModels.put(out,"rawSummary",sleep);return new Parsed(unknown==0?"VALID":"VALID_UNKNOWN_STAGES",null,out);
        }catch(Exception e){ExpansionModels.put(out,"errorCode","SLEEP_STRUCTURE_INVALID");return new Parsed("INVALID",null,out);}
    }
    static String stage(long code) {return code==0?"Бодрствование":code==1||code==2?"Лёгкий":code==3?"Глубокий":"Стадия "+code;}
    private static Parsed sports(Object raw,JSONObject out){try{JSONObject summary=raw instanceof String?new JSONObject((String)raw):raw instanceof Map?new JSONObject((Map<?,?>)raw):null;if(summary==null)return new Parsed("UNSUPPORTED_WIRE_SHAPE",null,out);
        long record=integer(summary.opt("recordId")),type=integer(summary.opt("workoutType")),status=integer(summary.opt("status")),start=integer(summary.opt("startTime")),end=integer(summary.opt("endTime"));if(record<0||type<Integer.MIN_VALUE||type>Integer.MAX_VALUE||status<Integer.MIN_VALUE||status>Integer.MAX_VALUE||start<0||end<start||end>253402300799L)throw new IllegalArgumentException("SPORTS_BOUNDS");
        ExpansionModels.put(out,"recordId",record);ExpansionModels.put(out,"workoutType",type);ExpansionModels.put(out,"status",status);ExpansionModels.put(out,"startTime",start);ExpansionModels.put(out,"endTime",end);ExpansionModels.put(out,"timestampUnit","SECONDS");ExpansionModels.put(out,"rawFields",summary);ExpansionModels.put(out,"method","Official RTT summary; end-start is an interval, not active duration");return new Parsed("VALID",null,out);
    }catch(Exception e){ExpansionModels.put(out,"errorCode","SPORTS_STRUCTURE_INVALID");return new Parsed("INVALID",null,out);}}
    static Object jsonValue(Object raw) {if(raw instanceof Map)return new JSONObject((Map<?,?>)raw);if(raw instanceof List)return new JSONArray((List<?>)raw);return raw;}
    static Parsed workoutDetail(String dataset,byte[] rawD,JSONObject parent){JSONObject out=new JSONObject();try{Object raw=UbjsonCodec.decode(rawD);ExpansionModels.put(out,"parentRecordId",parent.getLong("recordId"));ExpansionModels.put(out,"parentRawSha256",parent.getString("parentRawSha256"));
        if("sports_graph".equals(dataset)){Object ids=parent.optJSONObject("rawFields")==null?null:parent.optJSONObject("rawFields").opt("graphId");JSONArray graphIds=ids instanceof JSONArray?(JSONArray)ids:ids instanceof String?new JSONArray((String)ids):null;JSONArray values=raw instanceof List?new JSONArray((List<?>)raw):raw instanceof String?new JSONArray((String)raw):null;if(graphIds==null||values==null||graphIds.length()==0||graphIds.length()>32||values.length()!=graphIds.length())throw new IllegalArgumentException("SPORT_GRAPH_SHAPE");JSONArray columns=new JSONArray();for(int i=0;i<values.length();i++){Object value=values.opt(i),id=graphIds.opt(i);if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue())||!(id instanceof Integer)&&!(id instanceof Long))throw new IllegalArgumentException("SPORT_GRAPH_VALUE");JSONObject column=new JSONObject();ExpansionModels.put(column,"graphId",id);ExpansionModels.put(column,"value",value);ExpansionModels.put(column,"name",column(((Number)id).longValue()));columns.put(column);}ExpansionModels.put(out,"columns",columns);return new Parsed("VALID",null,out);}
        JSONObject fields=raw instanceof String?new JSONObject((String)raw):raw instanceof Map?new JSONObject((Map<?,?>)raw):null;if(fields==null)return new Parsed("UNSUPPORTED_WIRE_SHAPE",null,out);
        String[] known="sports_spo2".equals(dataset)?new String[]{"currentSportTime","value"}:"sports_pace".equals(dataset)?new String[]{"index","unit","pace","distanceTail"}:"sports_section_run".equals(dataset)?new String[]{"index","time","distance","pace","heartRate","cadence","stepLength","maxSpeed","avgSpeed"}:"sports_section_swim".equals(dataset)?new String[]{"index","time","distance","pace","unit","swimType","swimPullTimes","avgSwolf"}:null;if(known==null)return new Parsed("UNSUPPORTED_DATASET",null,out);
        JSONObject reported=new JSONObject();for(String key:known)if(fields.has(key)){Object value=fields.opt(key);if(value instanceof Number&&!Double.isFinite(((Number)value).doubleValue()))throw new IllegalArgumentException("SPORT_DETAIL_FINITE");ExpansionModels.put(reported,key,value);}ExpansionModels.put(out,"reportedFields",reported);ExpansionModels.put(out,"rawFields",fields);ExpansionModels.put(out,"method","Official RTT optional source fields; time/unit/distance scales are not inferred");return new Parsed("VALID",null,out);
    }catch(Exception e){ExpansionModels.put(out,"errorCode","SPORT_DETAIL_STRUCTURE");return new Parsed("INVALID",null,out);}}
    static String column(long id){return id==0?"speed":id==1?"trashFrequency":id==2?"swimEfficiency":id==3||id==6?"stepFrequency":id==4?"heartRate":id==5?"jumpSpeed":"Столбец "+id;}
    private static long integer(Object value) {if(!(value instanceof Integer)&&!(value instanceof Long))throw new IllegalArgumentException("INTEGER_REQUIRED");return ((Number)value).longValue();}
    private static Long decimalInteger(Object value) {
        if(value instanceof Long||value instanceof Integer)return ((Number)value).longValue();
        if(value instanceof String&&((String)value).matches("[+-]?[0-9]+")){try{return Long.parseLong((String)value);}catch(NumberFormatException ignored){return null;}}
        return null;
    }
    private HealthDatasetParser(){ }
}
