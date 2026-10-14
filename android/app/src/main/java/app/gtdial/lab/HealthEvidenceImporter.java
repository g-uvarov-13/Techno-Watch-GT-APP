package app.gtdial.lab;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/** Revalidates saved evidence, including request and MCF, without contacting a watch. */
final class HealthEvidenceImporter {
    static final int MAX_FILE=128*1024;
    private final Context context;
    private final ExpansionStore store;
    private final OperationJournal journal;
    HealthEvidenceImporter(Context context,ExpansionStore store) {this.context=context.getApplicationContext();this.store=store;journal=OperationJournal.get(context);}
    int scan() {
        if(store.healthImportSuppressed())return 0;
        int imported=0,attempted=0;java.util.Set<String> known=store.importedFileNames();
        for(File directory:new File[]{context.getFilesDir(),new File(context.getFilesDir(),"health-evidence")}){if(!directory.isDirectory())continue;
            try(java.nio.file.DirectoryStream<java.nio.file.Path> entries=java.nio.file.Files.newDirectoryStream(directory.toPath())){for(java.nio.file.Path path:entries){File file=path.toFile();if(!validName(file.getName())||known.contains(file.getName()))continue;if(++attempted>512){String op=journal.begin("health_import","scan","SAVED_TSDB","");journal.stage(op,"health_import","scan","PARTIAL","PARTIAL","SCAN_BATCH_LIMIT",attempted-1,0,0);return imported;}try{if(importFile(file))imported++;}catch(Exception e){/* importFile records the bounded failure. */}}}
            catch(Exception e){preReadFailure("SCAN_DIRECTORY",e);}}
        return imported;
    }
    boolean importFile(File file)throws Exception {
        return importFile(file,null);
    }
    /** Authority is only for one live response of the explicit request; scans stay suppressed. */
    static final class FreshRequest {
        final String profile,device;final long requestedAt;
        FreshRequest(String profile,String device,long requestedAt){this.profile=profile;this.device=device;this.requestedAt=requestedAt;}
    }
    boolean importFile(File file,FreshRequest fresh)throws Exception {
        try {
        File canonical=file.getCanonicalFile(),root=context.getFilesDir().getCanonicalFile(),selection=new File(root,"health-evidence").getCanonicalFile();
        if(!validName(file.getName())||(!canonical.getParentFile().equals(root)&&!canonical.getParentFile().equals(selection)))throw new IllegalArgumentException("EVIDENCE_PATH");
        byte[] serialized;try(InputStream in=new FileInputStream(canonical)){serialized=read(in);}
        return importBytes(serialized,file.getName(),true,fresh);
        }catch(Exception e){preReadFailure("IMPORT_FILE",e);throw e;}
    }
    File selected(InputStream stream)throws Exception {
        byte[] bytes=read(stream);JSONObject envelope=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        String event=envelope.getString("_eventId");ExpansionModels.requireId(event);
        String prefix=prefix(envelope.getString("profile"));
        String name=prefix+event+".json";
        File directory=new File(context.getFilesDir(),"health-evidence");File file=new File(directory,name);
        for(File existing:new File[]{file,new File(context.getFilesDir(),name)})if(existing.exists()){try(InputStream in=new FileInputStream(existing)){if(!Arrays.equals(bytes,read(in))){String op=journal.begin("health_import","selected","SAVED_TSDB","");journal.stage(op,"health_import","selected","FAILED","FAILED","EVIDENCE_ID_COLLISION",0,bytes.length,0);throw new IllegalArgumentException("EVIDENCE_ID_COLLISION");}}importBytes(bytes,name);return existing;}
        importBytes(bytes,name,false);
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("EVIDENCE_DIRECTORY");
        AtomicFile atomic=new AtomicFile(file);FileOutputStream output=null;
        try{output=atomic.startWrite();output.write(bytes);atomic.finishWrite(output);}catch(Exception e){if(output!=null)atomic.failWrite(output);throw e;}
        try(InputStream in=atomic.openRead()){if(!Arrays.equals(bytes,read(in)))throw new IllegalStateException("EVIDENCE_READBACK");}
        importBytes(bytes,name);return file;
    }
    File shareCopy(String evidenceId)throws Exception {
        ExpansionModels.requireId(evidenceId);File source=null;
        for(String prefix:new String[]{"heart-last-","sleep-history-","sports-page-","spo2-page-","sports-graph-","sports-spo2-","sports-pace-","sports-run-","sports-swim-"}){
            File top=new File(context.getFilesDir(),prefix+evidenceId+".json"),saved=new File(new File(context.getFilesDir(),"health-evidence"),prefix+evidenceId+".json");
            if(top.isFile())source=top;else if(saved.isFile())source=saved;
        }
        if(source==null)throw new IllegalStateException("EVIDENCE_UNAVAILABLE");
        byte[] bytes;try(InputStream in=new FileInputStream(source)){bytes=read(in);}
        File directory=new File(context.getFilesDir(),"health-evidence");if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("EVIDENCE_DIRECTORY");
        File copy=new File(directory,source.getName());try(FileOutputStream out=new FileOutputStream(copy)){out.write(bytes);}return copy;
    }
    private boolean importBytes(byte[] serialized,String name)throws Exception {
        return importBytes(serialized,name,true);
    }
    private boolean importBytes(byte[] serialized,String name,boolean persist)throws Exception {
        return importBytes(serialized,name,persist,null);
    }
    private boolean importBytes(byte[] serialized,String name,boolean persist,FreshRequest fresh)throws Exception {
        JSONObject receipt=new JSONObject(new String(serialized,StandardCharsets.UTF_8));
        long receivedAt=receipt.optLong("receivedAt"),cutoff=store.healthDeletionCutoff();
        boolean authorized=fresh!=null&&fresh.requestedAt>cutoff&&receivedAt>=fresh.requestedAt
                &&fresh.profile.equals(receipt.optString("profile"))&&fresh.device.equals(receipt.optString("deviceAddress"));
        if(receivedAt<=cutoff||store.healthImportSuppressed()&&!authorized)throw new IllegalStateException("HEALTH_IMPORT_DISABLED_BY_DELETION");
        String fileSha=sha(serialized);if(store.imported(fileSha))return false;
        String operation=journal.begin("health_import","import","SAVED_TSDB","");long began=android.os.SystemClock.elapsedRealtime();
        String event="",device="",profile="",state="FAILED";long received=System.currentTimeMillis();boolean validated=false;
        try {
            JSONObject e=new JSONObject(new String(serialized,StandardCharsets.UTF_8));event=e.getString("_eventId");ExpansionModels.requireId(event);
            profile=e.getString("profile");ImportSpec spec=spec(profile);TsdbReadProtocol.Profile p=spec.transport;
            if(!name.equals(prefix(profile)+event+".json"))throw new IllegalArgumentException("EVIDENCE_ID_FILENAME");
            device=e.getString("deviceAddress");if(!device.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))throw new IllegalArgumentException("EVIDENCE_DEVICE");
            long from=e.getLong("from"),to=e.getLong("to");received=e.getLong("receivedAt");state=e.getString("state");
            int index=e.getInt("index");if(index<0||(p==TsdbReadProtocol.Profile.SLEEP_PAGE?index>39:index!=0))throw new IllegalArgumentException("EVIDENCE_INDEX");
            int requested=e.getInt("count");if(!spec.path.equals(e.getString("path"))||requested!=spec.count||!"tsdb_query".equals(e.getString("_module"))||received<=0)throw new IllegalArgumentException("EVIDENCE_PROFILE");
            if(p!=TsdbReadProtocol.Profile.HEART_LAST)TsdbReadProtocol.validateRange(from,to);
            else if(to<0||from<=to||from-to>26L*3600)throw new IllegalArgumentException("EVIDENCE_HEART_RANGE");
            byte[] request=decode(e,"requestUbjsonBase64","requestSha256",UbjsonCodec.MAX_BYTES);
            byte[] raw=decode(e,"rawResponseBase64","rawResponseSha256",UbjsonCodec.MAX_BYTES);
            byte[] mcf=decode(e,"receivedMcfBase64","receivedMcfSha256",UbjsonCodec.MAX_BYTES+13);
            if(!"COMPLETE".equals(state)&&!"PARTIAL".equals(state)) {
                validated=true;
                if(persist)store.importPage(fileSha,event,device,profile,received,"FAILED","TRANSPORT_FAILED",name,new ArrayList<>());
                journal.stage(operation,"health_import","import","FAILED","FAILED","TRANSPORT_FAILED",0,serialized.length,android.os.SystemClock.elapsedRealtime()-began);return false;
            }
            Object decoded=UbjsonCodec.decode(request);if(!(decoded instanceof Map))throw new IllegalArgumentException("EVIDENCE_REQUEST_OBJECT");
            Map<?,?> r=(Map<?,?>)decoded;
            if(!spec.path.equals(r.get("path"))||!event.equals(r.get("_eventId"))||!"tsdb_query".equals(r.get("_module"))||number(r.get("from"))!=from||number(r.get("to"))!=to||number(r.get("index"))!=index||number(r.get("count"))!=requested||r.size()!=7)throw new IllegalArgumentException("EVIDENCE_REQUEST_MISMATCH");
            validateMcf(mcf,raw);
            HealthHistoryParser.Page page=p==null?HealthHistoryParser.parseLocal(raw,spec.cap):HealthHistoryParser.parse(raw,p);
            if(p==TsdbReadProtocol.Profile.SLEEP_PAGE||p==TsdbReadProtocol.Profile.SPO2_PAGE)for(HealthHistoryRecord record:page.records)if(record.ts<from||record.ts>to)throw new IllegalArgumentException("EVIDENCE_RECORD_RANGE");
            if(page.partial!=state.equals("PARTIAL"))throw new IllegalArgumentException("EVIDENCE_PAGE_STATE");
            journal.stage(operation,"health_import","import","VALIDATED","SUCCESS","",page.records.size(),raw.length,0);
            ArrayList<ExpansionModels.HealthRecord> records=new ArrayList<>();String zone=e.optString("zoneIdAtReceipt",ZoneId.systemDefault().getId());ZoneId.of(zone);
            for(HealthHistoryRecord record:page.records) {
                String dataset=spec.dataset;byte[] d=record.rawDataUbjson();
                HealthDatasetParser.Parsed parsed;
                if(dataset.startsWith("sports_")){validated=true;String parentDevice=e.getString("parentDeviceAddress");long parentId=e.getLong("parentRecordId");String parentSha=e.getString("parentRawSha256");if(!device.equals(parentDevice)||!parentSha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("SPORT_PARENT_IDENTITY");ExpansionModels.HealthRecord parent=null;for(ExpansionModels.HealthRecord candidate:store.health(device,"sports",0,253402300799L))if(candidate.validity.startsWith("VALID")&&candidate.data.optLong("recordId",-1)==parentId&&parentSha.equals(candidate.rawSha))parent=candidate;if(parent==null)throw new IllegalArgumentException("SPORT_PARENT_REVISION_REQUIRED");if(from!=parent.data.optLong("startTime")||to!=parent.data.optLong("endTime")||record.ts<from||record.ts>to)throw new IllegalArgumentException("SPORT_DETAIL_QUERY_RANGE");JSONObject context=ExpansionModels.copy(parent.data);ExpansionModels.put(context,"parentRawSha256",parentSha);parsed=HealthDatasetParser.workoutDetail(dataset,d,context);}
                else parsed=HealthDatasetParser.parse(dataset,d);String dSha=sha(d);
                JSONObject original=new JSONObject();ExpansionModels.put(original,"d",HealthDatasetParser.jsonValue(UbjsonCodec.decode(d)));
                ExpansionModels.put(parsed.data,"zoneProvenance",e.has("zoneIdAtReceipt")?"PHONE_RECEIPT":"IMPORT_FALLBACK_RECEIPT_ZONE_UNKNOWN");
                String binding=dataset.startsWith("sports_")?":"+parsed.data.optString("parentRecordId")+":"+parsed.data.optString("parentRawSha256"):"";
                records.add(new ExpansionModels.HealthRecord(UUID.nameUUIDFromBytes((device+":"+dataset+":"+record.ts+":"+dSha+binding).getBytes(StandardCharsets.UTF_8)).toString(),device,dataset,event,dSha,record.ts,received,zone,state,parsed.validity,"OBSERVATIONS_ONLY",parsed.value,original.toString(),parsed.data));
            }
            journal.stage(operation,"health_import","import","PARSED","SUCCESS","",records.size(),raw.length,0);
            validated=true;
            if(!persist)return true;
            store.importPage(fileSha,event,device,profile,received,state,"",name,records);
            journal.stage(operation,"health_import","import","PERSISTED","SUCCESS","",records.size(),serialized.length,0);
            journal.stage(operation,"health_import","import",state,"SUCCESS","",records.size(),serialized.length,android.os.SystemClock.elapsedRealtime()-began);return true;
        }catch(Exception ex) {
            journal.stage(operation,"health_import","import","FAILED","FAILED",validated?"LOCAL_STORAGE":"EVIDENCE_VALIDATION",0,serialized.length,android.os.SystemClock.elapsedRealtime()-began);
            if(persist&&!validated&&!store.imported(fileSha))store.importPage(fileSha,event,device,profile,received,"REJECTED","EVIDENCE_VALIDATION",name,new ArrayList<>());throw ex;
        }
    }
    static byte[] read(InputStream input)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];
        for(int n;(n=input.read(buffer))!=-1;){if(bytes.size()+n>MAX_FILE)throw new IllegalArgumentException("EVIDENCE_SIZE");bytes.write(buffer,0,n);}return bytes.toByteArray();
    }
    private static byte[] decode(JSONObject e,String base,String hash,int max)throws Exception {
        String encoded=e.getString(base);if(encoded.length()>((max+2)/3)*4+4)throw new IllegalArgumentException("EVIDENCE_BASE64_SIZE");
        byte[] value=Base64.decode(encoded,Base64.DEFAULT);if(value.length>max||!sha(value).equalsIgnoreCase(e.getString(hash)))throw new IllegalArgumentException("EVIDENCE_HASH");return value;
    }
    private static void validateMcf(byte[] mcf,byte[] raw) {
        int size=mcf.length;
        if(size<13||(mcf[0]&255)!=0xfc||((mcf[1]&255)<<8|(mcf[2]&255))!=size||(mcf[size-1]&255)!=0xcf||mcf[4]!=0||mcf[5]!=1||mcf[6]!=1||mcf[7]!=0||(mcf[9]&255)!=0x40)throw new IllegalArgumentException("EVIDENCE_MCF_HEADER");
        int crc=0xffff;for(int i=1;i<size-3;i++){crc^=mcf[i]&255;for(int j=0;j<8;j++)crc=(crc&1)!=0?(crc>>>1)^0xa001:crc>>>1;}
        if((crc&65535)!=((mcf[size-3]&255)<<8|(mcf[size-2]&255))||!Arrays.equals(raw,Arrays.copyOfRange(mcf,10,size-3)))throw new IllegalArgumentException("EVIDENCE_MCF_CONTENT");
    }
    static String sha(byte[] bytes)throws Exception {StringBuilder out=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.US,"%02x",value&255));return out.toString();}
    private static long number(Object value) {if(!(value instanceof Long))throw new IllegalArgumentException("EVIDENCE_REQUEST_INTEGER");return (Long)value;}
    private static boolean validName(String name){return name.matches("(?:heart-last-|sleep-history-|sports-page-|spo2-page-|sports-graph-|sports-spo2-|sports-pace-|sports-run-|sports-swim-)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[.]json");}
    private static final class ImportSpec {final String path,dataset,prefix;final int count,cap;final TsdbReadProtocol.Profile transport;ImportSpec(String path,String dataset,String prefix,int count,int cap,TsdbReadProtocol.Profile transport){this.path=path;this.dataset=dataset;this.prefix=prefix;this.count=count;this.cap=cap;this.transport=transport;}}
    private static ImportSpec spec(String profile){
        for(TsdbReadProtocol.Profile p:TsdbReadProtocol.Profile.values())if(p.name().equals(profile))return new ImportSpec(p.path,p==TsdbReadProtocol.Profile.HEART_LAST?"heart":p==TsdbReadProtocol.Profile.SLEEP_PAGE?"sleep":p==TsdbReadProtocol.Profile.SPO2_PAGE?"spo2":"sports",p==TsdbReadProtocol.Profile.HEART_LAST?"heart-last-":p==TsdbReadProtocol.Profile.SLEEP_PAGE?"sleep-history-":p==TsdbReadProtocol.Profile.SPO2_PAGE?"spo2-page-":"sports-page-",p.count,p.responseCap,p);
        String dataset="SPORTS_GRAPH".equals(profile)?"sports_graph":"SPORTS_SPO2".equals(profile)?"sports_spo2":"SPORTS_PACE".equals(profile)?"sports_pace":"SPORTS_RUN".equals(profile)?"sports_section_run":"SPORTS_SWIM".equals(profile)?"sports_section_swim":null;
        if(dataset==null)throw new IllegalArgumentException("EVIDENCE_PROFILE");int count="sports_graph".equals(dataset)||"sports_spo2".equals(dataset)?30:"sports_pace".equals(dataset)?20:10;String prefix="sports_section_run".equals(dataset)?"sports-run-":"sports_section_swim".equals(dataset)?"sports-swim-":dataset.replace('_','-')+"-";return new ImportSpec("/data/share/system/"+dataset,dataset,prefix,count,count,null);
    }
    private static String prefix(String profile){return spec(profile).prefix;}
    private void preReadFailure(String action,Exception e){String op=journal.begin("health_import",action,"SAVED_TSDB","");journal.stage(op,"health_import",action,"FAILED","FAILED",e instanceof IllegalArgumentException?"EVIDENCE_VALIDATION":e.getClass().getSimpleName(),0,0,0);}
}
