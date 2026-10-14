package app.gtdial.lab;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class LauncherEnvelopeCodecTest {
    @Test public void officialGoldenIncomingPhysicalFixture() {
        String h="7b550466726f6d5355086c61756e636865725502746f53550d6c6f63616c2e636f6e74726f6c5504626f64797b55037461675355076d6573736167655507636f6e74656e747b550361707053550d6c6f63616c2e636f6e74726f6c5505746f74616c552655036d73675b24552355267b22696e74656e74223a32313330302c2264617461223a22222c227461736b4964223a2d317d5505696e6465785526550974696d657374616d7055077d7d7d";
        LauncherEnvelopeCodec.Incoming in=LauncherEnvelopeCodec.parseIncoming(unhex(h));
        assertEquals("local.control",in.app); assertEquals(38,in.total); assertEquals(38,in.index); assertEquals(7,in.timestamp);
        LauncherEnvelopeCodec.InnerMessage msg=LauncherEnvelopeCodec.parseInner(in.msg);
        assertTrue(msg.matches(-1,21300)); assertEquals("",msg.data);
    }
    @Test public void officialGoldenOutgoingEnvelope() {
        byte[] actual=LauncherEnvelopeCodec.outgoing("launcher", "x".getBytes(StandardCharsets.UTF_8), 1);
        assertEquals("7b550466726f6d53550570686f6e655502746f5355086c61756e636865725504626f64797b55037461675355076d6573736167655507636f6e74656e747b55036170705355086c61756e636865725505746f74616c550155036d73675b2455235501785505696e6465785501550974696d657374616d7055017d7d7d", hex(actual));
    }
    @Test public void reassemblerRequiresMonotonicSameMetadata() {
        LauncherEnvelopeCodec.Reassembler r=new LauncherEnvelopeCodec.Reassembler();
        assertNull(r.accept(new LauncherEnvelopeCodec.Incoming("local.control",2,1,7,new byte[]{'a'})));
        try { r.accept(new LauncherEnvelopeCodec.Incoming("local.control",2,3,7,new byte[]{'b'})); fail(); } catch (IllegalArgumentException expected) { }
        try { r.accept(new LauncherEnvelopeCodec.Incoming("other",2,1,7,new byte[]{'a'})); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void malformedAndOversizeRejected() {
        try { LauncherEnvelopeCodec.parseIncoming(new byte[]{'{'}); fail(); } catch (IllegalArgumentException expected) { }
        try { LauncherEnvelopeCodec.parseIncoming(new byte[UbjsonCodec.MAX_BYTES+1]); fail(); } catch (IllegalArgumentException expected) { }
        try { LauncherEnvelopeCodec.parseInner(new byte[]{(byte)0xc3,0x28}); fail(); } catch (IllegalArgumentException expected) { }
        try { UbjsonCodec.decode(unhex("7b5501615502557d")); fail(); } catch (IllegalArgumentException expected) { }
    }
    private static byte[] unhex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
    @Test public void responseSwapsEndpointsAndUsesAckCode() {
        LauncherEnvelopeCodec.D2dResponse r=LauncherEnvelopeCodec.responseFor(1,2,9,3,3,true);
        assertEquals(2,r.source);assertEquals(1,r.destination);assertEquals(9,r.id);assertEquals(3,r.priority);assertEquals(3,r.reserve);assertEquals(1,r.type);assertEquals(205,r.payload);
        assertEquals(204,LauncherEnvelopeCodec.responseFor(1,2,9,0,0,false).payload);
        try { LauncherEnvelopeCodec.responseFor(1,2,9,4,0,true); fail(); } catch (IllegalArgumentException expected) { }
        try { LauncherEnvelopeCodec.responseFor(256,2,9,0,0,true); fail(); } catch (IllegalArgumentException expected) { }
    }
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x&255));return s.toString();}
}
