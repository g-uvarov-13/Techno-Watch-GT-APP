package app.gtdial.lab;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** Display metadata only. It never grants ownership, upload, recovery or write permission. */
final class LocalDialPreviewIndex {
    private final SharedPreferences preferences;
    LocalDialPreviewIndex(Context context){preferences=context.getSharedPreferences("local_dial_previews",Context.MODE_PRIVATE);}
    synchronized void remember(String address,String packageId,String alias,String artifact){
        if(address==null||!address.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")||artifact==null||!artifact.matches("[0-9a-f]{64}"))return;
        try{
            JSONArray old=new JSONArray(preferences.getString("entries","[]")),next=new JSONArray();
            next.put(new JSONObject().put("address",address).put("packageId",packageId).put("alias",alias).put("artifact",artifact));
            for(int i=0;i<old.length()&&next.length()<64;i++){JSONObject entry=old.getJSONObject(i);if(address.equals(entry.optString("address"))&&packageId.equals(entry.optString("packageId"))&&alias.equals(entry.optString("alias")))continue;next.put(entry);}
            preferences.edit().putString("entries",next.toString()).apply();
        }catch(Exception ignored){/* A missing display index must not alter successful watch installation. */}
    }
    JSONArray entries(String address){
        JSONArray result=new JSONArray();
        try{JSONArray saved=new JSONArray(preferences.getString("entries","[]"));for(int i=0;i<saved.length()&&i<64;i++){JSONObject entry=saved.getJSONObject(i);if(address.equals(entry.optString("address")))result.put(entry);}}
        catch(Exception ignored){/* Corrupt display metadata resolves to unknown. */}return result;
    }
}
