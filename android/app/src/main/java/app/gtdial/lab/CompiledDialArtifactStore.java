package app.gtdial.lab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Durable content-addressed artifacts. No deletion, rollback, or cleanup API. */
final class CompiledDialArtifactStore {
    Bitmap preview(String sha)throws Exception {
        Artifact artifact=load(sha);Bitmap raw=null,background=null;
        try{raw=Gt01PgfCodec.bitmap(artifact.snapshot.backgroundBytes());background=Gt01PgfCodec.quantizedPreview(raw);return DialEditorView.preview(artifact.snapshot.project,background);}
        finally{if(background!=null)background.recycle();if(raw!=null)raw.recycle();}
    }
    static final class Artifact {
        final Gt01DialValidator.VerifiedLocalPackage verified;
        final Gt01DialCompiler.Snapshot snapshot;
        final File directory;
        Artifact(Gt01DialValidator.VerifiedLocalPackage verified,Gt01DialCompiler.Snapshot snapshot,File directory){
            this.verified=verified;this.snapshot=snapshot;this.directory=directory;
        }
    }
    private static final Object LOCK=new Object();
    private final Context context;
    private final File root,artifacts;
    CompiledDialArtifactStore(Context context){this.context=context.getApplicationContext();root=new File(context.getFilesDir(),"dial-compiler");artifacts=new File(root,"artifacts");}
    Artifact prepare(DialProjectRepository.Revision revision)throws Exception {
        Gt01DialCompiler.Identity identity=allocateIdentity();Gt01DialCompiler compiler=new Gt01DialCompiler(context);
        byte[] source=read(revision.source,DialImageNormalizer.MAX_BYTES);Bitmap bitmap=null,background=null;
        Gt01DialCompiler.Snapshot snapshot;
        try {
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(source,0,source.length,bounds);
            DialImageNormalizer.checkDimensions(bounds.outWidth,bounds.outHeight);
            Gt01DialContainerCodec.require(bounds.outWidth==revision.project.background.width&&bounds.outHeight==revision.project.background.height,"Размер источника");
            bitmap=BitmapFactory.decodeByteArray(source,0,source.length);
            if(bitmap==null)throw new IOException("Не удалось прочитать фотографию");
            background=DialImageNormalizer.renderBackground(bitmap,revision.project.background);
            snapshot=Gt01DialCompiler.Snapshot.capture(revision.project,identity,source,Gt01PgfCodec.bitmapBytes(background));
        }catch(OutOfMemoryError e){throw new IOException("Недостаточно памяти для подготовки; выберите меньшую фотографию",e);}
        finally{if(background!=null)background.recycle();if(bitmap!=null)bitmap.recycle();}
        byte[] candidate=compiler.compile(snapshot);Gt01DialValidator.VerifiedLocalPackage verified=Gt01DialValidator.validate(compiler,snapshot,candidate);
        return publish(compiler,snapshot,verified);
    }
    private Gt01DialCompiler.Identity allocateIdentity()throws Exception {
        synchronized(LOCK){ensure(root);File file=new File(root,"identity.json");AtomicFile atomic=new AtomicFile(file);int next=1;
            if(file.exists()||new File(root,"identity.json.bak").exists()){
                try(FileInputStream input=atomic.openRead()){
                    JSONObject metadata=Gt01DialCompiler.json(DialImageNormalizer.read(input,1024));Object n=metadata.get("next");
                    Gt01DialContainerCodec.require(n instanceof Integer,"Счётчик идентификаторов повреждён");next=(Integer)n;
                }
            }
            Gt01DialCompiler.Identity identity=new Gt01DialCompiler.Identity(next);
            byte[] update=Gt01DialCompiler.utf8("{\"next\":"+(next+1)+"}");writeAtomic(file,update);sync(root);
            try(FileInputStream input=atomic.openRead()){
                Gt01DialContainerCodec.require(Arrays.equals(update,DialImageNormalizer.read(input,1024)),"Счётчик не подтверждён");}
            return identity;
        }
    }
    private Artifact publish(Gt01DialCompiler compiler,Gt01DialCompiler.Snapshot snapshot,Gt01DialValidator.VerifiedLocalPackage verified)throws Exception {
        synchronized(LOCK){ensure(artifacts);File target=directory(verified.sha256);
            if(target.exists()){Artifact existing=load(verified.sha256);
                Gt01DialContainerCodec.require(existing.verified.snapshotSha256.equals(snapshot.sha256),"Коллизия артефакта");return existing;}
            File pending=new File(artifacts,".pending-"+UUID.randomUUID());ensure(pending);
            writeNew(new File(pending,"face.dial"),verified.bytes());writeNew(new File(pending,"snapshot.json"),snapshot.metadata());
            writeNew(new File(pending,"project.json"),snapshot.projectBytes());writeNew(new File(pending,"source.bin"),snapshot.sourceBytes());
            writeNew(new File(pending,"background.bgra"),snapshot.backgroundBytes());sync(pending);
            Artifact checked=loadDirectory(compiler,pending,verified.sha256);
            Gt01DialContainerCodec.require(checked.snapshot.sha256.equals(snapshot.sha256),"Snapshot reread");
            Files.move(pending.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);sync(artifacts);
            return load(verified.sha256);
        }
    }
    Artifact load(String sha256)throws Exception {
        return loadDirectory(new Gt01DialCompiler(context),directory(sha256),sha256);
    }
    Artifact load(String sha256,DialInstallJournal journal,String stage)throws Exception {
        journal.event(stage,"STARTED");
        try{Artifact artifact=load(sha256);journal.hashEvent(stage,artifact.verified.sha256,artifact.verified.size);
            journal.hashEvent("SNAPSHOT_REVALIDATED",artifact.snapshot.sha256,Gt01PgfCodec.PIXEL_BYTES);return artifact;}
        catch(Exception|OutOfMemoryError e){try{journal.event(stage,"REJECTED");}catch(IOException|OutOfMemoryError log){e.addSuppressed(log);}throw e;}
    }
    Artifact find(DialProject project)throws Exception {
        byte[] canonical=Gt01DialCompiler.canonicalProject(project);File[] dirs=artifacts.listFiles(File::isDirectory);
        if(dirs==null)return null;Arrays.sort(dirs,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        for(File dir:dirs){if(!dir.getName().matches("[0-9a-f]{64}"))continue;
            byte[] saved=read(new File(dir,"project.json"),64*1024);
            if(Arrays.equals(saved,canonical))return load(dir.getName());
        }
        return null;
    }
    private Artifact loadDirectory(Gt01DialCompiler compiler,File directory,String expected)throws Exception {
        Gt01DialCompiler.Snapshot snapshot=Gt01DialCompiler.Snapshot.restore(read(new File(directory,"snapshot.json"),64*1024),
            read(new File(directory,"project.json"),64*1024),read(new File(directory,"source.bin"),DialImageNormalizer.MAX_BYTES),
            read(new File(directory,"background.bgra"),Gt01PgfCodec.PIXEL_BYTES));
        byte[] bytes=read(new File(directory,"face.dial"),Gt01DialContainerCodec.MAX_PACKAGE);
        Gt01DialContainerCodec.require(expected.equals(DialImageNormalizer.hash(bytes)),"Имя артефакта не совпадает с SHA");
        Gt01DialValidator.VerifiedLocalPackage verified=Gt01DialValidator.validate(compiler,snapshot,bytes);
        return new Artifact(verified,snapshot,directory);
    }
    File export(String sha256)throws Exception {
        Artifact artifact=load(sha256);File reports=new File(context.getFilesDir(),"reports");ensure(reports);
        File file=new File(reports,artifact.verified.manifestName+"-"+sha256.substring(0,12)+".dial");
        byte[] bytes=artifact.verified.bytes();writeAtomic(file,bytes);
        Gt01DialContainerCodec.require(Arrays.equals(bytes,read(file,Gt01DialContainerCodec.MAX_PACKAGE)),"Копия пакета не подтверждена");return file;
    }
    private static final String[] EVIDENCE_NAMES={"face.dial","snapshot.json","project.json","source.bin","background.bgra"};
    private static final int[] EVIDENCE_LIMITS={1024*1024,64*1024,64*1024,20*1024*1024,868624};
    private static final long EVIDENCE_ZIP_LIMIT=24L*1024*1024;

    /** Copies the validated immutable artifact; never allocates another dial identity. */
    File exportEvidence(String sha256)throws Exception {
        try {
            synchronized(LOCK) {
                Artifact artifact=load(sha256);
                byte[][] originals=readEvidence(artifact.directory);
                requireEvidenceMatches(artifact,originals);
                long[] crcs=new long[EVIDENCE_NAMES.length];long archiveLength=22;
                for(int i=0;i<originals.length;i++) {
                    CRC32 crc=new CRC32();crc.update(originals[i]);crcs[i]=crc.getValue();
                    // STORED, ASCII names, no extras/comments, and no ZIP64 or data descriptors.
                    archiveLength+=originals[i].length+76L+2L*EVIDENCE_NAMES[i].length();
                }
                Gt01DialContainerCodec.require(archiveLength<=EVIDENCE_ZIP_LIMIT,"Архив артефакта превышает предел");
                File reports=new File(context.getFilesDir(),"reports");ensure(reports);
                File exports=new File(reports,"dial-artifacts");ensure(exports);
                String name=artifact.verified.manifestName+"-"+sha256+"-"+UUID.randomUUID()+".zip";
                File target=new File(exports,name),pending=new File(exports,name+".pending");
                if(target.exists())throw new IOException("Архив артефакта уже существует");
                try(FileChannel output=FileChannel.open(pending.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                    ZipOutputStream zip=new ZipOutputStream(Channels.newOutputStream(output))) {
                    for(int i=0;i<originals.length;i++) {
                        ZipEntry entry=new ZipEntry(EVIDENCE_NAMES[i]);entry.setMethod(ZipEntry.STORED);
                        entry.setSize(originals[i].length);entry.setCompressedSize(originals[i].length);entry.setCrc(crcs[i]);
                        entry.setTime(946684800000L);zip.putNextEntry(entry);zip.write(originals[i]);zip.closeEntry();
                    }
                    zip.finish();zip.flush();output.force(true);
                }
                verifyEvidenceZip(pending,originals,crcs,archiveLength);
                requireEvidenceMatches(load(sha256),originals);
                for(int i=0;i<originals.length;i++)Gt01DialContainerCodec.require(Arrays.equals(originals[i],
                    read(new File(artifact.directory,EVIDENCE_NAMES[i]),EVIDENCE_LIMITS[i])),"Исходный артефакт изменился: "+EVIDENCE_NAMES[i]);
                // The UUID namespace and LOCK prevent collisions in this app-private directory.
                // ATOMIC_MOVE has platform-specific target replacement behavior, so fail before an existing target.
                if(target.exists())throw new IOException("Перезапись архива артефакта запрещена");
                Files.move(pending.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);sync(exports);
                verifyEvidenceZip(target,originals,crcs,archiveLength);return target;
            }
        }catch(OutOfMemoryError e){throw new IOException("Недостаточно памяти для архива артефакта",e);}
    }
    private static byte[][] readEvidence(File directory)throws IOException {
        byte[][] bytes=new byte[EVIDENCE_NAMES.length][];
        for(int i=0;i<bytes.length;i++)bytes[i]=read(new File(directory,EVIDENCE_NAMES[i]),EVIDENCE_LIMITS[i]);
        Gt01DialContainerCodec.require(bytes[4].length==868624,"Размер исходного BGRA артефакта");return bytes;
    }
    private static void requireEvidenceMatches(Artifact artifact,byte[][] originals)throws IOException {
        Gt01DialContainerCodec.require(Arrays.equals(originals[0],artifact.verified.bytes()),"Исходный face.dial изменился");
        Gt01DialContainerCodec.require(Arrays.equals(originals[1],artifact.snapshot.metadata()),"Исходный snapshot.json изменился");
        Gt01DialContainerCodec.require(Arrays.equals(originals[2],artifact.snapshot.projectBytes()),"Исходный project.json изменился");
        Gt01DialContainerCodec.require(Arrays.equals(originals[3],artifact.snapshot.sourceBytes()),"Исходный source.bin изменился");
        Gt01DialContainerCodec.require(Arrays.equals(originals[4],artifact.snapshot.backgroundBytes()),"Исходный background.bgra изменился");
    }
    private static void verifyEvidenceZip(File file,byte[][] originals,long[] crcs,long archiveLength)throws IOException {
        Gt01DialContainerCodec.require(file.isFile()&&file.length()==archiveLength&&archiveLength<=EVIDENCE_ZIP_LIMIT,"Размер архива артефакта");
        // Check both the central directory and the sequential local records, including their payloads.
        try(ZipFile zip=new ZipFile(file)) {
            Gt01DialContainerCodec.require(zip.size()==EVIDENCE_NAMES.length&&zip.getComment()==null,"Состав архива артефакта");
            Enumeration<? extends ZipEntry> entries=zip.entries();
            for(int i=0;i<originals.length;i++) {
                Gt01DialContainerCodec.require(entries.hasMoreElements(),"Архив артефакта неполон");
                ZipEntry entry=entries.nextElement();requireEvidenceEntry(entry,i,originals[i].length,crcs[i]);
                try(InputStream input=zip.getInputStream(entry)){requireEvidencePayload(input,originals[i],crcs[i]);}
            }
            Gt01DialContainerCodec.require(!entries.hasMoreElements(),"Лишняя запись архива артефакта");
        }
        try(ZipInputStream zip=new ZipInputStream(new FileInputStream(file))) {
            for(int i=0;i<originals.length;i++) {
                ZipEntry entry=zip.getNextEntry();requireEvidenceEntry(entry,i,originals[i].length,crcs[i]);
                requireEvidencePayload(zip,originals[i],crcs[i]);zip.closeEntry();
            }
            Gt01DialContainerCodec.require(zip.getNextEntry()==null,"Лишняя локальная запись архива артефакта");
        }
        Gt01DialContainerCodec.require(file.length()==archiveLength,"Архив артефакта изменился во время проверки");
    }
    private static void requireEvidenceEntry(ZipEntry entry,int index,int size,long crc)throws IOException {
        Gt01DialContainerCodec.require(entry!=null&&EVIDENCE_NAMES[index].equals(entry.getName())&&!entry.isDirectory()
            &&entry.getMethod()==ZipEntry.STORED&&entry.getSize()==size&&entry.getCompressedSize()==size&&entry.getCrc()==crc
            &&(entry.getExtra()==null||entry.getExtra().length==0)&&entry.getComment()==null,"Запись архива артефакта: "+EVIDENCE_NAMES[index]);
    }
    private static void requireEvidencePayload(InputStream input,byte[] expected,long expectedCrc)throws IOException {
        byte[] buffer=new byte[8192];int offset=0;CRC32 crc=new CRC32();
        for(;;) {
            int count=input.read(buffer,0,Math.min(buffer.length,expected.length-offset+1));if(count==-1)break;
            Gt01DialContainerCodec.require(count>0&&count<=expected.length-offset,"Предел записи архива артефакта");
            for(int i=0;i<count;i++)Gt01DialContainerCodec.require(buffer[i]==expected[offset+i],"Байты архива артефакта отличаются");
            crc.update(buffer,0,count);offset+=count;
        }
        Gt01DialContainerCodec.require(offset==expected.length&&crc.getValue()==expectedCrc,"Длина или CRC архива артефакта");
    }
    private File directory(String sha){if(sha==null||!sha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("SHA артефакта");return new File(artifacts,sha);}
    static byte[] read(File file,int max)throws IOException {
        if(!file.isFile()||file.length()>max)throw new IOException("Файл артефакта отсутствует или превышает предел");
        try(FileInputStream input=new FileInputStream(file)){return DialImageNormalizer.read(input,max);}
    }
    private static void ensure(File directory)throws IOException {
        if(directory.isDirectory())return;if(!directory.mkdirs())throw new IOException("Каталог артефактов");
        if(directory.getParentFile()!=null)sync(directory.getParentFile());
    }
    private static void writeNew(File file,byte[] bytes)throws IOException {
        if(file.exists())throw new IOException("Перезапись неизменяемого артефакта запрещена");
        try(FileOutputStream output=new FileOutputStream(file)){output.write(bytes);output.getFD().sync();}
    }
    private static void writeAtomic(File file,byte[] bytes)throws IOException {
        AtomicFile atomic=new AtomicFile(file);FileOutputStream output=null;
        try{output=atomic.startWrite();output.write(bytes);output.getFD().sync();atomic.finishWrite(output);}
        catch(IOException e){if(output!=null)atomic.failWrite(output);throw e;}sync(file.getParentFile());
    }
    private static void sync(File directory)throws IOException {
        try(FileChannel channel=FileChannel.open(directory.toPath(),StandardOpenOption.READ)){channel.force(true);}
    }
}
