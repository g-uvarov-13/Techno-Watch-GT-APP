package app.gtdial.lab;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Exact official JSON system-storage path, with compile-time gate and a terminal failure boundary. */
final class AlarmProtocol {
    static final String GET="svc_systemstorage_get",SET="svc_systemstorage_set";
    private static final byte[] GET_BODY="{\"keys\":[\"alarms\"]}".getBytes(StandardCharsets.UTF_8);
    enum Mode { READ,WRITE }
    interface Transport {int reservePacketId();boolean write(byte[] bytes);void finished(Result result);}
    static final class Entry {
        final int index,enable,hour,minute,alarmId;final String decodedName;final boolean known;private final JSONObject raw;
        Entry(JSONObject value){raw=ExpansionModels.copy(value);index=number(raw,"index",0,Integer.MAX_VALUE);enable=number(raw,"enable",0,1);hour=number(raw,"hour",0,23);minute=number(raw,"minute",0,59);alarmId=number(raw,"alarmId",0,Integer.MAX_VALUE);
            Object name=raw.opt("name");if(!(name instanceof String)||((String)name).length()>1024)throw new IllegalArgumentException("ALARM_NAME");decodedName=decodeName((String)name);
            JSONArray cycle=raw.optJSONArray("cycle");if(cycle==null||cycle.length()==0||cycle.length()>7)throw new IllegalArgumentException("ALARM_CYCLE");Set<Integer> days=new HashSet<>();for(int i=0;i<cycle.length();i++){Object day=cycle.opt(i);if(!(day instanceof Integer)&&!(day instanceof Long))throw new IllegalArgumentException("ALARM_CYCLE_INTEGER");long code=((Number)day).longValue();if(code<0||code>7||code==0&&cycle.length()!=1||!days.add((int)code))throw new IllegalArgumentException("ALARM_CYCLE");}
            known=raw.length()==7;for(String field:new String[]{"index","enable","hour","minute","cycle","name","alarmId"})if(!raw.has(field))throw new IllegalArgumentException("ALARM_FIELD_MISSING");
        }
        JSONObject wire(){return ExpansionModels.copy(raw);}
        boolean same(Entry other){
            if(other==null||!known||!other.known||index!=other.index||alarmId!=other.alarmId||enable!=other.enable||hour!=other.hour||minute!=other.minute||!raw.optString("name").equals(other.raw.optString("name")))return false;
            JSONArray a=raw.optJSONArray("cycle"),b=other.raw.optJSONArray("cycle");if(a.length()!=b.length())return false;for(int i=0;i<a.length();i++)if(a.optInt(i,-1)!=b.optInt(i,-1))return false;return true;
        }
    }
    static final class Snapshot {
        final String device,hash,rawHash;final long receivedAt;final List<Entry> entries;final boolean editable;private final byte[] raw;private final List<Entry> wireEntries;
        Snapshot(String device,byte[] raw,long receivedAt,List<Entry> values,boolean outerKnown){this.device=device;this.raw=raw.clone();this.receivedAt=receivedAt;wireEntries=Collections.unmodifiableList(new ArrayList<>(values));List<Entry> sorted=new ArrayList<>(values);sorted.sort(java.util.Comparator.comparingInt(e->e.index));entries=Collections.unmodifiableList(sorted);boolean valid=outerKnown&&values.size()<=3;JSONArray canonical=new JSONArray();for(Entry e:entries){valid&=e.known&&e.alarmId>=0&&e.alarmId<=2;JSONObject w=e.wire();JSONArray fields=new JSONArray();fields.put(e.index).put(e.alarmId).put(e.enable).put(e.hour).put(e.minute).put(w.optJSONArray("cycle")).put(w.optString("name"));canonical.put(fields);}editable=valid;hash=hash(canonical.toString().getBytes(StandardCharsets.UTF_8));rawHash=hash(raw);}
        boolean same(Snapshot other){if(other==null||!device.equals(other.device)||!editable||!other.editable||entries.size()!=other.entries.size())return false;for(int i=0;i<entries.size();i++){Entry a=entries.get(i),b=other.entries.get(i);if(a.index!=b.index||a.alarmId!=b.alarmId||a.enable!=b.enable||a.hour!=b.hour||a.minute!=b.minute||!a.wire().optString("name").equals(b.wire().optString("name")))return false;JSONArray x=a.wire().optJSONArray("cycle"),y=b.wire().optJSONArray("cycle");if(x.length()!=y.length())return false;for(int n=0;n<x.length();n++)if(x.optInt(n,-1)!=y.optInt(n,-1))return false;}return true;}
        JSONArray wireArray(){JSONArray out=new JSONArray();for(Entry e:wireEntries)out.put(e.wire());return out;}
        Entry byPair(int index,int id){for(Entry e:entries)if(e.index==index&&e.alarmId==id)return e;throw new IllegalArgumentException("ALARM_PAIR_MISSING");}
        Entry byIndex(int index){for(Entry e:entries)if(e.index==index)return e;throw new IllegalArgumentException("ALARM_INDEX_MISSING");}
        byte[] raw(){return raw.clone();}
    }
    static final class Step {
        final String stage,service;final int packetId;private final byte[] request,response,mcf;
        Step(String stage,String service,int id,byte[] request,byte[] response,byte[] mcf){this.stage=stage;this.service=service;packetId=id;this.request=request.clone();this.response=response.clone();this.mcf=mcf.clone();}
        JSONObject json(){JSONObject j=new JSONObject();ExpansionModels.put(j,"stage",stage);ExpansionModels.put(j,"service",service);ExpansionModels.put(j,"packetId",packetId);for(int i=0;i<3;i++){String key=new String[]{"request","response","mcf"}[i];byte[] bytes=new byte[][]{request,response,mcf}[i];ExpansionModels.put(j,key+"Base64",Base64.encodeToString(bytes,Base64.NO_WRAP));ExpansionModels.put(j,key+"Sha256",hash(bytes));}return j;}
    }
    static final class Result {
        final String operationId,device,state,error;final long receivedAt;final Snapshot snapshot;final AlarmTransferPlan plan;final List<Step> steps;final File backup,evidence;
        Result(String id,String device,String state,String error,Snapshot snapshot,List<Step> steps,File backup,File evidence,long at,AlarmTransferPlan plan){this.plan=plan;operationId=id;this.device=device;this.state=state;this.error=error;this.snapshot=snapshot;this.steps=Collections.unmodifiableList(new ArrayList<>(steps));this.backup=backup;this.evidence=evidence;receivedAt=at;}
        Result saved(File file){return new Result(operationId,device,state,error,snapshot,steps,backup,file,receivedAt,plan);}
        Result saveFailed(){return new Result(operationId,device,"VERIFIED".equals(state)||"STOPPED_UNCERTAIN".equals(state)?"STOPPED_UNCERTAIN":"FAILED","EVIDENCE_SAVE_FAILED",snapshot,steps,backup,null,receivedAt,plan);}
    }
    private final Context context;private final Transport transport;private final Mode mode;private final String device,operationId;private final AlarmTransferPlan plan;private final int maxFrame;
    private final Handler handler=new Handler(Looper.myLooper()==null?Looper.getMainLooper():Looper.myLooper());private final OperationJournal journal;
    private final ByteArrayOutputStream rx=new ByteArrayOutputStream();private final List<Step> steps=new ArrayList<>();
    private final Runnable timeout=()->fail("TIMEOUT");private boolean running,used,setSent;private int packetId;private String stage="LOCAL_VALIDATE",service="";private byte[] request=new byte[0];private Snapshot latest;private File backup;
    AlarmProtocol(Context context,Mode mode,String device,int maxFrame,AlarmTransferPlan plan,Transport transport){if(device==null||!device.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")||maxFrame<23)throw new IllegalArgumentException("ALARM_PREFLIGHT");if(mode==Mode.WRITE&&(plan==null||!device.equals(plan.device)))throw new IllegalArgumentException("ALARM_PLAN_DEVICE");this.context=context.getApplicationContext();this.mode=mode;this.device=device;this.maxFrame=maxFrame;this.plan=plan;this.transport=transport;journal=OperationJournal.get(context);operationId=journal.begin("alarm_transport",mode.name(),mode.name(),device);}
    boolean isRunning(){return running;}
    void start(){
        if(used)return;used=true;running=true;
        if(mode==Mode.READ&&!DeviceFeatureGate.alarmReadAllowed()||mode==Mode.WRITE&&!DeviceFeatureGate.alarmWriteAllowed(plan.action)){fail("COMPILE_GATE");return;}
        if(mode==Mode.READ){send(GET,GET_BODY,"READ");return;}
        if(plan.wireSize()>maxFrame){fail("SET_EXCEEDS_MTU");return;}
        stage="PENDING_DRAFT";
        // Durable intent precedes the first GET. Process death leaves a conservative stop marker.
        ExpansionStore.get(context).submit(()->{ExpansionStore.get(context).beginAlarmTransfer(operationId,plan);return true;},
                (saved,error)->handler.post(()->{if(!running||!"PENDING_DRAFT".equals(stage))return;if(!error.isEmpty()){fail("PENDING_DRAFT_REJECTED");return;}send(GET,GET_BODY,"FRESH_GET");}));
    }
    private void send(String service,byte[] body,String stage){if(!running)return;this.service=service;this.stage=stage;request=body.clone();rx.reset();packetId=transport.reservePacketId();if(packetId<0||packetId>255){fail("PACKET_ID");return;}byte[] frame=frame(service,body,packetId);if(frame.length>maxFrame){fail("REQUEST_EXCEEDS_MTU");return;}if(SET.equals(service))setSent=true;journal.stage(operationId,"alarm_transport",mode.name(),stage,"PENDING","",0,frame.length,0);if(!transport.write(frame)){fail("TX_REJECTED");return;}if(running)handler.postDelayed(timeout,3000);}
    void onChunk(byte[] chunk){if(!running)return;if("BACKUP".equals(stage)||"PENDING_DRAFT".equals(stage)){fail("UNEXPECTED_RX_DURING_STORAGE");return;}if(chunk==null||chunk.length==0||rx.size()+chunk.length>32768+13){fail("RX_SIZE");return;}rx.write(chunk,0,chunk.length);byte[] bytes=rx.toByteArray();if((bytes[0]&255)!=0xfc){fail("MCF_PREFIX");return;}if(bytes.length<3)return;int size=(bytes[1]&255)<<8|(bytes[2]&255);if(size<13||size>32768+13){fail("MCF_SIZE");return;}if(bytes.length<size)return;
        try{validateMcf(bytes,packetId);handler.removeCallbacks(timeout);byte[] payload=Arrays.copyOfRange(bytes,10,bytes.length-3);if(payload.length==0)throw new IllegalArgumentException("EMPTY_RESPONSE");steps.add(new Step(stage,service,packetId,request,payload,bytes));
            if("SET".equals(stage)){send(GET,GET_BODY,"READBACK");return;}
            Snapshot snapshot=parse(device,payload,System.currentTimeMillis());latest=snapshot;
            if(mode==Mode.READ){finish("COMPLETE","");return;}
            if("FRESH_GET".equals(stage)){if(!snapshot.editable||!plan.baseHash.equals(snapshot.hash)||!plan.original.same(snapshot)){fail("PREVIEW_SNAPSHOT_CHANGED");return;}stage="BACKUP";journal.stage(operationId,"alarm_transport",mode.name(),stage,"PENDING","",snapshot.entries.size(),payload.length,0);ExpansionStore.get(context).submit(()->saveBackup(snapshot),(file,error)->handler.post(()->{if(!running||!"BACKUP".equals(stage))return;if(!error.isEmpty()){fail("BACKUP_SAVE_FAILED");return;}backup=file;send(SET,plan.body(),"SET");}));return;}
            if("READBACK".equals(stage)){if(!snapshot.editable||!plan.expectedHash.equals(snapshot.hash)||!plan.proposed.same(snapshot)){fail("READBACK_MISMATCH");return;}finish("VERIFIED","");return;}
            fail("UNEXPECTED_STATE");
        }catch(RuntimeException e){fail(e instanceof IllegalArgumentException?"RESPONSE_INVALID":"RESPONSE_STATE");}}
    void cancel(String reason){if(running)fail("DISCONNECTED");}
    private void fail(String code){if(running)finish(setSent?"STOPPED_UNCERTAIN":"FAILED",code);}
    private void finish(String state,String error){if(!running)return;running=false;handler.removeCallbacks(timeout);journal.stage(operationId,"alarm_transport",mode.name(),state,"FAILED".equals(state)||"STOPPED_UNCERTAIN".equals(state)?"FAILED":"SUCCESS",error,latest==null?0:latest.entries.size(),0,0);transport.finished(new Result(operationId,device,state,error,latest,steps,backup,null,System.currentTimeMillis(),plan));}
    private File saveBackup(Snapshot snapshot)throws Exception{
        JSONObject j=new JSONObject();ExpansionModels.put(j,"schema",1);ExpansionModels.put(j,"operationId",operationId);
        ExpansionModels.put(j,"deviceAddress",device);ExpansionModels.put(j,"receivedAt",snapshot.receivedAt);ExpansionModels.put(j,"targetKey","alarms");
        ExpansionModels.put(j,"structuralSha256",snapshot.hash);ExpansionModels.put(j,"rawSha256",snapshot.rawHash);
        ExpansionModels.put(j,"rawBase64",Base64.encodeToString(snapshot.raw(),Base64.NO_WRAP));ExpansionModels.put(j,"alarms",snapshot.wireArray());
        ExpansionModels.put(j,"freshRead",steps.get(steps.size()-1).json());ExpansionModels.put(j,"expectedProposedSha256",plan.expectedHash);
        ExpansionModels.put(j,"action",plan.action.name());ExpansionModels.put(j,"draftId",plan.draftId);ExpansionModels.put(j,"draftHash",plan.draftHash);
        ExpansionModels.put(j,"index",plan.index);ExpansionModels.put(j,"alarmId",plan.alarmId);
        File dir=new File(context.getFilesDir(),"backups/alarms");if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("ALARM_BACKUP_DIRECTORY");
        File file=new File(dir,"alarm-backup-"+operationId+".json");atomic(file,j.toString().getBytes(StandardCharsets.UTF_8));
        ExpansionStore.get(context).alarmBackupReady(operationId,plan,file);return file;
    }
    static final class Backup {
        final Snapshot original;final String operationId,expectedHash,draftId,draftHash;final int index,alarmId;
        Backup(Snapshot original,JSONObject proof){this.original=original;operationId=proof.optString("operationId");expectedHash=proof.optString("expectedProposedSha256");draftId=proof.optString("draftId");draftHash=proof.optString("draftHash");index=proof.optInt("index",-1);alarmId=proof.optInt("alarmId",-1);}
    }
    static Backup ownBackup(Context context,String device,File file)throws Exception{
        if(file==null)throw new IllegalArgumentException("ALARM_OWN_BACKUP_REQUIRED");
        File root=context.getFilesDir().getCanonicalFile(),backups=new File(root,"backups"),dir=new File(backups,"alarms");
        if(!backups.getCanonicalFile().getParentFile().equals(root)||!dir.getCanonicalFile().getParentFile().equals(backups.getCanonicalFile())||
                !file.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile())||!file.isFile()||!file.getName().matches("alarm-backup-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.json"))throw new IllegalArgumentException("ALARM_OWN_BACKUP_PATH");
        JSONObject j;try(InputStream input=new AtomicFile(file).openRead()){j=new JSONObject(utf8(HealthEvidenceImporter.read(input)));}
        String operation=j.getString("operationId");ExpansionModels.requireId(operation);ExpansionModels.requireId(j.getString("draftId"));
        if(!file.getName().equals("alarm-backup-"+operation+".json")||j.getInt("schema")!=1||!device.equals(j.getString("deviceAddress"))||!"alarms".equals(j.getString("targetKey")))throw new IllegalArgumentException("ALARM_OWN_BACKUP_IDENTITY");
        AlarmTransferPlan.Action.valueOf(j.getString("action"));
        if(!j.getString("expectedProposedSha256").matches("[0-9a-f]{64}")||!j.getString("draftHash").matches("[0-9a-f]{64}")||j.getInt("index")<0||j.getInt("alarmId")<0||j.getInt("alarmId")>2)throw new IllegalArgumentException("ALARM_OWN_BACKUP_PLAN");
        byte[] raw=decoded(j,"raw");JSONObject proof=j.getJSONObject("freshRead");
        byte[] request=decoded(proof,"request"),response=decoded(proof,"response"),mcf=decoded(proof,"mcf");
        if(!GET.equals(proof.getString("service"))||!"FRESH_GET".equals(proof.getString("stage"))||!Arrays.equals(request,GET_BODY))throw new IllegalArgumentException("ALARM_OWN_BACKUP_GET");
        validateMcf(mcf,proof.getInt("packetId"));if(!Arrays.equals(raw,response)||!Arrays.equals(response,Arrays.copyOfRange(mcf,10,mcf.length-3)))throw new IllegalArgumentException("ALARM_OWN_BACKUP_RAW");
        Snapshot original=parse(device,raw,j.getLong("receivedAt"));
        if(!original.editable||!original.hash.equals(j.getString("structuralSha256"))||!original.wireArray().toString().equals(j.getJSONArray("alarms").toString()))throw new IllegalArgumentException("ALARM_OWN_BACKUP_SNAPSHOT");
        Backup backup=new Backup(original,j);ExpansionStore.get(context).requireOwnAlarmBackup(backup,file);return backup;
    }
    static Snapshot parse(String device,byte[] bytes,long receivedAt){if(bytes.length==0||bytes.length>32768||receivedAt<=0)throw new IllegalArgumentException("ALARM_JSON_SIZE");try{org.json.JSONTokener tokener=new org.json.JSONTokener(utf8(bytes));Object parsed=tokener.nextValue();if(!(parsed instanceof JSONObject)||tokener.nextClean()!=0)throw new IllegalArgumentException("ALARM_JSON_TRAILING");JSONObject object=(JSONObject)parsed;JSONArray list=object.optJSONArray("alarms");if(list==null||list.length()>64)throw new IllegalArgumentException("ALARM_ARRAY");List<Entry> entries=new ArrayList<>();Set<Integer> indices=new HashSet<>(),ids=new HashSet<>();for(int i=0;i<list.length();i++){Entry e=new Entry(list.getJSONObject(i));if(!indices.add(e.index)||!ids.add(e.alarmId))throw new IllegalArgumentException("ALARM_DUPLICATE");entries.add(e);}return new Snapshot(device,bytes,receivedAt,entries,object.length()==1);}catch(org.json.JSONException e){throw new IllegalArgumentException("ALARM_JSON",e);}}
    static File saveEvidence(Context context,Result result)throws Exception{
        File dir=new File(context.getFilesDir(),"health-evidence");if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("ALARM_EVIDENCE_DIRECTORY");JSONArray references=new JSONArray();
        for(int i=0;i<result.steps.size();i++){Step step=result.steps.get(i);JSONObject proof=step.json();ExpansionModels.put(proof,"schema",1);ExpansionModels.put(proof,"operationId",result.operationId);ExpansionModels.put(proof,"deviceAddress",result.device);ExpansionModels.put(proof,"receivedAt",result.receivedAt);File file=new File(dir,"alarm-step-"+result.operationId+"-"+i+".json");atomic(file,proof.toString().getBytes(StandardCharsets.UTF_8));JSONObject ref=new JSONObject();ExpansionModels.put(ref,"file",file.getName());ExpansionModels.put(ref,"stage",step.stage);ExpansionModels.put(ref,"responseSha256",hash(step.response));references.put(ref);}
        JSONObject summary=new JSONObject();ExpansionModels.put(summary,"schema",1);ExpansionModels.put(summary,"operationId",result.operationId);ExpansionModels.put(summary,"deviceAddress",result.device);ExpansionModels.put(summary,"receivedAt",result.receivedAt);ExpansionModels.put(summary,"state",result.state);ExpansionModels.put(summary,"errorCode",result.error);ExpansionModels.put(summary,"targetKey","alarms");ExpansionModels.put(summary,"steps",references);if(result.plan!=null){ExpansionModels.put(summary,"action",result.plan.action.name());ExpansionModels.put(summary,"draftId",result.plan.draftId);ExpansionModels.put(summary,"index",result.plan.index);ExpansionModels.put(summary,"alarmId",result.plan.alarmId);ExpansionModels.put(summary,"baseSha256",result.plan.baseHash);ExpansionModels.put(summary,"expectedSha256",result.plan.expectedHash);}if(result.backup!=null)ExpansionModels.put(summary,"backupFile",result.backup.getName());
        if(result.snapshot!=null&&("COMPLETE".equals(result.state)||"VERIFIED".equals(result.state))){Step step=result.steps.get(result.steps.size()-1);ExpansionModels.put(summary,"snapshotProof",step.json());ExpansionModels.put(summary,"structuralSha256",result.snapshot.hash);}
        File output=new File(dir,"alarm-operation-"+result.operationId+".json");atomic(output,summary.toString().getBytes(StandardCharsets.UTF_8));if(summary.has("snapshotProof"))atomic(new File(dir,"alarm-snapshot-"+OperationJournal.deviceKey(result.device)+".json"),summary.toString().getBytes(StandardCharsets.UTF_8));return output;
    }
    static Snapshot savedSnapshot(Context context,String device){File file=new File(new File(context.getFilesDir(),"health-evidence"),"alarm-snapshot-"+OperationJournal.deviceKey(device)+".json");if(!file.isFile())return null;try(InputStream input=new java.io.FileInputStream(file)){JSONObject summary=new JSONObject(utf8(HealthEvidenceImporter.read(input)));if(!device.equals(summary.getString("deviceAddress"))||summary.getInt("schema")!=1||!("COMPLETE".equals(summary.optString("state"))||"VERIFIED".equals(summary.optString("state"))))return null;JSONObject proof=summary.getJSONObject("snapshotProof");if(!GET.equals(proof.getString("service")))return null;byte[] request=decoded(proof,"request"),response=decoded(proof,"response"),mcf=decoded(proof,"mcf");if(!Arrays.equals(request,GET_BODY))return null;validateMcf(mcf,proof.getInt("packetId"));if(!Arrays.equals(response,Arrays.copyOfRange(mcf,10,mcf.length-3)))return null;Snapshot snapshot=parse(device,response,summary.getLong("receivedAt"));return snapshot.hash.equals(summary.getString("structuralSha256"))?snapshot:null;}catch(Exception e){String op=OperationJournal.get(context).begin("alarm_evidence","restore","ALARM",device);OperationJournal.get(context).stage(op,"alarm_evidence","restore","FAILED","FAILED","SNAPSHOT_INVALID",0,0,0);return null;}}
    static void atomic(File file,byte[] bytes)throws Exception{if(bytes.length>128*1024)throw new IllegalStateException("ALARM_EVIDENCE_SIZE");AtomicFile atomic=new AtomicFile(file);FileOutputStream stream=null;try{stream=atomic.startWrite();stream.write(bytes);atomic.finishWrite(stream);}catch(Exception e){if(stream!=null)atomic.failWrite(stream);throw e;}try(InputStream in=atomic.openRead()){if(!Arrays.equals(bytes,HealthEvidenceImporter.read(in)))throw new IllegalStateException("ALARM_LOCAL_READBACK");}}
    private static byte[] decoded(JSONObject proof,String key)throws Exception{String encoded=proof.getString(key+"Base64");if(encoded.length()>45000)throw new IllegalArgumentException("ALARM_BASE64_SIZE");byte[] bytes=Base64.decode(encoded,Base64.NO_WRAP);if(bytes.length>32768+13||!hash(bytes).equals(proof.getString(key+"Sha256")))throw new IllegalArgumentException("ALARM_EVIDENCE_HASH");return bytes;}
    static void validateMcf(byte[] f,int id){int n=f.length;if(n<13||n>32768+13||(f[0]&255)!=0xfc||((f[1]&255)<<8|(f[2]&255))!=n||(f[n-1]&255)!=0xcf||f[4]!=0||f[5]!=1||f[6]!=1||f[7]!=0||(f[8]&255)!=id||(f[9]&255)!=0x40||crc(f,1,n-4)!=((f[n-3]&255)<<8|(f[n-2]&255)))throw new IllegalArgumentException("ALARM_MCF");}
    private static byte[] frame(String service,byte[] body,int id){if(!GET.equals(service)&&!SET.equals(service))throw new IllegalArgumentException("ALARM_SERVICE_ALLOWLIST");byte[] name=service.getBytes(StandardCharsets.UTF_8);int n=13+name.length+2+body.length;byte[] f=new byte[n];f[0]=(byte)0xfc;f[1]=(byte)(n>>>8);f[2]=(byte)n;f[3]=1;f[5]=1;f[7]=1;f[8]=(byte)id;f[9]=0x10;System.arraycopy(name,0,f,10,name.length);f[10+name.length+1]=1;System.arraycopy(body,0,f,12+name.length,body.length);int crc=crc(f,1,n-4);f[n-3]=(byte)(crc>>>8);f[n-2]=(byte)crc;f[n-1]=(byte)0xcf;return f;}
    private static int crc(byte[] b,int offset,int length){int crc=65535;for(int i=offset;i<offset+length;i++){crc^=b[i]&255;for(int j=0;j<8;j++)crc=(crc&1)!=0?(crc>>>1)^0xa001:crc>>>1;}return crc&65535;}
    private static int number(JSONObject object,String key,long min,long max){Object value=object.opt(key);if(!(value instanceof Integer)&&!(value instanceof Long))throw new IllegalArgumentException("ALARM_INTEGER");long n=((Number)value).longValue();if(n<min||n>max)throw new IllegalArgumentException("ALARM_INTEGER_BOUNDS");return (int)n;}
    private static String decodeName(String raw){ByteArrayOutputStream bytes=new ByteArrayOutputStream();for(int i=0;i<raw.length();i++){char c=raw.charAt(i);if(c=='%'){if(i+2>=raw.length())throw new IllegalArgumentException("ALARM_URL_ENCODING");int a=Character.digit(raw.charAt(++i),16),b=Character.digit(raw.charAt(++i),16);if(a<0||b<0)throw new IllegalArgumentException("ALARM_URL_ENCODING");bytes.write((a<<4)|b);}else if(c=='+')bytes.write(' ');else{if(c>127)throw new IllegalArgumentException("ALARM_URL_ASCII");bytes.write(c);}}return utf8(bytes.toByteArray());}
    private static String utf8(byte[] bytes){try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException e){throw new IllegalArgumentException("ALARM_UTF8",e);}}
    private static String hash(byte[] bytes){try{return HealthEvidenceImporter.sha(bytes);}catch(Exception e){throw new IllegalStateException("ALARM_SHA",e);}}
}
