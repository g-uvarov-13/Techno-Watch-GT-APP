package app.gtdial.lab;
import org.junit.Test; import static org.junit.Assert.*; import java.util.*;
public class DynamicDialInstallProtocolTest {
 static class T implements DynamicDialInstallProtocol.Transport { List<String> ops=new ArrayList<>(); int terminal=-1; public void request(String o,byte[][] p){ops.add(o);} public void log(String m){} public void terminal(boolean ok){terminal=ok?1:0;} }
 @Test public void exactOneShotFlowEndsApplied(){T t=new T(); byte[] d={1,2,3}; DynamicDialInstallProtocol p=new DynamicDialInstallProtocol(d,t); p.start(); p.preflightComplete(true,true,true,true); p.uploadComplete(true); p.readbackComplete(d); p.installComplete(0); p.inventoryComplete(true); p.applyComplete(0); p.verifyComplete(true); assertEquals(DynamicDialInstallProtocol.State.DONE,p.state()); assertEquals(1,t.terminal); assertEquals(Arrays.asList("preflight","upload","readback","svc_dial_install","inventory","svc_dial_apply","verify-current"),t.ops); }
 @Test public void mismatchIsTerminalAndDoesNotApply(){T t=new T(); DynamicDialInstallProtocol p=new DynamicDialInstallProtocol(new byte[]{1},t); p.start(); p.preflightComplete(true,true,true,true); p.uploadComplete(true); p.readbackComplete(new byte[]{2}); assertEquals(DynamicDialInstallProtocol.State.FAILED,p.state()); assertEquals(0,t.terminal); assertFalse(t.ops.contains("svc_dial_apply")); }
}
