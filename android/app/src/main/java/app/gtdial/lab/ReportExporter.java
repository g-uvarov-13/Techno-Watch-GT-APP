package app.gtdial.lab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.Collections;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

/** User-selected app-private exports. Notes and exact routes are opt-in; no automatic send. */
final class ReportExporter {
    static final class Options {
        LocalDate from=LocalDate.now().minusDays(6),to=LocalDate.now().plusDays(1);
        ZoneId zone=ZoneId.systemDefault();String device="";
        boolean activity=true,health=true,workouts=true,plans=true,manual=false,notes=false,routes=false;
        void validate(){if(from==null||to==null||!to.isAfter(from)||java.time.temporal.ChronoUnit.DAYS.between(from,to)>366)throw new IllegalArgumentException("REPORT_PERIOD");}
    }
    static final class Report {
        final JSONObject json;final List<String> lines;
        Report(JSONObject json,List<String> lines){this.json=json;this.lines=lines;}
        String preview(){StringBuilder s=new StringBuilder();for(String line:lines)s.append(line).append('\n');return s.toString();}
    }
    private final Context context;private final ExpansionStore store;private final ActivityHistoryStore activity;
    ReportExporter(Context context,ExpansionStore store,ActivityHistoryStore activity){this.context=context.getApplicationContext();this.store=store;this.activity=activity;}
    File measurements()throws Exception{
        JSONObject json=new JSONObject();ExpansionModels.put(json,"schemaVersion",1);ExpansionModels.put(json,"createdAtMillis",System.currentTimeMillis());
        ExpansionModels.put(json,"sourceDescription","Показатели часов, сохранённые телефоном; прежние ручные показатели отмечены отдельно");
        JSONArray observations=new JSONArray();
        for(ActivityHistoryEntry e:activity.measurementsForExport()){
            JSONObject row=new JSONObject();ExpansionModels.put(row,"id",e.id);ExpansionModels.put(row,"deviceKey",OperationJournal.deviceKey(e.deviceAddress));ExpansionModels.put(row,"source","WATCH_READING_SAVED_BY_PHONE");ExpansionModels.put(row,"receivedAtMillis",e.receivedAt);ExpansionModels.put(row,"phoneDay",e.phoneDay);ExpansionModels.put(row,"phoneZoneId",e.phoneZoneId);ExpansionModels.put(row,"steps",e.steps);ExpansionModels.put(row,"calorieRaw",e.calorieRaw);ExpansionModels.put(row,"distanceRaw",e.distanceRaw);observations.put(row);
        }
        ExpansionModels.put(json,"activity",observations);JSONArray health=new JSONArray();
        for(ExpansionModels.HealthRecord r:store.measurementsForExport()){
            JSONObject row=normalizedHealth(r);ExpansionModels.put(row,"deviceKey",OperationJournal.deviceKey(r.device));ExpansionModels.put(row,"data",ExpansionModels.copy(r.data));health.put(row);
        }
        ExpansionModels.put(json,"health",health);JSONArray manual=new JSONArray();
        for(JSONObject r:store.reportRows("manual_entries"))if(!"NOTE".equals(r.optString("kind")))manual.put(select(r,new String[]{"id","source","timestamp","zoneIdAtImport","kind","value","unit","systolic","diastolic","pulse"}));
        ExpansionModels.put(json,"manual",manual);
        return export(new Report(json,Collections.emptyList()),"json");
    }

    /** Only exact app-created files in immediate app-private directories participate in confirmed deletion. */
    static void removeOwnedMeasurements(Context context)throws IOException{
        File root=context.getFilesDir().getCanonicalFile();String uuid="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        String evidence="(?:heart-last|sleep-history|sports-page|spo2-page|sports-graph|sports-spo2|sports-pace|sports-run|sports-swim)-"+uuid+"[.]json(?:[.](?:bak|new))?";
        String reports="chasovoy-"+uuid+"[.](?:json|csv|pdf)(?:[.](?:bak|new))?";
        List<String> failed=new ArrayList<>();int considered=0;
        for(File directory:new File[]{root,new File(root,"health-evidence"),new File(root,"reports")}){
            if(!directory.exists())continue;
            File canonical=directory.getCanonicalFile();if(!canonical.equals(directory.getAbsoluteFile())||!canonical.equals(root)&&!root.equals(canonical.getParentFile()))throw new IOException("MEASUREMENT_DELETE_DIRECTORY");
            String pattern=canonical.getName().equals("reports")?reports:evidence;
            try(java.nio.file.DirectoryStream<java.nio.file.Path> entries=java.nio.file.Files.newDirectoryStream(canonical.toPath())){
                for(java.nio.file.Path path:entries){File file=path.toFile();if(!file.getName().matches(pattern))continue;
                    if(++considered>10000)throw new IOException("MEASUREMENT_DELETE_LIMIT");
                    File target=file.getCanonicalFile();if(!canonical.equals(target.getParentFile())||!target.equals(file.getAbsoluteFile())||!target.isFile()){failed.add(file.getName());continue;}
                    if(!target.delete())failed.add(file.getName());
                }
            }
        }
        if(!failed.isEmpty())throw new IOException("MEASUREMENT_DELETE_PARTIAL_"+failed.size());
    }

    /** One bounded archive combines the operation snapshot with existing connection logs and an explicit manifest. */
    static File diagnostics(Context context,DiagnosticLog diagnostic,boolean flushed)throws Exception{
        File directory=new File(context.getFilesDir(),"reports");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("DIAGNOSTIC_DIRECTORY");
        File logs=new File(context.getFilesDir(),"logs").getCanonicalFile();File output=new File(directory,"diagnostics-"+UUID.randomUUID()+".zip");
        JSONObject manifest=new JSONObject();ExpansionModels.put(manifest,"schemaVersion",1);ExpansionModels.put(manifest,"createdAtMillis",System.currentTimeMillis());ExpansionModels.put(manifest,"diagnosticQueueFlushed",flushed);ExpansionModels.put(manifest,"operationStorageState",OperationJournal.get(context).storageState());
        JSONArray sources=new JSONArray();int omitted=0;long remaining=24L*1024*1024;
        List<LocalDiagnosticEntry> localEntries=localDiagnostics(context,manifest,sources);
        File operations=null;
        try{operations=OperationJournal.get(context).export();}catch(Exception e){ExpansionModels.put(manifest,"operationSnapshotError",e.getClass().getSimpleName());}
        List<File> files=new ArrayList<>();
        if(logs.isDirectory())try(java.nio.file.DirectoryStream<java.nio.file.Path> entries=java.nio.file.Files.newDirectoryStream(logs.toPath())){
            for(java.nio.file.Path path:entries){File candidate=path.toFile();if(candidate.getName().matches("gt-dial-lab-[0-9]{8}-[0-9]{6}[.]txt")&&candidate.isFile()&&candidate.getCanonicalFile().getParentFile().equals(logs)&&candidate.getCanonicalFile().equals(candidate.getAbsoluteFile()))files.add(candidate);}
        }
        files.sort(Comparator.comparingLong(File::lastModified).reversed());
        AtomicFile atomic=new AtomicFile(output);FileOutputStream stream=null;
        try{
            stream=atomic.startWrite();try(ZipOutputStream zip=new ZipOutputStream(stream)){
                if(operations!=null){JSONObject source=zipSnapshot(zip,operations,"operations.jsonl",Math.min(9L*1024*1024,remaining));sources.put(source);remaining-=source.optLong("copiedBytes");}
                // Exact weather receipt only: no health data, coordinates or rollback backup.
                File weatherRoot=context.getFilesDir().getCanonicalFile();
                File weatherReceipt=new File(weatherRoot,"weather-last-readback.json");
                ExpansionModels.put(manifest,"weatherEvidenceState",weatherReceipt.isFile()?"REJECTED_PATH_OR_LIMIT":"ABSENT");
                if(weatherReceipt.isFile()&&weatherReceipt.getCanonicalFile().getParentFile().equals(weatherRoot)
                        &&weatherReceipt.getCanonicalFile().equals(weatherReceipt.getAbsoluteFile())&&remaining>0){
                    JSONObject snapshot=zipSnapshot(zip,weatherReceipt,"weather-last-readback.json",Math.min(32L*1024,remaining));
                    sources.put(snapshot);remaining-=snapshot.optLong("copiedBytes");
                    ExpansionModels.put(manifest,"weatherEvidenceContainsCity",true);
                    ExpansionModels.put(manifest,"weatherEvidenceState","INCLUDED");
                }
                File weatherReadState=new File(weatherRoot,"weather-read-state.json");
                ExpansionModels.put(manifest,"weatherReadStateEvidence",weatherReadState.isFile()?"REJECTED_PATH_OR_LIMIT":"ABSENT");
                if(weatherReadState.isFile()&&weatherReadState.getCanonicalFile().getParentFile().equals(weatherRoot)&&weatherReadState.getCanonicalFile().equals(weatherReadState.getAbsoluteFile())&&weatherReadState.length()<=32L*1024&&remaining>=weatherReadState.length()){
                    JSONObject snapshot=zipSnapshot(zip,weatherReadState,"weather-read-state.json",32L*1024);sources.put(snapshot);remaining-=snapshot.optLong("copiedBytes");ExpansionModels.put(manifest,"weatherReadStateEvidence","INCLUDED");ExpansionModels.put(manifest,"weatherEvidenceContainsCity",true);
                }
                WeatherDeliveryController.WeeklyStatus weekly=WeatherDeliveryController.weeklyStatus(context);
                android.content.SharedPreferences weatherPrefs=context.getSharedPreferences("gt_dial_preferences",Context.MODE_PRIVATE);
                JSONObject weatherMetadata=new JSONObject().put("schemaVersion",1).put("weeklyStatus",weekly.status).put("weeklyReason",weekly.reason).put("verifiedDays",weekly.verifiedDays).put("source",weatherPrefs.getString("weather.source","")).put("fetchedAtMs",weatherPrefs.getLong("weather.fetched-at",0)).put("modelIssuedAtUnix",weatherPrefs.getLong("weather.model-issued-at",0)).put("forecastValidAtUnix",weatherPrefs.getLong("weather.forecast-valid-at",0)).put("evaluatedAtMs",weatherPrefs.getLong("weather.evaluated-at",0)).put("rangeBasis",weatherPrefs.getString("weather.range-basis","provider daily"));
                byte[] weatherMetadataBytes=weatherMetadata.toString().getBytes(StandardCharsets.UTF_8);
                if(weatherMetadataBytes.length>8192||remaining<weatherMetadataBytes.length)throw new IOException("WEATHER_METADATA_LIMIT");
                zip.putNextEntry(new ZipEntry("phone-weather-metadata.json"));zip.write(weatherMetadataBytes);zip.closeEntry();remaining-=weatherMetadataBytes.length;
                StringBuilder weatherMetadataHash=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(weatherMetadataBytes))weatherMetadataHash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                sources.put(new JSONObject().put("name","phone-weather-metadata.json").put("sourceBytes",weatherMetadataBytes.length).put("copiedBytes",weatherMetadataBytes.length).put("truncated",false).put("sha256",weatherMetadataHash.toString()));
                ExpansionModels.put(manifest,"weatherEvidenceContainsCity",true);
                int count=0;
                for(File source:files){if(++count>96||remaining<=0){omitted++;continue;}JSONObject snapshot=zipSnapshot(zip,source,"connection/"+source.getName(),Math.min(5L*1024*1024+512,remaining));sources.put(snapshot);remaining-=snapshot.optLong("copiedBytes");}
                if(diagnostic!=null){byte[] tail=diagnostic.tail().getBytes(StandardCharsets.UTF_8);zip.putNextEntry(new ZipEntry("current-session-tail.txt"));zip.write(tail);zip.closeEntry();}
                writeLocalDiagnostics(zip,localEntries);
                ExpansionModels.put(manifest,"sources",sources);ExpansionModels.put(manifest,"omittedOlderLogs",omitted);ExpansionModels.put(manifest,"snapshotNote","Operation snapshot contains metadata. Connection logs are copied as stored and may contain measurements or device identifiers. Separate raw health evidence and measurement report files are not included. Only the measured prefix of each source is copied; later appends are flagged.");
                zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));zip.closeEntry();zip.finish();zip.flush();atomic.finishWrite(stream);stream=null;
            }
        }catch(Exception e){if(stream!=null)atomic.failWrite(stream);throw e;}
        if(output.length()>64L*1024*1024)throw new IOException("DIAGNOSTIC_TOTAL_LIMIT");
        try(ZipFile verify=new ZipFile(output)){if(verify.getEntry("manifest.json")==null)throw new IOException("DIAGNOSTIC_READBACK");verifyLocalDiagnostics(verify,localEntries);}
        return output;
    }
    private static final long LOCAL_DIAGNOSTIC_LIMIT=32L*1024*1024;
    private static final class LocalDiagnosticEntry {
        final File source;final String name,sha;final byte[] bytes;final long crc;final int limit;final JSONObject metadata;
        boolean included;
        LocalDiagnosticEntry(File source,String name,byte[] bytes,int limit,JSONObject metadata)throws Exception {
            this.source=source;this.name=name;this.bytes=bytes;this.limit=limit;this.metadata=metadata;sha=DialImageNormalizer.hash(bytes);
            CRC32 checksum=new CRC32();checksum.update(bytes);crc=checksum.getValue();
            metadata.put("sha256",sha).put("crc",crc).put("copiedBytes",bytes.length).put("truncated",false).put("status","INCLUDED");
        }
    }
    private static List<LocalDiagnosticEntry> localDiagnostics(Context context,JSONObject manifest,JSONArray sources)throws Exception {
        List<LocalDiagnosticEntry> result=new ArrayList<>();JSONObject local=new JSONObject().put("budgetBytes",LOCAL_DIAGNOSTIC_LIMIT);manifest.put("localDial",local);
        JSONObject selection;
        try{selection=DialInstallJournal.selected(context);}catch(Exception|OutOfMemoryError e){local.put("selectionState","INVALID");selection=null;}
        if(selection==null){if(!local.has("selectionState"))local.put("selectionState","ABSENT");}
        else{
            String sha=selection.getString("artifactSha256"),operationId=selection.getString("operationId");local.put("selectionState","INCLUDED").put("artifactSha256",sha).put("operationId",operationId);
            String[] names={"face.dial","snapshot.json","project.json","source.bin","background.bgra"};int[] limits={1024*1024,64*1024,64*1024,20*1024*1024,868624};
            CompiledDialArtifactStore.Artifact artifact=null;String artifactState="INVALID";
            try{artifact=new CompiledDialArtifactStore(context).load(sha);artifactState="VALIDATED";}
            catch(IOException e){artifactState="UNREADABLE";}catch(Exception|OutOfMemoryError e){artifactState="INVALID";}
            local.put("artifactState",artifactState);
            // Android's filesDir may use /data/user/0 while canonical paths use /data/data.
            // Resolve the app root first, then keep the exact descendant path checks below.
            File artifactDir=new File(context.getFilesDir().getCanonicalFile(),"dial-compiler/artifacts/"+sha);
            for(int i=0;i<names.length;i++){
                String entryName="local/artifact/"+sha+"/"+names[i];JSONObject metadata=new JSONObject().put("name",entryName);sources.put(metadata);
                if(artifact==null){metadata.put("status",artifactState).put("copiedBytes",0);continue;}
                LocalDiagnosticEntry entry=captureLocal(new File(artifactDir,names[i]),entryName,limits[i],i==4?868624:-1,metadata);
                if(entry==null)continue;
                try{
                    byte[] validated=i==0?artifact.verified.bytes():i==1?artifact.snapshot.metadata():i==2?artifact.snapshot.projectBytes():i==3?artifact.snapshot.sourceBytes():artifact.snapshot.backgroundBytes();
                    if(!Arrays.equals(entry.bytes,validated)){metadata.put("status","CHANGED").put("copiedBytes",0);continue;}
                    addLocalEntry(result,entry);
                }catch(OutOfMemoryError e){metadata.put("status","LIMIT").put("copiedBytes",0);}
            }
            java.util.LinkedHashSet<String> journalIds=new java.util.LinkedHashSet<>();journalIds.add(operationId);
            String original=selection.optString("originalOperationId");if(!original.isEmpty())journalIds.add(original);
            for(String journalId:journalIds){
                File journalDir=new File(context.getFilesDir().getCanonicalFile(),"logs/dial-install/"+journalId);
                for(String name:new String[]{"events.jsonl","terminal.json","inventory-INITIAL.json","inventory-POST_INSTALL.json","inventory-RECHECK.json"}){
                    String entryName="local/install/"+journalId+"/"+name;JSONObject metadata=new JSONObject().put("name",entryName);sources.put(metadata);
                    int limit="events.jsonl".equals(name)?DialInstallJournal.MAX_BYTES:"terminal.json".equals(name)?8192:64*1024;
                    LocalDiagnosticEntry entry=captureLocal(new File(journalDir,name),entryName,limit,-1,metadata);
                    if(entry!=null)addLocalEntry(result,entry);
                }
            }
        }
        JSONObject ownership=new JSONObject().put("name","local/ownership-metadata.json");sources.put(ownership);
        try{
            byte[] metadata=new DynamicDialOwnershipStore(context).diagnosticMetadata().toString().getBytes(StandardCharsets.UTF_8);
            if(metadata.length>256*1024)ownership.put("status","LIMIT").put("copiedBytes",0);
            else addLocalEntry(result,new LocalDiagnosticEntry(null,"local/ownership-metadata.json",metadata,256*1024,ownership));
        }catch(Exception|OutOfMemoryError e){ownership.put("status","UNREADABLE").put("copiedBytes",0);}
        return result;
    }
    private static LocalDiagnosticEntry captureLocal(File source,String name,int limit,int exact,JSONObject metadata)throws Exception {
        try{
            if(!source.isFile()||!source.getCanonicalFile().equals(source.getAbsoluteFile())){metadata.put("status","UNREADABLE").put("copiedBytes",0);return null;}
            long size=source.length(),modified=source.lastModified();metadata.put("sourceBytes",size);
            if(size>limit){metadata.put("status","LIMIT").put("copiedBytes",0);return null;}
            if(size<=0||exact>=0&&size!=exact){metadata.put("status","INVALID").put("copiedBytes",0);return null;}
            byte[] bytes=CompiledDialArtifactStore.read(source,limit);
            if(bytes.length!=size||source.length()!=size||source.lastModified()!=modified){metadata.put("status","CHANGED").put("copiedBytes",0);return null;}
            return new LocalDiagnosticEntry(source,name,bytes,limit,metadata);
        }catch(IOException e){metadata.put("status","UNREADABLE").put("copiedBytes",0);return null;}
        catch(OutOfMemoryError e){metadata.put("status","LIMIT").put("copiedBytes",0);return null;}
    }
    private static void addLocalEntry(List<LocalDiagnosticEntry> entries,LocalDiagnosticEntry candidate)throws Exception {
        long total=candidate.bytes.length;for(LocalDiagnosticEntry entry:entries)total+=entry.bytes.length;
        if(total>LOCAL_DIAGNOSTIC_LIMIT)candidate.metadata.put("status","LIMIT").put("copiedBytes",0);else entries.add(candidate);
    }
    private static void writeLocalDiagnostics(ZipOutputStream zip,List<LocalDiagnosticEntry> entries)throws Exception {
        for(LocalDiagnosticEntry entry:entries){
            if(entry.source!=null)try{
                if(!Arrays.equals(entry.bytes,CompiledDialArtifactStore.read(entry.source,entry.limit))){entry.metadata.put("status","CHANGED").put("copiedBytes",0);continue;}
            }catch(IOException e){entry.metadata.put("status","UNREADABLE").put("copiedBytes",0);continue;}
            catch(OutOfMemoryError e){entry.metadata.put("status","LIMIT").put("copiedBytes",0);continue;}
            ZipEntry record=new ZipEntry(entry.name);record.setMethod(ZipEntry.STORED);record.setSize(entry.bytes.length);record.setCompressedSize(entry.bytes.length);record.setCrc(entry.crc);
            zip.putNextEntry(record);zip.write(entry.bytes);zip.closeEntry();entry.included=true;
        }
    }
    private static void verifyLocalDiagnostics(ZipFile zip,List<LocalDiagnosticEntry> entries)throws Exception {
        for(LocalDiagnosticEntry entry:entries){if(!entry.included)continue;ZipEntry record=zip.getEntry(entry.name);
            if(record==null||record.isDirectory()||record.getMethod()!=ZipEntry.STORED||record.getSize()!=entry.bytes.length||record.getCompressedSize()!=entry.bytes.length||record.getCrc()!=entry.crc)throw new IOException("LOCAL_DIAGNOSTIC_ENTRY_READBACK");
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");CRC32 crc=new CRC32();int offset=0;
            try(InputStream input=zip.getInputStream(record)){byte[] buffer=new byte[8192];for(;;){int count=input.read(buffer,0,Math.min(buffer.length,entry.bytes.length-offset+1));if(count==-1)break;
                if(count<=0||count>entry.bytes.length-offset)throw new IOException("LOCAL_DIAGNOSTIC_SIZE_READBACK");
                for(int i=0;i<count;i++)if(buffer[i]!=entry.bytes[offset+i])throw new IOException("LOCAL_DIAGNOSTIC_BYTES_READBACK");
                digest.update(buffer,0,count);crc.update(buffer,0,count);offset+=count;
            }}
            StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            if(offset!=entry.bytes.length||crc.getValue()!=entry.crc||!entry.sha.equals(sha.toString()))throw new IOException("LOCAL_DIAGNOSTIC_HASH_READBACK");
        }
    }
    private static JSONObject zipSnapshot(ZipOutputStream zip,File source,String name,long limit)throws Exception{
        long size=source.length(),modified=source.lastModified(),expected=Math.min(size,limit),copied=0;boolean unreadable=false;
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");zip.putNextEntry(new ZipEntry(name));
        try(InputStream in=new FileInputStream(source)){byte[] buffer=new byte[8192];while(copied<expected){int n=in.read(buffer,0,(int)Math.min(buffer.length,expected-copied));if(n<0)break;zip.write(buffer,0,n);digest.update(buffer,0,n);copied+=n;}}
        catch(IOException e){unreadable=true;}zip.closeEntry();
        JSONObject metadata=new JSONObject();ExpansionModels.put(metadata,"name",name);ExpansionModels.put(metadata,"sourceBytes",size);ExpansionModels.put(metadata,"copiedBytes",copied);ExpansionModels.put(metadata,"truncated",copied<size);ExpansionModels.put(metadata,"unreadable",unreadable);ExpansionModels.put(metadata,"changedDuringCopy",source.length()!=size||source.lastModified()!=modified);StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format(java.util.Locale.ROOT,"%02x",b&255));ExpansionModels.put(metadata,"sha256",sha.toString());return metadata;
    }
    Report collect(Options o){
        o.validate();JSONObject report=new JSONObject();ExpansionModels.put(report,"schemaVersion",2);ExpansionModels.put(report,"createdAtMillis",System.currentTimeMillis());
        JSONArray strengthEntries=new JSONArray(),strengthSets=new JSONArray(),observations=new JSONArray(),segments=new JSONArray();
        ExpansionModels.put(report,"strengthEntries",strengthEntries);ExpansionModels.put(report,"strengthSets",strengthSets);ExpansionModels.put(report,"workoutObservations",observations);ExpansionModels.put(report,"workoutSegments",segments);
        ExpansionModels.put(report,"fromLocalDate",o.from.toString());ExpansionModels.put(report,"toLocalDateExclusive",o.to.toString());ExpansionModels.put(report,"displayZone",o.zone.getId());
        ExpansionModels.put(report,"deviceKey",OperationJournal.deviceKey(o.device));ExpansionModels.put(report,"notesIncluded",o.notes);ExpansionModels.put(report,"routesIncluded",o.routes);for(String section:new String[]{"activity","health","workouts","plans","manual"})ExpansionModels.put(report,section+"Included", "activity".equals(section)?o.activity:"health".equals(section)?o.health:"workouts".equals(section)?o.workouts:"plans".equals(section)?o.plans:o.manual);
        List<String> lines=new ArrayList<>();lines.add("Часовой · личный отчёт");lines.add(o.from+" — "+o.to.minusDays(1)+" · "+o.zone);lines.add("Пропуски не являются нулём. Наблюдения не доказывают полный день.");
        long start=ExpansionModels.start(o.from,o.zone),end=ExpansionModels.start(o.to,o.zone);
        if(o.activity){JSONArray records=new JSONArray();lines.add("");lines.add("Активность · накопленные наблюдения телефона");
            if(!o.device.isEmpty())for(String day:activity.observedDays(o.device)){LocalDate d=LocalDate.parse(day);if(d.isBefore(o.from)||!d.isBefore(o.to))continue;ActivityHistoryEntry e=activity.latestByDay(o.device,day);if(e==null)continue;
                JSONObject r=new JSONObject();ExpansionModels.put(r,"source","PHONE_OBSERVED_CUMULATIVE");ExpansionModels.put(r,"phoneDay",day);ExpansionModels.put(r,"receivedAtMillis",e.receivedAt);ExpansionModels.put(r,"phoneZoneId",e.phoneZoneId);ExpansionModels.put(r,"steps",e.steps);ExpansionModels.put(r,"calorieRaw",e.calorieRaw);ExpansionModels.put(r,"distanceRaw",e.distanceRaw);ExpansionModels.put(r,"completeDay",false);records.put(r);lines.add(day+" · шаги "+e.steps+" · калории/расстояние raw "+e.calorieRaw+" / "+e.distanceRaw);}
            if(records.length()==0)lines.add("В этом периоде наблюдений нет.");ExpansionModels.put(report,"activity",records);}
        if(o.health){JSONArray records=new JSONArray();lines.add("");lines.add("Сохранённые записи часов");
            if(!o.device.isEmpty())for(String dataset:new String[]{"heart","sleep","spo2","sports"})for(ExpansionModels.HealthRecord r:store.healthForReport(o.device,dataset,start/1000,end/1000)){
                JSONObject j=normalizedHealth(r);ExpansionModels.put(j,"deviceKey",OperationJournal.deviceKey(r.device));records.put(j);
                String at=Instant.ofEpochSecond(r.tsSeconds).atZone(o.zone).toString();String value=r.value==null?"значение не определено":r.value.toString()+" "+r.data.optString("unit");
                if("sleep".equals(dataset)&&r.validity.startsWith("VALID"))value="сон "+Math.round(r.data.optLong("asleepSeconds")/60.0)+" мин; неизвестные стадии "+r.data.optLong("unknownSeconds")+" с";
                if("sports".equals(dataset)&&r.validity.startsWith("VALID"))value="тип "+r.data.optString("workoutType")+"; временной интервал "+Math.max(0,r.data.optLong("endTime")-r.data.optLong("startTime"))+" с; активная длительность неизвестна";
                lines.add(dataset+" · "+at+" · "+value+" · "+r.validity+" · "+r.pageState+" · "+r.coverage);}
            if(records.length()==0)lines.add("В этом периоде записей часов нет.");ExpansionModels.put(report,"health",records);}
        if(o.workouts){JSONArray records=new JSONArray();lines.add("");lines.add("Тренировки телефона");for(JSONObject r:store.reportRows("workouts")){long at=r.optLong("startedAt");if(at<start||at>=end||!"SAVED".equals(r.optString("status")))continue;
                JSONObject j=select(r,new String[]{"id","source","typeId","status","startedAt","endedAt","zoneIdAtImport","elapsedActiveMillis","accumulatedPauseMillis","goalMinutes","distanceMeters","heartRate","calories","gpsRequested","gpsState","gpsAccepted","gpsRejected","recoveryGapUnknown","planId"});
                for(String metric:new String[]{"zoneIdAtStart","localDateAtStart","stepsObserved","stepsCoverageMillis","heartSampleCount","heartAverage","heartMax","heartLast","heartLastMeasuredAt","metricState","metricProcessId"})if(r.has(metric))ExpansionModels.put(j,metric,r.opt(metric));
                ExpansionModels.put(j,"metricDeviceKey",OperationJournal.deviceKey(r.optString("metricDevice")));
                if(o.notes)ExpansionModels.put(j,"note",r.optString("note"));ExpansionModels.put(j,"laps",ExpansionModels.array(store.children("workout_laps",r.optString("id"))));if(o.routes){List<JSONObject> fixes=store.children("workout_locations",r.optString("id"));ExpansionModels.put(j,"locations",ExpansionModels.array(fixes));lines.add("Маршрут телефона · точные координаты по явному выбору: "+fixes.size()+" точек");for(JSONObject fix:fixes)lines.add(Instant.ofEpochMilli(fix.optLong("timestamp"))+" · "+fix.optDouble("latitude")+", "+fix.optDouble("longitude")+" · точность "+fix.optDouble("accuracyMeters")+" м");}records.put(j);
                lines.add(Instant.ofEpochMilli(at).atZone(o.zone).toLocalDate()+" · "+WorkoutCatalog.name(r)+" · "+WorkoutRecorder.duration(r.optLong("elapsedActiveMillis"))+" · "+r.optString("gpsState"));if(o.notes&&!r.optString("note").isEmpty())lines.add("Заметка: "+r.optString("note"));
                lines.add("Шаги в наблюдаемых интервалах: "+(r.isNull("stepsObserved")?"нет данных":r.optLong("stepsObserved"))+" · покрытие "+WorkoutRecorder.duration(r.optLong("stepsCoverageMillis")));
                if(r.optInt("heartSampleCount")>0)lines.add("Пульс: "+r.optInt("heartSampleCount")+" измерений · средний "+String.format(java.util.Locale.getDefault(),"%.1f",r.optDouble("heartAverage"))+" · максимум "+r.optInt("heartMax")+" уд/мин");
                collectDiary(o,r,strengthEntries,strengthSets,observations,segments,lines);
            }
            if(records.length()==0)lines.add("Сохранённых занятий за период нет.");ExpansionModels.put(report,"workouts",records);}
        if(o.plans){JSONArray records=new JSONArray();lines.add("");lines.add("Планы");for(JSONObject r:store.reportRows("plans")){LocalDate date=LocalDate.parse(r.optString("localDate"));if(date.isBefore(o.from)||!date.isBefore(o.to))continue;JSONObject j=select(r,new String[]{"id","source","localDate","startLocalTime","zoneId","sportId","durationMinutes","goal","goalUnit","status","workoutId"});if(o.notes)ExpansionModels.put(j,"note",r.optString("note"));records.put(j);lines.add(date+" · "+WorkoutCatalog.name(r.optString("sportId"))+" · "+r.optString("status"));if(o.notes&&!r.optString("note").isEmpty())lines.add("Заметка плана: "+r.optString("note"));}ExpansionModels.put(report,"plans",records);}
        if(o.manual||o.notes){JSONArray records=new JSONArray();lines.add("");lines.add("Ручной журнал · значения введены пользователем");for(JSONObject r:store.reportRows("manual_entries")){long at=r.optLong("timestamp");if(at<start||at>=end)continue;if("NOTE".equals(r.optString("kind"))&&!o.notes||!o.manual&&r.optString("text").isEmpty())continue;JSONObject j=select(r,o.manual?new String[]{"id","source","timestamp","zoneIdAtImport","kind","value","unit","systolic","diastolic","pulse"}:new String[]{"id","source","timestamp","zoneIdAtImport","kind","sleepRecordId","sleepEvidenceId"});if(o.notes)ExpansionModels.put(j,"text",r.optString("text"));records.put(j);lines.add(Instant.ofEpochMilli(at).atZone(o.zone).toLocalDate()+" · "+r.optString("kind")+" · "+(!o.manual?"только текст":r.isNull("value")?r.has("systolic")?r.optInt("systolic")+"/"+r.optInt("diastolic"):"без числового значения":r.opt("value")));if(o.notes&&!r.optString("text").isEmpty())lines.add(r.optString("text"));}ExpansionModels.put(report,"manual",records);}
        if(report.toString().getBytes(StandardCharsets.UTF_8).length>16*1024*1024)throw new IllegalStateException("REPORT_SIZE");
        return new Report(report,lines);
    }
    private void collectDiary(Options options,JSONObject workout,JSONArray entries,JSONArray sets,JSONArray observations,JSONArray segments,List<String> lines){
        String workoutId=workout.optString("id");
        for(JSONObject entry:store.diaryRows("strength_entries",workoutId)){
            JSONObject exported=select(entry,new String[]{"id","workoutId","variantId","nameSnapshot","group","illustration","loadMode","equipment","equipmentProfileId","repConvention","position","revision","updatedAt"});
            if(options.notes)ExpansionModels.put(exported,"note",entry.optString("note"));append(entries,exported);
            lines.add(entry.optString("nameSnapshot")+" · "+loadDescription(entry.optString("loadMode"))+(entry.optString("equipmentProfileId").isEmpty()?"":" · оборудование: "+entry.optString("equipmentProfileId")));
            if(options.notes&&!entry.optString("note").isEmpty())lines.add("Заметка упражнения: "+entry.optString("note"));
            for(JSONObject set:store.diaryRows("strength_sets",entry.optString("id"))){
                JSONObject exportedSet=select(set,new String[]{"id","entryId","position","revision","state","setKind","loadInput","repsInput","loadGrams","reps","completedAt","updatedAt","nextDraftId"});
                ExpansionModels.put(exportedSet,"workoutId",workoutId);ExpansionModels.put(exportedSet,"loadUnit","GRAMS");ExpansionModels.put(exportedSet,"loadMode",entry.optString("loadMode"));
                if(options.notes)ExpansionModels.put(exportedSet,"note",set.optString("note"));append(sets,exportedSet);
                if("DONE".equals(set.optString("state")))lines.add(("WARMUP".equals(set.optString("setKind"))?"Разминка":"Рабочий подход")+" · "+(set.isNull("loadGrams")?"свой вес":new java.math.BigDecimal(set.optLong("loadGrams")).movePointLeft(3).stripTrailingZeros().toPlainString()+" кг")+" × "+set.optInt("reps")+" повторений");
                if(options.notes&&!set.optString("note").isEmpty())lines.add("Заметка подхода: "+set.optString("note"));
            }
        }
        for(JSONObject row:store.diaryRows("workout_observations",workoutId)){
            JSONObject exported=select(row,new String[]{"id","workoutId","segmentId","kind","state","quality","revision","receivedAt","receivedElapsed","processId","bootIdentity","phoneDay","zoneId","stepsCumulative","stepsDelta","coverageMillis","calorieRaw","distanceRaw","measuredAt","value","unit","rawSha256","evidenceId","healthRecordId"});ExpansionModels.put(exported,"deviceKey",OperationJournal.deviceKey(row.optString("device")));append(observations,exported);
        }
        for(JSONObject row:store.diaryRows("workout_segments",workoutId))append(segments,select(row,new String[]{"id","workoutId","state","revision","startAt","startElapsed","lastAt","lastElapsed","endAt","endElapsed","endReason","unknownGapAfter","bootIdentity","processId"}));
    }
    private static void append(JSONArray array,JSONObject row){if(array.length()>=50000)throw new IllegalStateException("REPORT_RECORD_LIMIT");array.put(row);}
    private static String loadDescription(String mode){if("DUMBBELL_EACH".equals(mode))return "вес одной гантели";if("BARBELL_TOTAL".equals(mode))return "полный вес с грифом";if("BODYWEIGHT".equals(mode))return "свой вес";if("BODYWEIGHT_ADDED".equals(mode))return "добавочный вес";if("ASSISTANCE".equals(mode))return "помощь тренажёра; меньше помощи при тех же повторах";return "указанное значение тренажёра";}
    File export(Report report,String format)throws Exception{
        byte[] bytes;
        if("json".equals(format))bytes=report.json.toString(2).getBytes(StandardCharsets.UTF_8);
        else if("csv".equals(format))bytes=csv(report.json).getBytes(StandardCharsets.UTF_8);
        else if("pdf".equals(format))bytes=pdf(report.lines);
        else throw new IllegalArgumentException("REPORT_FORMAT");
        if(bytes.length>16*1024*1024)throw new IllegalStateException("REPORT_SIZE");
        File directory=new File(context.getFilesDir(),"reports");if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("REPORT_DIRECTORY");
        File output=new File(directory,"chasovoy-"+UUID.randomUUID()+"."+format);AtomicFile atomic=new AtomicFile(output);FileOutputStream stream=null;
        try{stream=atomic.startWrite();stream.write(bytes);atomic.finishWrite(stream);}catch(Exception e){if(stream!=null)atomic.failWrite(stream);throw e;}
        try(InputStream in=atomic.openRead()){ByteArrayOutputStream copy=new ByteArrayOutputStream();byte[] buffer=new byte[4096];for(int n;(n=in.read(buffer))!=-1;){if(copy.size()+n>16*1024*1024)throw new IllegalStateException("REPORT_READBACK_SIZE");copy.write(buffer,0,n);}if(!Arrays.equals(bytes,copy.toByteArray()))throw new IllegalStateException("REPORT_READBACK");}
        return output;
    }

    private static JSONObject normalizedHealth(ExpansionModels.HealthRecord r){JSONObject j=select(r.json(false),new String[]{"id","source","dataset","timestamp","timestampUnit","receivedAtMillis","zoneIdAtImport","evidenceId","rawSha256","pageState","recordValidity","datasetCoverage","value"});String[] keys="sleep".equals(r.dataset)?new String[]{"enter","exit","intervals","asleepSeconds","awakeSeconds","unknownSeconds","method","zoneProvenance"}:"sports".equals(r.dataset)?new String[]{"recordId","workoutType","status","startTime","endTime","timestampUnit","method","zoneProvenance"}:new String[]{"unit","method","zoneProvenance"};ExpansionModels.put(j,"data",select(r.data,keys));return j;}
    private static JSONObject select(JSONObject source,String[] keys){JSONObject result=new JSONObject();for(String key:keys)if(source.has(key))ExpansionModels.put(result,key,source.opt(key));return result;}
    private static String csv(JSONObject report){StringBuilder out=new StringBuilder("\ufeffsection,record_id,field,value\r\n");java.util.Iterator<String> metadata=report.keys();while(metadata.hasNext()){String key=metadata.next();if(!(report.opt(key) instanceof JSONArray))out.append(cell("metadata")).append(",,").append(cell(key)).append(',').append(cell(report.isNull(key)?"":String.valueOf(report.opt(key)))).append("\r\n");}for(String section:new String[]{"activity","health","workouts","strengthEntries","strengthSets","workoutObservations","workoutSegments","plans","manual"}){JSONArray rows=report.optJSONArray(section);if(rows==null)continue;for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;java.util.Iterator<String> keys=row.keys();while(keys.hasNext()){String key=keys.next();out.append(cell(section)).append(',').append(cell(row.optString("id",Integer.toString(i+1)))).append(',').append(cell(key)).append(',').append(cell(row.isNull(key)?"":String.valueOf(row.opt(key)))).append("\r\n");}}}return out.toString();}
    private static String cell(String value){String safe=value;if(!safe.isEmpty()&&"=+-@\t\r".indexOf(safe.charAt(0))>=0)safe="'"+safe;return "\""+safe.replace("\"","\"\"")+"\"";}
    private static byte[] pdf(List<String> lines)throws Exception{
        PdfDocument doc=new PdfDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream();Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTextSize(11);PdfDocument.Page page=null;Canvas canvas=null;int number=0;float y=0;
        try{for(String raw:lines){String[] paragraphs=raw.split("\\r?\\n",-1);for(String paragraph:paragraphs){String remaining=paragraph;do{if(page==null||y>790){if(page!=null)doc.finishPage(page);page=doc.startPage(new PdfDocument.PageInfo.Builder(595,842,++number).create());canvas=page.getCanvas();y=42;canvas.drawText("Часовой · страница "+number,36,818,paint);}int take=remaining.isEmpty()?0:paint.breakText(remaining,true,523,null);if(take==0&&!remaining.isEmpty())take=1;if(take<remaining.length()){int space=remaining.lastIndexOf(' ',take);if(space>0)take=space;}String part=remaining.substring(0,take);canvas.drawText(part,36,y,paint);y+=16;remaining=remaining.substring(take).trim();}while(!remaining.isEmpty());}}if(page==null){page=doc.startPage(new PdfDocument.PageInfo.Builder(595,842,1).create());}doc.finishPage(page);page=null;doc.writeTo(bytes);return bytes.toByteArray();}finally{if(page!=null)doc.finishPage(page);doc.close();}
    }
}
