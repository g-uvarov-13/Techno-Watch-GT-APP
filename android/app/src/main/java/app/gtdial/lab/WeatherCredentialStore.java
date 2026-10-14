package app.gtdial.lab;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-bound, non-backed-up credential; preferences contain only an invalidation revision. */
final class WeatherCredentialStore {
    static final String REVISION_PREF="weather.yandex-key-revision";
    private static final String ALIAS="gt-dial-weather-yandex-v1";
    private static AtomicFile file(Context c){return new AtomicFile(new File(c.getNoBackupFilesDir(),"weather-yandex-key-v1"));}
    private static SecretKey key()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    static synchronized String load(Context context)throws IOException{
        AtomicFile atomic=file(context);
        if(!atomic.getBaseFile().exists()&&!new File(atomic.getBaseFile().getPath()+".bak").exists())return "";
        try(InputStream input=atomic.openRead()){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[1024];
            for(int n;(n=input.read(buffer))!=-1;){if(bytes.size()+n>1024)throw new IOException("Credential size");bytes.write(buffer,0,n);}
            DataInputStream data=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
            if(data.readInt()!=1)throw new IOException("Credential version");
            int ivLength=data.readUnsignedByte();if(ivLength!=12)throw new IOException("Credential IV");
            byte[] iv=new byte[ivLength];data.readFully(iv);byte[] ciphertext=new byte[data.available()];data.readFully(ciphertext);
            if(ciphertext.length<16||ciphertext.length>528)throw new IOException("Credential ciphertext");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            String value=new String(cipher.doFinal(ciphertext),StandardCharsets.UTF_8);
            if(!validate(value).equals(value))throw new IOException("Credential format");return value;
        }catch(Exception e){throw new IOException("Credential unavailable");}
    }
    private static String validate(String value){
        if(value==null)throw new IllegalArgumentException("Credential missing");String clean=value.trim();
        if(clean.length()>512)throw new IllegalArgumentException("Credential length");
        for(int i=0;i<clean.length();i++)if(clean.charAt(i)<33||clean.charAt(i)>126)throw new IllegalArgumentException("Credential format");
        return clean;
    }
    static synchronized boolean save(Context context,String value)throws IOException{
        String clean=validate(value);
        try{if(load(context).equals(clean))return false;}catch(IOException unreadable){/* Allow replacement of a damaged credential. */}
        byte[] encoded;
        try{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
            byte[] iv=cipher.getIV();if(iv.length!=12)throw new IOException("Credential IV");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream data=new DataOutputStream(bytes);
            data.writeInt(1);data.writeByte(iv.length);data.write(iv);data.write(cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8)));encoded=bytes.toByteArray();
        }catch(Exception e){throw new IOException("Credential encryption unavailable");}
        SharedPreferences preferences=context.getSharedPreferences("gt_dial_preferences",Context.MODE_PRIVATE);
        long revision=preferences.getLong(REVISION_PREF,0);
        if(revision<0||revision==Long.MAX_VALUE||!preferences.edit().putLong(REVISION_PREF,revision+1).commit())throw new IOException("Credential revision unavailable");
        AtomicFile atomic=file(context);FileOutputStream output=null;
        try{output=atomic.startWrite();output.write(encoded);atomic.finishWrite(output);return true;}
        catch(Exception e){if(output!=null)atomic.failWrite(output);throw new IOException("Credential save unavailable");}
    }
}
