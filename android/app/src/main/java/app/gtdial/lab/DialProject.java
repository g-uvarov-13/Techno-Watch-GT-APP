package app.gtdial.lab;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Immutable authoring data; never grants permission to install a watch package. */
final class DialProject {
    static final class Element {
        final String id;
        final int x,y,width,height,argb;
        Element(String id,int x,int y,int w,int h,int argb) {
            this.id=id;this.x=x;this.y=y;width=w;height=h;this.argb=argb;
        }
        Element at(int x,int y) { return new Element(id,x,y,width,height,argb); }
        Element color(int color) { return new Element(id,x,y,width,height,color); }
        boolean inCircle() {
            return corner(x,y)&&corner(x+width,y)&&corner(x,y+height)&&corner(x+width,y+height);
        }
        private boolean corner(int x,int y) {
            long dx=(long)x-233,dy=(long)y-233;
            return dx*dx+dy*dy<=233L*233;
        }
        boolean overlaps(Element other) {
            return x<other.x+other.width&&x+width>other.x&&y<other.y+other.height&&y+height>other.y;
        }
    }
    static final class Background {
        final String sha256;
        final int width,height,x,y,size;
        Background(String hash,int width,int height,int x,int y,int size) {
            sha256=hash;this.width=width;this.height=height;this.x=x;this.y=y;this.size=size;
        }
        Background crop(int x,int y,int size) { return new Background(sha256,width,height,x,y,size); }
    }
    final String id,name;
    final int revision;
    final Background background;
    final List<Element> elements;
    DialProject(String id,int revision,String name,Background background,List<Element> elements) {
        this.id=id;this.revision=revision;this.name=name;this.background=background;
        ArrayList<Element> ordered=new ArrayList<>(elements);
        ordered.sort((a,b)->Integer.compare(DialRuntimeProfile.FIELDS.indexOf(DialRuntimeProfile.field(a.id)),
            DialRuntimeProfile.FIELDS.indexOf(DialRuntimeProfile.field(b.id))));
        this.elements=Collections.unmodifiableList(ordered);
    }
    static DialProject blank() {
        return new DialProject(UUID.randomUUID().toString(),0,"Мой циферблат",null,Collections.emptyList());
    }
    DialProject with(String name,Background bg,List<Element> nodes) {
        return new DialProject(id,revision,name,bg,nodes);
    }
    DialProject revised(int revision) { return new DialProject(id,revision,name,background,elements); }
    void validate(boolean requireBackground) {
        UUID.fromString(id);
        if(revision<0||name==null||(requireBackground&&name.trim().isEmpty())||name.length()>80||name.indexOf('\0')>=0)
            throw new IllegalArgumentException("Название: от 1 до 80 символов");
        if(background==null) {
            if(requireBackground) throw new IllegalArgumentException("Сначала выберите фотографию");
        } else {
            Background b=background;
            if(b.sha256==null||!b.sha256.matches("[0-9a-f]{64}")||b.width<=0||b.height<=0
                ||(long)b.width*b.height>DialImageNormalizer.MAX_PIXELS||b.size<1
                ||b.x<0||b.y<0||(long)b.x+b.size>b.width||(long)b.y+b.size>b.height)
                throw new IllegalArgumentException("Недопустимая область фотографии");
        }
        HashSet<String> ids=new HashSet<>();
        if(elements.size()>11) throw new IllegalArgumentException("Слишком много показателей");
        if(requireBackground&&elements.isEmpty())throw new IllegalArgumentException("Добавьте хотя бы один показатель");
        for(Element e:elements) {
            DialRuntimeProfile.Field f=DialRuntimeProfile.field(e.id);
            if(!ids.add(e.id)||e.width!=f.width||e.height!=f.height||(e.argb>>>24)!=255
                ||e.x<0||e.y<0||e.x>466-e.width||e.y>466-e.height||!e.inCircle())
                throw new IllegalArgumentException("Показатель выходит за круг или имеет неверные параметры");
        }
    }
    JSONObject toJson() throws JSONException {
        JSONObject j=new JSONObject().put("format","gtdial-project").put("schemaVersion",1)
            .put("id",id).put("revision",revision).put("name",name)
            .put("deviceProfile",DialRuntimeProfile.DEVICE).put("runtimeProfile",DialRuntimeProfile.ID);
        if(background!=null) {
            Background b=background;
            j.put("background",new JSONObject().put("source","source.png").put("sha256",b.sha256)
                .put("width",b.width).put("height",b.height)
                .put("crop",new JSONArray().put(b.x).put(b.y).put(b.size).put(b.size)));
        }
        JSONArray nodes=new JSONArray();
        for(Element e:elements) nodes.put(new JSONObject().put("id",e.id)
            .put("rect",new JSONArray().put(e.x).put(e.y).put(e.width).put(e.height)).put("argb",e.argb));
        return j.put("elements",nodes);
    }
    static DialProject fromJson(JSONObject j,boolean requireBackground) throws JSONException {
        if(!"gtdial-project".equals(j.getString("format"))||integer(j,"schemaVersion")!=1
            ||!DialRuntimeProfile.DEVICE.equals(j.getString("deviceProfile"))
            ||!DialRuntimeProfile.ID.equals(j.getString("runtimeProfile")))
            throw new IllegalArgumentException("Неподдерживаемый формат проекта");
        Background b=null;
        if(j.has("background")) {
            JSONObject bg=j.getJSONObject("background");JSONArray c=bg.getJSONArray("crop");
            if(!"source.png".equals(bg.getString("source"))||c.length()!=4||integer(c,2)!=integer(c,3))
                throw new IllegalArgumentException("Недопустимая область фотографии");
            b=new Background(bg.getString("sha256"),integer(bg,"width"),integer(bg,"height"),integer(c,0),integer(c,1),integer(c,2));
        }
        JSONArray nodes=j.getJSONArray("elements");
        if(nodes.length()>11) throw new IllegalArgumentException("Слишком много показателей");
        List<Element> elements=new ArrayList<>();
        for(int i=0;i<nodes.length();i++) {
            JSONObject n=nodes.getJSONObject(i);JSONArray r=n.getJSONArray("rect");
            if(r.length()!=4) throw new IllegalArgumentException("Недопустимые координаты");
            elements.add(new Element(n.getString("id"),integer(r,0),integer(r,1),integer(r,2),integer(r,3),integer(n,"argb")));
        }
        DialProject p=new DialProject(j.getString("id"),integer(j,"revision"),j.getString("name"),b,elements);
        p.validate(requireBackground);return p;
    }
    private static int integer(JSONObject j,String key) throws JSONException { return exactInteger(j.get(key)); }
    private static int integer(JSONArray j,int index) throws JSONException { return exactInteger(j.get(index)); }
    private static int exactInteger(Object value) {
        if(!(value instanceof Integer)&&!(value instanceof Long)) throw new IllegalArgumentException("Ожидалось целое число");
        long n=((Number)value).longValue();
        if(n<Integer.MIN_VALUE||n>Integer.MAX_VALUE) throw new IllegalArgumentException("Число вне диапазона");
        return (int)n;
    }
}
