package app.gtdial.lab;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;

/** Durable per-device ownership receipts written only after exact installed/current verification. */
final class DynamicDialOwnershipStore {
    static final class Receipt {
        final String deviceAddress,path,faceId,paletteId,packageSha256,internalPackageId,alias;
        final long packageSize,verifiedAtEpochMs;
        final BundledDynamicDial.PackageVariant variant;
        final DynamicDialPackage descriptor;
        final int schemaVersion;
        final String origin,profileSha256,snapshotSha256,artifactSha256;
        Receipt(String deviceAddress,String path,BundledDynamicDial.PackageVariant variant)throws IOException{this(deviceAddress,path,variant,System.currentTimeMillis());}
        Receipt(String deviceAddress,String path,BundledDynamicDial.PackageVariant variant,long verifiedAtEpochMs)throws IOException{
            this.deviceAddress=normalizeAddress(deviceAddress);this.path=normalizePath(path);
            if(variant==null||!BundledDynamicDialRepository.isCompiledVariant(variant.internalPackageId,variant.alias,variant.packageSize,variant.packageSha256))throw new IOException("Receipt package is not in the compiled registry");
            if(verifiedAtEpochMs<=0)throw new IOException("Receipt verification time invalid");
            this.variant=variant;this.faceId=variant.faceId;this.paletteId=variant.paletteId;this.packageSha256=variant.packageSha256;this.packageSize=variant.packageSize;this.internalPackageId=variant.internalPackageId;this.alias=variant.alias;this.verifiedAtEpochMs=verifiedAtEpochMs;
            this.descriptor=DynamicDialPackage.bundled(variant);schemaVersion=1;origin=descriptor.origin.name();profileSha256=null;snapshotSha256=null;artifactSha256=null;
        }
        Receipt(String address,String path,DynamicDialPackage descriptor)throws IOException{this(address,path,descriptor,System.currentTimeMillis());}
        private Receipt(String address,String path,DynamicDialPackage descriptor,long verifiedAt)throws IOException {
            if(descriptor==null||descriptor.origin!=DynamicDialPackage.Origin.LOCAL_PROFILE_BUILD||verifiedAt<=0)throw new IOException("Invalid local ownership descriptor");
            deviceAddress=normalizeAddress(address);this.path=normalizePath(path);this.descriptor=descriptor;variant=null;schemaVersion=2;
            faceId=descriptor.projectId;paletteId=descriptor.runtimeProfile;packageSha256=descriptor.packageSha256;packageSize=descriptor.packageSize;
            internalPackageId=descriptor.internalPackageId;alias=descriptor.alias;verifiedAtEpochMs=verifiedAt;origin=descriptor.origin.name();
            profileSha256=descriptor.profileSha256;snapshotSha256=descriptor.snapshotSha256;artifactSha256=descriptor.artifactSha256;
        }
        boolean sameOwnership(Receipt other){return other!=null&&deviceAddress.equals(other.deviceAddress)&&path.equals(other.path)&&faceId.equals(other.faceId)&&paletteId.equals(other.paletteId)&&packageSha256.equals(other.packageSha256)&&packageSize==other.packageSize&&internalPackageId.equals(other.internalPackageId)&&alias.equals(other.alias)&&origin.equals(other.origin)&&java.util.Objects.equals(profileSha256,other.profileSha256)&&java.util.Objects.equals(snapshotSha256,other.snapshotSha256)&&java.util.Objects.equals(artifactSha256,other.artifactSha256);}
    }

    private final File root;
    private final CompiledDialArtifactStore artifacts;
    private static final Object LOCK=new Object();
    DynamicDialOwnershipStore(File filesDirectory){root=new File(filesDirectory,"dynamic-dial-ownership");artifacts=null;}
    DynamicDialOwnershipStore(Context context){root=new File(context.getFilesDir(),"dynamic-dial-ownership");artifacts=new CompiledDialArtifactStore(context);}

    synchronized Receipt load(String address,String path)throws IOException{
        synchronized(LOCK){assertNoPending();return readReceipt(address,path);}
    }
    private Receipt readReceipt(String address,String path)throws IOException{
        String normalizedAddress=normalizeAddress(address),normalizedPath=normalizePath(path);File file=receiptFile(normalizedAddress,normalizedPath);if(!file.exists())return null;
        byte[] bytes=readBounded(file,4096);return decodeReceipt(bytes,normalizedAddress,normalizedPath);
    }
    private Receipt decodeReceipt(byte[] bytes,String normalizedAddress,String normalizedPath)throws IOException {
        try{
            JSONObject json=new JSONObject(new String(bytes,StandardCharsets.UTF_8));int schema=json.getInt("schemaVersion");if(schema!=1&&schema!=2)throw new IOException("Unsupported ownership receipt version");
            String faceId=json.getString("faceId"),paletteId=json.getString("paletteId");
            String id=json.getString("internalPackageId"),alias=json.getString("alias"),hash=json.getString("packageSha256");
            long size=json.getLong("packageSize");
            Receipt receipt;
            if(schema==1){BundledDynamicDial.PackageVariant variant=BundledDynamicDialRepository.compiledVariant(faceId,paletteId);
                if(!variant.internalPackageId.equals(id)||!variant.alias.equals(alias)||variant.packageSize!=size||!variant.packageSha256.equals(hash))
                    variant=BundledDynamicDialRepository.historicalVariant(faceId,paletteId,id,alias,size,hash);
                receipt=new Receipt(json.getString("deviceAddress"),json.getString("path"),variant,json.getLong("verifiedAtEpochMs"));
            }else{
                if(artifacts==null||!DynamicDialPackage.Origin.LOCAL_PROFILE_BUILD.name().equals(json.getString("origin")))throw new IOException("Local receipt requires trusted artifact validation");
                DynamicDialPackage descriptor=DynamicDialAuthorization.verifiedLocal(artifacts,json.getString("artifactSha256"));
                receipt=new Receipt(json.getString("deviceAddress"),json.getString("path"),descriptor,json.getLong("verifiedAtEpochMs"));
                if(!matches(bytes,receipt))throw new IOException("Local ownership artifact/profile/snapshot mismatch");
            }
            if(!normalizedAddress.equals(receipt.deviceAddress)||!normalizedPath.equals(receipt.path)||!receipt.packageSha256.equals(json.getString("packageSha256"))||receipt.packageSize!=json.getLong("packageSize")||!receipt.internalPackageId.equals(json.getString("internalPackageId"))||!receipt.alias.equals(json.getString("alias")))throw new IOException("Ownership receipt tuple mismatch");
            return receipt;
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Malformed ownership receipt",e);}
    }

    synchronized boolean saveAfterVerifiedCurrent(Receipt expectedPrior,Receipt next)throws IOException{
        synchronized(LOCK){assertNoPending();return saveReadOnlyReceipt(expectedPrior,next);}
    }
    private boolean saveReadOnlyReceipt(Receipt expectedPrior,Receipt next)throws IOException{
        if(next==null||!BundledDynamicDial.REMOTE_PATH.equals(next.path))throw new IOException("Ownership receipt path mismatch");
        Receipt current=load(next.deviceAddress,next.path);
        if(expectedPrior==null?current!=null:!expectedPrior.sameOwnership(current))return false;
        File target=receiptFile(next.deviceAddress,next.path);if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Unable to create ownership directory");File pending=pendingFile(next.deviceAddress,next.path);if(pending.exists())throw new IOException("An uncertain ownership update already blocks staging");writeSynced(pending,Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII));syncDirectory(root);
        byte[] encoded=encode(next);File temp=File.createTempFile("receipt-",".tmp",root);
        try{
            writeSynced(temp,encoded);if(!matches(readBounded(temp,4096),next))return false;
            Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);syncDirectory(root);
            if(matches(readBounded(target,4096),next)){if(!pending.delete())throw new IOException("Unable to clear ownership block");syncDirectory(root);return true;}
            throw new IOException("New ownership receipt reread mismatch; staging remains blocked");
        }catch(IOException|RuntimeException e){
            if(!temp.delete()&&temp.exists())throw new IOException("Receipt temporary file could not be removed",e);
            try{if(!pending.exists())writeSynced(pending,Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII));syncDirectory(root);}catch(IOException blockFailure){e.addSuppressed(blockFailure);}
            if(e instanceof IOException)throw(IOException)e;throw new IOException("Ownership receipt save failed",e);
        }finally{if(temp.exists())temp.delete();}
    }

    /** Fail closed even for legacy numeric markers and for another device's uncertain mutation. */
    private void assertNoPending()throws IOException {
        if(!root.exists())return;File[] pending=root.listFiles((d,n)->n.endsWith(".pending"));
        if(pending==null)throw new IOException("Cannot inspect dynamic mutation state");
        if(pending.length!=0)throw new IOException("An incomplete dynamic mutation blocks all dynamic installs");
    }
    void requireNoPendingForLocal()throws IOException {synchronized(LOCK){assertNoPending();}}
    synchronized boolean armMutation(String operationId,String address,String path,Receipt prior,DynamicDialPackage target,String backupHash,long backupSize)throws IOException {
        if(!DynamicDialAuthorization.physicallyAllowed(target))return false;
        return armMutation(operationId,address,path,prior,target,null,backupHash,backupSize);
    }
    synchronized boolean armLocalMutation(String operationId,String address,String path,Receipt prior,DynamicDialAuthorization.LocalExecution execution,String backupHash,long backupSize)throws IOException {
        address=normalizeAddress(address);path=normalizePath(path);
        if(execution==null||!execution.boundForMutation(operationId,address,path))return false;
        return armMutation(operationId,address,path,prior,execution.target,execution,backupHash,backupSize);
    }
    private boolean armMutation(String operationId,String address,String path,Receipt prior,DynamicDialPackage target,DynamicDialAuthorization.LocalExecution execution,String backupHash,long backupSize)throws IOException {
        synchronized(LOCK){
            validateOperationId(operationId);address=normalizeAddress(address);path=normalizePath(path);
            Receipt current=readReceipt(address,path);if(!fingerprint(prior).equals(fingerprint(current)))return false;
            if(target==null||target.origin==DynamicDialPackage.Origin.BUNDLED_PINNED&&!DynamicDialAuthorization.physicallyAllowed(target)||backupHash==null||!backupHash.matches("[0-9a-f]{64}")||backupSize<=0||backupSize>32L*1024*1024)return false;
            if(prior!=null&&(!address.equals(prior.deviceAddress)||!path.equals(prior.path)))return false;
            if(target.origin==DynamicDialPackage.Origin.LOCAL_PROFILE_BUILD){
                if(prior==null||artifacts==null||execution==null||!execution.boundForMutation(operationId,address,path)||execution.target!=target||backupSize!=prior.packageSize||!backupHash.equals(prior.packageSha256))return false;
                try{if(!DynamicDialAuthorization.executionArtifactMatches(execution,artifacts.load(target.artifactSha256)))return false;}
                catch(Exception|OutOfMemoryError e){throw new IOException("Local mutation artifact authorization failed",e);}
            }
            if(target.origin==DynamicDialPackage.Origin.LOCAL_PROFILE_BUILD)retainPrior(prior);
            ensureRoot();byte[] bytes;
            try{bytes=new JSONObject().put("schemaVersion",1).put("operationId",operationId).put("deviceAddress",address).put("path",path)
                .put("priorFingerprint",fingerprint(prior)).put("target",targetJson(target)).put("backupHash",backupHash)
                .put("backupSize",backupSize).put("state","ARMED").toString().getBytes(StandardCharsets.UTF_8);}
            catch(JSONException e){throw new IOException("Mutation marker serialization",e);}
            File pending=pendingFile(address,path);
            if(pending.exists()){
                File[] all=root.listFiles((d,n)->n.endsWith(".pending"));
                if(all==null||all.length!=1||!all[0].equals(pending)||!java.util.Arrays.equals(bytes,readBounded(pending,4096)))throw new IOException("Another or unknown mutation blocks all dynamic installs");
                syncDirectory(root);return java.util.Arrays.equals(bytes,readBounded(pending,4096));
            }
            assertNoPending();writeSynced(pending,bytes);syncDirectory(root);
            if(!java.util.Arrays.equals(bytes,readBounded(pending,4096)))throw new IOException("Mutation marker reread mismatch");
            return true;
        }
    }
    synchronized boolean saveAfterVerifiedMutation(String operationId,String exactCurrent,Receipt expectedPrior,Receipt next)throws IOException {
        synchronized(LOCK){
            validateOperationId(operationId);if(next==null||!AlbumStageProtocol.selectedCurrentIdentifierMatches(exactCurrent,next.internalPackageId,next.alias))return false;
            File pending=pendingFile(next.deviceAddress,next.path);File[] all=root.listFiles((d,n)->n.endsWith(".pending"));
            if(all==null||all.length!=1||!all[0].equals(pending)||!pending.isFile())return false;
            JSONObject marker;
            try{marker=new JSONObject(new String(readBounded(pending,4096),StandardCharsets.UTF_8));
                if(marker.getInt("schemaVersion")!=1||!"ARMED".equals(marker.getString("state"))||!operationId.equals(marker.getString("operationId"))
                    ||!next.deviceAddress.equals(marker.getString("deviceAddress"))||!next.path.equals(marker.getString("path"))
                    ||!fingerprint(expectedPrior).equals(marker.getString("priorFingerprint"))||!targetMatches(marker.getJSONObject("target"),next))return false;
            }catch(JSONException e){throw new IOException("Unknown mutation marker; all dynamic installs remain blocked",e);}
            Receipt current=readReceipt(next.deviceAddress,next.path);
            if(!fingerprint(current).equals(fingerprint(expectedPrior)))return false;
            if(next.schemaVersion==2){if(artifacts==null)return false;try{
                DynamicDialPackage fresh=DynamicDialAuthorization.verifiedLocal(artifacts,next.artifactSha256);
                if(!targetMatches(targetJson(fresh),next))return false;
            }catch(Exception e){throw new IOException("New receipt artifact verification failed",e);}}
            if(next.schemaVersion==2)retainPrior(expectedPrior);
            byte[] encoded=encode(next);File temporary=File.createTempFile("receipt-",".tmp",root);
            writeSynced(temporary,encoded);if(!matches(readBounded(temporary,4096),next))return false;
            Files.move(temporary.toPath(),receiptFile(next.deviceAddress,next.path).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);syncDirectory(root);
            Receipt reread=readReceipt(next.deviceAddress,next.path);
            if(!fingerprint(next).equals(fingerprint(reread))||!matches(readBounded(receiptFile(next.deviceAddress,next.path),4096),next))return false;
            // Only the operation that verified exact current and committed this receipt can consume its marker.
            JSONObject finalMarker;
            try{finalMarker=new JSONObject(new String(readBounded(pending,4096),StandardCharsets.UTF_8));
                if(!operationId.equals(finalMarker.getString("operationId"))||!marker.toString().equals(finalMarker.toString()))return false;
            }catch(JSONException e){throw new IOException("Mutation marker changed before completion",e);}
            byte[] markerBytes=readBounded(pending,4096);
            if(!pending.delete())throw new IOException("Cannot consume successful mutation marker");
            try{syncDirectory(root);}catch(IOException failure){
                // A failed directory sync is terminal. Restore the live barrier as far as storage permits.
                try{writeSynced(pending,markerBytes);syncDirectory(root);}catch(IOException restore){failure.addSuppressed(restore);}throw failure;
            }
            return true;
        }
    }
    /** Closed, read-only context. It conveys no mutation-arm capability. */
    static final class RecoveryContext {
        final String originalOperationId,address,path;
        final Receipt prior;
        final DynamicDialPackage target;
        private final byte[] marker;
        private RecoveryContext(String id,String address,String path,Receipt prior,DynamicDialPackage target,byte[] marker){
            originalOperationId=id;this.address=address;this.path=path;this.prior=prior;this.target=target;this.marker=marker.clone();
        }
        boolean same(RecoveryContext other){return other!=null&&originalOperationId.equals(other.originalOperationId)&&address.equals(other.address)&&path.equals(other.path)&&java.util.Arrays.equals(marker,other.marker);}
    }
    boolean pendingMutationPresent()throws IOException {synchronized(LOCK){if(!root.exists())return false;
        try(java.nio.file.DirectoryStream<java.nio.file.Path> files=Files.newDirectoryStream(root.toPath(),"*.pending")){return files.iterator().hasNext();}
    }}
    /** Presence only, bounded and cheap; full trust is established on the preparation worker. */
    boolean hasPendingLocalRecovery(String address){synchronized(LOCK){try{
        address=normalizeAddress(address);File[] files=root.listFiles((d,n)->n.endsWith(".pending"));
        if(files==null||files.length!=1||!files[0].equals(pendingFile(address,BundledDynamicDial.REMOTE_PATH)))return false;
        JSONObject marker=new JSONObject(new String(readBounded(files[0],4096),StandardCharsets.UTF_8));
        return marker.getInt("schemaVersion")==1&&"ARMED".equals(marker.getString("state"))&&address.equals(marker.getString("deviceAddress"))
            &&BundledDynamicDial.REMOTE_PATH.equals(marker.getString("path"))&&"LOCAL_PROFILE_BUILD".equals(marker.getJSONObject("target").getString("origin"));
    }catch(Exception e){return false;}}}
    RecoveryContext recoveryContext(String address)throws IOException {synchronized(LOCK){try{
        address=normalizeAddress(address);String path=BundledDynamicDial.REMOTE_PATH;File pending=pendingFile(address,path);
        File[] files=root.listFiles((d,n)->n.endsWith(".pending"));if(files==null||files.length!=1||!files[0].equals(pending))throw new IOException("RECOVERY_PENDING_NOT_SOLE");
        byte[] bytes=readBounded(pending,4096);org.json.JSONTokener parser=new org.json.JSONTokener(new String(bytes,StandardCharsets.UTF_8));Object parsed=parser.nextValue();if(!(parsed instanceof JSONObject)||parser.nextClean()!=0)throw new IOException("RECOVERY_PENDING_INVALID");JSONObject marker=(JSONObject)parsed;
        if(marker.length()!=9||marker.getJSONObject("target").length()!=9)throw new IOException("RECOVERY_PENDING_INVALID");
        String id=marker.getString("operationId");validateOperationId(id);
        if(marker.getInt("schemaVersion")!=1||!"ARMED".equals(marker.getString("state"))||!address.equals(marker.getString("deviceAddress"))||!path.equals(marker.getString("path")))throw new IOException("RECOVERY_PENDING_INVALID");
        Receipt prior=readReceipt(address,path);if(prior==null||!fingerprint(prior).equals(marker.getString("priorFingerprint"))||prior.packageSize!=marker.getLong("backupSize")||!prior.packageSha256.equals(marker.getString("backupHash")))throw new IOException("RECOVERY_PRIOR_CHANGED");
        new DynamicDialBackupStore(root.getParentFile()).readExact(prior.packageSha256,prior.packageSize);
        JSONObject tuple=marker.getJSONObject("target");if(artifacts==null||!"LOCAL_PROFILE_BUILD".equals(tuple.getString("origin")))throw new IOException("RECOVERY_TARGET_INVALID");
        DynamicDialPackage target=DynamicDialAuthorization.verifiedLocal(artifacts,tuple.getString("artifactSha256"));
        if(!DynamicDialAuthorization.localCandidate(target)||!targetMatches(tuple,new Receipt(address,path,target)))throw new IOException("RECOVERY_TARGET_CHANGED");
        if(!java.util.Arrays.equals(bytes,readBounded(pending,4096)))throw new IOException("RECOVERY_PENDING_CHANGED");
        return new RecoveryContext(id,address,path,prior,target,bytes);
    }catch(IOException e){throw e;}catch(Exception e){throw new IOException("RECOVERY_CONTEXT_INVALID",e);}}}
    boolean saveAfterVerifiedRecovery(RecoveryContext expected,String exactCurrent,Receipt next)throws IOException {synchronized(LOCK){
        RecoveryContext fresh=recoveryContext(expected.address);if(!expected.same(fresh))return false;
        return saveAfterVerifiedMutation(expected.originalOperationId,exactCurrent,expected.prior,next);
    }}
    java.util.List<Receipt> knownReceipts(String address,String path)throws IOException {synchronized(LOCK){
        address=normalizeAddress(address);path=normalizePath(path);java.util.ArrayList<Receipt> result=new java.util.ArrayList<>();Receipt current=readReceipt(address,path);if(current!=null)result.add(current);
        File directory=historyDirectory(address,path);if(!directory.exists())return java.util.Collections.unmodifiableList(result);
        if(!directory.getCanonicalFile().equals(new File(root.getCanonicalFile(),"history/"+sha256(address+"\n"+path))))throw new IOException("HISTORY_PATH");
        File[] files=directory.listFiles();if(files==null||files.length>32)throw new IOException("HISTORY_LIMIT");
        for(File file:files){if(!file.getName().matches("[0-9a-f]{64}[.]json")||!file.getCanonicalFile().equals(new File(directory.getCanonicalFile(),file.getName())))throw new IOException("HISTORY_INVALID");
            Receipt receipt=decodeReceipt(readBounded(file,4096),address,path);if(!file.getName().equals(fingerprint(receipt)+".json"))throw new IOException("HISTORY_CHANGED");
            boolean present=false;for(Receipt known:result)if(known.sameOwnership(receipt)){present=true;break;}if(!present)result.add(receipt);
        }
        return java.util.Collections.unmodifiableList(result);
    }}
    private File historyDirectory(String address,String path){return new File(root,"history/"+sha256(address+"\n"+path));}
    private void retainPrior(Receipt prior)throws IOException {
        if(prior==null)throw new IOException("PRIOR_RECEIPT_REQUIRED");
        // Read every historic tuple again before granting another mutation; no unknown/corrupt entries are ignored.
        knownReceipts(prior.deviceAddress,prior.path);ensureRoot();File parent=new File(root,"history");if(!parent.isDirectory()){if(!parent.mkdir())throw new IOException("HISTORY_DIRECTORY");syncDirectory(root);}
        File directory=historyDirectory(prior.deviceAddress,prior.path);if(!directory.isDirectory()){if(!directory.mkdir())throw new IOException("HISTORY_DIRECTORY");syncDirectory(parent);}
        byte[] bytes=readBounded(receiptFile(prior.deviceAddress,prior.path),4096);if(!matches(bytes,prior))throw new IOException("HISTORY_PRIOR_CHANGED");File target=new File(directory,fingerprint(prior)+".json");
        if(target.exists()){if(!java.util.Arrays.equals(bytes,readBounded(target,4096)))throw new IOException("HISTORY_CHANGED");syncDirectory(directory);return;}
        File[] files=directory.listFiles();if(files==null||files.length>=32)throw new IOException("HISTORY_LIMIT");
        File temporary=new File(directory,"history-"+java.util.UUID.randomUUID()+".pending");
        try(FileChannel out=FileChannel.open(temporary.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())if(out.write(buffer)<=0)throw new IOException("HISTORY_SHORT_WRITE");out.force(true);}
        if(!java.util.Arrays.equals(bytes,readBounded(temporary,4096)))throw new IOException("HISTORY_READBACK");
        Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);syncDirectory(directory);
        if(!java.util.Arrays.equals(bytes,readBounded(target,4096)))throw new IOException("HISTORY_READBACK");
    }
    private static JSONObject targetJson(DynamicDialPackage target)throws JSONException {
        JSONObject json=new JSONObject().put("origin",target.origin.name()).put("id",target.internalPackageId).put("alias",target.alias)
            .put("size",target.packageSize).put("sha256",target.packageSha256);
        if(target.origin==DynamicDialPackage.Origin.LOCAL_PROFILE_BUILD)json.put("runtimeProfile",target.runtimeProfile).put("profileSha256",target.profileSha256)
            .put("snapshotSha256",target.snapshotSha256).put("artifactSha256",target.artifactSha256);return json;
    }
    private static boolean targetMatches(JSONObject target,Receipt next)throws JSONException {
        return next.origin.equals(target.getString("origin"))&&next.internalPackageId.equals(target.getString("id"))&&next.alias.equals(target.getString("alias"))
            &&next.packageSize==target.getLong("size")&&next.packageSha256.equals(target.getString("sha256"))
            &&(next.schemaVersion==1||next.paletteId.equals(target.getString("runtimeProfile"))&&next.profileSha256.equals(target.getString("profileSha256"))&&next.snapshotSha256.equals(target.getString("snapshotSha256"))&&next.artifactSha256.equals(target.getString("artifactSha256")));
    }
    static String fingerprint(Receipt receipt)throws IOException{return receipt==null?"ABSENT":sha256(new String(encode(receipt),StandardCharsets.UTF_8));}
    private static void validateOperationId(String id)throws IOException{try{if(id==null||!java.util.UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new IOException("Invalid dynamic operation ID",e);}}
    private void ensureRoot()throws IOException{if(!root.isDirectory()){if(!root.mkdirs())throw new IOException("Ownership directory");syncDirectory(root.getParentFile());}}

    private static boolean matches(byte[] bytes,Receipt receipt){try{JSONObject json=new JSONObject(new String(bytes,StandardCharsets.UTF_8));return json.getInt("schemaVersion")==receipt.schemaVersion&&receipt.deviceAddress.equals(json.getString("deviceAddress"))&&receipt.path.equals(json.getString("path"))&&receipt.faceId.equals(json.getString("faceId"))&&receipt.paletteId.equals(json.getString("paletteId"))&&receipt.packageSha256.equals(json.getString("packageSha256"))&&receipt.packageSize==json.getLong("packageSize")&&receipt.internalPackageId.equals(json.getString("internalPackageId"))&&receipt.alias.equals(json.getString("alias"))&&receipt.verifiedAtEpochMs==json.getLong("verifiedAtEpochMs")&&(receipt.schemaVersion==1||receipt.origin.equals(json.getString("origin"))&&receipt.paletteId.equals(json.getString("runtimeProfile"))&&receipt.profileSha256.equals(json.getString("profileSha256"))&&receipt.snapshotSha256.equals(json.getString("snapshotSha256"))&&receipt.artifactSha256.equals(json.getString("artifactSha256")));}catch(Exception e){return false;}}
    /** Read-only metadata export deliberately bypasses load's pending block, never its installation gate. */
    JSONObject diagnosticMetadata()throws Exception {
        synchronized(LOCK){
            org.json.JSONArray rows=new org.json.JSONArray();JSONObject result=new JSONObject().put("schemaVersion",1).put("validation","READ_ONLY_METADATA").put("records",rows);
            if(!root.exists())return result.put("state","ABSENT");
            File[] files=root.listFiles((d,n)->n.matches("[0-9a-f]{64}[.](json|pending)"));if(files==null)throw new IOException("OWNERSHIP_METADATA_UNREADABLE");
            java.util.Arrays.sort(files,(a,b)->a.getName().compareTo(b.getName()));
            int count=0;
            for(File file:files){if(++count>128){result.put("state","LIMIT");break;}
                JSONObject row=new JSONObject().put("recordKey",file.getName()).put("modifiedAtEpochMs",file.lastModified());rows.put(row);
                try{
                    File expectedDirectory=new File(root.getParentFile().getCanonicalFile(),root.getName());
                    if(!file.getCanonicalFile().equals(new File(expectedDirectory,file.getName())))throw new IOException("OWNERSHIP_METADATA_PATH");
                    org.json.JSONTokener parser=new org.json.JSONTokener(new String(readBounded(file,4096),StandardCharsets.UTF_8));Object value=parser.nextValue();
                    if(!(value instanceof JSONObject)||parser.nextClean()!=0){row.put("state",file.getName().endsWith(".pending")?"LEGACY_PENDING":"INVALID");continue;}
                    JSONObject json=(JSONObject)value;String address=normalizeAddress(json.getString("deviceAddress")),path=normalizePath(json.getString("path"));
                    row.put("deviceKey",sha256(address)).put("pathKey",sha256(path));
                    if(file.getName().endsWith(".pending")){
                        String operationId=json.getString("operationId");validateOperationId(operationId);
                        row.put("operationId",operationId).put("state",metadataToken(json.getString("state"),"ARMED"));
                        String prior=json.getString("priorFingerprint");if(!"ABSENT".equals(prior))metadataHash(prior);
                        row.put("priorFingerprint",prior).put("backupHash",metadataHash(json.getString("backupHash"))).put("backupSize",metadataSize(json.getLong("backupSize")));
                        row.put("target",safeTargetMetadata(json.getJSONObject("target"),true));
                    }else{
                        row.put("state","PRESENT").put("schemaVersion",json.getInt("schemaVersion")).put("verifiedAtEpochMs",json.getLong("verifiedAtEpochMs"))
                            .put("target",safeTargetMetadata(json,false));
                    }
                }catch(IOException e){row.put("state","UNREADABLE");}
                catch(Exception e){row.put("state","INVALID");}
            }
            if(!result.has("state"))result.put("state",files.length==0?"ABSENT":"READ_ONLY_METADATA");return result;
        }
    }
    private static JSONObject safeTargetMetadata(JSONObject json,boolean marker)throws Exception {
        String id=json.getString(marker?"id":"internalPackageId"),alias=json.getString("alias");
        if(!id.matches("[A-Za-z0-9_.]{1,96}")||!alias.matches("[0-9]{1,32}"))throw new JSONException("OWNERSHIP_METADATA_TUPLE");
        JSONObject target=new JSONObject().put("id",id).put("alias",alias).put("size",metadataSize(json.getLong(marker?"size":"packageSize")))
            .put("sha256",metadataHash(json.getString(marker?"sha256":"packageSha256")));
        for(String key:new String[]{"profileSha256","snapshotSha256","artifactSha256"})if(json.has(key))target.put(key,metadataHash(json.getString(key)));
        if(json.has("origin"))target.put("origin",metadataToken(json.getString("origin"),"BUNDLED_PINNED|LOCAL_PROFILE_BUILD"));
        return target;
    }
    private static String metadataToken(String value,String pattern)throws JSONException {if(!value.matches(pattern))throw new JSONException("OWNERSHIP_METADATA_TOKEN");return value;}
    private static String metadataHash(String value)throws JSONException {if(!value.matches("[0-9a-f]{64}"))throw new JSONException("OWNERSHIP_METADATA_HASH");return value;}
    private static long metadataSize(long size)throws JSONException {if(size<=0||size>32L*1024*1024)throw new JSONException("OWNERSHIP_METADATA_SIZE");return size;}
    private static byte[] encode(Receipt r)throws IOException{try{JSONObject json=new JSONObject().put("schemaVersion",r.schemaVersion).put("deviceAddress",r.deviceAddress).put("path",r.path).put("faceId",r.faceId).put("paletteId",r.paletteId).put("packageSha256",r.packageSha256).put("packageSize",r.packageSize).put("internalPackageId",r.internalPackageId).put("alias",r.alias).put("verifiedAtEpochMs",r.verifiedAtEpochMs);if(r.schemaVersion==2)json.put("origin",r.origin).put("runtimeProfile",r.paletteId).put("profileSha256",r.profileSha256).put("snapshotSha256",r.snapshotSha256).put("artifactSha256",r.artifactSha256);return json.toString().getBytes(StandardCharsets.UTF_8);}catch(JSONException e){throw new IOException("Ownership receipt serialization failed",e);}}
    private File receiptFile(String address,String path){return new File(root,sha256(address+"\n"+path)+".json");}
    private File pendingFile(String address,String path){return new File(root,sha256(address+"\n"+path)+".pending");}
    static String normalizePath(String path)throws IOException{if(!BundledDynamicDial.REMOTE_PATH.equals(path))throw new IOException("Unsupported dynamic dial path");return path;}
    static String normalizeAddress(String address)throws IOException{if(address==null||!address.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))throw new IOException("Invalid watch address");return address.toUpperCase(Locale.ROOT);}
    private static void writeSynced(File file,byte[] bytes)throws IOException{try(FileOutputStream out=new FileOutputStream(file,false)){out.write(bytes);out.flush();out.getFD().sync();}}
    private static void syncDirectory(File directory)throws IOException{try(FileChannel channel=FileChannel.open(directory.toPath(),StandardOpenOption.READ)){channel.force(true);}}
    private static byte[] readBounded(File file,int max)throws IOException{if(file.length()<1||file.length()>max)throw new IOException("Ownership receipt size invalid");byte[] result=new byte[(int)file.length()];try(FileInputStream in=new FileInputStream(file)){int offset=0;while(offset<result.length){int n=in.read(result,offset,result.length-offset);if(n<0)throw new IOException("Short ownership receipt read");offset+=n;}if(in.read()!=-1)throw new IOException("Ownership receipt changed while reading");}return result;}
    private static String sha256(String value){try{StringBuilder s=new StringBuilder(64);for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
