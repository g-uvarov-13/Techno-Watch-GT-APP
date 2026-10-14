package app.gtdial.lab;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class McfProtocolProbeTest {
    private static final class S implements McfProtocolProbe.Scheduler {
        Runnable pending;
        public void removeCallbacks(Runnable r) { if (pending == r) pending = null; }
        public void postDelayed(Runnable r, long d) { pending = r; }
        void fire() { if (pending != null) { Runnable r = pending; pending = null; r.run(); } }
    }
    private static final class T implements McfProtocolProbe.Transport {
        final List<byte[]> writes = new ArrayList<>(); final List<String> logs = new ArrayList<>();
        boolean acceptWrites = true; int finished; int terminal; Boolean success;
        public boolean write(byte[] f) { writes.add(f); return acceptWrites; }
        public void log(String s) { logs.add(s); }
        public void finished(boolean ok) { finished++; success=ok; }
        public void backupReady(java.io.File f) { }
        public void albumFinished(boolean ok) { terminal++; success = ok; }
        public void activityFinished(boolean ok, ActivitySnapshot snapshot) { terminal++; success = ok; }
    }

    @Test public void malformedConfigAndDuplicateInOneChunkFailsOnceAndSendsNoConvention() {
        S s = new S(); T t = new T(); McfProtocolProbe p = new McfProtocolProbe(null, t, s, c -> { throw new AssertionError(); });
        p.startAlbum();
        for (int i = 1; i <= 7; i++) p.onChunk(response(i, new byte[]{1}));
        int writesBeforeConfig = t.writes.size();
        p.onChunk(join(response(8, "{\"bad\":".getBytes(StandardCharsets.UTF_8)),
                response(8, "{\"imageWatchFace\":{}}".getBytes(StandardCharsets.UTF_8))));
        assertEquals(8, writesBeforeConfig); assertEquals(8, t.writes.size());
        assertEquals(1, t.terminal); assertEquals(Boolean.FALSE, t.success); assertEquals(0, t.finished);
        assertEquals("svc_systemstorage_get", serviceName(t.writes.get(7)));
        assertEquals("{\"keys\":[\"imageWatchFace\"]}", serviceInput(t.writes.get(7)));
    }

    @Test public void successfulFlowSendsTwoExactFixedRequestsAndCompletesOnce() {
        S s = new S(); T t = new T(); McfProtocolProbe p = new McfProtocolProbe(null, t, s, c -> { throw new AssertionError(); });
        p.startAlbum();
        for (int i = 1; i <= 7; i++) p.onChunk(response(i, new byte[]{1}));
        assertEquals(8, t.writes.size());
        assertEquals("svc_systemstorage_get", serviceName(t.writes.get(7)));
        assertEquals("{\"keys\":[\"imageWatchFace\"]}", serviceInput(t.writes.get(7)));

        p.onChunk(response(8, "{\"imageWatchFace\":{\"type\":1}}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(9, t.writes.size());
        assertEquals("svc_systemstorage_get", serviceName(t.writes.get(8)));
        assertEquals("{\"keys\":[\"imageWatchFaceConvention\"]}", serviceInput(t.writes.get(8)));
        assertFalse(serviceInput(t.writes.get(8)).contains("pool"));

        p.onChunk(response(9, "{\"imageWatchFaceConvention\":{\"maxCount\":5}}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(9, t.writes.size());
        assertEquals(1, t.terminal); assertEquals(Boolean.TRUE, t.success); assertEquals(0, t.finished);
    }

    @Test public void timeoutWriteRejectionAndLateResponseAreTerminal() {
        S s = new S(); T t = new T(); McfProtocolProbe p = new McfProtocolProbe(null, t, s, c -> { throw new AssertionError(); });
        p.startAlbum(); s.fire();
        assertEquals(1, t.terminal); assertEquals(Boolean.FALSE, t.success); int writes = t.writes.size();
        p.onChunk(response(1, new byte[]{1})); assertEquals(writes, t.writes.size()); assertEquals(1, t.terminal);
        S s2 = new S(); T t2 = new T(); t2.acceptWrites = false;
        McfProtocolProbe p2 = new McfProtocolProbe(null, t2, s2, c -> { throw new AssertionError(); }); p2.startAlbum();
        assertEquals(1, t2.terminal); assertEquals(Boolean.FALSE, t2.success); assertEquals(0, t2.finished);
        s2.fire(); assertEquals(1, t2.writes.size()); assertEquals(1, t2.terminal);
    }

    @Test public void genericProbeFailureIsReportedExactlyOnce() {
        S scheduler=new S(); T transport=new T(); transport.acceptWrites=false;
        McfProtocolProbe probe=new McfProtocolProbe(null,transport,scheduler,c->{throw new AssertionError();});
        probe.start();
        assertEquals(1,transport.finished);
        assertEquals(Boolean.FALSE,transport.success);
        int writes=transport.writes.size();
        probe.cancel("late cancel"); scheduler.fire(); probe.onChunk(response(1,new byte[]{1}));
        assertEquals(1,transport.finished);
        assertEquals(writes,transport.writes.size());
    }

    private static byte[] response(int id, byte[] payload) { return frame(new byte[]{1, 0, 1, (byte) id, 0x40}, payload); }
    private static byte[] join(byte[] a, byte[] b) { byte[] out = new byte[a.length + b.length]; System.arraycopy(a, 0, out, 0, a.length); System.arraycopy(b, 0, out, a.length, b.length); return out; }
    private static String serviceName(byte[] frame) {
        int end = 10;
        while (end < frame.length && frame[end] != 0) end++;
        return new String(frame, 10, end - 10, StandardCharsets.UTF_8);
    }
    private static String serviceInput(byte[] frame) {
        int end = 10;
        while (end < frame.length && frame[end] != 0) end++;
        int start = end + 2; // service-name NUL and service protocol version byte
        return new String(frame, start, frame.length - 3 - start, StandardCharsets.UTF_8);
    }
    private static byte[] frame(byte[] d2d, byte[] payload) {
        byte[] d = new byte[d2d.length + payload.length]; System.arraycopy(d2d, 0, d, 0, d2d.length); System.arraycopy(payload, 0, d, d2d.length, payload.length);
        int n = d.length + 8; byte[] f = new byte[n]; f[0] = (byte) 0xFC; f[1] = (byte) (n >>> 8); f[2] = (byte) n; f[3] = 1; System.arraycopy(d, 0, f, 5, d.length);
        int crc = 0xFFFF; for (int i = 1; i < n - 3; i++) { crc ^= f[i] & 255; for (int bit = 0; bit < 8; bit++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1; }
        f[n - 3] = (byte) (crc >>> 8); f[n - 2] = (byte) crc; f[n - 1] = (byte) 0xCF; return f;
    }
}
