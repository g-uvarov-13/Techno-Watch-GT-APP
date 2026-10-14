package app.gtdial.lab;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

/** Strict LOCAL metadata ledger. A storage/cap failure is an operation stop, never a dropped event. */
final class DialInstallJournal {
    static final int MAX_EVENTS=20_000,MAX_BYTES=8*1024*1024;
    private final String operationId,originalOperationId;
    private final File directory;
    private final FileChannel events;
    private final long started=SystemClock.elapsedRealtime();
    private long sequence,bytes;
    private boolean failed,finished;
    DialInstallJournal(Context context,String operationId,String artifactSha)throws IOException {this(context,operationId,artifactSha,"");}
    DialInstallJournal(Context context,String operationId,String artifactSha,String original)throws IOException {
        if(!original.isEmpty())validateOperation(original);originalOperationId=original;
        validateOperation(operationId);requireHash(artifactSha);this.operationId=operationId;
        File root=new File(context.getFilesDir(),"logs/dial-install");ensure(root);
        directory=new File(root,operationId);if(!directory.mkdir())throw new IOException("LOCAL_LOG_DIRECTORY");sync(root);
        events=FileChannel.open(new File(directory,"events.jsonl").toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        try{event("PREPARE","STARTED");select(context,artifactSha,operationId,original);}
        catch(IOException e){failed=true;try{terminal(false,"PREPARE_FAILED","LOG_FAILED");}catch(IOException close){e.addSuppressed(close);}throw e;}
    }
    synchronized void header(DynamicDialAuthorization.PreparedLocal p)throws IOException {
        try{JSONObject fields=new JSONObject().put("projectName",p.projectName).put("id",p.internalPackageId).put("alias",p.alias)
            .put("size",p.packageSize).put("packageSha256",p.packageSha256).put("profileSha256",p.profileSha256)
            .put("snapshotSha256",p.snapshotSha256).put("artifactSha256",p.artifactSha256).put("runtimeProfile",p.runtimeProfile)
            .put("mode",p.mode).put("originalOperationId",p.originalOperationId).put("deviceKey",DialImageNormalizer.hash(p.deviceAddress.getBytes(StandardCharsets.US_ASCII)));
            append(base("HEADER","VALIDATED").put("metadata",fields),4096);
        }catch(Exception e){throw io(e);}
    }
    synchronized void event(String event,String outcome)throws IOException {record(event,"LOCAL","","",-1,-1,-1,-1,0,outcome,"");}
    synchronized void hashEvent(String event,String hash,long size)throws IOException {
        try{requireHash(hash);append(base(event,"VERIFIED").put("sha256",hash).put("size",size),384);}catch(Exception e){throw io(e);}
    }
    synchronized void inventory(String stage,byte[] payload)throws IOException {
        try{
            if(!stage.matches("INITIAL|POST_INSTALL|RECHECK")||payload==null||payload.length==0||payload.length>64*1024)throw new IOException("LOCAL_LOG_INVENTORY_LIMIT");
            File file=new File(directory,"inventory-"+stage+".json");writeUnique(file,payload);sync(directory);
            if(!Arrays.equals(payload,CompiledDialArtifactStore.read(file,64*1024)))throw new IOException("LOCAL_LOG_INVENTORY_READBACK");
            hashEvent("INVENTORY_RAW_"+stage,DialImageNormalizer.hash(payload),payload.length);
            int rows=-1,users=0,systems=0,unknown=0;
            try{org.json.JSONTokener parser=new org.json.JSONTokener(new String(payload,StandardCharsets.UTF_8));Object value=parser.nextValue();
                if(value instanceof org.json.JSONArray&&parser.nextClean()==0){org.json.JSONArray array=(org.json.JSONArray)value;rows=array.length();for(int i=0;i<rows;i++){JSONObject row=array.optJSONObject(i);String group=row==null?"":row.optString("group");if("user".equals(group))users++;else if("system".equals(group))systems++;else unknown++;}}
            }catch(Exception ignored){unknown=-1;}
            append(base("INVENTORY_SUMMARY_"+stage,"CAPTURED").put("rows",rows).put("users",users).put("systems",systems).put("unknown",unknown),384);
        }catch(Exception e){throw io(e);}
    }
    synchronized void currentPair(String event,String id,String alias,String mode)throws IOException {
        try{
            if(id==null||!id.matches("[A-Za-z0-9_.]{1,96}")||alias==null||!alias.matches("[0-9]{1,32}")
                ||mode==null||!mode.matches("OWNED_ACTIVE|SYSTEM_BOLT01"))throw new IOException("LOCAL_LOG_CURRENT_PAIR");
            append(base(event,"VERIFIED").put("currentId",id).put("currentAlias",alias).put("mode",mode),384);
        }catch(Exception e){throw io(e);}
    }
    synchronized void record(String event,String step,String service,String ffi,int rpcId,int type,int crc,int offset,int count,String outcome,String error)throws IOException {
        try{JSONObject row=base(event,outcome).put("step",token(step)).put("service",token(service)).put("ffiFunction",token(ffi))
            .put("rpcId",rpcId).put("type",type).put("crc",crc).put("offset",offset).put("count",count);
            if(!error.isEmpty())row.put("error",token(error));append(row,384);
        }catch(Exception e){throw io(e);}
    }
    synchronized void sync()throws IOException {if(failed||finished)throw new IOException("LOCAL_LOG_UNAVAILABLE");try{events.force(true);sync(directory);}catch(IOException e){failed=true;throw e;}}
    synchronized boolean loggingFailed(){return failed;}
    synchronized void terminal(boolean protocolSuccess,String outcome,String error)throws IOException {
        if(finished)return;boolean priorFailure=failed;IOException failure=null;
        try{if(!failed){event("TERMINAL",outcome);events.force(true);}}catch(IOException e){priorFailure=true;failure=e;}
        try{events.close();}catch(IOException e){priorFailure=true;if(failure==null)failure=e;else failure.addSuppressed(e);}
        try{JSONObject row=base("TERMINAL",protocolSuccess&&priorFailure?"PROTOCOL_SUCCESS_LOG_FAILED":outcome)
            .put("originalOperationId",originalOperationId).put("protocolSuccess",protocolSuccess).put("loggingFailed",priorFailure).put("error",token(error))
            .put("eventCount",sequence).put("eventBytes",bytes).put("finishedAtEpochMs",System.currentTimeMillis());
            byte[] terminal=row.toString().getBytes(StandardCharsets.UTF_8);if(terminal.length>8192)throw new IOException("LOCAL_TERMINAL_LIMIT");
            writeUnique(new File(directory,"terminal.json"),terminal);sync(directory);finished=true;
        }catch(Exception e){IOException io=io(e);if(failure==null)failure=io;else failure.addSuppressed(io);}
        failed=priorFailure||failure!=null;if(failure!=null)throw failure;
    }
    private JSONObject base(String event,String outcome)throws Exception {return new JSONObject().put("operationId",operationId).put("seq",sequence+1).put("elapsedMs",Math.max(0,SystemClock.elapsedRealtime()-started)).put("event",token(event)).put("outcome",token(outcome));}
    private void append(JSONObject row,int limit)throws IOException {
        if(failed||finished)throw new IOException("LOCAL_LOG_UNAVAILABLE");byte[] encoded=(row.toString()+"\n").getBytes(StandardCharsets.UTF_8);
        if(encoded.length>limit||sequence>=MAX_EVENTS||bytes>MAX_BYTES-encoded.length){failed=true;throw new IOException("LOCAL_LOG_LIMIT");}
        try{ByteBuffer buffer=ByteBuffer.wrap(encoded);while(buffer.hasRemaining())if(events.write(buffer)<=0)throw new IOException("LOCAL_LOG_SHORT_WRITE");bytes+=encoded.length;sequence++;}
        catch(IOException e){failed=true;throw e;}
    }
    private IOException io(Exception e){failed=true;return e instanceof IOException?(IOException)e:new IOException("LOCAL_LOG_METADATA",e);}
    private static String token(String value)throws IOException {if(value==null||!value.matches("[A-Za-z0-9_.]{0,64}"))throw new IOException("LOCAL_LOG_TOKEN");return value;}
    static void validateOperation(String id)throws IOException {try{if(id==null||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new IOException("LOCAL_OPERATION_ID",e);}}
    private static void requireHash(String sha)throws IOException {if(sha==null||!sha.matches("[0-9a-f]{64}"))throw new IOException("LOCAL_ARTIFACT_SHA");}
    private static void select(Context context,String sha,String operationId,String original)throws IOException {
        File root=new File(context.getFilesDir(),"logs/dial-install");
        byte[] encoded=("{\"artifactSha256\":\""+sha+"\",\"operationId\":\""+operationId+"\",\"originalOperationId\":\""+original+"\"}").getBytes(StandardCharsets.UTF_8);
        File pending=new File(root,"selected-"+UUID.randomUUID()+".pending"),target=new File(root,"last-selected-local.json");
        writeUnique(pending,encoded);Files.move(pending.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync(root);
        if(!Arrays.equals(encoded,CompiledDialArtifactStore.read(target,1024)))throw new IOException("LOCAL_SELECTION_READBACK");
    }
    static JSONObject selected(Context context)throws Exception {
        File file=new File(context.getFilesDir(),"logs/dial-install/last-selected-local.json");if(!file.exists())return null;
        JSONObject selected=new JSONObject(new String(CompiledDialArtifactStore.read(file,1024),StandardCharsets.UTF_8));
        requireHash(selected.getString("artifactSha256"));validateOperation(selected.getString("operationId"));if(!selected.optString("originalOperationId").isEmpty())validateOperation(selected.getString("originalOperationId"));return selected;
    }
    private static void writeUnique(File file,byte[] bytes)throws IOException {try(FileChannel channel=FileChannel.open(file.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())if(channel.write(buffer)<=0)throw new IOException("LOCAL_LOG_SHORT_WRITE");channel.force(true);}}
    private static void ensure(File dir)throws IOException {if(dir.isDirectory())return;if(!dir.mkdirs())throw new IOException("LOCAL_LOG_DIRECTORY");if(dir.getParentFile()!=null)sync(dir.getParentFile());}
    private static void sync(File dir)throws IOException {try(FileChannel channel=FileChannel.open(dir.toPath(),StandardOpenOption.READ)){channel.force(true);}}
}
