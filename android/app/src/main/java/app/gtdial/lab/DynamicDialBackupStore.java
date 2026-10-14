package app.gtdial.lab;

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
import org.json.JSONObject;

/** Exact local backups; witnesses attest a completed full remote read, never a size-only check. */
final class DynamicDialBackupStore {
    private static final Object LOCK=new Object();
    private final File files;
    DynamicDialBackupStore(File files){this.files=files;}
    byte[] readExact(String hash,long size)throws IOException {
        if(hash==null||!hash.matches("[0-9a-f]{64}")||size<=0||size>32L*1024*1024)throw new IOException("BACKUP_TUPLE_INVALID");
        File file=new File(files,"dynamic-staging-backups/staging-"+hash+".dial");
        if(!file.getCanonicalFile().equals(new File(files.getCanonicalFile(),"dynamic-staging-backups/staging-"+hash+".dial")))throw new IOException("BACKUP_PATH");
        byte[] bytes;try{bytes=CompiledDialArtifactStore.read(file,(int)size);}catch(IOException e){throw new IOException("BACKUP_UNREADABLE",e);}
        if(bytes.length!=size||!hash.equals(DialImageNormalizer.hash(bytes)))throw new IOException("BACKUP_HASH_MISMATCH");return bytes;
    }
    byte[] reusable(DynamicDialOwnershipStore ownership,DynamicDialOwnershipStore.Receipt active)throws IOException {
        if(active==null)return null;ownership.requireNoPendingForLocal();
        File file=witness(active);if(!file.exists())return null;
        if(!file.getCanonicalFile().equals(new File(files.getCanonicalFile(),"dynamic-staging-backups/witnesses/"+DynamicDialOwnershipStore.fingerprint(active)+".json")))return null;
        try{JSONObject json=new JSONObject(new String(CompiledDialArtifactStore.read(file,4096),StandardCharsets.UTF_8));
            if(json.getInt("schemaVersion")!=1||!deviceKey(active).equals(json.getString("deviceKey"))||!active.path.equals(json.getString("path"))
                ||!DynamicDialOwnershipStore.fingerprint(active).equals(json.getString("receiptFingerprint"))||active.packageSize!=json.getLong("size")
                ||!active.packageSha256.equals(json.getString("sha256"))||!"FULL_REMOTE_READBACK".equals(json.getString("verification"))||json.getLong("verifiedAtEpochMs")<=0)return null;
            byte[] bytes=readExact(active.packageSha256,active.packageSize);ownership.requireNoPendingForLocal();return bytes;
        }catch(Exception e){return null;}
    }
    void saveVerified(DynamicDialOwnershipStore.Receipt next,byte[] bytes)throws IOException {synchronized(LOCK){try{
        if(next==null||bytes.length!=next.packageSize||!next.packageSha256.equals(DialImageNormalizer.hash(bytes)))throw new IOException("BACKUP_VERIFIED_TUPLE");
        File directory=new File(files,"dynamic-staging-backups");ensure(directory);
        publish(new File(directory,"staging-"+next.packageSha256+".dial"),bytes);
        readExact(next.packageSha256,next.packageSize);
        File witnessDirectory=new File(directory,"witnesses");ensure(witnessDirectory);
        byte[] metadata=new JSONObject().put("schemaVersion",1).put("deviceKey",deviceKey(next)).put("path",next.path)
            .put("receiptFingerprint",DynamicDialOwnershipStore.fingerprint(next)).put("size",next.packageSize).put("sha256",next.packageSha256)
            .put("verification","FULL_REMOTE_READBACK").put("verifiedAtEpochMs",next.verifiedAtEpochMs).toString().getBytes(StandardCharsets.UTF_8);
        if(metadata.length>4096)throw new IOException("BACKUP_WITNESS_LIMIT");publish(witness(next),metadata);
    }catch(IOException e){throw e;}catch(Exception e){throw new IOException("BACKUP_WITNESS_FAILED",e);}}}
    private File witness(DynamicDialOwnershipStore.Receipt receipt)throws IOException {return new File(files,"dynamic-staging-backups/witnesses/"+DynamicDialOwnershipStore.fingerprint(receipt)+".json");}
    private static String deviceKey(DynamicDialOwnershipStore.Receipt receipt){return DialImageNormalizer.hash(receipt.deviceAddress.getBytes(StandardCharsets.US_ASCII));}
    private static void publish(File target,byte[] bytes)throws IOException {
        if(target.exists()){if(!Arrays.equals(bytes,CompiledDialArtifactStore.read(target,bytes.length)))throw new IOException("BACKUP_EXISTING_CHANGED");sync(target.getParentFile());return;}
        File pending=new File(target.getParentFile(),"backup-"+UUID.randomUUID()+".pending");
        try(FileChannel channel=FileChannel.open(pending.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())if(channel.write(data)<=0)throw new IOException("BACKUP_SHORT_WRITE");channel.force(true);}
        if(!Arrays.equals(bytes,CompiledDialArtifactStore.read(pending,bytes.length)))throw new IOException("BACKUP_READBACK");
        if(target.exists())throw new IOException("BACKUP_TARGET_EXISTS");Files.move(pending.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);sync(target.getParentFile());
        if(!Arrays.equals(bytes,CompiledDialArtifactStore.read(target,bytes.length)))throw new IOException("BACKUP_READBACK");
    }
    private static void ensure(File directory)throws IOException {if(directory.isDirectory())return;if(!directory.mkdir())throw new IOException("BACKUP_DIRECTORY");sync(directory.getParentFile());}
    private static void sync(File directory)throws IOException {try(FileChannel channel=FileChannel.open(directory.toPath(),StandardOpenOption.READ)){channel.force(true);}}
}
