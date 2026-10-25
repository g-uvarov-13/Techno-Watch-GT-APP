package app.gtdial.lab;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Metadata only. Health values, coordinates, note text and arbitrary exception messages cannot enter. */
final class OperationJournal {
    private static OperationJournal instance;
    private static final int MAX_QUEUE=512,MAX_TAIL=240;
    private static final long MAX_FILE=2L*1024*1024;
    private final Handler writer;
    private final File file;
    private final android.content.SharedPreferences retentionPrefs;
    private long fileStartedAt;
    private JSONObject connectionSummary=new JSONObject();
    private final ArrayDeque<JSONObject> tail=new ArrayDeque<>();
    private long seq,dropped;
    private final String sessionId=UUID.randomUUID().toString();
    private int pending;
    private volatile boolean diskFailed,capped;
    static synchronized OperationJournal get(Context context) {if(instance==null)instance=new OperationJournal(context.getApplicationContext());return instance;}
    private OperationJournal(Context context) {
        File directory=new File(context.getFilesDir(),"logs");boolean available=directory.isDirectory()||directory.mkdirs();
        file=available?new File(directory,"operations-v1.jsonl"):null;
        retentionPrefs=context.getSharedPreferences("diagnostic_retention",Context.MODE_PRIVATE);fileStartedAt=retentionPrefs.getLong("operationFileStartedAt",file!=null&&file.exists()?file.lastModified():System.currentTimeMillis());retentionPrefs.edit().putLong("operationFileStartedAt",fileStartedAt).apply();
        HandlerThread thread=new HandlerThread("chasovoy-operations");thread.start();writer=new Handler(thread.getLooper());
    }
    private String episodeId="",attemptId="";
    private long episodeStartedElapsed=-1,attemptStartedElapsed=-1,lastReadyElapsed=-1;
    private boolean episodeOpen,attemptFailed;
    synchronized JSONObject connectionSummary(){JSONObject out=ExpansionModels.copy(connectionSummary);if(episodeOpen)ExpansionModels.put(out,"unavailableMillis",Math.max(0,SystemClock.elapsedRealtime()-episodeStartedElapsed));return out;}
    /** Connection metadata only; these hooks never schedule work or alter transport state. */
    private void openConnectionEpisode(String cause){if(episodeOpen)return;episodeOpen=true;episodeId=UUID.randomUUID().toString();episodeStartedElapsed=SystemClock.elapsedRealtime();connectionSummary=new JSONObject();ExpansionModels.put(connectionSummary,"episodeId",episodeId);ExpansionModels.put(connectionSummary,"startedAt",System.currentTimeMillis());ExpansionModels.put(connectionSummary,"state","UNAVAILABLE");ExpansionModels.put(connectionSummary,"cause",token(cause,48));ExpansionModels.put(connectionSummary,"processLocal",true);ExpansionModels.put(connectionSummary,"attemptCount",0);ExpansionModels.put(connectionSummary,"setupFailureCount",0);}
    synchronized void connectionUnavailable(){openConnectionEpisode("TRANSPORT_RECOVERY");}
    synchronized void connectionAttempt(){openConnectionEpisode("CONNECT");attemptId=UUID.randomUUID().toString();attemptStartedElapsed=SystemClock.elapsedRealtime();attemptFailed=false;ExpansionModels.put(connectionSummary,"attemptCount",connectionSummary.optLong("attemptCount")+1);event(attemptId,episodeId,"ble_episode","connect","REQUESTED","PENDING","","","",0,0,0,"phase","connect");}
    synchronized void connectionFailure(String phase,int status,boolean wasReady){openConnectionEpisode(wasReady?"READY_CONNECTION_LOST":"SETUP_FAILED");if(!wasReady&&attemptFailed)return;attemptFailed=true;ExpansionModels.put(connectionSummary,"lastFailurePhase",token(phase,48));ExpansionModels.put(connectionSummary,"lastStatus",status);ExpansionModels.put(connectionSummary,"lastFailureAt",System.currentTimeMillis());if(!wasReady)ExpansionModels.put(connectionSummary,"setupFailureCount",connectionSummary.optLong("setupFailureCount")+1);if(wasReady&&lastReadyElapsed>=0)ExpansionModels.put(connectionSummary,"previousReadyMillis",Math.max(0,SystemClock.elapsedRealtime()-lastReadyElapsed));event(attemptId,episodeId,"ble_episode",wasReady?"ready_loss":"setup_failure","FAILED","FAILED","GATT_"+status,"","",1,0,attemptStartedElapsed<0?0:SystemClock.elapsedRealtime()-attemptStartedElapsed,"phase",phase);}
    synchronized void connectionReady(){if(!episodeOpen){if(lastReadyElapsed<0)lastReadyElapsed=SystemClock.elapsedRealtime();return;}long now=SystemClock.elapsedRealtime();ExpansionModels.put(connectionSummary,"state","RECOVERED");ExpansionModels.put(connectionSummary,"readyAt",System.currentTimeMillis());ExpansionModels.put(connectionSummary,"unavailableMillis",Math.max(0,now-episodeStartedElapsed));ExpansionModels.put(connectionSummary,"attemptToReadyMillis",attemptStartedElapsed<0?0:Math.max(0,now-attemptStartedElapsed));event(attemptId,episodeId,"ble_episode","ready","COMPLETE","SUCCESS","","","",connectionSummary.optLong("attemptCount"),0,now-episodeStartedElapsed,"phase","ready");episodeOpen=false;lastReadyElapsed=now;}
    synchronized void connectionStopped(){if(!episodeOpen)return;ExpansionModels.put(connectionSummary,"state","USER_STOPPED");ExpansionModels.put(connectionSummary,"unavailableMillis",Math.max(0,SystemClock.elapsedRealtime()-episodeStartedElapsed));event(attemptId,episodeId,"ble_episode","stop","COMPLETE","CANCELLED","USER_STOPPED","","",0,0,SystemClock.elapsedRealtime()-episodeStartedElapsed,"phase","user");episodeOpen=false;}
    void maintainRetention(java.util.function.Consumer<Boolean> finished){writer.post(()->{boolean success=true;try{if(file!=null&&file.exists()&&System.currentTimeMillis()-fileStartedAt>=DiagnosticRetention.WEEK&&!DiagnosticRetention.leased())rotate();}catch(Exception e){diskFailed=true;success=false;}finally{finished.accept(success);}});}
    String begin(String component,String action,String profile,String device) {
        String id=UUID.randomUUID().toString();event(id,"",component,action,"REQUESTED","PENDING","",profile,device,0,0,0,"","");return id;
    }
    void stage(String id,String component,String action,String stage,String outcome,String error,long count,long bytes,long duration) {
        event(id,"",component,action,stage,outcome,error,"","",count,bytes,duration,"","");
    }
    void event(String id,String parent,String component,String action,String stage,String outcome,String error,String profile,String device,long count,long bytes,long duration,String dependency,String state) {
        event(id,parent,component,action,stage,outcome,error,profile,device,count,bytes,duration,
                dependency.isEmpty()?java.util.Collections.emptyMap():java.util.Collections.singletonMap(dependency,state));
    }
    /** Small metadata snapshot only; callers cannot append payloads or unbounded fields. */
    void event(String id,String parent,String component,String action,String stage,String outcome,String error,String profile,String device,long count,long bytes,long duration,java.util.Map<String,String> states) {
        if(states==null||states.size()>5)throw new IllegalArgumentException("JOURNAL_METADATA_BOUNDS");
        JSONObject o=new JSONObject();
        synchronized(this) {
            ExpansionModels.put(o,"schemaVersion",1);ExpansionModels.put(o,"seq",++seq);ExpansionModels.put(o,"wallTime",System.currentTimeMillis());
            ExpansionModels.put(o,"sessionId",sessionId);
            ExpansionModels.put(o,"elapsedRealtime",SystemClock.elapsedRealtime());ExpansionModels.put(o,"operationId",token(id,80));ExpansionModels.put(o,"parentOperationId",token(parent,80));
            ExpansionModels.put(o,"component",token(component,48));ExpansionModels.put(o,"action",token(action,64));ExpansionModels.put(o,"stage",token(stage,48));
            ExpansionModels.put(o,"outcome",token(outcome,48));ExpansionModels.put(o,"errorCode",token(error,64));ExpansionModels.put(o,"sourceProfile",token(profile,64));
            ExpansionModels.put(o,"deviceKey",deviceKey(device));JSONObject counts=new JSONObject();ExpansionModels.put(counts,"records",Math.max(0,count));ExpansionModels.put(o,"counts",counts);
            ExpansionModels.put(o,"bytes",Math.max(0,bytes));ExpansionModels.put(o,"durationMs",Math.max(0,duration));
            JSONObject dependencies=new JSONObject();for(java.util.Map.Entry<String,String> entry:states.entrySet())ExpansionModels.put(dependencies,token(entry.getKey(),48),token(entry.getValue(),48));ExpansionModels.put(o,"dependencyStates",dependencies);
            tail.addLast(o);while(tail.size()>MAX_TAIL)tail.removeFirst();
            if(file==null||capped||diskFailed||pending>=MAX_QUEUE){dropped++;return;}pending++;
        }
        writer.post(()->write(o));
    }
    synchronized List<JSONObject> tail() {return new ArrayList<>(tail);}
    List<JSONObject> readRecent() throws Exception {
        if(LooperCheck.main())throw new IllegalStateException("JOURNAL_READ_ON_UI");
        CountDownLatch done=new CountDownLatch(1);List<JSONObject> result=new ArrayList<>();Exception[] error=new Exception[1];
        writer.post(()->{try{if(file!=null){ArrayDeque<JSONObject> records=new ArrayDeque<>();for(int archive=3;archive>=0;archive--){File part=archive==0?file:new File(file.getParentFile(),file.getName()+"."+archive);if(!part.isFile())continue;if(part.length()>MAX_FILE+512)throw new IllegalStateException("JOURNAL_READ_SIZE");try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(new FileInputStream(part),StandardCharsets.UTF_8))){for(String line;(line=reader.readLine())!=null;){if(line.length()>4096)continue;try{records.addLast(new JSONObject(line));while(records.size()>1000)records.removeFirst();}catch(org.json.JSONException ignored){}}}}result.addAll(records);}if(diskFailed||result.isEmpty())result.addAll(tail());}catch(Exception e){error[0]=e;}finally{done.countDown();}});
        if(!done.await(15,TimeUnit.SECONDS))throw new IllegalStateException("JOURNAL_READ_TIMEOUT");if(error[0]!=null)throw error[0];return result;
    }
    synchronized String storageState() {return diskFailed?"DISK_FAILED":capped?"FILE_CAP_REACHED":"dropped="+dropped;}
    long clear()throws Exception {
        if(LooperCheck.main())throw new IllegalStateException("JOURNAL_CLEAR_ON_UI");
        CountDownLatch done=new CountDownLatch(1);long[] removed={0};Exception[] error={null};
        if(!writer.post(()->{try{
            if(file==null)throw new IllegalStateException("JOURNAL_UNAVAILABLE");
            removed[0]=DiagnosticFiles.clear(file.getParentFile(),"operations-v1[.]jsonl([.][123])?");
            removed[0]+=DiagnosticFiles.clear(file.getParentFile(),DiagnosticFiles.SNAPSHOT);
            synchronized(this){tail.clear();dropped=0;diskFailed=false;capped=false;}
        }catch(Exception e){error[0]=e;}finally{done.countDown();}}))throw new IllegalStateException("JOURNAL_WRITER_UNAVAILABLE");
        if(!done.await(15,TimeUnit.SECONDS))throw new IllegalStateException("JOURNAL_CLEAR_TIMEOUT");
        if(error[0]!=null)throw error[0];return removed[0];
    }
    File export() throws Exception {
        try(DiagnosticRetention.Lease lease=DiagnosticRetention.exportLease()){return exportLeased();}
    }
    private File exportLeased() throws Exception {
        if(LooperCheck.main())throw new IllegalStateException("JOURNAL_EXPORT_ON_UI");
        CountDownLatch drained=new CountDownLatch(1);File[] output=new File[1];Exception[] error=new Exception[1];
        // Snapshot on the same writer prevents a rotation or append between flush and copy.
        writer.post(()->{try{output[0]=snapshotExport();}catch(Exception e){error[0]=e;}finally{drained.countDown();}});
        if(!drained.await(15,TimeUnit.SECONDS))throw new IllegalStateException("JOURNAL_FLUSH_TIMEOUT");
        if(error[0]!=null)throw error[0];return output[0];
    }
    private File snapshotExport() throws Exception {
        if(file==null)throw new IllegalStateException("JOURNAL_UNAVAILABLE");
        File output=new File(file.getParentFile(),"operations-export-"+UUID.randomUUID()+".jsonl");
        try(FileOutputStream out=new FileOutputStream(output)) {
            JSONObject status=new JSONObject();ExpansionModels.put(status,"schemaVersion",1);ExpansionModels.put(status,"stage","EXPORT_STORAGE_STATE");ExpansionModels.put(status,"diskFailed",diskFailed);ExpansionModels.put(status,"dropped",dropped);
            out.write((status.toString()+"\n").getBytes(StandardCharsets.UTF_8));
            for(int archive=3;archive>=0;archive--){File part=archive==0?file:new File(file.getParentFile(),file.getName()+"."+archive);if(part.isFile()){try(FileInputStream in=new FileInputStream(part)){byte[] buf=new byte[4096];for(int n;(n=in.read(buf))!=-1;)out.write(buf,0,n);}}}
            if(diskFailed)for(JSONObject entry:tail())out.write((entry.toString()+"\n").getBytes(StandardCharsets.UTF_8));
        }return output;
    }
    private void write(JSONObject o) {
        try {
            if(file==null)return;
            long omitted; synchronized(this){omitted=dropped;}
            byte[] line=(o.toString()+"\n").getBytes(StandardCharsets.UTF_8);
            if(file.length()+line.length>MAX_FILE||System.currentTimeMillis()-fileStartedAt>=DiagnosticRetention.WEEK){if(DiagnosticRetention.leased()){synchronized(this){dropped++;}return;}mark("FILE_ROTATED",omitted);if(!rotate()){synchronized(this){dropped++;}return;}}
            try(FileOutputStream out=new FileOutputStream(file,true)){out.write(line);}
            if(omitted>0){mark("LINES_OMITTED",omitted);synchronized(this){dropped=Math.max(0,dropped-omitted);}}
        }catch(Exception e){diskFailed=true;}finally{synchronized(this){pending--;}}
    }
    private boolean rotate()throws java.io.IOException {return DiagnosticRetention.whileUnleased(this::rotateUnleased);}
    private void rotateUnleased()throws java.io.IOException {
        DiagnosticFiles.matching(file.getParentFile(),"operations-v1[.]jsonl([.][123])?");
        // Only the four exact app-owned journal files participate in bounded runtime retention.
        File oldest=new File(file.getParentFile(),file.getName()+".3");
        if(oldest.exists()&&!oldest.delete())throw new IllegalStateException("JOURNAL_ARCHIVE_RETENTION");
        for(int index=2;index>=1;index--){File from=new File(file.getParentFile(),file.getName()+"."+index);File to=new File(file.getParentFile(),file.getName()+"."+(index+1));if(from.exists()&&!from.renameTo(to))throw new IllegalStateException("JOURNAL_ARCHIVE_ROTATE");}
        if(!file.renameTo(new File(file.getParentFile(),file.getName()+".1")))throw new IllegalStateException("JOURNAL_ROTATE");
        fileStartedAt=System.currentTimeMillis();retentionPrefs.edit().putLong("operationFileStartedAt",fileStartedAt).apply();
    }
    private void mark(String code,long count)throws Exception {
        JSONObject o=new JSONObject();ExpansionModels.put(o,"schemaVersion",1);ExpansionModels.put(o,"stage",code);ExpansionModels.put(o,"omitted",count);
        byte[] bytes=(o.toString()+"\n").getBytes(StandardCharsets.UTF_8);if(file.length()+bytes.length>MAX_FILE+256)return;
        try(FileOutputStream out=new FileOutputStream(file,true)){out.write(bytes);}
    }
    static String deviceKey(String device) {
        if(device==null||device.isEmpty())return "";
        try{return HealthEvidenceImporter.sha(device.getBytes(StandardCharsets.UTF_8)).substring(0,12);}catch(Exception e){return "unavailable";}
    }
    private static String token(String value,int max) {if(value==null)return "";String safe=value.replaceAll("[^A-Za-z0-9_.:-]","_");return safe.substring(0,Math.min(safe.length(),max));}
    private static final class LooperCheck { static boolean main(){return android.os.Looper.myLooper()==android.os.Looper.getMainLooper();} }
}
