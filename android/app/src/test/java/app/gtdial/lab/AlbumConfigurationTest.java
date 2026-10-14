package app.gtdial.lab;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class AlbumConfigurationTest {
    @Test public void requestsAreExactUtf8Objects() throws Exception {
        assertArrayEquals("{\"keys\":[\"imageWatchFace\"]}".getBytes(StandardCharsets.UTF_8),
                AlbumConfiguration.request(AlbumConfiguration.IMAGE_KEY));
        assertArrayEquals("{\"keys\":[\"imageWatchFaceConvention\"]}".getBytes(StandardCharsets.UTF_8),
                AlbumConfiguration.request(AlbumConfiguration.CONVENTION_KEY));
    }

    @Test public void objectAndStringValuesParse() throws Exception {
        assertEquals("{\"type\":1}", AlbumConfiguration.parse("imageWatchFace", "{\"imageWatchFace\":{\"type\":1}}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("{\"type\":1}", AlbumConfiguration.parse("imageWatchFace", "{\"imageWatchFace\":\"{\\\"type\\\":1}\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("{\"maxCount\":5}", AlbumConfiguration.parse("imageWatchFaceConvention",
                "{\"imageWatchFaceConvention\":{\"maxCount\":5}}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test(expected = IllegalArgumentException.class) public void unsupportedRequestRejected() throws Exception { AlbumConfiguration.request("other"); }
    @Test(expected = IllegalArgumentException.class) public void nullRequestRejected() throws Exception { AlbumConfiguration.request(null); }
    @Test(expected = IllegalArgumentException.class) public void unsupportedParseRejected() throws Exception { AlbumConfiguration.parse("other", "{\"other\":{}}".getBytes(StandardCharsets.UTF_8)); }

    @Test public void malformedShapesAreRejected() {
        String[] bad = {"", "[]", "{\"imageWatchFace\":null}", "{\"imageWatchFace\":3}",
                "{\"imageWatchFace\":true}", "{\"imageWatchFace\":[]}", "{\"x\":{}}",
                "{\"imageWatchFace\":\"{broken\"}",
                "{\"imageWatchFace\":\"key was get value failed\"}"};
        for (String value : bad) assertParseRejected(value.getBytes(StandardCharsets.UTF_8));
        assertParseRejected(null);
    }

    @Test public void rawSizeBoundaryIsEnforced() throws Exception {
        byte[] base = "{\"imageWatchFace\":{}}".getBytes(StandardCharsets.UTF_8);
        byte[] atLimit = new byte[AlbumConfiguration.MAX_RAW_BYTES];
        System.arraycopy(base, 0, atLimit, 0, base.length);
        for (int i = base.length; i < atLimit.length; i++) atLimit[i] = ' ';
        assertEquals("{}", AlbumConfiguration.parse(AlbumConfiguration.IMAGE_KEY, atLimit));
        assertParseRejected(new byte[AlbumConfiguration.MAX_RAW_BYTES + 1]);
    }

    @Test public void malformedUtf8IsRejectedAndUnicodeIsAccepted() throws Exception {
        byte[][] malformed = {
                {(byte) 0x80},
                {(byte) 0xC3},
                {(byte) 0xC0, (byte) 0xAF},
                {(byte) 0xED, (byte) 0xA0, (byte) 0x80}
        };
        for (byte[] raw : malformed) assertParseRejected(raw);
        String parsed = AlbumConfiguration.parse(AlbumConfiguration.IMAGE_KEY,
                "{\"imageWatchFace\":{\"name\":\"часы⌚\"}}".getBytes(StandardCharsets.UTF_8));
        assertTrue(parsed.contains("часы⌚"));
    }

    private static void assertParseRejected(byte[] raw) {
        try {
            AlbumConfiguration.parse(AlbumConfiguration.IMAGE_KEY, raw);
            fail("Expected response rejection");
        } catch (Exception expected) {
            // Expected: the production parser must fail closed for malformed input.
        }
    }
}
