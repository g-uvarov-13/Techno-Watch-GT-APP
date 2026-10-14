package app.gtdial.lab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CR01/CR02 relocation preserving resource aliases, table fields and opaque gaps. */
final class Gt01DialContainerCodec {
    static final int MAX_PACKAGE=1024*1024;
    static final class Entry {
        final int hash,offset,length,fourth;
        Entry(int hash,int offset,int length,int fourth){this.hash=hash;this.offset=offset;this.length=length;this.fourth=fourth;}
    }
    static final class LocaleTable {
        final int hash,offset,length;
        final byte[] nameBytes;
        final String name;
        final List<Entry> entries;
        LocaleTable(int hash,int offset,int length,byte[] nameBytes,String name,List<Entry> entries){
            this.hash=hash;this.offset=offset;this.length=length;this.nameBytes=nameBytes;this.name=name;this.entries=entries;
        }
    }
    static final class Container {
        final byte[] bytes;
        final List<LocaleTable> locales;
        Container(byte[] bytes,List<LocaleTable> locales){this.bytes=bytes.clone();this.locales=locales;}
        byte[] resource(int index)throws IOException {
            for(LocaleTable locale:locales)if("default".equals(locale.name))return blob(bytes,locale.entries.get(index));
            throw new IOException("Нет default locale");
        }
    }
    private static final class Extent {
        final int offset,length;
        final boolean table;
        Extent(int offset,int length,boolean table){this.offset=offset;this.length=length;this.table=table;}
    }
    static Container parse(byte[] bytes)throws IOException {
        require(bytes.length<=MAX_PACKAGE,"Пакет превышает 1 МиБ");magic(bytes,0,new byte[]{'C','R',1,0});
        int count=positive32(bytes,4);require(count==23,"Ожидалось 23 локали");int outer=8+count*20;range(bytes,0,outer);
        List<LocaleTable> locales=new ArrayList<>();List<Extent> extents=new ArrayList<>();
        extents.add(new Extent(0,outer,true));Map<String,Boolean> names=new HashMap<>();
        for(int i=0;i<count;i++) {
            int p=8+i*20,hash=i32(bytes,p),offset=positive32(bytes,p+4),length=positive32(bytes,p+8);
            byte[] nameBytes=Arrays.copyOfRange(bytes,p+12,p+20);int n=0;
            while(n<8&&nameBytes[n]!=0){require((nameBytes[n]&255)<128,"Название локали");n++;}
            for(int j=n;j<8;j++)require(nameBytes[j]==0,"Padding локали");
            String name=new String(nameBytes,0,n,StandardCharsets.US_ASCII);
            require(names.put(name,true)==null&&bkdr(name)==hash,"Хеш или повтор локали");
            range(bytes,offset,length);magic(bytes,offset,new byte[]{'C','R',2,0});
            int resources=positive32(bytes,offset+4);require(resources==71&&length==8+resources*16,"Таблица ресурсов");
            extents.add(new Extent(offset,length,true));List<Entry> entries=new ArrayList<>();
            for(int k=0;k<resources;k++) {
                int q=offset+8+k*16;Entry e=new Entry(i32(bytes,q),positive32(bytes,q+4),positive32(bytes,q+8),i32(bytes,q+12));
                range(bytes,e.offset,e.length);require(e.length>0,"Пустой ресурс");entries.add(e);extents.add(new Extent(e.offset,e.length,false));
            }
            locales.add(new LocaleTable(hash,offset,length,nameBytes,name,entries));
        }
        extents.sort(Comparator.comparingInt((Extent e)->e.offset).thenComparingInt(e->e.length));
        for(int i=1;i<extents.size();i++) {
            Extent a=extents.get(i-1),b=extents.get(i);
            if(b.offset<a.offset+a.length)require(a.offset==b.offset&&a.length==b.length&&a.table==b.table,"Пересечение частей контейнера");
        }
        require(names.containsKey("default"),"Нет default locale");return new Container(bytes,locales);
    }
    static byte[] rebuild(Container donor,Map<Integer,byte[]> replacements)throws IOException {
        byte[] raw=donor.bytes;int outer=8+donor.locales.size()*20;
        Map<Integer,byte[]> resources=new LinkedHashMap<>();Map<Integer,LocaleTable> tables=new LinkedHashMap<>();
        for(LocaleTable locale:donor.locales){tables.put(locale.offset,locale);for(Entry e:locale.entries){
            byte[] original=blob(raw,e),previous=resources.putIfAbsent(e.offset,original);
            require(previous==null||Arrays.equals(previous,original),"Несогласованный alias ресурса");}}
        for(Integer offset:replacements.keySet())require(resources.containsKey(offset),"Неизвестный ресурс для замены");
        List<Extent> occupied=new ArrayList<>();
        for(Map.Entry<Integer,byte[]> e:resources.entrySet())occupied.add(new Extent(e.getKey(),e.getValue().length,false));
        for(LocaleTable table:tables.values())occupied.add(new Extent(table.offset,table.length,true));
        occupied.sort(Comparator.comparingInt(e->e.offset));Map<Integer,Integer> relocated=new HashMap<>();
        int oldCursor=outer,newCursor=outer;
        for(Extent e:occupied){require(e.offset>=oldCursor,"Пересечение donor частей");newCursor+=e.offset-oldCursor;
            relocated.put(e.offset,newCursor);byte[] replacement=replacements.get(e.offset);
            newCursor+=!e.table&&replacement!=null?replacement.length:e.length;oldCursor=e.offset+e.length;
            require(newCursor<=MAX_PACKAGE,"Пакет превышает 1 МиБ");}
        require(newCursor+raw.length-oldCursor<=MAX_PACKAGE,"Пакет превышает 1 МиБ");
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] header=Arrays.copyOf(raw,outer);
        for(int i=0;i<donor.locales.size();i++)put32(header,8+i*20+4,relocated.get(donor.locales.get(i).offset));
        out.write(header);oldCursor=outer;
        for(Extent e:occupied){out.write(raw,oldCursor,e.offset-oldCursor);
            if(!e.table){byte[] replacement=replacements.get(e.offset);out.write(replacement==null?resources.get(e.offset):replacement);}
            else{LocaleTable table=tables.get(e.offset);byte[] bytes=Arrays.copyOfRange(raw,e.offset,e.offset+e.length);
                for(int i=0;i<table.entries.size();i++){Entry entry=table.entries.get(i);int p=8+i*16;
                    put32(bytes,p+4,relocated.get(entry.offset));byte[] replacement=replacements.get(entry.offset);
                    put32(bytes,p+8,replacement==null?entry.length:replacement.length);}
                out.write(bytes);}
            oldCursor=e.offset+e.length;
        }
        out.write(raw,oldCursor,raw.length-oldCursor);byte[] result=out.toByteArray();parse(result);return result;
    }
    static byte[] blob(byte[] bytes,Entry e){return Arrays.copyOfRange(bytes,e.offset,e.offset+e.length);}
    static int bkdr(String text){int hash=0;for(byte b:text.getBytes(StandardCharsets.UTF_8))hash=hash*131+(b&255);return hash;}
    static void require(boolean condition,String reason)throws IOException{if(!condition)throw new IOException(reason);}
    static void range(byte[] b,int p,int n)throws IOException{require(p>=0&&n>=0&&(long)p+n<=b.length,"Чтение за границей данных");}
    static void magic(byte[] b,int p,byte[] magic)throws IOException{range(b,p,magic.length);for(int i=0;i<magic.length;i++)require(b[p+i]==magic[i],"Неверная сигнатура");}
    static int u16(byte[] b,int p)throws IOException{range(b,p,2);return (b[p]&255)|((b[p+1]&255)<<8);}
    static int i32(byte[] b,int p)throws IOException{range(b,p,4);return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|(b[p+3]<<24);}
    static int positive32(byte[] b,int p)throws IOException{int n=i32(b,p);require(n>=0,"Размер вне диапазона");return n;}
    static void put16(byte[] b,int p,int value){b[p]=(byte)value;b[p+1]=(byte)(value>>>8);}
    static void put32(byte[] b,int p,int value){for(int i=0;i<4;i++)b[p+i]=(byte)(value>>>(8*i));}
}
