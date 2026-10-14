package app.gtdial.lab;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Secrets never enter preferences, Bundles, diagnostics or archives. Removal retains only a revision. */
final class OpenRouterCredentialStore {
    private static OpenRouterCredentialStore instance;
    private static final String ALIAS="gtdial.openrouter.byok.v1";
    private final AtomicFile file;
    private volatile long revision;
    private boolean unavailable;
    private Runnable invalidated=()->{};
    static synchronized OpenRouterCredentialStore get(Context context){if(instance==null)instance=new OpenRouterCredentialStore(context);return instance;}
    private OpenRouterCredentialStore(Context context){file=new AtomicFile(new File(context.getApplicationContext().getNoBackupFilesDir(),"openrouter-credential.v1"));try{JSONObject data=read();revision=data==null?0:NutritionModels.integer(data,"revision",0,Long.MAX_VALUE-1);}catch(RuntimeException e){unavailable=true;revision=0;}}
    synchronized void onChanged(Runnable callback){invalidated=callback;}
    long revision(){return revision;}
    synchronized boolean hasKey(){if(unavailable)return false;try{JSONObject data=read();return data!=null&&!data.isNull("ciphertext");}catch(RuntimeException e){unavailable=true;return false;}}
    synchronized String key(){try{JSONObject data=read();if(data==null||data.isNull("ciphertext"))throw new IllegalStateException("KEY_MISSING");byte[] iv=android.util.Base64.decode(data.getString("iv"),android.util.Base64.NO_WRAP),bytes=android.util.Base64.decode(data.getString("ciphertext"),android.util.Base64.NO_WRAP);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,secret(),new GCMParameterSpec(128,iv));cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(bytes),StandardCharsets.UTF_8);}catch(IllegalStateException e){if("KEY_MISSING".equals(e.getMessage()))throw e;throw new IllegalStateException("KEY_UNAVAILABLE");}catch(Exception e){throw new IllegalStateException("KEY_UNAVAILABLE");}}
    synchronized void save(String value){String key=value.trim();if(key.length()<8||key.length()>512||!key.matches("[A-Za-z0-9._:-]+"))throw new IllegalArgumentException("KEY_FORMAT");try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secret());cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));byte[] encrypted=cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));write(NutritionModels.object("revision",Math.addExact(revision,1),"iv",android.util.Base64.encodeToString(cipher.getIV(),android.util.Base64.NO_WRAP),"ciphertext",android.util.Base64.encodeToString(encrypted,android.util.Base64.NO_WRAP)));revision++;invalidated.run();}catch(Exception e){throw new IllegalStateException("KEY_SAVE_FAILED");}}
    synchronized void remove(){write(NutritionModels.object("revision",Math.addExact(revision,1),"iv",null,"ciphertext",null));revision++;invalidated.run();}
    private JSONObject read(){try{if(!file.getBaseFile().exists())return null;try(java.io.InputStream input=file.openRead()){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[512];int n;while((n=input.read(buffer))!=-1){if(out.size()+n>4096)throw new IllegalStateException();out.write(buffer,0,n);}return NutritionModels.parseObject(out.toString(StandardCharsets.UTF_8.name()));}}catch(IllegalStateException e){if("KEY_MISSING".equals(e.getMessage()))throw e;throw new IllegalStateException("KEY_UNAVAILABLE");}catch(Exception e){throw new IllegalStateException("KEY_UNAVAILABLE");}}
    private void write(JSONObject data){FileOutputStream stream=null;try{stream=file.startWrite();stream.write(data.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(stream);unavailable=false;}catch(Exception e){if(stream!=null)file.failWrite(stream);throw new IllegalStateException("KEY_STORE_WRITE_FAILED");}}
    private static SecretKey secret()throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(ALIAS))return ((KeyStore.SecretKeyEntry)store.getEntry(ALIAS,null)).getSecretKey();KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());return generator.generateKey();}
}

