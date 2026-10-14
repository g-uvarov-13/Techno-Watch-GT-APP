package app.gtdial.lab;

import org.json.JSONObject;
import java.nio.charset.CharacterCodingException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class ActivitySnapshot {
    final int steps, calorie, distance;
    final long receivedAt;
    private ActivitySnapshot(int s, int c, int d) { steps=s; calorie=c; distance=d; receivedAt=System.currentTimeMillis(); }
    static ActivitySnapshot parse(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length > 32 * 1024) throw new IllegalArgumentException("activity response size");
        String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        JSONObject o=new JSONObject(raw); if (o.length()!=3) throw new IllegalArgumentException("activity keys");
        return new ActivitySnapshot(value(o,"target.steps"),value(o,"target.calorie"),value(o,"target.distance"));
    }
    private static int value(JSONObject o,String key) {
        Object raw=o.opt(key);
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)) throw new IllegalArgumentException("activity key "+key);
        long value=((Number)raw).longValue();
        if(value<0||value>Integer.MAX_VALUE) throw new IllegalArgumentException("activity key "+key);
        return (int)value;
    }
}
