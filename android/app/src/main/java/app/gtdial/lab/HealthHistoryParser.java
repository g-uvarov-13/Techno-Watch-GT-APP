package app.gtdial.lab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Strict bounded envelope; does not interpret sleep stages, durations or units. */
final class HealthHistoryParser {
    static final class Page {
        final List<HealthHistoryRecord> records;
        final boolean partial;
        Page(List<HealthHistoryRecord> records,boolean partial){this.records=Collections.unmodifiableList(records);this.partial=partial;}
    }
    static Page parse(byte[] bytes){return parse(bytes,TsdbReadProtocol.Profile.SLEEP_PAGE);}
    static Page parse(byte[] bytes,TsdbReadProtocol.Profile profile){
        return parseBounded(bytes,profile.responseCap,profile==TsdbReadProtocol.Profile.HEART_LAST,profile!=TsdbReadProtocol.Profile.HEART_LAST&&profile!=TsdbReadProtocol.Profile.SPO2_PAGE,profile==TsdbReadProtocol.Profile.SPORTS_PAGE||profile==TsdbReadProtocol.Profile.SPO2_PAGE);
    }
    /** Saved official datasets only. This accessor is never used to construct a transport command. */
    static Page parseLocal(byte[] bytes,int cap){if(cap<1||cap>256)throw new IllegalArgumentException("Local dataset cap");return parseBounded(bytes,cap,false,true,true);}
    private static Page parseBounded(byte[] bytes,int cap,boolean heart,boolean fullAtCap,boolean rejectZeroFull){
        Object decoded=UbjsonCodec.decode(bytes);
        if(!(decoded instanceof Map))throw new IllegalArgumentException("TSDB envelope object");
        Map<?,?> envelope=(Map<?,?>)decoded;
        long result=integer(envelope.get("result"),"result");
        if(result!=0)throw new IllegalArgumentException("TSDB result="+result+"; unavailable/error, not empty");
        long count=integer(envelope.get("count"),"count");
        if(count<0||count>cap)throw new IllegalArgumentException("TSDB count bounds");
        Object full=envelope.get("buf_is_full");
        if(!(full instanceof Boolean))throw new IllegalArgumentException("TSDB buf_is_full boolean");
        if(rejectZeroFull&&count==0&&(Boolean)full)throw new IllegalArgumentException("TSDB count zero with full buffer");
        Object rows=envelope.get("records");
        if(!envelope.containsKey("records")&&count==0)rows=Collections.emptyList();
        if(!(rows instanceof List)||((List<?>)rows).size()!=count)throw new IllegalArgumentException("TSDB records/count mismatch");
        ArrayList<HealthHistoryRecord> records=new ArrayList<>();
        for(Object row:(List<?>)rows){
            if(!(row instanceof Map))throw new IllegalArgumentException("TSDB record object");
            Map<?,?> record=(Map<?,?>)row;
            long ts=integer(record.get("ts"),"ts");
            if(ts<0||ts>253402300799L||!record.containsKey("d"))throw new IllegalArgumentException("TSDB record ts/d");
            Object data=record.get("d");
            records.add(heart?new HealthHistoryRecord(ts,data,heartRate(data)):new HealthHistoryRecord(ts,data));
        }
        return new Page(records,(Boolean)full||fullAtCap&&count==cap);
    }
    private static long heartRate(Object data){
        if(data instanceof Long)return (Long)data;
        if(data instanceof String&&((String)data).matches("[+-]?[0-9]+")){
            try{return Long.parseLong((String)data);}catch(NumberFormatException e){throw new IllegalArgumentException("Heart rate decimal integer overflow");}
        }
        throw new IllegalArgumentException("Unsupported heart-rate d type; original response retained");
    }
    private static long integer(Object value,String name){if(!(value instanceof Long))throw new IllegalArgumentException("TSDB "+name+" integer");return (Long)value;}
    private HealthHistoryParser(){}
}
