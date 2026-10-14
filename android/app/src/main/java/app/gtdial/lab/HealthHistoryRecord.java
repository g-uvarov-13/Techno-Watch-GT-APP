package app.gtdial.lab;

/** Original TSDB timestamp and uninterpreted d value; full wire bytes stay in evidence. */
final class HealthHistoryRecord {
    final long ts;
    final Long heartRateRaw;
    private final byte[] rawD;
    HealthHistoryRecord(long ts,Object data){this(ts,data,null);}
    HealthHistoryRecord(long ts,Object data,Long heartRateRaw){this.ts=ts;this.rawD=UbjsonCodec.encode(data);this.heartRateRaw=heartRateRaw;}
    boolean heartRateAvailable(){return heartRateRaw!=null&&heartRateRaw>0;}
    byte[] rawDataUbjson(){return rawD.clone();}
}
