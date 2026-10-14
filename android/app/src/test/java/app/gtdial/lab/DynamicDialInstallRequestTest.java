package app.gtdial.lab;
import org.junit.Test; import static org.junit.Assert.*; import java.nio.charset.StandardCharsets;
public class DynamicDialInstallRequestTest {
 @Test public void exactInstallSchema(){DynamicDialInstallRequest r=DynamicDialInstallRequest.install("/download/1909202601.dial","1909202601");assertEquals("svc_dial_install",r.service);assertEquals(1,r.destination);assertEquals(30,r.timeoutSeconds);assertArrayEquals("/download/1909202601.dial\0".getBytes(StandardCharsets.UTF_8),r.args[0]);assertArrayEquals("1909202601\0".getBytes(StandardCharsets.UTF_8),r.args[1]);}
 @Test public void exactApplySchema(){DynamicDialInstallRequest r=DynamicDialInstallRequest.apply("1909202601");assertEquals("svc_dial_apply",r.service);assertEquals(10,r.timeoutSeconds);assertEquals(1,r.args.length);}
 @Test(expected=IllegalArgumentException.class) public void rejectsEmbeddedNul(){DynamicDialInstallRequest.apply("bad\0alias");}
}
