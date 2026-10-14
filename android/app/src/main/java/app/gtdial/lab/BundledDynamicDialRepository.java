package app.gtdial.lab;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the presentation catalog, while package writes use a separate compiled allow-list. */
public final class BundledDynamicDialRepository {
    interface CoordinateLogger { void log(String line); }
    private static final String REGISTRY_SHA256="19ed85dad6c61446edd617c46811bb3b2599da8bfe6a3faba7984e77c3b26a0a";
    private static final String SHARED_COORDINATES_SHA256="426704a20a68e3973cd02f0f3aa748679a54447756c2090208a4efbae68d8bf7";
    private static final String LEGACY_SHA256="928f548c744539c751f20c2d11771f21c052923beab258056d3f6ff21f219e3e";
    private static final String LEGACY_BASE_SHA256="dfaac700817e3d363c7a03e8f6b053c1e7daf8b869e2a6c800fdcd782123edad";
    private static final String LEGACY_ASSET="dynamic_dials/pipboy01/face.dial";
    private static final Map<String,BundledDynamicDial.PackageVariant> COMPILED_VARIANTS=compiledVariants();
    private final Context context;
    private final List<BundledDynamicDial> items;

    public BundledDynamicDialRepository(Context context)throws IOException{
        this.context=context.getApplicationContext();
        try{
            verifyRegistryAsset();
            JSONObject root=new JSONObject(new String(read(context,"dynamic_dials/catalog.json"),java.nio.charset.StandardCharsets.UTF_8));
            if(root.getInt("schemaVersion")!=2)throw new IOException("Unsupported bundled catalog");
            JSONArray source=root.optJSONArray("items");if(source==null||source.length()!=14)throw new IOException("Invalid bundled catalog items");
            java.util.ArrayList<BundledDynamicDial> parsed=new java.util.ArrayList<>();java.util.HashSet<String> faceIds=new java.util.HashSet<>();
            for(int i=0;i<source.length();i++){
                JSONObject item=source.getJSONObject(i);String id=item.getString("id");if(!faceIds.add(id))throw new IOException("Duplicate bundled dial id");
                JSONArray rawPalettes=item.getJSONArray("palettes");int expectedPalettes="base3000".equals(id)?1:6;if(rawPalettes.length()!=expectedPalettes)throw new IOException("Invalid dial palettes");
                BundledDynamicDial.Palette[] palettes=new BundledDynamicDial.Palette[expectedPalettes];java.util.HashSet<String> paletteIds=new java.util.HashSet<>();
                for(int x=0;x<expectedPalettes;x++){JSONObject p=rawPalettes.getJSONObject(x),c=p.getJSONObject("colors");palettes[x]=new BundledDynamicDial.Palette(p.getString("id"),p.getString("name"),c.getString("background"),c.getString("primary"),c.getString("secondary"),c.getString("accent"),c.getString("muted"));if(!paletteIds.add(palettes[x].id))throw new IOException("Duplicate palette id");}
                if(expectedPalettes==1&&!paletteIds.contains("phosphor"))throw new IOException("Invalid shared-base palette");
                String faceId="pipboy01".equals(id)?null:id;
                Map<String,BundledDynamicDial.PackageVariant> variants=variantsFor(faceId,paletteIds);
                String legacyAsset=null,legacyHash=null,baseHash=null,legacyInternal=null,legacyPalette=null;long legacySize=0;
                if("pipboy01".equals(id)){
                    legacyPalette=item.optString("installablePaletteId","");legacyAsset=item.optString("packageAsset","");legacyHash=item.optString("packageSha256","").toLowerCase(java.util.Locale.ROOT);legacySize=item.optLong("packageSize",0);legacyInternal=item.optString("internalPackageId","");String alias=item.optString("alias","");baseHash=item.optString("basePackageSha256","");
                    if(!"phosphor".equals(legacyPalette)||!LEGACY_ASSET.equals(legacyAsset)||!LEGACY_SHA256.equals(legacyHash)||legacySize!=536153L||!"com.gtdial.pipboy01".equals(legacyInternal)||!BundledDynamicDial.REMOTE_ALIAS.equals(alias)||!LEGACY_BASE_SHA256.equals(baseHash)||!item.optBoolean("physicalInstallAllowed",false))throw new IOException("Legacy package descriptor mismatch");
                    BundledDynamicDial.PackageVariant legacy=new BundledDynamicDial.PackageVariant("pipboy01","phosphor",LEGACY_ASSET,LEGACY_SHA256,536153L,"com.gtdial.pipboy01",BundledDynamicDial.REMOTE_ALIAS);
                    variants.put("phosphor",legacy);
                }else if(item.optBoolean("physicalInstallAllowed",false))throw new IOException("Catalog cannot grant physical installation");
                JSONArray rawFields=item.getJSONArray("displayFields");String[] fields=new String[rawFields.length()];for(int x=0;x<fields.length;x++)fields[x]=rawFields.getString(x);
                BundledDynamicDial d=new BundledDynamicDial(id,item.getString("name"),item.getString("previewAsset"),item.getString("renderSpecAsset"),fields,palettes,item.getString("defaultPaletteId"),legacyPalette,legacyAsset,legacyHash,baseHash,legacyInternal,legacySize,false,variants);
                read(context,d.previewAsset);read(context,d.renderSpecAsset);parsed.add(d);
            }
            if(!faceIds.containsAll(java.util.Arrays.asList("pipboy01","pipboy3000","bitax","pixbot","base3000","dial3000","dial3000walk")))throw new IOException("Bundled dial set mismatch");
            items=Collections.unmodifiableList(parsed);
        }catch(org.json.JSONException|IllegalArgumentException e){throw new IOException("Invalid bundled dial metadata",e);}
    }

    private static Map<String,BundledDynamicDial.PackageVariant> variantsFor(String faceId,java.util.Set<String> paletteIds)throws IOException{
        Map<String,BundledDynamicDial.PackageVariant> result=new LinkedHashMap<>();if(faceId==null)return result;
        for(BundledDynamicDial.PackageVariant v:COMPILED_VARIANTS.values())if(v.faceId.equals(faceId)&&paletteIds.contains(v.paletteId))result.put(v.paletteId,v);
        if(result.size()!=paletteIds.size()||!result.keySet().equals(paletteIds))throw new IOException("Compiled package variants do not match catalog palettes: "+faceId);
        return result;
    }

    private void verifyRegistryAsset()throws IOException{
        byte[] bytes=read(context,"dynamic_dials/offline-package-registry.json");if(!REGISTRY_SHA256.equals(sha256(bytes)))throw new IOException("Offline package registry hash mismatch");
        try{JSONObject root=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));JSONArray records=root.getJSONArray("items");if(root.getInt("schemaVersion")!=1||records.length()!=73)throw new IOException("Offline package registry shape mismatch");java.util.HashSet<String> seen=new java.util.HashSet<>();
            for(int i=0;i<records.length();i++){JSONObject o=records.getJSONObject(i);String key=key(o.getString("faceId"),o.getString("paletteId"));BundledDynamicDial.PackageVariant expected=COMPILED_VARIANTS.get(key);if(expected==null||!seen.add(key)||!expected.packageAsset.equals(o.getString("packageAsset"))||!expected.packageSha256.equalsIgnoreCase(o.getString("packageSha256"))||expected.packageSize!=o.getLong("packageSize")||!expected.internalPackageId.equals(o.getString("internalPackageId"))||!expected.alias.equals(o.getString("alias")))throw new IOException("Offline package registry entry mismatch: "+key);}
            if(seen.size()!=COMPILED_VARIANTS.size())throw new IOException("Offline package registry incomplete");
        }catch(org.json.JSONException e){throw new IOException("Malformed offline package registry",e);}
    }

    public List<BundledDynamicDial> list(){return items;}
    public BundledDynamicDial.VerifiedPackage packageForPreview(BundledDynamicDial dial,String paletteId)throws IOException{
        BundledDynamicDial.PackageVariant variant=resolve(dial,paletteId);byte[] bytes=read(context,variant.packageAsset);if(bytes.length!=variant.packageSize||!sha256(bytes).equals(variant.packageSha256))throw new IOException("Bundled package verification failed");return new BundledDynamicDial.VerifiedPackage(variant,bytes);
    }
    static BundledDynamicDial.VerifiedPackage capturedAnimationForRecovery(Context context)throws IOException{
        BundledDynamicDial.PackageVariant variant=HISTORICAL_V10.get(key("dial3000walk","violet"));
        if(variant==null)throw new IOException("Captured animation descriptor missing");
        byte[] bytes=read(context,variant.packageAsset);
        if(bytes.length!=variant.packageSize||!sha256(bytes).equals(variant.packageSha256))throw new IOException("Captured animation asset mismatch");
        return new BundledDynamicDial.VerifiedPackage(variant,bytes);
    }
    public BundledDynamicDial.VerifiedPackage packageForInstall(BundledDynamicDial dial,String paletteId)throws IOException{
        return packageForInstall(dial,paletteId,null);
    }
    public BundledDynamicDial.VerifiedPackage packageForInstall(BundledDynamicDial dial,String paletteId,CoordinateLogger logger)throws IOException{
        resolve(dial,paletteId);
        BundledDynamicDial.VerifiedPackage result=packageForPreview(dial,paletteId);
        if("base3000".equals(dial.id)||"dial3000".equals(dial.id)||"dial3000walk".equals(dial.id))logSharedCoordinates(dial.id,paletteId,result.variant.packageSha256,logger);
        return result;
    }
    private void logSharedCoordinates(String faceId,String paletteId,String expectedPackageSha,CoordinateLogger logger)throws IOException{
        byte[] bytes=read(context,"dynamic_dials/shared-base-coordinate-map.json");
        if(!SHARED_COORDINATES_SHA256.equals(sha256(bytes)))throw new IOException("Shared coordinate map hash mismatch");
        try{
            JSONObject root=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            JSONObject face=root.getJSONObject("variants").getJSONObject("base3000".equals(faceId)?"base":faceId+"_"+paletteId);
            if(!expectedPackageSha.equals(face.getString("packageSha256")))throw new IOException("Shared coordinate map package mismatch");
            JSONArray nodes=face.getJSONArray("rootAndChildren");
            if(nodes.length()!=(("base3000".equals(faceId))?12:("dial3000walk".equals(faceId)?15:14)))throw new IOException("Shared coordinate node count mismatch");
            for(int i=0;i<nodes.length();i++){
                JSONObject item=nodes.getJSONObject(i);
                String line="GTDialCoords face="+faceId+" id="+item.getString("id")+" type="+item.getString("type")+" rect="+item.getJSONArray("rect")+" fontPx="+item.optInt("fontPixels",0)+" argb="+item.optString("argb","-");
                Log.i("GTDialCoords",line);
                if(logger!=null)logger.log(line);
            }
            String background="GTDialCoords face="+faceId+" background=images/background.png rect=[0,0,466,466] rasterParts=notIndependentNodes";
            Log.i("GTDialCoords",background);
            if(logger!=null)logger.log(background);
        }catch(org.json.JSONException e){throw new IOException("Invalid shared coordinate map",e);}
    }
    private BundledDynamicDial.PackageVariant resolve(BundledDynamicDial dial,String paletteId)throws IOException{
        if(dial==null||!items.contains(dial))throw new IOException("Dial is not part of the bundled catalog");BundledDynamicDial.PackageVariant variant=dial.packageVariant(paletteId);if(variant==null)throw new IOException("Dial palette has no compiled package descriptor");
        if(!"pipboy01".equals(dial.id)&&COMPILED_VARIANTS.get(key(variant.faceId,variant.paletteId))!=variant)throw new IOException("Package descriptor is not compiled in the allow-list");
        return variant;
    }
    static BundledDynamicDial.PackageVariant compiledVariant(String faceId,String paletteId)throws IOException{
        if("pipboy01".equals(faceId)&&"phosphor".equals(paletteId))return new BundledDynamicDial.PackageVariant("pipboy01","phosphor",LEGACY_ASSET,LEGACY_SHA256,536153L,"com.gtdial.pipboy01",BundledDynamicDial.REMOTE_ALIAS);
        BundledDynamicDial.PackageVariant variant=COMPILED_VARIANTS.get(key(faceId,paletteId));if(variant==null)throw new IOException("Receipt package is not in the compiled registry");return variant;
    }
    static BundledDynamicDial.PackageVariant historicalVariant(String faceId,String paletteId,String internalId,String alias,long size,String sha)throws IOException{
        BundledDynamicDial.PackageVariant v=matchingHistoricalVariant(faceId,paletteId,internalId,alias,size,sha);
        if(v==null)throw new IOException("Receipt package is not a pinned prior Dial Lab variant");
        return v;
    }
    static boolean isCompiledVariant(String internalId,String alias,long size,String sha){if(internalId==null||alias==null||sha==null)return false;for(BundledDynamicDial.PackageVariant v:COMPILED_VARIANTS.values())if(matches(v,internalId,alias,size,sha))return true;for(BundledDynamicDial.PackageVariant v:HISTORICAL_DIAL_LAB.values())if(matches(v,internalId,alias,size,sha))return true;for(BundledDynamicDial.PackageVariant v:HISTORICAL_DIAL_LAB_V4.values())if(matches(v,internalId,alias,size,sha))return true;for(BundledDynamicDial.PackageVariant v:HISTORICAL_V5.values())if(matches(v,internalId,alias,size,sha))return true;for(BundledDynamicDial.PackageVariant v:HISTORICAL_V6.values())if(matches(v,internalId,alias,size,sha))return true;for(BundledDynamicDial.PackageVariant v:HISTORICAL_V7.values())if(matches(v,internalId,alias,size,sha))return true;for(BundledDynamicDial.PackageVariant v:HISTORICAL_V8.values())if(matches(v,internalId,alias,size,sha))return true;for(BundledDynamicDial.PackageVariant v:HISTORICAL_V9.values())if(matches(v,internalId,alias,size,sha))return true;for(BundledDynamicDial.PackageVariant v:HISTORICAL_V10.values())if(matches(v,internalId,alias,size,sha))return true;for(BundledDynamicDial.PackageVariant v:HISTORICAL_V11.values())if(matches(v,internalId,alias,size,sha))return true;return "com.gtdial.pipboy01".equals(internalId)&&BundledDynamicDial.REMOTE_ALIAS.equals(alias)&&size==536153L&&LEGACY_SHA256.equalsIgnoreCase(sha);}

    private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_DIAL_LAB=historicalDialLab();
    private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_DIAL_LAB_V4=historicalDialLabV4();
    private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V5=historicalV5();
    private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V6=historicalV6();
    private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V7=historicalV7();
    private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V8=historicalV8();
    private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V9=historicalV9();
    private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V10=historicalV10();
    private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V11=historicalV11();
    private static Map<String,BundledDynamicDial.PackageVariant> historicalV11(){Map<String,BundledDynamicDial.PackageVariant> m=new LinkedHashMap<>();BundledDynamicDial.PackageVariant[] all={
new BundledDynamicDial.PackageVariant("bitax","amber","dynamic_dials/previous-v11/bitax/amber.dial","5aea65c92bbcaa56f78d49771a04a10d3433afb4da674ceb82520c3b342e2dfb",475847L,"com.gtdial.nixiecore","2309202602"),
new BundledDynamicDial.PackageVariant("bitax","copper","dynamic_dials/previous-v11/bitax/copper.dial","3ad5290b6c4e7a3fff59d81058d200a5b18512211580214f1a5c002504bac5c8",475851L,"com.gtdial.nx.copper","2309202609"),
new BundledDynamicDial.PackageVariant("bitax","redorange","dynamic_dials/previous-v11/bitax/redorange.dial","05489457ae41f375a5e3f01e7334c8880e6b9ced75f686447213df083dd56bc3",475843L,"com.gtdial.nx.redorange","2309202610"),
new BundledDynamicDial.PackageVariant("bitax","gold","dynamic_dials/previous-v11/bitax/gold.dial","2f075c7bc3912097b83692871181d6e913b981fd23c000dbdcdde549c5839e3b",475863L,"com.gtdial.nx.gold","2309202611"),
new BundledDynamicDial.PackageVariant("bitax","ice","dynamic_dials/previous-v11/bitax/ice.dial","c709ff9ccc24c4a16a40c330f1631a4391840112bc0128ce87e128a07ec33ded",475859L,"com.gtdial.nx.ice","2309202612"),
new BundledDynamicDial.PackageVariant("bitax","green","dynamic_dials/previous-v11/bitax/green.dial","1a14dac44a1f3ebe3bc2355520d985972b44968ac5ee76cedd2e4432198b4e0e",475851L,"com.gtdial.nx.green","2309202613")
};for(BundledDynamicDial.PackageVariant v:all)m.put(key(v.faceId,v.paletteId),v);return Collections.unmodifiableMap(m);}

    private static BundledDynamicDial.PackageVariant matchingHistoricalVariant(String faceId,String paletteId,String internalId,String alias,long size,String sha){for(Map<String,BundledDynamicDial.PackageVariant> history:java.util.Arrays.asList(HISTORICAL_DIAL_LAB,HISTORICAL_DIAL_LAB_V4,HISTORICAL_V5,HISTORICAL_V6,HISTORICAL_V7,HISTORICAL_V8,HISTORICAL_V9,HISTORICAL_V10,HISTORICAL_V11)){BundledDynamicDial.PackageVariant v=history.get(key(faceId,paletteId));if(matches(v,internalId,alias,size,sha))return v;}return null;}
    private static boolean matches(BundledDynamicDial.PackageVariant v,String internalId,String alias,long size,String sha){return v!=null&&v.internalPackageId.equals(internalId)&&v.alias.equals(alias)&&v.packageSize==size&&v.packageSha256.equalsIgnoreCase(sha);}
    private static Map<String,BundledDynamicDial.PackageVariant> historicalDialLab(){Map<String,BundledDynamicDial.PackageVariant> m=new LinkedHashMap<>();BundledDynamicDial.PackageVariant[] all={
        new BundledDynamicDial.PackageVariant("pipboy3000","phosphor","dynamic_dials/previous/pipboy3000/phosphor.dial","6d519314696b31d2714886ec5ee8271f9ebf82c131d0670112e3f71514f0d58d",807083L,"com.gtdial.dl3000","2309202601"),
        new BundledDynamicDial.PackageVariant("pipboy3000","amber","dynamic_dials/previous/pipboy3000/amber.dial","9eafc80385262a8bfbf49ae6aa68c9f3a8f550791a096ccb4b8536c0756d52b3",807091L,"com.gtdial.dl.amber","2309202604"),
        new BundledDynamicDial.PackageVariant("pipboy3000","cyan","dynamic_dials/previous/pipboy3000/cyan.dial","e86b6f75a61aaac187cfa4349034acc6169fe108ad4a082c1c3e734b5b999c56",807091L,"com.gtdial.dl.cyan","2309202605"),
        new BundledDynamicDial.PackageVariant("pipboy3000","white","dynamic_dials/previous/pipboy3000/white.dial","00bd83d81b40983e0cd5d52622213813b2cbd7b0c7869e14c701811442d02a52",807107L,"com.gtdial.dl.white","2309202606"),
        new BundledDynamicDial.PackageVariant("pipboy3000","red","dynamic_dials/previous/pipboy3000/red.dial","57d1f13a952424679897752df0cf21a1dce57f6b1b683ebe138d3ae0b58625c3",807083L,"com.gtdial.dl.red","2309202607"),
        new BundledDynamicDial.PackageVariant("pipboy3000","violet","dynamic_dials/previous/pipboy3000/violet.dial","d995e7534a6ac5dd32e6e0bb1cb3270da87b90aff6d440824c1c424bcb61fec1",807091L,"com.gtdial.dl.violet","2309202608")
    };for(BundledDynamicDial.PackageVariant v:all)m.put(key(v.faceId,v.paletteId),v);return Collections.unmodifiableMap(m);}
    private static Map<String,BundledDynamicDial.PackageVariant> historicalDialLabV4(){Map<String,BundledDynamicDial.PackageVariant> m=new LinkedHashMap<>();BundledDynamicDial.PackageVariant[] all={
        new BundledDynamicDial.PackageVariant("pipboy3000","phosphor","dynamic_dials/previous-v4/pipboy3000/phosphor.dial","f83977fb3048f99f478ba5e86aca7c7c20639f5ab2b31536fc6788cca8f21db4",807083L,"com.gtdial.dl3000","2309202601"),
        new BundledDynamicDial.PackageVariant("pipboy3000","amber","dynamic_dials/previous-v4/pipboy3000/amber.dial","d7eb741f831ade380cc5c94cb3591d9af525c932695ce793c5e163ad99ae74f7",807091L,"com.gtdial.dl.amber","2309202604"),
        new BundledDynamicDial.PackageVariant("pipboy3000","cyan","dynamic_dials/previous-v4/pipboy3000/cyan.dial","1299c74ccded0f0efc93813345969ab8ee19245f426fd80670bc7a47e563a506",807091L,"com.gtdial.dl.cyan","2309202605"),
        new BundledDynamicDial.PackageVariant("pipboy3000","white","dynamic_dials/previous-v4/pipboy3000/white.dial","8b5ef0064423e32e3ed073b58d08ad0562f046d8dc6d4194469dbe25046c6b8b",807107L,"com.gtdial.dl.white","2309202606"),
        new BundledDynamicDial.PackageVariant("pipboy3000","red","dynamic_dials/previous-v4/pipboy3000/red.dial","819fa49a521897132b2e363def61429b2aec9c5521ac40669356f49fa7b4765e",807083L,"com.gtdial.dl.red","2309202607"),
        new BundledDynamicDial.PackageVariant("pipboy3000","violet","dynamic_dials/previous-v4/pipboy3000/violet.dial","4dc1f1fea9b469bac8626fe6e86f20755dd91effb4a99074f5d4328ff993188e",807091L,"com.gtdial.dl.violet","2309202608")
    };for(BundledDynamicDial.PackageVariant v:all)m.put(key(v.faceId,v.paletteId),v);return Collections.unmodifiableMap(m);}

    private static Map<String,BundledDynamicDial.PackageVariant> historicalV5(){Map<String,BundledDynamicDial.PackageVariant> m=new LinkedHashMap<>();BundledDynamicDial.PackageVariant[] all={
        new BundledDynamicDial.PackageVariant("pipboy3000","phosphor","dynamic_dials/previous-v5/pipboy3000/phosphor.dial","d63c3adf5f075a06b55cd4eea2b190d323b49c7586a7e5f09f184c40c477fa8a",807083L,"com.gtdial.dl3000","2309202601"),
        new BundledDynamicDial.PackageVariant("pipboy3000","amber","dynamic_dials/previous-v5/pipboy3000/amber.dial","6000ea5fcce688b68a4a38eb3fce8742be5a6dadaf1113f51f6f849b12eac13a",807091L,"com.gtdial.dl.amber","2309202604"),
        new BundledDynamicDial.PackageVariant("pipboy3000","cyan","dynamic_dials/previous-v5/pipboy3000/cyan.dial","b440d970a94ca5133f29fe058a3d2d370292523e9157cf5185abf7ff6a03f9fc",807091L,"com.gtdial.dl.cyan","2309202605"),
        new BundledDynamicDial.PackageVariant("pipboy3000","white","dynamic_dials/previous-v5/pipboy3000/white.dial","ab9e96f554077d73a787d6b840f1f3a227261f9899512f67b40d7f17abe2bf47",807107L,"com.gtdial.dl.white","2309202606"),
        new BundledDynamicDial.PackageVariant("pipboy3000","red","dynamic_dials/previous-v5/pipboy3000/red.dial","1d6005ce49c6cd404445e69e658a4bbe4973cbf933e8289ff02a791256b499ba",807083L,"com.gtdial.dl.red","2309202607"),
        new BundledDynamicDial.PackageVariant("pipboy3000","violet","dynamic_dials/previous-v5/pipboy3000/violet.dial","f054ffc4e7e7995733a28351483d8786c69b2ce52a0bce680ace5cf3d48d00f4",807091L,"com.gtdial.dl.violet","2309202608"),
        new BundledDynamicDial.PackageVariant("bitax","amber","dynamic_dials/previous-v5/bitax/amber.dial","b98a2dfd83ef479250a71f9ba833c9d0e1eed8d8643723c55a00861db7bbbbd6",475847L,"com.gtdial.nixiecore","2309202602"),
        new BundledDynamicDial.PackageVariant("bitax","copper","dynamic_dials/previous-v5/bitax/copper.dial","8caac6f4b65e6133f0b0c963c6d1e49bd872eb122643e489470eb3ee4a0fe914",475851L,"com.gtdial.nx.copper","2309202609"),
        new BundledDynamicDial.PackageVariant("bitax","redorange","dynamic_dials/previous-v5/bitax/redorange.dial","0bb796caab214a5fb376e4b745d0a5db1589156ab15881f7660baa9e3ef33c73",475843L,"com.gtdial.nx.redorange","2309202610"),
        new BundledDynamicDial.PackageVariant("bitax","gold","dynamic_dials/previous-v5/bitax/gold.dial","cabcb97086581e7e13225d105bc2721b865592528de7ab576aad9ff834917d62",475863L,"com.gtdial.nx.gold","2309202611"),
        new BundledDynamicDial.PackageVariant("bitax","ice","dynamic_dials/previous-v5/bitax/ice.dial","8fdb52a0ba4619fa0da0308ffe6d7680b35125ad1d8fcf9cd31633b6123de11a",475859L,"com.gtdial.nx.ice","2309202612"),
        new BundledDynamicDial.PackageVariant("bitax","green","dynamic_dials/previous-v5/bitax/green.dial","42793d9ce94ba042e29d8873ece7a6418b512cff7d4204ac85e05cfcff56b9ed",475851L,"com.gtdial.nx.green","2309202613"),
        new BundledDynamicDial.PackageVariant("pixbot","ice","dynamic_dials/previous-v5/pixbot/ice.dial","14c0030532c7e791832880a1ec4fab4f472aabee8a4beaa2feeb0edc2979908a",465667L,"com.gtdial.pixeldeck","2309202603"),
        new BundledDynamicDial.PackageVariant("pixbot","amber","dynamic_dials/previous-v5/pixbot/amber.dial","4813c60ea84db48b5744464073964c0400b98791ee912184e85fc03a6f63aa43",465663L,"com.gtdial.px.amber","2309202614"),
        new BundledDynamicDial.PackageVariant("pixbot","vfd","dynamic_dials/previous-v5/pixbot/vfd.dial","9313483c20ef0120983586ef5df8b0210a30a25059e483f7b6bb742e303d67cd",465663L,"com.gtdial.px.vfd","2309202615"),
        new BundledDynamicDial.PackageVariant("pixbot","red","dynamic_dials/previous-v5/pixbot/red.dial","d1cdbc97daca371fa034c5eaa7f18c40374311047e40b5aafb25c681df64b7d3",465655L,"com.gtdial.px.red","2309202616"),
        new BundledDynamicDial.PackageVariant("pixbot","violet","dynamic_dials/previous-v5/pixbot/violet.dial","ab979538ae382a81e4e21525480385fe26f599f29002490b8496e2948459163b",465667L,"com.gtdial.px.violet","2309202617"),
        new BundledDynamicDial.PackageVariant("pixbot","white","dynamic_dials/previous-v5/pixbot/white.dial","e40f2c4fa5098aff7434f80852db271d2abf90b36b233289e0ff7138a63cc97c",465655L,"com.gtdial.px.white","2309202618")
    };for(BundledDynamicDial.PackageVariant v:all)if(m.put(key(v.faceId,v.paletteId),v)!=null)throw new IllegalStateException("Duplicate historical variant");return Collections.unmodifiableMap(m);}

    private static Map<String,BundledDynamicDial.PackageVariant> historicalV6(){Map<String,BundledDynamicDial.PackageVariant> m=new LinkedHashMap<>();BundledDynamicDial.PackageVariant[] all={
        new BundledDynamicDial.PackageVariant("pipboy3000","phosphor","dynamic_dials/previous-v6/pipboy3000/phosphor.dial","92e1c761eab9275d818a903064a59fbf815e8875a7cad3af6fcd05e789545e67",614045L,"com.gtdial.dl3000","2309202601")
    };for(BundledDynamicDial.PackageVariant v:all)if(m.put(key(v.faceId,v.paletteId),v)!=null)throw new IllegalStateException("Duplicate historical variant");return Collections.unmodifiableMap(m);}
    private static Map<String,BundledDynamicDial.PackageVariant> historicalV7(){Map<String,BundledDynamicDial.PackageVariant> m=new LinkedHashMap<>();BundledDynamicDial.PackageVariant v=
        new BundledDynamicDial.PackageVariant("dial3000","phosphor","dynamic_dials/previous-v7/dial3000/phosphor.dial","71c6c18f914f8b0caee64faf9f3cad26faddd059a2308846e3431a6265713326",485421L,"com.gtdial.dial3000","2809202602");m.put(key(v.faceId,v.paletteId),v);return Collections.unmodifiableMap(m);}

    private static Map<String,BundledDynamicDial.PackageVariant> historicalV8(){Map<String,BundledDynamicDial.PackageVariant> m=new LinkedHashMap<>();BundledDynamicDial.PackageVariant[] all={
        new BundledDynamicDial.PackageVariant("dial3000","phosphor","dynamic_dials/previous-v8/dial3000/phosphor.dial","f227f65b1a516bc44e79a68823ca4bcaee4ffa02f4269bfbc2a3bbe01edf0b5c",491835L,"com.gtdial.dial3000v2.phosphor","2909202601"),
        new BundledDynamicDial.PackageVariant("dial3000","amber","dynamic_dials/previous-v8/dial3000/amber.dial","b39468e6fa94cabf95db928b8812e8ecd16bcf8433a57196d21be5ca99211324",491933L,"com.gtdial.dial3000v2.amber","2909202602"),
        new BundledDynamicDial.PackageVariant("dial3000","cyan","dynamic_dials/previous-v8/dial3000/cyan.dial","f6beb38286f2c8356d9a7d9d17d8a4426252c94477922429e8506d935be389d9",491931L,"com.gtdial.dial3000v2.cyan","2909202603"),
        new BundledDynamicDial.PackageVariant("dial3000","white","dynamic_dials/previous-v8/dial3000/white.dial","3f07a5ee71c5f187bc4042e64ee333936d11351e7726fdb1567416f7f70f2c42",491993L,"com.gtdial.dial3000v2.white","2909202604"),
        new BundledDynamicDial.PackageVariant("dial3000","red","dynamic_dials/previous-v8/dial3000/red.dial","eb4a0fa658d3e5e0b104932dbacfe6e141b7ac8f1113e039cebe3d56c7d01fcb",491853L,"com.gtdial.dial3000v2.red","2909202605"),
        new BundledDynamicDial.PackageVariant("dial3000","violet","dynamic_dials/previous-v8/dial3000/violet.dial","67276a726ce2eb0323a98e653c61c9547898c1d2d4d54a9f300a06f83496ad24",491927L,"com.gtdial.dial3000v2.violet","2909202606")
    };for(BundledDynamicDial.PackageVariant v:all)if(m.put(key(v.faceId,v.paletteId),v)!=null)throw new IllegalStateException("Duplicate historical variant");return Collections.unmodifiableMap(m);}

    private static Map<String,BundledDynamicDial.PackageVariant> historicalV9(){Map<String,BundledDynamicDial.PackageVariant> m=new LinkedHashMap<>();BundledDynamicDial.PackageVariant[] all={
        new BundledDynamicDial.PackageVariant("dial3000","phosphor","dynamic_dials/previous-v9/dial3000/phosphor.dial","477f052f3aedc54a31eacc2f4f8c19ec4175ec70bad52252114762de75869228",491835L,"com.gtdial.dial3000v3.phosphor","2909202611"),
        new BundledDynamicDial.PackageVariant("dial3000","amber","dynamic_dials/previous-v9/dial3000/amber.dial","f5e48935cd7189404e980f4f4103abead26d430a91efd69873a1d363a1566bfd",491933L,"com.gtdial.dial3000v3.amber","2909202612"),
        new BundledDynamicDial.PackageVariant("dial3000","cyan","dynamic_dials/previous-v9/dial3000/cyan.dial","813c91da15cbbfdf336ce7fbd40a4bf1fdefaffa37b78842fd8f4021b9c48bdf",491931L,"com.gtdial.dial3000v3.cyan","2909202613"),
        new BundledDynamicDial.PackageVariant("dial3000","white","dynamic_dials/previous-v9/dial3000/white.dial","faf90680a713de06d4851d33af461829cb609eb18cf570ac80745a1cde23785e",491993L,"com.gtdial.dial3000v3.white","2909202614"),
        new BundledDynamicDial.PackageVariant("dial3000","red","dynamic_dials/previous-v9/dial3000/red.dial","3339b968c9df39812e5dbe5809491a8247a5ab9a7f20efc266b451e5de2c38d7",491853L,"com.gtdial.dial3000v3.red","2909202615"),
        new BundledDynamicDial.PackageVariant("dial3000","violet","dynamic_dials/previous-v9/dial3000/violet.dial","c3296a696a2e8dc6cc0bd63e41d9151ef3e113fa8ac10cd6defd591cb1bf8e45",491927L,"com.gtdial.dial3000v3.violet","2909202616")
    };for(BundledDynamicDial.PackageVariant v:all)if(m.put(key(v.faceId,v.paletteId),v)!=null)throw new IllegalStateException("Duplicate historical variant");return Collections.unmodifiableMap(m);}

    private static Map<String,BundledDynamicDial.PackageVariant> historicalV10(){Map<String,BundledDynamicDial.PackageVariant> m=new LinkedHashMap<>();BundledDynamicDial.PackageVariant[] all={
        new BundledDynamicDial.PackageVariant("dial3000walk","phosphor","dynamic_dials/previous-v10/dial3000walk/phosphor.dial","2085d89ddff09ed00216061d169273edc1e52af353a926ee34596e85df4e8d27",547546L,"com.gtdial.dial3000anim.phosphor","2909202621"),
        new BundledDynamicDial.PackageVariant("dial3000walk","amber","dynamic_dials/previous-v10/dial3000walk/amber.dial","be46f8fb6036b3b8af97b9b193f3fa7a00ac651e5ffcb6260144d7dec27064d9",547676L,"com.gtdial.dial3000anim.amber","2909202622"),
        new BundledDynamicDial.PackageVariant("dial3000walk","cyan","dynamic_dials/previous-v10/dial3000walk/cyan.dial","fa8db763ac1a4ab4184ccc38a0a9417081afc972f6ea139e04cdf95cb4dc8f30",547674L,"com.gtdial.dial3000anim.cyan","2909202623"),
        new BundledDynamicDial.PackageVariant("dial3000walk","white","dynamic_dials/previous-v10/dial3000walk/white.dial","ff0d9942100b0c68b28121a4cc683e045071aef848c4b11a1ee8188039aafed8",547800L,"com.gtdial.dial3000anim.white","2909202624"),
        new BundledDynamicDial.PackageVariant("dial3000walk","red","dynamic_dials/previous-v10/dial3000walk/red.dial","44e583ec753c8ccefa13c40ebf9ead25510877485bfef8b4663fee8f58e65e3c",547564L,"com.gtdial.dial3000anim.red","2909202625"),
        new BundledDynamicDial.PackageVariant("dial3000walk","violet","dynamic_dials/previous-v10/dial3000walk/violet.dial","ae57c97378485a158015486832c94cd287dd1bca3663a38f2c91ac84f0497655",547670L,"com.gtdial.dial3000anim.violet","2909202626")
    };for(BundledDynamicDial.PackageVariant v:all)if(m.put(key(v.faceId,v.paletteId),v)!=null)throw new IllegalStateException("Duplicate historical variant");return Collections.unmodifiableMap(m);}

    private static Map<String,BundledDynamicDial.PackageVariant> compiledVariants(){Map<String,BundledDynamicDial.PackageVariant> m=new LinkedHashMap<>();BundledDynamicDial.PackageVariant[] all={
        new BundledDynamicDial.PackageVariant("pipwalk","phosphor","dynamic_dials/pipwalk/phosphor/face.dial","b377bd80b2cbbd509c4737d53cf04e7b16086b1b03cbb4ae11de4859cbd3331e",548447L,"com.gtdial.pip.walk.phosphor","1010202601"),
        new BundledDynamicDial.PackageVariant("pipwalk","amber","dynamic_dials/pipwalk/amber/face.dial","dc9bed2ff8076bfa4ef8a2c05fdbba717fd03ee98051c36f18a51f4bc1233a44",548577L,"com.gtdial.pip.walk.amber","1010202602"),
        new BundledDynamicDial.PackageVariant("pipwalk","cyan","dynamic_dials/pipwalk/cyan/face.dial","0341817554f8080cf3a466e81aeefeb160d633599a1fdadc01196f05b52bb2f2",548575L,"com.gtdial.pip.walk.cyan","1010202603"),
        new BundledDynamicDial.PackageVariant("pipwalk","white","dynamic_dials/pipwalk/white/face.dial","5bf4554ff28fb4a1a80898c396dc6bc0702c190fab49d291354ed766b9579a3e",548701L,"com.gtdial.pip.walk.white","1010202604"),
        new BundledDynamicDial.PackageVariant("pipwalk","red","dynamic_dials/pipwalk/red/face.dial","29900e32a933b69a6ea89e4302c785ccbae696abc18ded9bbb14e04438740e67",548465L,"com.gtdial.pip.walk.red","1010202605"),
        new BundledDynamicDial.PackageVariant("pipwalk","violet","dynamic_dials/pipwalk/violet/face.dial","b0a72521f8e620d058b4eb4c03ed1c120227311ddf097f1a173443f333ba597a",548571L,"com.gtdial.pip.walk.violet","1010202606"),
        new BundledDynamicDial.PackageVariant("piprpg","phosphor","dynamic_dials/piprpg/phosphor/face.dial","a47fe2bc0c107a88d07e3bcbf81cba0649a88f01c880d5a4b38956bff1db5e5c",548445L,"com.gtdial.pip.rpg.phosphor","1010202611"),
        new BundledDynamicDial.PackageVariant("piprpg","amber","dynamic_dials/piprpg/amber/face.dial","bb197c7146cac3c0b8dff6c823a3569758e44d9a4082984b5aff0d4aa0150fde",548575L,"com.gtdial.pip.rpg.amber","1010202612"),
        new BundledDynamicDial.PackageVariant("piprpg","cyan","dynamic_dials/piprpg/cyan/face.dial","a3f4434f07994b44d284665d003550fdf0d09b3b3c2dc516868bc0f758bf5472",548573L,"com.gtdial.pip.rpg.cyan","1010202613"),
        new BundledDynamicDial.PackageVariant("piprpg","white","dynamic_dials/piprpg/white/face.dial","2df36e3f18139d689c1ad5e47afdbe479c04d385b6321f290c97f104a53a1f91",548699L,"com.gtdial.pip.rpg.white","1010202614"),
        new BundledDynamicDial.PackageVariant("piprpg","red","dynamic_dials/piprpg/red/face.dial","57487e7e9a20936d61e5dc70f11abe83d6d7f9fe299e75f8267b4d09a929d691",548463L,"com.gtdial.pip.rpg.red","1010202615"),
        new BundledDynamicDial.PackageVariant("piprpg","violet","dynamic_dials/piprpg/violet/face.dial","1f24579cf538406fb77149257faf21c84b92837890c959a8b6d6792a22d50ab2",548569L,"com.gtdial.pip.rpg.violet","1010202616"),
        new BundledDynamicDial.PackageVariant("pipauto","phosphor","dynamic_dials/pipauto/phosphor/face.dial","60f94a41de6ec7afb39738623b7949a09a1be75721b4019399989eec1be1835a",548447L,"com.gtdial.pip.auto.phosphor","1010202621"),
        new BundledDynamicDial.PackageVariant("pipauto","amber","dynamic_dials/pipauto/amber/face.dial","0272b0259ae0249a201ea9013305b8ed2ffd2c0b1831bacef0ed3c011b5659fc",548577L,"com.gtdial.pip.auto.amber","1010202622"),
        new BundledDynamicDial.PackageVariant("pipauto","cyan","dynamic_dials/pipauto/cyan/face.dial","29ca7c9fdd5cbad69e259d9c9f94bd398caf552c81623372451127097b651b4a",548575L,"com.gtdial.pip.auto.cyan","1010202623"),
        new BundledDynamicDial.PackageVariant("pipauto","white","dynamic_dials/pipauto/white/face.dial","8dbba5afc6364e09fe07da8ad689e95553eebabe989f1bd8f6f0a06b4f0a8ce7",548701L,"com.gtdial.pip.auto.white","1010202624"),
        new BundledDynamicDial.PackageVariant("pipauto","red","dynamic_dials/pipauto/red/face.dial","2768f5b1eee288e054fcfe4252ac08e40b63202bc1f035259dec67b88b4f10de",548465L,"com.gtdial.pip.auto.red","1010202625"),
        new BundledDynamicDial.PackageVariant("pipauto","violet","dynamic_dials/pipauto/violet/face.dial","ee6e79622e789c2b6911338058a856cb9e120af21899db55762b20ced845c3fa",548571L,"com.gtdial.pip.auto.violet","1010202626"),
        new BundledDynamicDial.PackageVariant("pipstranger","phosphor","dynamic_dials/pipstranger/phosphor/face.dial","6d366f8cf9227c9ce814668ee4b12892de3f8a6c9c90b3944d8259858f7eb11a",548455L,"com.gtdial.pip.stranger.phosphor","1010202631"),
        new BundledDynamicDial.PackageVariant("pipstranger","amber","dynamic_dials/pipstranger/amber/face.dial","2d66d8233df9f83feca8ee23742eb222db2d827ecbaf7b907ad49438ecc6bf71",548585L,"com.gtdial.pip.stranger.amber","1010202632"),
        new BundledDynamicDial.PackageVariant("pipstranger","cyan","dynamic_dials/pipstranger/cyan/face.dial","84635abd58efbaa916f6a775578ab5f94d3f5cff206d1b07215b7e596a54d1ff",548583L,"com.gtdial.pip.stranger.cyan","1010202633"),
        new BundledDynamicDial.PackageVariant("pipstranger","white","dynamic_dials/pipstranger/white/face.dial","4089419aa92672dc4642a1f7c4f28f3e32e70c999f9f83502594ef337fac7eff",548709L,"com.gtdial.pip.stranger.white","1010202634"),
        new BundledDynamicDial.PackageVariant("pipstranger","red","dynamic_dials/pipstranger/red/face.dial","abc58e6d0c6ac98734a914d0f7552e3cf8ef9ff85a9c938225a52ce267296a30",548473L,"com.gtdial.pip.stranger.red","1010202635"),
        new BundledDynamicDial.PackageVariant("pipstranger","violet","dynamic_dials/pipstranger/violet/face.dial","cbc1a01a1016aeea4fb7d74a2e45f051dee309a39aae39597282801dec69db7f",548579L,"com.gtdial.pip.stranger.violet","1010202636"),
        new BundledDynamicDial.PackageVariant("pipbottle","phosphor","dynamic_dials/pipbottle/phosphor/face.dial","d40885bf42683905993da13917f4fc1c6e738e955eef21eb22833f8a6c7da8ce",605092L,"com.gtdial.pip.bottle.phosphor","1010202641"),
        new BundledDynamicDial.PackageVariant("pipbottle","amber","dynamic_dials/pipbottle/amber/face.dial","dfe63d49334d75453c05da1abf701116fb57880a93ce4b597c094530d116f13e",605254L,"com.gtdial.pip.bottle.amber","1010202642"),
        new BundledDynamicDial.PackageVariant("pipbottle","cyan","dynamic_dials/pipbottle/cyan/face.dial","ae9b46daf49fd61656222c92d9201a1969d65cb16a9bb0893ad0fc0489883514",605252L,"com.gtdial.pip.bottle.cyan","1010202643"),
        new BundledDynamicDial.PackageVariant("pipbottle","white","dynamic_dials/pipbottle/white/face.dial","e73849d32329c9bd17e8f35162e992bab7ae1a406195a1c270d328214cfb75c3",605442L,"com.gtdial.pip.bottle.white","1010202644"),
        new BundledDynamicDial.PackageVariant("pipbottle","red","dynamic_dials/pipbottle/red/face.dial","9f520a4efd024ec82c840cc4bec598a2e8cd221fbd6f7cec061bf123e1c022c5",605110L,"com.gtdial.pip.bottle.red","1010202645"),
        new BundledDynamicDial.PackageVariant("pipbottle","violet","dynamic_dials/pipbottle/violet/face.dial","f1e4b89e97645c6f0ea2149cfdac7731b70f8f542126619594a8ec5d12269e91",605248L,"com.gtdial.pip.bottle.violet","1010202646"),
        new BundledDynamicDial.PackageVariant("pipsavant","phosphor","dynamic_dials/pipsavant/phosphor/face.dial","0187c04e6d7009b9a437455884cadeeeffc332873951e04370eb5679e52e6b27",548451L,"com.gtdial.pip.savant.phosphor","1010202651"),
        new BundledDynamicDial.PackageVariant("pipsavant","amber","dynamic_dials/pipsavant/amber/face.dial","3b27bf28a979a8bd804fe593c4ff895339c821ec1f9b144884d7340c743b6ab6",548581L,"com.gtdial.pip.savant.amber","1010202652"),
        new BundledDynamicDial.PackageVariant("pipsavant","cyan","dynamic_dials/pipsavant/cyan/face.dial","49180ca4e589c2d03254897c60c4c31468160c2f8ee49fdebc247faec0d226d7",548579L,"com.gtdial.pip.savant.cyan","1010202653"),
        new BundledDynamicDial.PackageVariant("pipsavant","white","dynamic_dials/pipsavant/white/face.dial","870f943002bf0d26af6c73bcf47346a2ba0317d504cc2fcd01b545eebd4a1e79",548705L,"com.gtdial.pip.savant.white","1010202654"),
        new BundledDynamicDial.PackageVariant("pipsavant","red","dynamic_dials/pipsavant/red/face.dial","ebb8e2c9e09426e363853c0327bf3856f21ee7a2dd1b0c2f78b29d4dc35fbc28",548469L,"com.gtdial.pip.savant.red","1010202655"),
        new BundledDynamicDial.PackageVariant("pipsavant","violet","dynamic_dials/pipsavant/violet/face.dial","ea09a96931994d344e8f1cc30c916edd52ebc6386ec757c2614243b785139e53",548575L,"com.gtdial.pip.savant.violet","1010202656"),
        new BundledDynamicDial.PackageVariant("pipthumb","phosphor","dynamic_dials/pipthumb/phosphor/face.dial","194f24296c9fedc8c7856c258d7a83ef632439af2cfadd14793284deabc6887d",548449L,"com.gtdial.pip.thumb.phosphor","1010202661"),
        new BundledDynamicDial.PackageVariant("pipthumb","amber","dynamic_dials/pipthumb/amber/face.dial","697240e3b29f5ff3f78a0a929428b5b5bf66a8b55ef4e26e7c38ba251a02d556",548579L,"com.gtdial.pip.thumb.amber","1010202662"),
        new BundledDynamicDial.PackageVariant("pipthumb","cyan","dynamic_dials/pipthumb/cyan/face.dial","5ee6199286e8d8ddbc12a899501138e6df491d3c17c5d274c0019149d6fd3204",548577L,"com.gtdial.pip.thumb.cyan","1010202663"),
        new BundledDynamicDial.PackageVariant("pipthumb","white","dynamic_dials/pipthumb/white/face.dial","ec5022131b41d159715160f951b5fcee046642a3b3ae1468d8a67950993b306a",548703L,"com.gtdial.pip.thumb.white","1010202664"),
        new BundledDynamicDial.PackageVariant("pipthumb","red","dynamic_dials/pipthumb/red/face.dial","0b924c9d23704c4855af487d26242e71c5845ff8eca273843264c05a7584fa01",548467L,"com.gtdial.pip.thumb.red","1010202665"),
        new BundledDynamicDial.PackageVariant("pipthumb","violet","dynamic_dials/pipthumb/violet/face.dial","c495baddf2b1af83fffda08d7cd501949446fb354bd7a276a81d8ecc9c1d47a9",548573L,"com.gtdial.pip.thumb.violet","1010202666"),
        new BundledDynamicDial.PackageVariant("base3000","phosphor","dynamic_dials/base3000/phosphor/face.dial","39907fc7f284862f9591a63415dd48e7e4b5c7e3956b0670de1055b4279ac676",485349L,"com.gtdial.base3000","2809202601"),
        new BundledDynamicDial.PackageVariant("dial3000walk","phosphor","dynamic_dials/dial3000walk/phosphor/face.dial","4dac0ce8155dd275d7c72b8dfde61110e73fcc0e4dd3ec8fbb86260088fbd052",547611L,"com.gtdial.dial3000anim2.phosphor","2909202631"),
        new BundledDynamicDial.PackageVariant("dial3000walk","amber","dynamic_dials/dial3000walk/amber/face.dial","b8fef094728400c637829a3f433e7c98b0c815810ffde85dfbe80b99822d97bf",547741L,"com.gtdial.dial3000anim2.amber","2909202632"),
        new BundledDynamicDial.PackageVariant("dial3000walk","cyan","dynamic_dials/dial3000walk/cyan/face.dial","d99767cea3f752f1a70254fc661505861fa9f8d6ad128b19b59cfed3631c0288",547739L,"com.gtdial.dial3000anim2.cyan","2909202633"),
        new BundledDynamicDial.PackageVariant("dial3000walk","white","dynamic_dials/dial3000walk/white/face.dial","e8da713a8a3cdf21375ae9c0be24c662eb730b733e8e2feb057ccdc8822de4a5",547865L,"com.gtdial.dial3000anim2.white","2909202634"),
        new BundledDynamicDial.PackageVariant("dial3000walk","red","dynamic_dials/dial3000walk/red/face.dial","7f60115334933f1cf699f29bea5109457367f360191a1854b24842fc8c0da544",547629L,"com.gtdial.dial3000anim2.red","2909202635"),
        new BundledDynamicDial.PackageVariant("dial3000walk","violet","dynamic_dials/dial3000walk/violet/face.dial","aefa75b55e1b43de3f9d2d7593f5613e6e327606dd07e13d650f73829ea5d789",547735L,"com.gtdial.dial3000anim2.violet","2909202636"),
        new BundledDynamicDial.PackageVariant("dial3000","phosphor","dynamic_dials/dial3000/phosphor/face.dial","477f052f3aedc54a31eacc2f4f8c19ec4175ec70bad52252114762de75869228",491835L,"com.gtdial.dial3000v3.phosphor","2909202611"),
        new BundledDynamicDial.PackageVariant("dial3000","amber","dynamic_dials/dial3000/amber/face.dial","f5e48935cd7189404e980f4f4103abead26d430a91efd69873a1d363a1566bfd",491933L,"com.gtdial.dial3000v3.amber","2909202612"),
        new BundledDynamicDial.PackageVariant("dial3000","cyan","dynamic_dials/dial3000/cyan/face.dial","813c91da15cbbfdf336ce7fbd40a4bf1fdefaffa37b78842fd8f4021b9c48bdf",491931L,"com.gtdial.dial3000v3.cyan","2909202613"),
        new BundledDynamicDial.PackageVariant("dial3000","white","dynamic_dials/dial3000/white/face.dial","faf90680a713de06d4851d33af461829cb609eb18cf570ac80745a1cde23785e",491993L,"com.gtdial.dial3000v3.white","2909202614"),
        new BundledDynamicDial.PackageVariant("dial3000","red","dynamic_dials/dial3000/red/face.dial","3339b968c9df39812e5dbe5809491a8247a5ab9a7f20efc266b451e5de2c38d7",491853L,"com.gtdial.dial3000v3.red","2909202615"),
        new BundledDynamicDial.PackageVariant("dial3000","violet","dynamic_dials/dial3000/violet/face.dial","c3296a696a2e8dc6cc0bd63e41d9151ef3e113fa8ac10cd6defd591cb1bf8e45",491927L,"com.gtdial.dial3000v3.violet","2909202616"),
        new BundledDynamicDial.PackageVariant("pipboy3000","phosphor","dynamic_dials/pipboy3000/phosphor/face.dial","2154c496a8fb7176d25847418a332d8e67cdd493013753e4d009dd64558544d9",335897L,"com.gtdial.dl3000","2309202601"),
        new BundledDynamicDial.PackageVariant("pipboy3000","amber","dynamic_dials/pipboy3000/amber/face.dial","f6e5fa2bb116df8d6f69873068307d3547b25352eb915f0fefff54da4de1b08d",614053L,"com.gtdial.dl.amber","2309202604"),
        new BundledDynamicDial.PackageVariant("pipboy3000","cyan","dynamic_dials/pipboy3000/cyan/face.dial","641f42210d95cc8e271f7077c537ea68be202538d6122c8f081a33490b17df63",614053L,"com.gtdial.dl.cyan","2309202605"),
        new BundledDynamicDial.PackageVariant("pipboy3000","white","dynamic_dials/pipboy3000/white/face.dial","fc4f0fa2855b6b1dd89f74c875c87a215c53f24014fa80d93d7e01ffb5688348",614073L,"com.gtdial.dl.white","2309202606"),
        new BundledDynamicDial.PackageVariant("pipboy3000","red","dynamic_dials/pipboy3000/red/face.dial","ae24e6eb4a5fc7687f32d9ef872d1262e4788614cdc819aa28be3d8a89494203",614045L,"com.gtdial.dl.red","2309202607"),
        new BundledDynamicDial.PackageVariant("pipboy3000","violet","dynamic_dials/pipboy3000/violet/face.dial","946ded483f61b33bb43e4a30df6ef355480225a33e7b1f8a086d9977aab88fb2",614053L,"com.gtdial.dl.violet","2309202608"),
        new BundledDynamicDial.PackageVariant("bitax","amber","dynamic_dials/bitax/amber/face.dial","3c2669147ae51b909b5f611494e4a6eb2b523c4e3996405c03d899e058bc14f9",475867L,"com.gtdial.nixiecore","2309202602"),
        new BundledDynamicDial.PackageVariant("bitax","copper","dynamic_dials/bitax/copper/face.dial","2880c2516522212afd1bd5442f1de960959a19817a5e5fcc92ce0c7415f960f3",475867L,"com.gtdial.nx.copper","2309202609"),
        new BundledDynamicDial.PackageVariant("bitax","redorange","dynamic_dials/bitax/redorange/face.dial","1fc1e527385b5576f1f4bfb200b7c5bcc4b7f06c99ee20b76b7aae9bf0deec3e",475867L,"com.gtdial.nx.redorange","2309202610"),
        new BundledDynamicDial.PackageVariant("bitax","gold","dynamic_dials/bitax/gold/face.dial","5d2f79a262c17b03454b645badb4f4f07f6f2cfe2b19a9131706e8e35239e151",475867L,"com.gtdial.nx.gold","2309202611"),
        new BundledDynamicDial.PackageVariant("bitax","ice","dynamic_dials/bitax/ice/face.dial","7418f1e999dc55626af3b281d6dc0adb0638219be9d264c98651eb7834ad94f9",475867L,"com.gtdial.nx.ice","2309202612"),
        new BundledDynamicDial.PackageVariant("bitax","green","dynamic_dials/bitax/green/face.dial","ff154a9eeced2e79ed4b44819802d6d2b4adc50715a3c7d05ec672d56f93bc1f",475867L,"com.gtdial.nx.green","2309202613"),
        new BundledDynamicDial.PackageVariant("pixbot","ice","dynamic_dials/pixbot/ice/face.dial","f1f7fa0dbf6c8171ff6212ba4ebd0ae217f0fcf93bf4e29e6d30d2203a53be0b",465667L,"com.gtdial.pixeldeck","2309202603"),
        new BundledDynamicDial.PackageVariant("pixbot","amber","dynamic_dials/pixbot/amber/face.dial","8c08ba2f1afd0c8292a0826184675d0f99718f56db6a2cd47f3793aed3fbc55a",465663L,"com.gtdial.px.amber","2309202614"),
        new BundledDynamicDial.PackageVariant("pixbot","vfd","dynamic_dials/pixbot/vfd/face.dial","f0870946b5d7b5eed037d2b67a4b8bc9721d70b05d65a582a5dc4dcba6131ddb",465663L,"com.gtdial.px.vfd","2309202615"),
        new BundledDynamicDial.PackageVariant("pixbot","red","dynamic_dials/pixbot/red/face.dial","dddc201051a92ca8cd6fab57327edd83cb644e8d1a47b3bc2e7a7eb19fa5e14f",465655L,"com.gtdial.px.red","2309202616"),
        new BundledDynamicDial.PackageVariant("pixbot","violet","dynamic_dials/pixbot/violet/face.dial","b2b9527a425b7b104bf029538ae70687f3055b24b44a6616adb6ab760b1ebc7a",465667L,"com.gtdial.px.violet","2309202617"),
        new BundledDynamicDial.PackageVariant("pixbot","white","dynamic_dials/pixbot/white/face.dial","1b0afd5f2b17cd5fdd829acb8056847402b860b4d89da0d4a1bc5eef68fa90ac",465655L,"com.gtdial.px.white","2309202618")
    };for(BundledDynamicDial.PackageVariant v:all)if(m.put(key(v.faceId,v.paletteId),v)!=null)throw new IllegalStateException("Duplicate compiled package variant");return Collections.unmodifiableMap(m);}
    private static String key(String face,String palette){return face+"|"+palette;}
    private static byte[] read(Context c,String path)throws IOException{try(InputStream in=c.getAssets().open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>32*1024*1024)throw new IOException("Bundled asset too large");out.write(b,0,n);}return out.toByteArray();}}
    private static String sha256(byte[] data)throws IOException{try{StringBuilder s=new StringBuilder(64);for(byte b:MessageDigest.getInstance("SHA-256").digest(data))s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}catch(java.security.NoSuchAlgorithmException e){throw new IOException("SHA-256 unavailable",e);}}
}
