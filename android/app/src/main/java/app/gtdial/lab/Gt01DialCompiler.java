package app.gtdial.lab;

import android.content.Context;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.security.DigestOutputStream;
import java.security.MessageDigest;

/** Deterministic compiler: persisted snapshots are never decoded/cropped a second time. */
final class Gt01DialCompiler {
    static final String PREFIX="dial_editor/gt01-text11-v1/";
    static final String DONOR_SHA="71c6c18f914f8b0caee64faf9f3cad26faddd059a2308846e3431a6265713326";
    static final String APP_SHA="ab29e2c90444c809014bdc8de95a2465f5dc254b84f0070507d3692138207f3c";
    static final String MAIN_SHA="93129836ba3a2b6e700e7096c680a46d50a9120d739f3c8f7c06ae9267621afb";
    static final String PROFILE_SHA="c81bfcf5749b0e0e5b1428b8c5ff55791a20d50c6eb132610929d14e0deccee6";
    static final String MARKER="[\"__GT_EDITOR_SELECTED_IDS__\"]";
    static final class Identity {
        final int counter;
        final String id,alias,name;
        Identity(int counter)throws IOException {
            Gt01DialContainerCodec.require(counter>=1&&counter<=99999999,"Пространство идентификаторов исчерпано");
            this.counter=counter;String digits=String.format(Locale.US,"%08d",counter);
            id="com.gtdial.ed."+digits;alias="80"+digits;name="gtdialed"+digits;
        }
    }
    static final class Snapshot {
        final DialProject project;
        final Identity identity;
        final String sha256;
        private final byte[] canonical,source,bgra;
        private Snapshot(DialProject project,Identity identity,byte[] source,byte[] bgra)throws Exception {
            project.validate(true);Gt01DialContainerCodec.require(project.revision>=1,"Сначала сохраните проект");
            this.project=project;this.identity=identity;this.canonical=canonicalProject(project);
            this.source=source.clone();this.bgra=bgra.clone();checkSource(project,this.source);
            Gt01DialContainerCodec.require(bgra.length==Gt01PgfCodec.PIXEL_BYTES,"Snapshot BGRA размер");
            for(int i=3;i<bgra.length;i+=4)Gt01DialContainerCodec.require((bgra[i]&255)==255,"Snapshot прозрачность");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            DataOutputStream framed=new DataOutputStream(new DigestOutputStream(new OutputStream(){
                public void write(int value){}
                public void write(byte[] bytes,int offset,int length){}
            },digest));
            for(byte[] part:new byte[][]{utf8("gtdial-compiler-snapshot-v1"),utf8(DialRuntimeProfile.ID),utf8(PROFILE_SHA),utf8(DONOR_SHA),
                utf8(APP_SHA),utf8(MAIN_SHA),utf8(identity.id),utf8(identity.alias),utf8(identity.name),canonical,this.source,this.bgra}) {
                framed.writeInt(part.length);framed.write(part);
            }
            framed.flush();StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.US,"%02x",b&255));
            sha256=hash.toString();
        }
        byte[] projectBytes(){return canonical.clone();}
        byte[] sourceBytes(){return source.clone();}
        byte[] backgroundBytes(){return bgra.clone();}
        byte[] metadata() {
            return utf8("{\"format\":\"gtdial-compiler-snapshot\",\"schemaVersion\":1,\"runtimeProfile\":"+quote(DialRuntimeProfile.ID)
                +",\"profileSha256\":"+quote(PROFILE_SHA)+",\"donorSha256\":"+quote(DONOR_SHA)+",\"appSha256\":"+quote(APP_SHA)
                +",\"mainSha256\":"+quote(MAIN_SHA)+",\"counter\":"+identity.counter+",\"id\":"+quote(identity.id)
                +",\"alias\":"+quote(identity.alias)+",\"manifestName\":"+quote(identity.name)+",\"projectSha256\":"+quote(DialImageNormalizer.hash(canonical))
                +",\"sourceSha256\":"+quote(DialImageNormalizer.hash(source))+",\"sourceLength\":"+source.length
                +",\"backgroundSha256\":"+quote(DialImageNormalizer.hash(bgra))+",\"backgroundLength\":"+bgra.length
                +",\"snapshotSha256\":"+quote(sha256)+"}");
        }
        static Snapshot capture(DialProject project,Identity identity,byte[] source,byte[] exactOpaqueBgra)throws Exception {
            return new Snapshot(project,identity,source,exactOpaqueBgra);
        }
        static Snapshot restore(byte[] metadata,byte[] canonical,byte[] source,byte[] bgra)throws Exception {
            JSONObject json=json(metadata);Object n=json.get("counter");
            Gt01DialContainerCodec.require(n instanceof Integer,"Snapshot identity counter");
            DialProject p=parseCanonical(canonical);Snapshot restored=new Snapshot(p,new Identity((Integer)n),source,bgra);
            Gt01DialContainerCodec.require(Arrays.equals(metadata,restored.metadata())&&Arrays.equals(canonical,restored.canonical),"Snapshot metadata или байты изменены");
            return restored;
        }
    }
    private final Gt01DialContainerCodec.Container donor;
    private final byte[] mainTemplate;
    Gt01DialCompiler(Context context)throws Exception {
        byte[] template=asset(context,"template.dial",DONOR_SHA,Gt01DialContainerCodec.MAX_PACKAGE);
        byte[] app=asset(context,"app.js",APP_SHA,64*1024);mainTemplate=asset(context,"main.js",MAIN_SHA,64*1024);
        byte[] profile=asset(context,"profile.json",PROFILE_SHA,64*1024);donor=Gt01DialContainerCodec.parse(template);
        Gt01DialContainerCodec.require(Arrays.equals(app,donor.resource(2)),"App не совпадает с donor");
        Gt01DialContainerCodec.require(DialRuntimeProfile.ID.equals(json(profile).getString("id")),"Профиль");
        String runtime=new String(mainTemplate,StandardCharsets.UTF_8);
        Gt01DialContainerCodec.require(runtime.indexOf(MARKER)>=0&&runtime.indexOf(MARKER)==runtime.lastIndexOf(MARKER),"Runtime marker");
        Gt01PlbCodec.Node layout=Gt01PlbCodec.parse(donor.resource(37));
        Gt01DialContainerCodec.require(layout.children.size()==11,"Donor состав");
        for(int i=0;i<11;i++){
            Gt01PlbCodec.Node node=layout.children.get(i);DialRuntimeProfile.Field f=DialRuntimeProfile.FIELDS.get(i);
            byte[] plb=donor.resource(37);int font=Gt01PlbCodec.property(node,3),align=Gt01PlbCodec.property(node,4),rect=Gt01PlbCodec.property(node,6);
            Gt01DialContainerCodec.require(f.id.equals(node.id)&&Gt01DialContainerCodec.u16(plb,font)==f.fontPixels
                &&Gt01DialContainerCodec.u16(plb,align)==f.align&&Gt01DialContainerCodec.u16(plb,rect+4)==f.width
                &&Gt01DialContainerCodec.u16(plb,rect+6)==f.height,"Java профиль отличается от donor");
        }
        Gt01PgfCodec.decode(donor.resource(60));
    }
    byte[] compile(Snapshot snapshot)throws Exception {
        snapshot.project.validate(true);Map<Integer,byte[]> replacements=new HashMap<>();
        byte[] runtime=runtime(snapshot.project),plb=Gt01PlbCodec.build(donor.resource(37),snapshot.project);
        byte[] pgf=Gt01PgfCodec.encode(donor.resource(60),snapshot.backgroundBytes()),manifest=manifest(snapshot.identity);
        for(Gt01DialContainerCodec.LocaleTable locale:donor.locales){
            for(int index:new int[]{34,37,44,60}){
                byte[] bytes=index==34?runtime:index==37?plb:index==44?manifest:pgf;
                int offset=locale.entries.get(index).offset;byte[] prior=replacements.put(offset,bytes);
                Gt01DialContainerCodec.require(prior==null||Arrays.equals(prior,bytes),"Несогласованная замена");
            }
        }
        return Gt01DialContainerCodec.rebuild(donor,replacements);
    }
    byte[] runtime(DialProject p) {
        StringBuilder ids=new StringBuilder("[");for(DialProject.Element e:p.elements){if(ids.length()>1)ids.append(',');ids.append(quote(e.id));}ids.append(']');
        return utf8(new String(mainTemplate,StandardCharsets.UTF_8).replace(MARKER,ids.toString()));
    }
    byte[] manifest(Identity identity)throws Exception {
        String text=new String(donor.resource(44),StandardCharsets.UTF_8);
        JSONObject manifest=json(donor.resource(44));
        for(String key:new String[]{"id","name","alias"}) {
            String before=quote(key)+":"+quote(manifest.getString(key)),after=quote(key)+":"+quote(key.equals("id")?identity.id:key.equals("name")?identity.name:identity.alias);
            Gt01DialContainerCodec.require(text.indexOf(before)>=0&&text.indexOf(before)==text.lastIndexOf(before),"Manifest поле");text=text.replace(before,after);
        }
        Gt01DialContainerCodec.require(text.endsWith(" ")&&text.indexOf('\0')<0,"Manifest завершение");return utf8(text);
    }
    Gt01DialContainerCodec.Container donor(){return donor;}
    static byte[] canonicalProject(DialProject p) {
        DialProject.Background b=p.background;StringBuilder text=new StringBuilder("{\"format\":\"gtdial-project\",\"schemaVersion\":1,\"id\":")
            .append(quote(p.id)).append(",\"revision\":").append(p.revision).append(",\"name\":").append(quote(p.name))
            .append(",\"deviceProfile\":").append(quote(DialRuntimeProfile.DEVICE)).append(",\"runtimeProfile\":").append(quote(DialRuntimeProfile.ID))
            .append(",\"background\":{\"source\":\"source.png\",\"sha256\":").append(quote(b.sha256))
            .append(",\"width\":").append(b.width).append(",\"height\":").append(b.height).append(",\"crop\":[")
            .append(b.x).append(',').append(b.y).append(',').append(b.size).append(',').append(b.size).append("]},\"elements\":[");
        boolean first=true;for(DialProject.Element e:p.elements){if(!first)text.append(',');first=false;
            text.append("{\"id\":").append(quote(e.id)).append(",\"rect\":[").append(e.x).append(',').append(e.y).append(',').append(e.width).append(',').append(e.height)
                .append("],\"argb\":").append(quote(String.format(Locale.US,"#%08X",e.argb))).append('}');}
        return utf8(text.append("]}").toString());
    }
    static DialProject parseCanonical(byte[] bytes)throws Exception {
        JSONObject json=json(bytes);JSONArray nodes=json.getJSONArray("elements");
        for(int i=0;i<nodes.length();i++){JSONObject node=nodes.getJSONObject(i);String hex=node.getString("argb");
            Gt01DialContainerCodec.require(hex.matches("#FF[0-9A-F]{6}"),"Canonical ARGB");node.put("argb",(int)Long.parseLong(hex.substring(1),16));}
        DialProject p=DialProject.fromJson(json,true);
        Gt01DialContainerCodec.require(Arrays.equals(bytes,canonicalProject(p)),"Неканонический проект");return p;
    }
    static JSONObject json(byte[] bytes)throws Exception {
        Gt01DialContainerCodec.require(bytes.length<=64*1024,"JSON предел");String text=new String(bytes,StandardCharsets.UTF_8);
        Gt01DialContainerCodec.require(text.indexOf('\0')<0,"NUL в JSON");JSONTokener tokener=new JSONTokener(text);Object value=tokener.nextValue();
        Gt01DialContainerCodec.require(value instanceof JSONObject&&tokener.nextClean()==0,"JSON EOF");return (JSONObject)value;
    }
    private static void checkSource(DialProject p,byte[] source)throws IOException {
        Gt01DialContainerCodec.require(source.length>0&&source.length<=DialImageNormalizer.MAX_BYTES&&DialImageNormalizer.hash(source).equals(p.background.sha256),"Snapshot source SHA/size");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(source,0,source.length,bounds);
        DialImageNormalizer.checkDimensions(bounds.outWidth,bounds.outHeight);
        Gt01DialContainerCodec.require("image/png".equals(bounds.outMimeType)&&bounds.outWidth==p.background.width&&bounds.outHeight==p.background.height,"Snapshot source dimensions");
    }
    private static byte[] asset(Context context,String name,String expected,int max)throws IOException {
        try(InputStream stream=context.getAssets().open(PREFIX+name)){byte[] bytes=DialImageNormalizer.read(stream,max);
            Gt01DialContainerCodec.require(expected.equals(DialImageNormalizer.hash(bytes)),"Повреждён встроенный ресурс "+name);return bytes;}
    }
    static String quote(String text){return JSONObject.quote(text);}
    static byte[] utf8(String text){return text.getBytes(StandardCharsets.UTF_8);}
}
