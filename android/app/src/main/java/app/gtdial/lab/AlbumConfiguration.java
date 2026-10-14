package app.gtdial.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Fixed, read-only requests and bounded parsing for the GT01 photo dial configuration. */
final class AlbumConfiguration {
    static final int MAX_RAW_BYTES = 32 * 1024;
    static final String IMAGE_KEY = "imageWatchFace";
    static final String CONVENTION_KEY = "imageWatchFaceConvention";

    private AlbumConfiguration() { }

    static byte[] request(String key) throws Exception {
        if (!IMAGE_KEY.equals(key) && !CONVENTION_KEY.equals(key)) {
            throw new IllegalArgumentException("Unsupported album configuration key");
        }
        JSONArray keys = new JSONArray().put(key);
        return new JSONObject().put("keys", keys).toString().getBytes(StandardCharsets.UTF_8);
    }

    static String parse(String key, byte[] raw) throws Exception {
        if (!IMAGE_KEY.equals(key) && !CONVENTION_KEY.equals(key)) {
            throw new IllegalArgumentException("Unsupported album configuration key");
        }
        if (raw == null || raw.length == 0 || raw.length > MAX_RAW_BYTES) {
            throw new IllegalArgumentException("bounded raw response rejected: " + (raw == null ? 0 : raw.length) + " B");
        }
        String text = strictUtf8(raw).trim();
        if (text.isEmpty()) throw new IllegalArgumentException("empty JSON response");
        JSONObject top = new JSONObject(text);
        if (!top.has(key) || top.isNull(key)) throw new IllegalArgumentException("missing or null key " + key);
        Object value = top.get(key);
        JSONObject object;
        if (value instanceof JSONObject) object = (JSONObject) value;
        else if (value instanceof String) object = new JSONObject((String) value);
        else throw new IllegalArgumentException("unexpected type for " + key);
        String result = object.toString();
        if (result.contains("key was get value failed")) throw new IllegalArgumentException("key was get value failed");
        return result;
    }

    static void logFields(String key, String objectJson, McfProtocolProbe.Transport transport) throws Exception {
        JSONObject object = new JSONObject(objectJson);
        if (IMAGE_KEY.equals(key)) {
            transport.log("ALBUM parsed imageWatchFace: type=" + printable(object, "type")
                    + ", positionIndex=" + printable(object, "positionIndex")
                    + ", styleIndex=" + printable(object, "styleIndex")
                    + ", color=" + printable(object, "color")
                    + ", imageWatchFaceList=" + printable(object, "imageWatchFaceList"));
            Object list = object.opt("imageWatchFaceList");
            if (list instanceof JSONArray) {
                JSONArray entries = (JSONArray) list;
                for (int i = 0; i < entries.length(); i++) {
                    Object entry = entries.opt(i);
                    if (entry instanceof JSONObject) {
                        JSONObject item = (JSONObject) entry;
                        transport.log("ALBUM imageWatchFaceList[" + i + "]: index="
                                + printable(item, "index") + ", name=" + printable(item, "name"));
                    } else {
                        transport.log("ALBUM imageWatchFaceList[" + i + "] observed type="
                                + (entry == null ? "null" : entry.getClass().getSimpleName()));
                    }
                }
            } else if (list != null && list != JSONObject.NULL) {
                transport.log("ALBUM imageWatchFaceList observed type=" + list.getClass().getSimpleName());
            }
        } else {
            transport.log("ALBUM parsed imageWatchFaceConvention: maxCount=" + printable(object, "maxCount")
                    + ", imageTypes=" + printable(object, "imageTypes")
                    + ", maxSize=" + printable(object, "maxSize"));
        }
        JSONArray names = object.names();
        if (names != null) {
            StringBuilder unknown = new StringBuilder();
            for (int i = 0; i < names.length(); i++) {
                String name = names.getString(i);
                if (!isRequired(key, name)) {
                    if (unknown.length() > 0) unknown.append(',');
                    unknown.append(name);
                }
            }
            transport.log("ALBUM unknown fields " + key + ": " + (unknown.length() == 0 ? "<none>" : unknown));
        }
    }

    private static boolean isRequired(String key, String name) {
        if (IMAGE_KEY.equals(key)) return name.equals("type") || name.equals("positionIndex")
                || name.equals("styleIndex") || name.equals("color") || name.equals("imageWatchFaceList");
        return name.equals("maxCount") || name.equals("imageTypes") || name.equals("maxSize");
    }

    private static String printable(JSONObject object, String name) {
        return object.has(name) ? String.valueOf(object.opt(name)) : "<missing>";
    }

    private static String strictUtf8(byte[] raw) throws CharacterCodingException {
        CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw));
        return chars.toString();
    }
}
