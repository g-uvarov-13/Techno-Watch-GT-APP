package app.gtdial.lab;

import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One explicit closed official TSDB profile. Bounded sleep continuation; no retry, count query or remote write path. */
final class TsdbReadProtocol {
    static final String PATH="/data/share/system/activity_sleep";
    enum Profile {
        SPO2_PAGE("/data/share/system/activity_spo2",-1,256), SLEEP_PAGE(PATH,5), HEART_LAST("/data/share/system/activity_heartRate",1), SPORTS_PAGE("/data/share/system/sports_record",5);
        final String path;final int count,responseCap;
        Profile(String path,int count){this(path,count,count);}
        Profile(String path,int count,int cap){this.path=path;this.count=count;this.responseCap=cap;}
    }
    static final long MAX_RANGE_SECONDS=7L*24*60*60;
    interface Transport {boolean write(byte[] frame);void log(String message);void finished(Result result); default void persist(Result result,java.util.function.Consumer<Result> acknowledged){acknowledged.accept(null);} default int nextPacketId(){throw new IllegalStateException("packet reservation unavailable");}}
    /** Whole-chain counts include only pages acknowledged after durable save and import. */
    static final class SleepSummary {
        final int savedPages,savedRecords,usableRecords;
        final long latestTimestamp;
        final boolean historyComplete;
        SleepSummary(int pages,int records,int usable,long latest,boolean complete){savedPages=pages;savedRecords=records;usableRecords=usable;latestTimestamp=latest;historyComplete=complete;}
    }
    static final class Result {
        final String state,detail,deviceAddress,eventId,profile,path;
        final int requestedCount,index;
        final long fromUnix,toUnix,receivedAt;
        final List<HealthHistoryRecord> records;
        final File evidenceFile;
        final SleepSummary sleepSummary;
        private final byte[] raw,request,receivedMcf;
        Result(Profile profile,String state,String detail,String device,String eventId,long from,long to,List<HealthHistoryRecord> records,byte[] raw,byte[] request,byte[] receivedMcf,File file,long receivedAt){
            this(profile,state,detail,device,eventId,from,to,records,raw,request,receivedMcf,file,receivedAt,profile==Profile.SLEEP_PAGE?new SleepSummary(0,0,0,0,false):null);
        }
        private Result(Profile profile,String state,String detail,String device,String eventId,long from,long to,List<HealthHistoryRecord> records,byte[] raw,byte[] request,byte[] receivedMcf,File file,long receivedAt,SleepSummary summary){
            this.sleepSummary=summary;
            this.profile=profile.name();this.path=profile.path;this.requestedCount=profile.count;this.state=state;this.detail=detail;this.deviceAddress=device;this.eventId=eventId;this.fromUnix=from;this.toUnix=to;
            Object decodedRequest=request.length==0?null:UbjsonCodec.decode(request);this.index=decodedRequest instanceof Map?((Number)((Map<?,?>)decodedRequest).get("index")).intValue():0;
            this.records=Collections.unmodifiableList(new java.util.ArrayList<>(records));this.raw=raw.clone();this.request=request.clone();this.receivedMcf=receivedMcf.clone();this.evidenceFile=file;this.receivedAt=receivedAt;
        }
        byte[] rawResponse(){return raw.clone();}
        byte[] requestBytes(){return request.clone();}
        byte[] receivedMcfBytes(){return receivedMcf.clone();}
        Result withSleepSummary(SleepSummary summary){return new Result(Profile.valueOf(profile),state,detail,deviceAddress,eventId,fromUnix,toUnix,records,raw,request,receivedMcf,evidenceFile,receivedAt,summary);}
        Result described(String reason){return new Result(Profile.valueOf(profile),state,reason,deviceAddress,eventId,fromUnix,toUnix,records,raw,request,receivedMcf,evidenceFile,receivedAt,sleepSummary);}
        Result saved(File file){return new Result(Profile.valueOf(profile),state,detail,deviceAddress,eventId,fromUnix,toUnix,records,raw,request,receivedMcf,file,receivedAt,sleepSummary);}
        Result saveFailed(String reason){return new Result(Profile.valueOf(profile),"FAILED",reason,deviceAddress,eventId,fromUnix,toUnix,records,raw,request,receivedMcf,null,receivedAt,sleepSummary);}
    }
    private final String device; private String eventId=UUID.randomUUID().toString();
    private final long from,to;
    private final Profile profile;
    private final Transport transport;
    private final int maxFrameBytes; private int packetId,index,pages,total,saveGeneration; private boolean awaitingSave; private long latestTimestamp; private int usable; private final java.util.Set<String> seenRecords=new java.util.HashSet<>(),seenPages=new java.util.HashSet<>();
    private final Handler handler=new Handler(Looper.myLooper()==null?Looper.getMainLooper():Looper.myLooper());
    private final ByteArrayOutputStream buffer=new ByteArrayOutputStream();
    private int ignoredFrames,ignoredBytes;
    private byte[] expectedFrame;
    private boolean responseDelivered,acknowledgementReceived;
    private Runnable savedContinuation;
    private final Runnable timeout=()->fail("timeout; no retry");
    private final Runnable persistTimeout=()->{if(this.running&&awaitingSave)fail("local page save/import timeout; completed pages preserved; no retry");};
    private boolean running,used;
    private byte[] request=new byte[0],raw=new byte[0];
    TsdbReadProtocol(String device,long from,long to,int maxFrameBytes,int packetId,Transport transport){this(Profile.SLEEP_PAGE,device,from,to,maxFrameBytes,packetId,transport);}
    TsdbReadProtocol(Profile profile,String device,long from,long to,int maxFrameBytes,int packetId,Transport transport){
        if(profile==null)throw new IllegalArgumentException("profile missing");
        if(profile==Profile.SLEEP_PAGE||profile==Profile.SPORTS_PAGE||profile==Profile.SPO2_PAGE)validateRange(from,to);else validateHeartRange(from,to);
        this.profile=profile;this.device=device;this.from=from;this.to=to;this.maxFrameBytes=maxFrameBytes;if(packetId<0||packetId>255)throw new IllegalArgumentException("packet id bounds");this.packetId=packetId;this.transport=transport;}
    static void validateRange(long from,long to){if(from<0||to<=from||to-from>MAX_RANGE_SECONDS)throw new IllegalArgumentException("Sleep query requires a finite range of at most seven days");}
    static void validateHeartRange(long nowUnix,long midnightUnix){
        if(midnightUnix<0||nowUnix<=midnightUnix||nowUnix-midnightUnix>26L*60*60)throw new IllegalArgumentException("Heart last window must be positive and at most 26 hours");
        java.time.ZoneId zone=java.time.ZoneId.systemDefault();
        long exact=java.time.Instant.ofEpochSecond(nowUnix).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond();
        if(midnightUnix!=exact)throw new IllegalArgumentException("Heart last requires exact phone-local midnight");
    }
    static Result localSpo2Failure(String device,long from,long to,String detail){return new Result(Profile.SPO2_PAGE,"FAILED",detail,device==null?"":device,UUID.randomUUID().toString(),from,to,Collections.emptyList(),new byte[0],new byte[0],new byte[0],null,System.currentTimeMillis());}
    static Result localSleepFailure(String device,long from,long to,String detail){return new Result(Profile.SLEEP_PAGE,"FAILED",detail,device==null?"":device,UUID.randomUUID().toString(),from,to,Collections.emptyList(),new byte[0],new byte[0],new byte[0],null,System.currentTimeMillis());}
    static Result localHeartFailure(String device,long from,long to,String detail){return new Result(Profile.HEART_LAST,"FAILED",detail,device,UUID.randomUUID().toString(),from,to,Collections.emptyList(),new byte[0],new byte[0],new byte[0],null,System.currentTimeMillis());}
    boolean isRunning(){return running;}
    void start(){
        if(used)return;used=true;running=true;
        if(profile==Profile.SPORTS_PAGE&&!DeviceFeatureGate.sportsAllowed()){fail("COMPILE_GATE; no TX");return;}
        sendPage();
    }
    private void sendPage(){
        buffer.reset();raw=new byte[0];request=new byte[0];expectedFrame=null;responseDelivered=false;acknowledgementReceived=false;ignoredFrames=0;ignoredBytes=0;savedContinuation=null;
        try{
            Map<String,Object> args=new LinkedHashMap<>();args.put("path",profile.path);args.put("from",from);args.put("to",to);args.put("index",(long)index);args.put("count",(long)profile.count);args.put("_eventId",eventId);args.put("_module","tsdb_query");
            request=UbjsonCodec.encode(args);
            byte[] service="tsdb_query".getBytes(StandardCharsets.UTF_8);
            byte[] rpc=new byte[service.length+2+request.length];System.arraycopy(service,0,rpc,0,service.length);rpc[service.length+1]=1;System.arraycopy(request,0,rpc,service.length+2,request.length);
            byte[] frame=frame(rpc);
            if(frame.length>maxFrameBytes){fail("request exceeds negotiated MTU; no fragmentation experiment");return;}
            transport.log(profile.name()+" QUERY_ONE_PAGE path="+profile.path+" from="+from+" to="+to+" index="+index+" count="+profile.count+" eventId="+eventId+" packetId="+packetId);
            if(!transport.write(frame)){fail("Android rejected TX");return;}
            if(running)handler.postDelayed(timeout,3000L);
        }catch(RuntimeException e){fail("request rejected: "+e.getMessage());}
    }
    void cancel(String reason){fail(reason);}
    void onChunk(byte[] bytes){
        if(!running)return;
        if(bytes==null||bytes.length==0){fail("empty RX chunk");return;}
        // One maximum response plus the separate, bounded ignored-frame budget may coalesce.
        if(buffer.size()+bytes.length>2*(UbjsonCodec.MAX_BYTES+13)){fail("MCF stream exceeds bounded capacity");return;}
        buffer.write(bytes,0,bytes.length);byte[] stream=buffer.toByteArray();int offset=0;
        while(offset<stream.length){
            if((stream[offset]&255)!=0xFC){fail("MCF prefix");return;}
            if(stream.length-offset<3)break;
            int length=((stream[offset+1]&255)<<8)|(stream[offset+2]&255);
            if(length<13||length>UbjsonCodec.MAX_BYTES+13){fail("MCF length bounds");return;}
            if(stream.length-offset<length)break;
            byte[] frame=Arrays.copyOfRange(stream,offset,offset+length);offset+=length;
            if((frame[length-1]&255)!=0xCF||crc16(frame,1,length-4)!=(((frame[length-3]&255)<<8)|(frame[length-2]&255))){describeRejectedFrame(frame,"WAIT_RX",bytes.length);fail("MCF CRC/terminator");return;}
            int attrs=frame[9]&255,type=attrs>>>6,id=frame[8]&255;
            if(frame[4]!=0||frame[5]!=1||frame[6]!=1||frame[7]!=0||(attrs&3)!=0){describeRejectedFrame(frame,"WAIT_RX",bytes.length);fail("MCF addressing/type/flags");return;}
            if(type==0||(type==1||type==2)&&id!=packetId){
                if(++ignoredFrames>16||ignoredBytes+length>UbjsonCodec.MAX_BYTES+13){fail("unrelated MCF budget exceeded");return;}
                ignoredBytes+=length;transport.log("TSDB_RX ignored validated type="+type+" id="+id+" expectedId="+packetId+" bytes="+length);continue;
            }
            if(id!=packetId||attrs!=0x40){fail("MCF addressing/id/type/flags");return;}
            if(expectedFrame!=null||responseDelivered||awaitingSave){fail("duplicate expected MCF response");return;}
            expectedFrame=frame;
        }
        buffer.reset();if(offset<stream.length)buffer.write(stream,offset,stream.length-offset);
        // Classify the whole available stream, including any fragmented tail, before delivery.
        if(buffer.size()!=0)return;
        if(savedContinuation!=null){Runnable continuation=savedContinuation;savedContinuation=null;continuation.run();return;}
        if(expectedFrame!=null&&!responseDelivered&&!awaitingSave){responseDelivered=true;deliverResponse(expectedFrame);}
    }
    private void deliverResponse(byte[] frame){
        raw=Arrays.copyOfRange(frame,10,frame.length-3);
        try{
            HealthHistoryParser.Page page=HealthHistoryParser.parse(raw,profile);
            String detail=page.records.isEmpty()?"No records returned for this range":"One page received; sleep d remains uninterpreted";
            if(profile==Profile.SPO2_PAGE)detail=page.records.isEmpty()?"No SpO2 records returned for this range":page.partial?"Partial SpO2 page received; raw observations retained":"Bounded SpO2 page received; raw observations retained";
            if(profile==Profile.HEART_LAST)detail=page.records.isEmpty()||!page.records.get(0).heartRateAvailable()?"Latest heart-rate observation unavailable":"Latest stored heart-rate observation received";
            if(profile==Profile.SPORTS_PAGE)detail=page.records.isEmpty()?"No completed workout records returned":"One bounded workout page received; source values retained";
            Result result=new Result(profile,page.partial?"PARTIAL":"COMPLETE",detail,device,eventId,from,to,page.records,raw,request,frame,null,System.currentTimeMillis());
            if(profile==Profile.SPO2_PAGE)for(HealthHistoryRecord record:page.records)if(record.ts<from||record.ts>to)throw new IllegalArgumentException("SpO2 record outside requested range");
            if(profile!=Profile.SLEEP_PAGE){finish(result);return;}
            if(page.records.isEmpty()&&page.partial)throw new IllegalArgumentException("full buffer with zero records");
            if(!seenPages.add(fingerprint(raw)))throw new IllegalArgumentException("repeated sleep page");
            java.util.Set<String> thisPage=new java.util.HashSet<>();
            for(HealthHistoryRecord record:page.records){if(record.ts<from||record.ts>to)throw new IllegalArgumentException("sleep record outside requested range");String key=record.ts+":"+fingerprint(record.rawDataUbjson());if(seenRecords.contains(key)||!thisPage.add(key))throw new IllegalArgumentException("repeated sleep record");}
            seenRecords.addAll(thisPage);
            handler.removeCallbacks(timeout);awaitingSave=true;
            final int pendingSave=++saveGeneration;handler.postDelayed(persistTimeout,10_000L);
            final boolean more=page.partial,cap=pages+1>=8||total+page.records.size()>=40;
            transport.persist(result,saved->handler.post(()->{
                if(!running||!awaitingSave||pendingSave!=saveGeneration||acknowledgementReceived)return;
                acknowledgementReceived=true;
                if(saved==null||"FAILED".equals(saved.state)){fail("page save/import failed; completed pages preserved");return;}
                Runnable continuation=()->{
                if(!running||!awaitingSave||pendingSave!=saveGeneration)return;handler.removeCallbacks(persistTimeout);awaitingSave=false;
                pages++;total+=page.records.size();for(HealthHistoryRecord record:page.records){latestTimestamp=Math.max(latestTimestamp,record.ts);try{if(HealthDatasetParser.parse("sleep",record.rawDataUbjson()).validity.startsWith("VALID"))usable++;}catch(RuntimeException ignored){/* Raw page remains retained; unusable observations are not charted. */}}
                if(!more||cap){transport.log("SLEEP summary pages="+pages+" records="+total+" cap="+(more&&cap));finish(saved.withSleepSummary(new SleepSummary(pages,total,usable,latestTimestamp,!more)).described("Sleep pages="+pages+" records="+total+" usable="+usable+" latestUnix="+latestTimestamp+(more&&cap?"; limit reached; partial history":"; bounded history complete")));return;}
                index+=page.records.size();eventId=UUID.randomUUID().toString();
                try{packetId=transport.nextPacketId();sendPage();}catch(RuntimeException e){fail("continuation rejected; completed pages preserved");}
                };
                // A partial tail must drain within the original save deadline before next-page TX.
                if(buffer.size()!=0)savedContinuation=continuation;else continuation.run();
            }));
        }catch(RuntimeException e){fail("response rejected: "+e.getMessage());}
    }
    /** Metadata only for rejected frames; their payload never changes the failure decision. */
    private void describeRejectedFrame(byte[] frame,String phase,int chunkBytes){
        int size=frame==null?0:frame.length,declared=size>=3?((frame[1]&255)<<8)|(frame[2]&255):-1;
        boolean bounded=declared>=13&&declared<=UbjsonCodec.MAX_BYTES+13;
        boolean complete=bounded&&size==declared,prefix=size>0&&(frame[0]&255)==0xFC;
        String crc="UNKNOWN",terminator="UNKNOWN",envelope="INVALID";
        if(prefix&&bounded&&size<declared)envelope="INCOMPLETE";else if(prefix&&bounded&&size>declared)envelope="EXTRA";
        boolean trusted=false;
        if(complete){terminator=(frame[size-1]&255)==0xCF?"OK":"BAD";crc=crc16(frame,1,size-4)==(((frame[size-3]&255)<<8)|(frame[size-2]&255))?"OK":"BAD";
            trusted=prefix&&"OK".equals(crc)&&"OK".equals(terminator)&&frame[4]==0&&frame[5]==1&&(frame[9]&3)==0;envelope=trusted?"VALID":"INVALID";}
        StringBuilder metadata=new StringBuilder("TSDB_RX phase=").append(phase).append(" profile=").append(profile.name()).append(" expectedId=").append(packetId)
            .append(" chunkBytes=").append(chunkBytes).append(" assembledBytes=").append(size).append(" declaredBytes=").append(declared<0?"UNKNOWN":Integer.toString(declared))
            .append(" envelope=").append(envelope).append(" crc=").append(crc).append(" terminator=").append(terminator);
        if(trusted){int attrs=frame[9]&255,type=attrs>>>6;metadata.append(" linkFrameId=").append(frame[3]&255).append(" linkReserved=0 transport=1 src=").append(frame[6]&255).append(" dst=").append(frame[7]&255).append(" id=").append(frame[8]&255)
            .append(" attrs=0x").append(String.format(java.util.Locale.ROOT,"%02X",attrs)).append(" type=").append(type==0?"REQ":type==1?"RSP":type==2?"ACK":"OTHER")
            .append(" needAck=").append((attrs&32)!=0).append(" needRsp=").append((attrs&16)!=0).append(" priority=").append((attrs>>>2)&3).append(" reserve=").append(attrs&3)
            .append(" rpc=").append(prefixMatches(frame,"msg_send_ubjson")?"KNOWN_MSG_UBJSON_V1":prefixMatches(frame,"udbd_gethostbyname")?"KNOWN_DNS_V1":"UNKNOWN");
        }else metadata.append(" header=untrusted linkFrameId=UNKNOWN linkReserved=UNKNOWN transport=UNKNOWN src=UNKNOWN dst=UNKNOWN id=UNKNOWN attrs=UNKNOWN type=UNKNOWN needAck=UNKNOWN needRsp=UNKNOWN priority=UNKNOWN reserve=UNKNOWN rpc=UNKNOWN");
        transport.log(metadata.toString());
    }
    private static boolean prefixMatches(byte[] frame,String known){byte[] prefix=known.getBytes(StandardCharsets.US_ASCII);if(frame.length<13+prefix.length+2)return false;for(int i=0;i<prefix.length;i++)if(frame[10+i]!=prefix[i])return false;return frame[10+prefix.length]==0&&frame[11+prefix.length]==1;}
    // Failure receipts have a separate UUID so late frames or local import failures
    // cannot overwrite an already durable successful page. Failed receipts import no records.
    private void fail(String reason){if(running)finish(new Result(profile,"FAILED",reason+(profile==Profile.SLEEP_PAGE?"; saved pages="+pages+" records="+total+" usable="+usable+" latestUnix="+latestTimestamp:""),device,profile==Profile.SLEEP_PAGE?UUID.randomUUID().toString():eventId,from,to,Collections.emptyList(),raw,request,buffer.toByteArray(),null,System.currentTimeMillis()).withSleepSummary(profile==Profile.SLEEP_PAGE?new SleepSummary(pages,total,usable,latestTimestamp,false):null));}
    private void finish(Result result){if(!running)return;running=false;awaitingSave=false;saveGeneration++;handler.removeCallbacks(timeout);handler.removeCallbacks(persistTimeout);transport.log(profile.name()+" "+result.state+": "+result.detail);transport.finished(result);}
    private static String fingerprint(byte[] bytes){try{return java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private byte[] frame(byte[] rpc){int length=13+rpc.length;byte[] f=new byte[length];f[0]=(byte)0xFC;f[1]=(byte)(length>>>8);f[2]=(byte)length;f[3]=1;f[5]=1;f[7]=1;f[8]=(byte)packetId;f[9]=0x10;System.arraycopy(rpc,0,f,10,rpc.length);int crc=crc16(f,1,length-4);f[length-3]=(byte)(crc>>>8);f[length-2]=(byte)crc;f[length-1]=(byte)0xCF;return f;}
    private static int crc16(byte[] bytes,int offset,int length){int crc=0xFFFF;for(int i=offset;i<offset+length;i++){crc^=bytes[i]&255;for(int j=0;j<8;j++)crc=(crc&1)!=0?(crc>>>1)^0xA001:crc>>>1;}return crc&0xFFFF;}
}
