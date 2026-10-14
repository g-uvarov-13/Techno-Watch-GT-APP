package app.gtdial.lab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Immutable revision directories are published by a single AtomicFile pointer. */
final class DialProjectRepository {
    static final int MAX_PROJECTS=100, MAX_JSON=64*1024;
    static final class Revision {
        final DialProject project;
        final File source,preview,json;
        Revision(DialProject project,File directory) {
            this.project=project;source=new File(directory,"source.png");
            preview=new File(directory,"preview.png");json=new File(directory,"project.json");
        }
    }
    static final class Draft {
        final DialProject project;
        final String selectedId;
        final boolean cropMode;
        final String pendingUri;
        Draft(DialProject project,String selectedId,boolean cropMode,String pendingUri) {
            this.project=project;this.selectedId=selectedId;this.cropMode=cropMode;this.pendingUri=pendingUri;
        }
    }
    private final File root,sources;
    DialProjectRepository(Context context) {
        root=new File(context.getFilesDir(),"dial-projects");sources=new File(root,"sources");
    }
    synchronized void storeSource(DialImageNormalizer.Result image) throws IOException {
        File f=source(image.background.sha256);
        if(f.isFile()) {
            if(!image.background.sha256.equals(DialImageNormalizer.hash(read(f,DialImageNormalizer.MAX_BYTES))))
                throw new IOException("Повреждён сохранённый источник");
            return;
        }
        write(f,image.png);
    }
    File source(String hash) {
        if(hash==null||!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Хеш фотографии");
        return new File(sources,hash+".png");
    }
    synchronized void saveDraft(DialProject p,String selectedId,boolean cropMode,String pendingUri) throws Exception {
        p.validate(false);requireVisible(p.id);
        if(p.background!=null&&!source(p.background.sha256).isFile())throw new IOException("Нет источника фотографии");
        writeJson(new File(root,"draft.json"),new JSONObject().put("project",p.toJson())
            .put("selectedId",selectedId==null?"":selectedId).put("cropMode",cropMode)
            .put("pendingUri",pendingUri==null?"":pendingUri));
    }
    synchronized Draft loadDraft() throws Exception {
        AtomicFile file=new AtomicFile(new File(root,"draft.json"));
        if(!file.getBaseFile().exists()&&!new File(root,"draft.json.bak").exists())return null;
        JSONObject j=new JSONObject(readAtomic(file,MAX_JSON));
        if(j.optBoolean("closed"))return null;
        DialProject p=DialProject.fromJson(j.getJSONObject("project"),false);
        try{requireVisible(p.id);}catch(IOException deleted){return null;}
        if(p.background!=null&&!source(p.background.sha256).isFile())throw new IOException("Нет источника фотографии");
        String selected=j.optString("selectedId","");if(!selected.isEmpty())DialRuntimeProfile.field(selected);
        return new Draft(p,selected.isEmpty()?null:selected,j.optBoolean("cropMode"),j.optString("pendingUri",""));
    }
    synchronized void closeDraft() throws Exception {
        // Retain immutable sources/revisions; closing never deletes an owned artifact.
        writeJson(new File(root,"draft.json"),new JSONObject().put("closed",true));
    }
    synchronized Revision importAsNew(DialProjectArchive.ValidatedImport incoming)throws Exception {
        if(incoming==null)throw new IOException("Нет проверенного проекта");
        Bitmap sourceBitmap=null,background=null,quantized=null,preview=null;
        try{
            DialProject original=incoming.project();original.validate(true);
            // Importing an unchanged export must not duplicate an existing gallery card.
            // Compare authoring content, not the archive UUID or revision counter.
            for(Revision existing:list())if(sameContent(original,existing.project))return load(existing.project.id);
            DialProject project=new DialProject(UUID.randomUUID().toString(),0,original.name,original.background,original.elements);
            project.validate(true);byte[] sourceBytes=incoming.sourceBytes();
            if(sourceBytes.length>DialImageNormalizer.MAX_BYTES||!project.background.sha256.equals(DialImageNormalizer.hash(sourceBytes)))throw new IOException("Источник проекта не прошёл проверку");
            File stored=source(project.background.sha256);
            if(!stored.exists())write(stored,sourceBytes);
            verifySource(stored,project.background);
            // The archive's preview is untrusted. Rebuild from its exact normalized source and authoring crop.
            sourceBitmap=loadSource(project.background);background=DialImageNormalizer.renderBackground(sourceBitmap,project.background);
            quantized=Gt01PgfCodec.quantizedPreview(background);preview=DialEditorView.preview(project,quantized);
            return save(project,preview);
        }catch(OutOfMemoryError error){throw new IOException("Недостаточно памяти для импорта; выберите меньший проект",error);}
        finally{if(preview!=null)preview.recycle();if(quantized!=null)quantized.recycle();if(background!=null)background.recycle();if(sourceBitmap!=null)sourceBitmap.recycle();}
    }
    private static boolean sameContent(DialProject a,DialProject b) {
        if(!a.name.equals(b.name)||a.background==null||b.background==null||a.elements.size()!=b.elements.size())return false;
        DialProject.Background x=a.background,y=b.background;
        if(!x.sha256.equals(y.sha256)||x.width!=y.width||x.height!=y.height||x.x!=y.x||x.y!=y.y||x.size!=y.size)return false;
        for(int i=0;i<a.elements.size();i++){
            DialProject.Element p=a.elements.get(i),q=b.elements.get(i);
            if(!p.id.equals(q.id)||p.x!=q.x||p.y!=q.y||p.width!=q.width||p.height!=q.height||p.argb!=q.argb)return false;
        }
        return true;
    }
    synchronized Revision save(DialProject p,Bitmap preview) throws Exception {
        p.validate(true);File project=projectDirectory(p.id);requireVisible(p.id);
        if(!project.isDirectory()&&list().size()>=MAX_PROJECTS)throw new IOException("Галерея заполнена");
        ensure(project);int next=1;File[] existing=project.listFiles(File::isDirectory);
        if(existing!=null)for(File file:existing)try {
            int r=Integer.parseInt(file.getName());if(r>=Integer.MAX_VALUE)throw new IOException("Предел ревизий");
            next=Math.max(next,r+1);
        }catch(NumberFormatException ignored){}
        DialProject saved=p.revised(next);File revision=new File(project,Integer.toString(next));
        if(revision.exists())throw new IOException("Ревизия уже существует");ensure(revision);
        write(new File(revision,"source.png"),read(source(p.background.sha256),DialImageNormalizer.MAX_BYTES));
        writeJson(new File(revision,"project.json"),saved.toJson());
        if(preview.getWidth()!=466||preview.getHeight()!=466)throw new IOException("Размер предпросмотра");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(!preview.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Предпросмотр не сохранён");
        write(new File(revision,"preview.png"),out.toByteArray());Revision checked=loadRevision(p.id,next);
        // The previous pointer remains valid if writing or validation fails above.
        syncDirectory(revision);syncDirectory(project);syncDirectory(root);
        writeJson(new File(project,"current.json"),new JSONObject().put("revision",next));syncDirectory(project);
        Revision published=load(p.id);
        if(published.project.revision!=next)throw new IOException("Не удалось подтвердить текущую ревизию");
        return checked;
    }
    synchronized Revision load(String id) throws Exception {
        return loadRevision(id,currentRevision(id));
    }
    private int currentRevision(String id) throws Exception {
        requireVisible(id);
        JSONObject pointer=new JSONObject(readAtomic(new AtomicFile(new File(projectDirectory(id),"current.json")),MAX_JSON));
        Object value=pointer.get("revision");
        if(!(value instanceof Integer)||((Integer)value)<1)throw new IOException("Указатель ревизии");
        return (Integer)value;
    }
    synchronized List<Revision> revisions(String id) {
        List<Revision> out=new ArrayList<>();int latest;
        try{latest=currentRevision(id);}catch(Exception e){return out;}
        File[] dirs=projectDirectory(id).listFiles(File::isDirectory);
        if(dirs!=null)for(File d:dirs)try {int number=Integer.parseInt(d.getName());
            if(number<=latest)out.add(loadMetadata(id,number));}
            catch(Exception ignored) {/* Incomplete unpublished revisions are never offered. */}
        out.sort((a,b)->Integer.compare(b.project.revision,a.project.revision));return out;
    }
    synchronized List<Revision> list() {
        List<Revision> out=new ArrayList<>();File[] dirs=root.listFiles(File::isDirectory);
        if(dirs!=null)for(File d:dirs)try {UUID.fromString(d.getName());out.add(loadMetadata(d.getName(),currentRevision(d.getName())));}
            catch(Exception ignored) {/* Invalid entries cannot reach the editor/compiler. */}
        out.sort((a,b)->Long.compare(b.json.lastModified(),a.json.lastModified()));return out;
    }
    synchronized Revision loadRevision(String id,int revision) throws Exception {
        requireVisible(id);Revision result=loadMetadata(id,revision);verifySource(result.source,result.project.background);return result;
    }
    private Revision loadMetadata(String id,int revision) throws Exception {
        if(revision<1)throw new IOException("Ревизия");File directory=new File(projectDirectory(id),Integer.toString(revision));
        DialProject p=DialProject.fromJson(new JSONObject(new String(read(new File(directory,"project.json"),MAX_JSON),StandardCharsets.UTF_8)),true);
        if(!id.equals(p.id)||p.revision!=revision)throw new IOException("Несовпадение ревизии");
        File sourceFile=new File(directory,"source.png");
        if(!sourceFile.isFile()||sourceFile.length()>DialImageNormalizer.MAX_BYTES)throw new IOException("Нет источника фотографии");
        byte[] bytes=read(new File(directory,"preview.png"),DialImageNormalizer.MAX_BYTES);
        BitmapFactory.Options b=new BitmapFactory.Options();b.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,b);
        if(b.outWidth!=466||b.outHeight!=466||!"image/png".equals(b.outMimeType))throw new IOException("Предпросмотр повреждён");
        return new Revision(p,directory);
    }
    synchronized Bitmap loadSource(DialProject.Background bg) throws IOException {
        File f=source(bg.sha256);verifySource(f,bg);
        try {Bitmap bitmap=BitmapFactory.decodeFile(f.getPath());
            if(bitmap==null)throw new IOException("Не удалось открыть сохранённую фотографию");return bitmap;
        }catch(OutOfMemoryError e){throw new IOException("Недостаточно памяти для фотографии",e);}
    }
    synchronized byte[] sourceBytes(DialProject.Background bg) throws IOException {
        File f=source(bg.sha256);verifySource(f,bg);return read(f,DialImageNormalizer.MAX_BYTES);
    }
    private void verifySource(File source,DialProject.Background bg) throws IOException {
        byte[] png=read(source,DialImageNormalizer.MAX_BYTES);
        if(!bg.sha256.equals(DialImageNormalizer.hash(png)))throw new IOException("Хеш фотографии не совпадает");
        BitmapFactory.Options b=new BitmapFactory.Options();b.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(png,0,png.length,b);
        DialImageNormalizer.checkDimensions(b.outWidth,b.outHeight);
        if(b.outWidth!=bg.width||b.outHeight!=bg.height||!"image/png".equals(b.outMimeType))throw new IOException("Размеры фотографии не совпадают");
    }
    synchronized void delete(String id) throws Exception {
        Revision current=load(id);
        Draft draft=loadDraft();
        // Keep immutable compiled packages and ownership evidence usable for an installed face.
        writeJson(new File(projectDirectory(id),"deleted.json"),new JSONObject().put("deleted",true).put("projectId",current.project.id));
        requireDeleted(id);
        if(draft!=null&&id.equals(draft.project.id))closeDraft();
    }
    private void requireDeleted(String id) throws Exception {
        JSONObject marker=new JSONObject(readAtomic(new AtomicFile(new File(projectDirectory(id),"deleted.json")),MAX_JSON));
        if(!marker.optBoolean("deleted")||!id.equals(marker.optString("projectId")))throw new IOException("Удаление проекта не подтверждено");
    }
    private void requireVisible(String id) throws IOException {
        File dir=projectDirectory(id);
        if(new File(dir,"deleted.json").exists()||new File(dir,"deleted.json.bak").exists())throw new IOException("Проект удалён");
    }
    private File projectDirectory(String id) {UUID.fromString(id);return new File(root,id);}
    private static void ensure(File directory) throws IOException {
        if(directory.isDirectory())return;
        if(!directory.mkdirs())throw new IOException("Не удалось создать каталог проекта");
        File parent=directory.getParentFile();if(parent!=null&&parent.isDirectory())syncDirectory(parent);
    }
    private static void writeJson(File file,JSONObject json) throws IOException {
        byte[] bytes=json.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_JSON)throw new IOException("Проект превышает 64 КиБ");write(file,bytes);
    }
    private static void write(File file,byte[] bytes) throws IOException {
        ensure(file.getParentFile());AtomicFile atomic=new AtomicFile(file);FileOutputStream stream=null;
        try {stream=atomic.startWrite();stream.write(bytes);stream.getFD().sync();atomic.finishWrite(stream);}
        catch(IOException e){if(stream!=null)atomic.failWrite(stream);throw e;}
        syncDirectory(file.getParentFile());
    }
    private static byte[] read(File file,int max) throws IOException {
        if(!file.isFile()||file.length()>max)throw new IOException("Файл проекта отсутствует или слишком велик");
        try(FileInputStream in=new FileInputStream(file)){return DialImageNormalizer.read(in,max);}
    }
    private static String readAtomic(AtomicFile file,int max) throws IOException {
        try(FileInputStream in=file.openRead()){return new String(DialImageNormalizer.read(in,max),StandardCharsets.UTF_8);}
    }
    private static void syncDirectory(File directory) throws IOException {
        try(FileChannel channel=FileChannel.open(directory.toPath(),StandardOpenOption.READ)){channel.force(true);}
    }
}
