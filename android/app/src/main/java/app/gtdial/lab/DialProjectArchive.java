package app.gtdial.lab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Bounded portable authoring archive; never carries executable resources or install authority. */
final class DialProjectArchive {
    private static final int MAX_ARCHIVE=20*1024*1024, MAX_TOTAL=32*1024*1024, MAX_PREVIEW=1024*1024;
    static final class ValidatedImport {
        private final DialProject project;
        private final byte[] source;
        private ValidatedImport(DialProject project,byte[] source){this.project=project;this.source=source.clone();}
        DialProject project(){return project;}
        byte[] sourceBytes(){return source.clone();}
    }
    private static final class Entry {
        String name;
        int flags,method,packed,size,offset;
        long crc;
    }
    static ValidatedImport read(InputStream input)throws Exception {
        try {
            byte[] archive=DialImageNormalizer.read(input,MAX_ARCHIVE);
            Map<String,byte[]> files=unzip(archive);
            byte[] json=files.get("project.json");
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(json)).toString();
            require(!text.startsWith("\ufeff")&&text.indexOf('\0')<0,"Кодировка проекта");
            checkDepth(text);
            DialProject project=Gt01DialCompiler.parseCanonical(json);
            require(UUID.fromString(project.id).toString().equals(project.id)&&project.revision>=1,"Идентификатор проекта");
            byte[] source=files.get("source.png");
            require(DialImageNormalizer.hash(source).equals(project.background.sha256),"Хеш фотографии проекта");
            checkPng(source,project.background.width,project.background.height,true);
            checkPng(files.get("preview.png"),466,466,false);
            return new ValidatedImport(project,source);
        }catch(OutOfMemoryError error){throw new IOException("Недостаточно памяти для импорта; выберите меньший проект",error);}
    }
    static File export(Context context,DialProjectRepository.Revision revision)throws Exception {
        revision.project.validate(true);
        byte[] source=CompiledDialArtifactStore.read(revision.source,DialImageNormalizer.MAX_BYTES);
        byte[] preview=CompiledDialArtifactStore.read(revision.preview,MAX_PREVIEW);
        byte[] project=Gt01DialCompiler.canonicalProject(revision.project);
        require(project.length<=64*1024,"Размер описания проекта");
        File directory=new File(context.getFilesDir(),"reports/dial-projects");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Каталог экспорта проекта");
        sync(directory.getParentFile());
        String name=revision.project.id+"-r"+revision.project.revision+"-"+UUID.randomUUID();
        File pending=new File(directory,name+".pending"),published=new File(directory,name+".gtdial");
        require(!pending.exists()&&!published.exists(),"Экспорт уже существует");
        try(FileOutputStream output=new FileOutputStream(pending);ZipOutputStream zip=new ZipOutputStream(output)){
            // STORED has no data descriptor; exports are independently checked before publication.
            put(zip,"project.json",project);put(zip,"source.png",source);put(zip,"preview.png",preview);
            zip.finish();zip.flush();output.getFD().sync();
        }
        require(pending.length()<=MAX_ARCHIVE,"Проект превышает 20 МиБ; выберите меньшую фотографию");
        try(FileInputStream input=new FileInputStream(pending)){
            ValidatedImport checked=read(input);
            require(Arrays.equals(project,Gt01DialCompiler.canonicalProject(checked.project()))
                &&Arrays.equals(source,checked.sourceBytes()),"Проверка экспортного проекта");
        }
        Files.move(pending.toPath(),published.toPath(),StandardCopyOption.ATOMIC_MOVE);sync(directory);
        return published;
    }
    private static void put(ZipOutputStream zip,String name,byte[] data)throws IOException {
        CRC32 crc=new CRC32();crc.update(data);ZipEntry entry=new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);entry.setSize(data.length);entry.setCompressedSize(data.length);entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);zip.write(data);zip.closeEntry();
    }
    private static Map<String,byte[]> unzip(byte[] b)throws Exception {
        require(b.length>=22,"Оборванный архив");int end=-1;
        for(int p=b.length-22;p>=Math.max(0,b.length-65557);p--)
            if(u32(b,p)==0x06054b50L&&p+22+u16(b,p+20)==b.length){end=p;break;}
        require(end>=0,"Нет окончания ZIP");
        require(u16(b,end+4)==0&&u16(b,end+6)==0&&u16(b,end+8)==3&&u16(b,end+10)==3,"ZIP: только три файла, один том");
        int central=bounded32(b,end+16),centralSize=bounded32(b,end+12);
        require((long)central+centralSize==end,"Каталог ZIP или ZIP64");
        Map<String,Entry> entries=new HashMap<>();int p=central;
        for(int i=0;i<3;i++){
            range(b,p,46);require(u32(b,p)==0x02014b50L,"Каталог ZIP");
            Entry e=new Entry();e.flags=u16(b,p+8);e.method=u16(b,p+10);e.crc=u32(b,p+16);
            e.packed=bounded32(b,p+20);e.size=bounded32(b,p+24);e.offset=bounded32(b,p+42);
            int names=u16(b,p+28),extra=u16(b,p+30),comment=u16(b,p+32);
            require(u16(b,p+6)<=20&&u16(b,p+34)==0&&(e.flags&~0x808)==0&&(e.method==0||e.method==8),"Неподдерживаемый ZIP");
            range(b,p+46,names+extra+comment);e.name=ascii(b,p+46,names);checkName(e.name);
            checkExtra(b,p+46+names,extra);require(entries.put(e.name,e)==null,"Повтор файла ZIP");
            require(e.size<=limit(e.name)&&e.packed<=MAX_ARCHIVE,"Размер файла ZIP");
            p+=46+names+extra+comment;
        }
        require(p==end,"Лишние записи ZIP");Entry[] ordered=entries.values().toArray(new Entry[0]);
        Arrays.sort(ordered,(a,c)->Integer.compare(a.offset,c.offset));
        Map<String,byte[]> result=new HashMap<>();int cursor=0,total=0;
        for(Entry e:ordered){
            require(e.offset==cursor,"Скрытые или пересекающиеся записи ZIP");p=e.offset;range(b,p,30);
            require(u32(b,p)==0x04034b50L&&u16(b,p+4)<=20&&u16(b,p+6)==e.flags&&u16(b,p+8)==e.method,"Локальная запись ZIP");
            int names=u16(b,p+26),extra=u16(b,p+28);range(b,p+30,names+extra);
            require(e.name.equals(ascii(b,p+30,names)),"Имя файла ZIP отличается");checkExtra(b,p+30+names,extra);
            long localCrc=u32(b,p+14),localPacked=u32(b,p+18),localSize=u32(b,p+22);
            if((e.flags&8)==0)require(localCrc==e.crc&&localPacked==e.packed&&localSize==e.size,"Размеры локальной записи ZIP");
            else require((localCrc==0||localCrc==e.crc)&&(localPacked==0||localPacked==e.packed)&&(localSize==0||localSize==e.size),"Описание локальной записи ZIP");
            int data=p+30+names+extra;range(b,data,e.packed);require((long)data+e.packed<=central,"Данные ZIP за каталогом");
            byte[] decoded=inflate(b,data,e);total+=decoded.length;require(total<=MAX_TOTAL,"Распакованный проект превышает 32 МиБ");
            CRC32 crc=new CRC32();crc.update(decoded);require(crc.getValue()==e.crc,"CRC файла ZIP");
            cursor=data+e.packed;
            if((e.flags&8)!=0){if(u32(b,cursor)==0x08074b50L)cursor+=4;
                require(u32(b,cursor)==e.crc&&u32(b,cursor+4)==e.packed&&u32(b,cursor+8)==e.size,"Описание данных ZIP");cursor+=12;}
            require(cursor<=central,"Пересечение ZIP");result.put(e.name,decoded);
        }
        require(cursor==central&&result.size()==3,"Лишние данные ZIP");return result;
    }
    private static byte[] inflate(byte[] b,int offset,Entry e)throws Exception {
        if(e.method==0){require(e.packed==e.size,"Размер STORED");return Arrays.copyOfRange(b,offset,offset+e.size);}
        Inflater inflater=new Inflater(true);
        try {inflater.setInput(b,offset,e.packed);ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];
            while(!inflater.finished()){
                int n=inflater.inflate(buffer);require(output.size()<=e.size-n&&output.size()<=limit(e.name)-n,"Размер DEFLATED");
                output.write(buffer,0,n);
                require(n>0||inflater.finished(),"Оборванный или неподдерживаемый DEFLATED");
            }
            require(output.size()==e.size&&inflater.getRemaining()==0,"DEFLATED EOF");return output.toByteArray();
        }finally{inflater.end();}
    }
    private static void checkPng(byte[] bytes,int width,int height,boolean source)throws Exception {
        byte[] signature={(byte)137,80,78,71,13,10,26,10};range(bytes,0,8);
        require(Arrays.equals(signature,Arrays.copyOf(bytes,8)),"Не PNG");int p=8;
        boolean ihdr=false,idat=false,iend=false,plte=false,idatClosed=false;
        while(p<bytes.length){range(bytes,p,12);int size=boundedBig32(bytes,p);range(bytes,p+8,size+4);
            String tag=ascii(bytes,p+4,4);
            for(int k=0;k<4;k++){char c=tag.charAt(k);require(c>='A'&&c<='Z'||c>='a'&&c<='z',"Тип PNG chunk");}
            require(tag.charAt(2)>='A'&&tag.charAt(2)<='Z',"Reserved bit PNG chunk");
            require(tag.charAt(0)>='a'||"IHDR".equals(tag)||"PLTE".equals(tag)||"IDAT".equals(tag)||"IEND".equals(tag),"Неизвестный critical PNG chunk");
            CRC32 crc=new CRC32();crc.update(bytes,p+4,size+4);
            require(crc.getValue()==big32(bytes,p+8+size),"CRC PNG");
            if(!ihdr){require("IHDR".equals(tag)&&size==13,"Заголовок PNG");
                require(big32(bytes,p+8)==width&&big32(bytes,p+12)==height&&(bytes[p+16]&255)==8
                    &&((bytes[p+17]&255)==2||(bytes[p+17]&255)==6)&&bytes[p+18]==0&&bytes[p+19]==0&&bytes[p+20]==0,
                    "PNG: требуется нормализованный 8-bit RGB/RGBA без interlace");ihdr=true;}
            else require(!"IHDR".equals(tag),"Повтор IHDR");
            require(!"acTL".equals(tag)&&!"fcTL".equals(tag)&&!"fdAT".equals(tag)&&!"eXIf".equals(tag),"PNG должен быть нормализован");
            if("PLTE".equals(tag)){require(!plte&&!idat&&size>0&&size<=768&&size%3==0,"Структура PLTE");plte=true;}
            if("IDAT".equals(tag)){require(!idatClosed,"Разорванная последовательность IDAT");idat=true;}
            else if(idat)idatClosed=true;
            if("IEND".equals(tag)){require(size==0&&idat&&p+12==bytes.length,"PNG EOF");iend=true;}
            p+=size+12;
        }
        require(iend,"Нет окончания PNG");BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);DialImageNormalizer.checkDimensions(options.outWidth,options.outHeight);
        require(options.outWidth==width&&options.outHeight==height&&"image/png".equals(options.outMimeType),"Размер PNG");
        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
        require(bitmap!=null&&bitmap.getWidth()==width&&bitmap.getHeight()==height,"Декодирование PNG");
        try {if(source){int[] row=new int[width];for(int y=0;y<height;y++){bitmap.getPixels(row,0,width,0,y,width,1);
            for(int color:row)require((color>>>24)==255,"Фотография содержит прозрачность");}}}
        finally{bitmap.recycle();}
    }
    private static void checkDepth(String text)throws IOException {
        int depth=0;boolean string=false,escape=false;
        for(int i=0;i<text.length();i++){char c=text.charAt(i);
            if(string){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')string=false;}
            else if(c=='"')string=true;
            else if(c=='{'||c=='['){require(++depth<=4,"Слишком глубокий JSON");}
            else if(c=='}'||c==']')require(--depth>=0,"Структура JSON");
        }
        require(depth==0&&!string&&!escape,"Оборванный JSON");
    }
    private static void checkName(String name)throws IOException{require(name.equals("project.json")||name.equals("source.png")||name.equals("preview.png"),"Неизвестный файл проекта");}
    private static int limit(String name){return name.equals("project.json")?64*1024:name.equals("preview.png")?MAX_PREVIEW:DialImageNormalizer.MAX_BYTES;}
    private static void checkExtra(byte[] b,int p,int size)throws IOException {
        int end=p+size;while(p<end){require(end-p>=4,"ZIP extra");int id=u16(b,p),n=u16(b,p+2);require(id!=1&&n<=end-p-4,"ZIP64 или extra");p+=4+n;}
    }
    private static String ascii(byte[] b,int p,int n)throws IOException{range(b,p,n);for(int i=p;i<p+n;i++)require((b[i]&255)>=32&&(b[i]&255)<127,"Имя ZIP/PNG");return new String(b,p,n,StandardCharsets.US_ASCII);}
    private static int u16(byte[] b,int p)throws IOException{range(b,p,2);return (b[p]&255)|((b[p+1]&255)<<8);}
    private static long u32(byte[] b,int p)throws IOException{range(b,p,4);return (b[p]&255L)|((b[p+1]&255L)<<8)|((b[p+2]&255L)<<16)|((b[p+3]&255L)<<24);}
    private static int bounded32(byte[] b,int p)throws IOException{long n=u32(b,p);require(n<=MAX_TOTAL,"ZIP размер или ZIP64");return (int)n;}
    private static long big32(byte[] b,int p)throws IOException{range(b,p,4);return ((b[p]&255L)<<24)|((b[p+1]&255L)<<16)|((b[p+2]&255L)<<8)|(b[p+3]&255L);}
    private static int boundedBig32(byte[] b,int p)throws IOException{long n=big32(b,p);require(n<=MAX_ARCHIVE,"Размер PNG chunk");return (int)n;}
    private static void range(byte[] b,int p,int n)throws IOException{require(p>=0&&n>=0&&(long)p+n<=b.length,"Оборванные данные проекта");}
    private static void require(boolean condition,String reason)throws IOException{if(!condition)throw new IOException(reason);}
    private static void sync(File directory)throws IOException{try(FileChannel channel=FileChannel.open(directory.toPath(),StandardOpenOption.READ)){channel.force(true);}}
    private DialProjectArchive(){}
}
