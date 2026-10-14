package app.gtdial.lab;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Exact observed format6/compression1/466x466/stride480 encoder and bounded LZ4 decoder. */
final class Gt01PgfCodec {
    static final int PIXEL_BYTES=466*466*4, PLANE_BYTES=480*466;
    static byte[] encode(byte[] donor,byte[] bgra)throws IOException {
        decode(donor);Gt01DialContainerCodec.require(bgra.length==PIXEL_BYTES,"Размер BGRA");
        boolean[] used=new boolean[256];int[] codes=new int[466*466];
        for(int i=0;i<codes.length;i++){
            int p=i*4;Gt01DialContainerCodec.require((bgra[p+3]&255)==255,"BGRA должен быть непрозрачным");
            codes[i]=((bgra[p+2]&255)>>5)<<5|((bgra[p+1]&255)>>5)<<2|((bgra[p]&255)>>6);used[codes[i]]=true;
        }
        int count=0;int[] index=new int[256];for(int code=0;code<256;code++)if(used[code])index[code]=count++;
        byte[] payload=new byte[PLANE_BYTES+count*4];
        for(int y=0;y<466;y++)for(int x=0;x<466;x++)payload[y*480+x]=(byte)index[codes[y*466+x]];
        for(int code=0;code<256;code++)if(used[code]){
            int p=PLANE_BYTES+index[code]*4;payload[p]=(byte)((code&3)*255/3);
            payload[p+1]=(byte)(((code>>2)&7)*255/7);payload[p+2]=(byte)(((code>>5)&7)*255/7);payload[p+3]=(byte)255;
        }
        ByteArrayOutputStream packed=new ByteArrayOutputStream();packed.write(Math.min(payload.length,15)<<4);
        if(payload.length>=15){int remaining=payload.length-15;while(remaining>=255){packed.write(255);remaining-=255;}packed.write(remaining);}
        packed.write(payload);byte[] header=Arrays.copyOf(donor,46);
        Gt01DialContainerCodec.put16(header,36,count);Gt01DialContainerCodec.put32(header,38,payload.length);
        Gt01DialContainerCodec.put32(header,42,packed.size());ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(header);packed.writeTo(out);
        byte[] bytes=out.toByteArray();decode(bytes);return bytes;
    }
    static byte[] decode(byte[] bytes)throws IOException {
        Gt01DialContainerCodec.require(bytes.length>=46&&bytes.length<=1024*1024,"PGF размер");
        Gt01DialContainerCodec.magic(bytes,0,new byte[]{'P','G','F',0});
        Gt01DialContainerCodec.require(Gt01DialContainerCodec.u16(bytes,28)==466&&Gt01DialContainerCodec.u16(bytes,30)==466
            &&Gt01DialContainerCodec.u16(bytes,32)==480&&bytes[34]==6&&bytes[35]==1,"PGF профиль");
        int palette=Gt01DialContainerCodec.u16(bytes,36),size=Gt01DialContainerCodec.positive32(bytes,38),packed=Gt01DialContainerCodec.positive32(bytes,42);
        Gt01DialContainerCodec.require(palette>=1&&palette<=256&&size==PLANE_BYTES+palette*4&&packed==bytes.length-46,"PGF размеры");
        byte[] payload=lz4(bytes,46,size),bgra=new byte[PIXEL_BYTES];
        for(int i=0;i<palette;i++)Gt01DialContainerCodec.require((payload[PLANE_BYTES+i*4+3]&255)==255,"PGF прозрачность");
        for(int y=0;y<466;y++){
            for(int x=0;x<466;x++){int code=payload[y*480+x]&255;Gt01DialContainerCodec.require(code<palette,"PGF индекс палитры");
                System.arraycopy(payload,PLANE_BYTES+code*4,bgra,(y*466+x)*4,4);}
            for(int x=466;x<480;x++)Gt01DialContainerCodec.require(payload[y*480+x]==0,"PGF padding");
        }
        return bgra;
    }
    private static byte[] lz4(byte[] src,int p,int expected)throws IOException {
        byte[] out=new byte[expected];int written=0;
        while(p<src.length){int token=src[p++]&255,literal=token>>>4;
            if(literal==15){int extension;do{Gt01DialContainerCodec.require(p<src.length,"LZ4 literal length");extension=src[p++]&255;
                literal+=extension;Gt01DialContainerCodec.require(literal<=expected,"LZ4 literal limit");}while(extension==255);}
            Gt01DialContainerCodec.range(src,p,literal);Gt01DialContainerCodec.require(literal<=expected-written,"LZ4 literal output");
            System.arraycopy(src,p,out,written,literal);p+=literal;written+=literal;if(p==src.length)break;
            int offset=Gt01DialContainerCodec.u16(src,p);p+=2;
            Gt01DialContainerCodec.require(offset>0&&offset<=written,"LZ4 match offset");int match=(token&15)+4;
            if((token&15)==15){int extension;do{Gt01DialContainerCodec.require(p<src.length,"LZ4 match length");extension=src[p++]&255;
                match+=extension;Gt01DialContainerCodec.require(match<=expected,"LZ4 match limit");}while(extension==255);}
            Gt01DialContainerCodec.require(match<=expected-written,"LZ4 match output");
            for(int i=0;i<match;i++){out[written]=out[written-offset];written++;}
        }
        Gt01DialContainerCodec.require(written==expected&&p==src.length,"LZ4 EOF");return out;
    }
    static byte[] bitmapBytes(Bitmap bitmap)throws IOException {
        Gt01DialContainerCodec.require(bitmap.getWidth()==466&&bitmap.getHeight()==466,"Размер фона");
        int[] pixels=new int[466*466];bitmap.getPixels(pixels,0,466,0,0,466,466);byte[] bytes=new byte[PIXEL_BYTES];
        for(int i=0;i<pixels.length;i++){int value=pixels[i],p=i*4;bytes[p]=(byte)value;bytes[p+1]=(byte)(value>>>8);
            bytes[p+2]=(byte)(value>>>16);bytes[p+3]=(byte)(value>>>24);}
        return bytes;
    }
    static Bitmap bitmap(byte[] bgra)throws IOException {
        Gt01DialContainerCodec.require(bgra.length==PIXEL_BYTES,"Размер BGRA");int[] pixels=new int[466*466];
        for(int i=0;i<pixels.length;i++){int p=i*4;pixels[i]=(bgra[p]&255)|((bgra[p+1]&255)<<8)|((bgra[p+2]&255)<<16)|(bgra[p+3]<<24);}
        return Bitmap.createBitmap(pixels,466,466,Bitmap.Config.ARGB_8888);
    }
    static Bitmap quantizedPreview(Bitmap background)throws IOException {
        Gt01DialContainerCodec.require(background.getWidth()==466&&background.getHeight()==466,"Размер предпросмотра");
        int[] pixels=new int[466*466];background.getPixels(pixels,0,466,0,0,466,466);
        // Exactly equivalent to encode/decode RGB332; keep gesture frames free of LZ4/plane allocations.
        for(int i=0;i<pixels.length;i++){int color=pixels[i];
            Gt01DialContainerCodec.require((color>>>24)==255,"Прозрачный предпросмотр");
            int red=((color>>>16)&255)>>5,green=((color>>>8)&255)>>5,blue=(color&255)>>6;
            pixels[i]=0xff000000|(red*255/7)<<16|(green*255/7)<<8|(blue*255/3);}
        return Bitmap.createBitmap(pixels,466,466,Bitmap.Config.ARGB_8888);
    }
    private Gt01PgfCodec(){}
}
