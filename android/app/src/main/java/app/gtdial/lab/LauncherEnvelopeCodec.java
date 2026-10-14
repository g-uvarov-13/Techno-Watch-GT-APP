package app.gtdial.lab;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/** Official launcher message envelope and strict reassembly boundary. */
final class LauncherEnvelopeCodec {
    static final int SUCCESS = 205, FAILURE = 204;
    private LauncherEnvelopeCodec() { }

    static byte[] innerJson(int intent, Object data, int taskId) {
        try { return new JSONObject().put("intent", intent).put("data", data).put("taskId", taskId).toString().getBytes(StandardCharsets.UTF_8); }
        catch (Exception e) { throw new IllegalArgumentException("inner JSON", e); }
    }
    static byte[] outgoing(String app, byte[] inner, long timestamp) {
        if (app == null || inner == null || inner.length > UbjsonCodec.MAX_BYTES) throw new IllegalArgumentException("message bounds");
        Map<String,Object> content=new LinkedHashMap<>(); content.put("app",app);content.put("total",(long)inner.length);content.put("msg",inner);content.put("index",(long)inner.length);content.put("timestamp",timestamp);
        Map<String,Object> body=new LinkedHashMap<>();body.put("tag","message");body.put("content",content);
        Map<String,Object> outer=new LinkedHashMap<>();outer.put("from","phone");outer.put("to","launcher");outer.put("body",body); return UbjsonCodec.encode(outer);
    }
    static Incoming parseIncoming(byte[] raw) {
        Object root=UbjsonCodec.decode(raw); if(!(root instanceof Map)) throw bad("outer"); Map<?,?> o=(Map<?,?>)root;
        Object from=o.get("from"); if (!"launcher".equals(from) && !"com.realthread.Menuapplication".equals(from)) throw bad("from");
        need(o,"to","local.control"); Map<?,?> body=map(o.get("body"));need(body,"tag","message");Map<?,?> c=map(body.get("content"));
        String app=str(c.get("app")); byte[] msg=bytes(c.get("msg")); long total=num(c.get("total")), index=num(c.get("index")), ts=num(c.get("timestamp"));
        if(total<0||total>UbjsonCodec.MAX_BYTES||index<0||index>total||msg.length>total)throw bad("bounds"); return new Incoming(app,total,index,ts,msg);
    }
    private static void need(Map<?,?> m,String k,String v){if(!v.equals(m.get(k)))throw bad(k);}
    private static Map<?,?> map(Object x){if(!(x instanceof Map))throw bad("object");return (Map<?,?>)x;}
    private static String str(Object x){if(!(x instanceof String))throw bad("string");return (String)x;}
    private static byte[] bytes(Object x){if(!(x instanceof byte[]))throw bad("byte array");return (byte[])x;}
    private static long num(Object x){if(!(x instanceof Long))throw bad("integer");return (Long)x;}
    private static IllegalArgumentException bad(String x){return new IllegalArgumentException("launcher UBJSON: "+x);}
    static final class Incoming { final String app;final long total,index,timestamp;final byte[] msg; Incoming(String a,long t,long i,long ts,byte[] m){app=a;total=t;index=i;timestamp=ts;msg=m;} }

    static final class Reassembler {
        private long timestamp=-1,total=-1,index; private final java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream();
        byte[] accept(Incoming in){if(in==null||!"local.control".equals(in.app))throw bad("app");if(timestamp<0){timestamp=in.timestamp;total=in.total;}if(in.timestamp!=timestamp||in.total!=total||in.index!=index+in.msg.length)throw bad("sequence");data.write(in.msg,0,in.msg.length);index=in.index;if(index==total)return data.toByteArray();return null;}
    }

    static final class D2dResponse { final int source,destination,id,priority,reserve,type,payload; D2dResponse(int src,int dst,int id,int priority,int reserve,int payload){check(src);check(dst);check(id);checkSmall(priority);checkSmall(reserve);source=dst;destination=src;this.id=id;this.priority=priority;this.reserve=reserve;type=1;this.payload=payload;} }
    static D2dResponse responseFor(int source,int destination,int id,int priority,int reserve,boolean ok){return new D2dResponse(source,destination,id,priority,reserve,ok?SUCCESS:FAILURE);}
    private static void check(int n){if(n<0||n>255)throw bad("metadata");} private static void checkSmall(int n){if(n<0||n>3)throw bad("metadata");}

    static InnerMessage parseInner(byte[] raw) {
        try { if(raw==null) throw bad("inner null"); String decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString(); JSONObject json=new JSONObject(decoded);
            if (!json.has("intent") || !json.has("taskId") || !json.has("data")) throw bad("inner fields");
            return new InnerMessage(json.getInt("intent"), json.getInt("taskId"), json.get("data"));
        } catch (Exception e) { throw e instanceof IllegalArgumentException ? (IllegalArgumentException)e : bad("inner JSON"); }
    }
    static final class InnerMessage { final int intent,taskId; final Object data; InnerMessage(int i,int t,Object d){intent=i;taskId=t;data=d;} boolean matches(int expectedTask,int expectedIntent){return taskId==expectedTask&&intent==expectedIntent;} }
}
