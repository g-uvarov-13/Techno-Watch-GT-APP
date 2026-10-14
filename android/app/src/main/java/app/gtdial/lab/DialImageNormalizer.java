package app.gtdial.lab;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class DialImageNormalizer {
    static final int MAX_BYTES=20*1024*1024;
    static final long MAX_PIXELS=16_000_000L;
    static final class Result {
        final byte[] png;
        final DialProject.Background background;
        Result(byte[] png,int w,int h) {
            this.png=png;int side=Math.min(w,h);
            background=new DialProject.Background(hash(png),w,h,(w-side)/2,(h-side)/2,side);
        }
    }
    static Result normalize(ContentResolver resolver,Uri uri) throws IOException {
        byte[] bytes;
        try(InputStream in=resolver.openInputStream(uri)) {
            if(in==null) throw new IOException("Не удалось открыть фотографию");
            bytes=read(in,MAX_BYTES);
        }catch(OutOfMemoryError e){throw new IOException("Недостаточно памяти для фотографии; выберите меньшую",e);}
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        checkDimensions(bounds.outWidth,bounds.outHeight);
        if(Build.VERSION.SDK_INT<30&&hasUnsupportedExif(bytes,bounds.outMimeType))
            throw new IOException("Эта версия Android не читает ориентацию PNG/WebP. Выберите JPEG или фотографию с уже применённым поворотом.");
        int orientation=ExifInterface.ORIENTATION_NORMAL;
        // Platform ExifInterface is available at minSdk26; no extra dependency is needed.
        if("image/jpeg".equals(bounds.outMimeType)||"image/png".equals(bounds.outMimeType)
            ||"image/webp".equals(bounds.outMimeType)||"image/heif".equals(bounds.outMimeType)) {
            try { orientation=new ExifInterface(new ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL); }
            catch(IOException e) { throw new IOException("Не удалось прочитать ориентацию фотографии",e); }
        }
        Bitmap raw=null,oriented=null,opaque=null;
        try {
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            raw=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            if(raw==null) throw new IOException("Не удалось прочитать фотографию");
            Matrix matrix=new Matrix();
            switch(orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1,1);break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180);break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1,-1);break;
                case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90);matrix.postScale(-1,1);break;
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90);break;
                case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(-90);matrix.postScale(-1,1);break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(-90);break;
                default: break;
            }
            oriented=Bitmap.createBitmap(raw,0,0,raw.getWidth(),raw.getHeight(),matrix,true);
            opaque=Bitmap.createBitmap(oriented.getWidth(),oriented.getHeight(),Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(opaque);c.drawColor(Color.BLACK);c.drawBitmap(oriented,0,0,new Paint(3));
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            if(!opaque.compress(Bitmap.CompressFormat.PNG,100,out)||out.size()>MAX_BYTES)
                throw new IOException("Нормализованная фотография превышает 20 МиБ");
            return new Result(out.toByteArray(),opaque.getWidth(),opaque.getHeight());
        } catch(OutOfMemoryError e) { throw new IOException("Недостаточно памяти для фотографии; выберите меньшую",e); }
        finally {
            if(opaque!=null)opaque.recycle();
            if(oriented!=null&&oriented!=raw)oriented.recycle();
            if(raw!=null)raw.recycle();
        }
    }
    static Bitmap renderBackground(Bitmap source,DialProject.Background bg) {
        Bitmap out=Bitmap.createBitmap(466,466,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(out);canvas.drawColor(Color.BLACK);
        if(source!=null&&bg!=null) canvas.drawBitmap(source,new Rect(bg.x,bg.y,bg.x+bg.size,bg.y+bg.size),
            new Rect(0,0,466,466),new Paint(3));
        return out;
    }
    static void checkDimensions(int width,int height) throws IOException {
        if(width<=0||height<=0||(long)width*height>MAX_PIXELS)
            throw new IOException("Фотография должна содержать не более 16 миллионов пикселей");
    }
    private static boolean hasUnsupportedExif(byte[] bytes,String mime) throws IOException {
        if("image/png".equals(mime)) {
            int offset=8;
            while(offset+12<=bytes.length) {
                long length=unsigned(bytes,offset,false);
                if(length>bytes.length-offset-12L)throw new IOException("Повреждена структура PNG");
                if(tag(bytes,offset+4,"eXIf"))return true;
                offset+=(int)length+12;
            }
        }else if("image/webp".equals(mime)) {
            int offset=12;
            while(offset+8<=bytes.length) {
                long length=unsigned(bytes,offset+4,true);
                if(length>bytes.length-offset-8L)throw new IOException("Повреждена структура WebP");
                if(tag(bytes,offset,"EXIF"))return true;
                long next=offset+8L+length+(length&1);
                if(next>bytes.length)throw new IOException("Повреждена структура WebP");
                offset=(int)next;
            }
        }
        return false;
    }
    private static long unsigned(byte[] bytes,int offset,boolean littleEndian) {
        long value=0;
        for(int i=0;i<4;i++)value=(value<<8)|(bytes[offset+(littleEndian?3-i:i)]&255L);
        return value;
    }
    private static boolean tag(byte[] bytes,int offset,String tag) {
        for(int i=0;i<4;i++)if((bytes[offset+i]&255)!=tag.charAt(i))return false;
        return true;
    }
    static byte[] read(InputStream in,int maximum) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];
        for(int n;(n=in.read(buffer))!=-1;) {
            if(out.size()>maximum-n) throw new IOException("Файл превышает допустимый размер");
            out.write(buffer,0,n);
        }
        return out.toByteArray();
    }
    static String hash(byte[] bytes) {
        try {
            StringBuilder out=new StringBuilder();
            for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.US,"%02x",b&255));
            return out.toString();
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private DialImageNormalizer() {}
}
