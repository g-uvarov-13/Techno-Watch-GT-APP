package app.gtdial.lab;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class AlbumStageProtocolTest {
    static final class S implements AlbumStageProtocol.Scheduler {
        Runnable pending;
        public void removeCallbacks(Runnable r) { if (pending == r) pending = null; }
        public void postDelayed(Runnable r, long ms) { pending = r; }
        void fire() { Runnable r = pending; pending = null; if (r != null) r.run(); }
    }
    static final class T implements AlbumStageProtocol.Transport {
        final List<byte[]> writes = new ArrayList<>();
        boolean accept = true; int terminals; Boolean success; final List<String> logs=new ArrayList<>();
        public boolean write(byte[] frame) { writes.add(frame); return accept; }
        public void log(String message) { logs.add(message); }
        public boolean backup(byte[] bytes,String sha256) { return true; }
        public void terminal(boolean ok) { terminals++; success = ok; }
    }

    @Test public void acceptsCapturedGt01WatchToPhoneResponseAddressing() {
        S s = new S(); T t = new T();
        AlbumStageProtocol p = new AlbumStageProtocol(new byte[10], "gtdl_1234567890123.png", t, s);
        p.start();
        assertEquals(1, t.writes.size());
        p.onChunk(hex("FC 00 12 EE 00 01 01 00 01 40 04 00 00 00 00 6F 4A CF"));
        assertEquals("the physical response must advance to _link_up2", 2, t.writes.size());
        assertEquals(0, t.terminals);
    }

    @Test public void completeBoundedStageUses384ByteChunksAndStartsApply() {
        byte[] image = new byte[5000]; for (int i=0;i<image.length;i++) image[i]=(byte)i;
        S s=new S(); T t=new T(); AlbumStageProtocol p=new AlbumStageProtocol(image,"gtdl_1234567890123.png",t,s);
        p.start();
        respond(p,1,new byte[]{1}); respond(p,2,new byte[]{1}); respond(p,3,new byte[]{1}); respond(p,4,new byte[]{1}); respond(p,5,new byte[]{1});
        respond(p,6,"{\"id\":\"com.QC.qc176\",\"alias\":\"1789483905\"}".getBytes(StandardCharsets.UTF_8));
        respond(p,7,"[{\"id\":\"com.QC.album\",\"alias\":\"60001001\"}]".getBytes(StandardCharsets.UTF_8));
        respond(p,8,"{\"imageWatchFace\":{\"positionIndex\":1,\"styleIndex\":1,\"imageWatchFaceList\":[]}}".getBytes(StandardCharsets.UTF_8));
        respond(p,9,"{\"imageWatchFaceConvention\":{\"maxCount\":5,\"imageTypes\":[0]}}".getBytes(StandardCharsets.UTF_8));
        respond(p,10,ffi(-1)); respond(p,11,ffi(-1)); respond(p,12,ffi(4));
        assertEquals("write_by_offset", ffiName(t.writes.get(12)));
        assertTrue(has(t.writes.get(11), littleU32(578)));
        int id=13,off=0;
        while(off<image.length){int n=Math.min(384,image.length-off);respond(p,id++,ffi(n));off+=n;}
        respond(p,id++,ffi(0)); respond(p,id++,ffi(-1));
        assertEquals("rename",ffiName(t.writes.get(id-1)));
        assertTrue(hasText(t.writes.get(id-1),"/user/widgets/indexDial/gtdl_1234567890123.png.cache\0"));
        assertTrue(hasText(t.writes.get(id-1),"/user/widgets/indexDial/gtdl_1234567890123.png\0"));
        respond(p,id++,ffi(0)); respond(p,id++,ffi(0)); respond(p,id++,ffi(5)); respond(p,id++,ffi(5000));
        off=0; while(off<image.length){int n=Math.min(384,image.length-off);respond(p,id++,read(image,off,n));off+=n;}
        respond(p,id,ffi(0));
        assertFalse(p.isVerified()); assertEquals(0,t.terminals);
        String all=allAscii(t.writes); assertTrue(all.contains("msg_recv_ubjson"));
        for(String forbidden:new String[]{"remove","mkdir_r","exception_cleanup"}) assertFalse(forbidden,all.contains(forbidden));
    }

    @Test public void shortWriteIsTerminalAndLateResponseDoesNothing() {
        byte[] image=new byte[10]; S s=new S(); T t=new T(); AlbumStageProtocol p=toFirstWrite(image,s,t);
        int before=t.writes.size(); respond(p,12,ffi(9)); assertEquals(1,t.terminals); assertEquals(Boolean.FALSE,t.success); assertEquals(before,t.writes.size());
        respond(p,12,ffi(10)); assertEquals(before,t.writes.size()); assertEquals(1,t.terminals);
    }

    @Test public void existingFinalAndTimeoutFailOnceWithoutFollowup() {
        byte[] image=new byte[10]; S s=new S(); T t=new T(); AlbumStageProtocol p=startThroughConvention(image,s,t);
        int before=t.writes.size(); respond(p,10,ffi(0)); assertEquals(before,t.writes.size()); assertEquals(1,t.terminals);
        S s2=new S();T t2=new T();AlbumStageProtocol p2=new AlbumStageProtocol(image,"gtdl_1234567890123.png",t2,s2);p2.start();int writes=t2.writes.size();s2.fire();assertEquals(writes,t2.writes.size());assertEquals(1,t2.terminals);respond(p2,1,new byte[]{1});assertEquals(writes,t2.writes.size());
    }

    @Test public void dynamicDialUsesFixedPathAndRequiresExactPreflightInventory() {
        byte[] dial;
        try { dial=java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/main/assets/dynamic_dials/pipboy01/face.dial")); }
        catch(java.io.IOException e){throw new AssertionError(e);}
        S s=new S(); T t=new T(); AlbumStageProtocol p=new AlbumStageProtocol(dial,t,s,509,true); p.start();
        for(int i=1;i<=5;i++)respond(p,i,new byte[]{1});
        respond(p,6,"{\"alias\":\"1789773882\"}".getBytes(StandardCharsets.UTF_8));
        respond(p,7,"[{\"id\":\"com.QC.qc176\",\"alias\":\"1789773882\"},{\"id\":\"com.gtdial.pipboy01\",\"alias\":\"1909202601\"}]".getBytes(StandardCharsets.UTF_8));
        assertEquals("access",ffiName(t.writes.get(7)));
        assertTrue(hasText(t.writes.get(7),"/download/1909202601.dial\0"));
        assertEquals(0,t.terminals);
    }

    @Test public void everyPinnedVariantCanEnterTheExperimentalFixedPathProtocol() throws Exception {
        byte[] dial=java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/main/assets/dynamic_dials/pixbot/ice/face.dial"));
        S s=new S();T t=new T();
        AlbumStageProtocol protocol=new AlbumStageProtocol(dial,"com.gtdial.pixeldeck","2309202603",t,s,509,true);
        protocol.start();assertEquals(1,t.writes.size());
    }

    @Test public void currentVerificationAcceptsOnlyExactSelectedIdOrAlias(){
        assertTrue(AlbumStageProtocol.selectedCurrentIdentifierMatches("com.gtdial.nixiecore","com.gtdial.nixiecore","2309202602"));
        assertTrue(AlbumStageProtocol.selectedCurrentIdentifierMatches("2309202602","com.gtdial.nixiecore","2309202602"));
        assertFalse(AlbumStageProtocol.selectedCurrentIdentifierMatches("com.gtdial.pipboy01","com.gtdial.nixiecore","2309202602"));
    }

    @Test public void exactOwnedStagingCanOnlyBeReusedOrReplacedByPinnedPackage(){
        String oldHash="928f548c744539c751f20c2d11771f21c052923beab258056d3f6ff21f219e3e";
        assertEquals(AlbumStageProtocol.ExistingDialAction.REUSE,AlbumStageProtocol.classifyExistingDial(536153L,oldHash,536153L,oldHash));
        assertEquals(AlbumStageProtocol.ExistingDialAction.REPLACE,AlbumStageProtocol.classifyExistingDial(536153L,oldHash,465655L,"d1cdbc97daca371fa034c5eaa7f18c40374311047e40b5aafb25c681df64b7d3"));
        assertEquals(AlbumStageProtocol.ExistingDialAction.REJECT,AlbumStageProtocol.classifyExistingDial(465655L,"d1cdbc97daca371fa034c5eaa7f18c40374311047e40b5aafb25c681df64b7d3",465655L,"d1cdbc97daca371fa034c5eaa7f18c40374311047e40b5aafb25c681df64b7d3"));
    }

    @Test public void duplicateSelectedIdentityStopsAtInventoryBeforeStagingAccess() throws Exception {
        byte[] dial=java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/main/assets/dynamic_dials/pipboy01/face.dial"));
        S s=new S();T t=new T();AlbumStageProtocol p=new AlbumStageProtocol(dial,t,s,509,true);p.start();
        for(int i=1;i<=5;i++)respond(p,i,new byte[]{1});
        respond(p,6,"{\"alias\":\"1789773882\"}".getBytes(StandardCharsets.UTF_8));
        respond(p,7,"[{\"id\":\"com.QC.qc176\",\"alias\":\"1789773882\"},{\"id\":\"com.gtdial.pipboy01\",\"alias\":\"1909202601\"},{\"id\":\"com.gtdial.pipboy01\",\"alias\":\"1909202601\"}]".getBytes(StandardCharsets.UTF_8));
        assertEquals(1,t.terminals);assertEquals(Boolean.FALSE,t.success);assertEquals(7,t.writes.size());assertFalse(allAscii(t.writes).contains("/download/1909202601.dial"));
    }

    @Test public void dynamicUnexpectedIncomingFrameStopsWithoutFollowup() throws Exception {
        byte[] dial=java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/main/assets/dynamic_dials/pipboy01/face.dial"));S s=new S();T t=new T();AlbumStageProtocol p=new AlbumStageProtocol(dial,t,s,509,true);p.start();int sent=t.writes.size();
        byte[] incoming=frame(1,new byte[]{1});incoming[9]=0x10;repairCrc(incoming);p.onChunk(incoming);
        assertEquals(1,t.terminals);assertEquals(Boolean.FALSE,t.success);assertEquals(sent,t.writes.size());
    }

    @Test public void capturedLauncherBackgroundRequestDoesNotAbortInstallRead() throws Exception {
        byte[] dial=java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/main/assets/dynamic_dials/pipboy01/face.dial"));
        S s=new S();T t=new T();AlbumStageProtocol p=new AlbumStageProtocol(dial,t,s,509,true);p.start();
        byte[] body="msg_send_ubjson\0\1launcher local.control".getBytes(StandardCharsets.ISO_8859_1);
        byte[] incoming=frame(64,body);incoming[6]=1;incoming[7]=0;incoming[9]=0x10;repairCrc(incoming);
        p.onChunk(incoming);
        assertEquals(0,t.terminals);assertEquals(1,t.writes.size());
        respond(p,1,ffi(0));
        assertEquals(2,t.writes.size());
    }

    @Test public void identicalVerifiedStagingSkipsRedundantWriteAndReadback() throws Exception {
        byte[] dial=java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/main/assets/dynamic_dials/pipboy01/face.dial"));
        S s=new S();T t=new T();AlbumStageProtocol p=new AlbumStageProtocol(dial,t,s,509,true);p.start();
        for(int i=1;i<=5;i++)respond(p,i,new byte[]{1});
        respond(p,6,"{\"alias\":\"1789773882\"}".getBytes(StandardCharsets.UTF_8));
        respond(p,7,"[{\"id\":\"com.QC.qc176\",\"alias\":\"1789773882\"},{\"id\":\"com.gtdial.pipboy01\",\"alias\":\"1909202601\"}]".getBytes(StandardCharsets.UTF_8));
        respond(p,8,ffi(0));respond(p,9,ffi(3));respond(p,10,ffi(dial.length));respond(p,11,ffi(dial.length));
        int id=12;for(int off=0;off<dial.length;){int n=Math.min(384,dial.length-off);respond(p,id++,read(dial,off,n));off+=n;}
        respond(p,id,ffi(0));
        assertEquals("svc_dial_install",ffiName(t.writes.get(t.writes.size()-1)));
        assertFalse(allAscii(t.writes).contains("write_by_offset"));
        assertEquals(0,t.terminals);
    }

    private static AlbumStageProtocol toFirstWrite(byte[] image,S s,T t){AlbumStageProtocol p=startThroughConvention(image,s,t);respond(p,10,ffi(-1));respond(p,11,ffi(4));return p;}
    private static AlbumStageProtocol startThroughConvention(byte[] image,S s,T t){AlbumStageProtocol p=new AlbumStageProtocol(image,"gtdl_1234567890123.png",t,s);p.start();for(int i=1;i<=5;i++)respond(p,i,new byte[]{1});respond(p,6,"{\"alias\":\"1789483905\"}".getBytes(StandardCharsets.UTF_8));respond(p,7,"[]".getBytes(StandardCharsets.UTF_8));respond(p,8,"{\"imageWatchFace\":{}}".getBytes(StandardCharsets.UTF_8));respond(p,9,"{\"imageWatchFaceConvention\":{\"maxCount\":5,\"imageTypes\":[0]}}".getBytes(StandardCharsets.UTF_8));return p;}
    private static void respond(AlbumStageProtocol p,int id,byte[] payload){p.onChunk(frame(id,payload));}
    private static byte[] ffi(int n){return new byte[]{4,(byte)n,(byte)(n>>>8),(byte)(n>>>16),(byte)(n>>>24)};}
    private static byte[] read(byte[] src,int off,int len){byte[] r=new byte[8+len];System.arraycopy(ffi(len),0,r,0,5);r[5]=(byte)0xC1;r[6]=(byte)len;r[7]=(byte)(len>>>8);System.arraycopy(src,off,r,8,len);return r;}
    private static byte[] frame(int id,byte[] payload){byte[] d=new byte[5+payload.length];d[0]=1;d[1]=1;d[2]=0;d[3]=(byte)id;d[4]=0x40;System.arraycopy(payload,0,d,5,payload.length);int n=d.length+8;byte[] f=new byte[n];f[0]=(byte)0xFC;f[1]=(byte)(n>>>8);f[2]=(byte)n;f[3]=1;System.arraycopy(d,0,f,5,d.length);int c=0xFFFF;for(int i=1;i<n-3;i++){c^=f[i]&255;for(int b=0;b<8;b++)c=(c&1)!=0?(c>>>1)^0xA001:c>>>1;}f[n-3]=(byte)(c>>>8);f[n-2]=(byte)c;f[n-1]=(byte)0xCF;return f;}
    private static void repairCrc(byte[] f){int c=0xFFFF;for(int i=1;i<f.length-3;i++){c^=f[i]&255;for(int b=0;b<8;b++)c=(c&1)!=0?(c>>>1)^0xA001:c>>>1;}f[f.length-3]=(byte)(c>>>8);f[f.length-2]=(byte)c;}
    private static String ffiName(byte[] f){int p=10;while(f[p]!=0)p++;p+=2;if((f[p]&255)!=0x81)throw new AssertionError();int n=(f[p+1]&255)|((f[p+2]&255)<<8);return new String(f,p+3,n,StandardCharsets.UTF_8);}
    private static boolean hasText(byte[] b,String s){return has(b,s.getBytes(StandardCharsets.UTF_8));}private static boolean has(byte[] b,byte[] q){outer:for(int i=0;i+q.length<=b.length;i++){for(int j=0;j<q.length;j++)if(b[i+j]!=q[j])continue outer;return true;}return false;}
    private static byte[] littleU32(int n){return new byte[]{4,(byte)n,(byte)(n>>>8),(byte)(n>>>16),(byte)(n>>>24)};}
    private static String allAscii(List<byte[]> frames){StringBuilder x=new StringBuilder();for(byte[] b:frames)x.append(new String(b,StandardCharsets.ISO_8859_1));return x.toString();}
    private static byte[] hex(String value){String[] parts=value.split(" ");byte[] out=new byte[parts.length];for(int i=0;i<parts.length;i++)out[i]=(byte)Integer.parseInt(parts[i],16);return out;}
}
