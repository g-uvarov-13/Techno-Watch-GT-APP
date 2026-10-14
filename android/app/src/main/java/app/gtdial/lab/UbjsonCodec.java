package app.gtdial.lab;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict UBJSON subset matching the bundled official Python encoder. */
final class UbjsonCodec {
    static final int MAX_BYTES = 32 * 1024;
    private UbjsonCodec() { }

    static byte[] encode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); encodeValue(out, value); return out.toByteArray();
    }
    static Object decode(byte[] bytes) { if (bytes == null || bytes.length > MAX_BYTES) throw bad("bounds"); Decoder d = new Decoder(bytes); Object v=d.value(); if(!d.done()) throw bad("trailing bytes"); return v; }
    private static void encodeValue(ByteArrayOutputStream o,Object v) {
        if(v==null){o.write('Z');return;} if(v instanceof String){byte[] b=((String)v).getBytes(StandardCharsets.UTF_8); if(b.length==1){o.write('C');}else{o.write('S'); integer(o,b.length);} o.write(b,0,b.length);return;}
        if(v instanceof Boolean){o.write((Boolean)v?'T':'F');return;} if(v instanceof Byte[]){byte[] b=new byte[((Byte[])v).length];for(int i=0;i<b.length;i++)b[i]=((Byte[])v)[i];v=b;}
        if(v instanceof byte[]){byte[] b=(byte[])v;o.write('[');o.write('$');o.write('U');o.write('#');integer(o,b.length);o.write(b,0,b.length);return;}
        if(v instanceof Number){integer(o,((Number)v).longValue());return;}
        if(v instanceof Map){Map<?,?> m=(Map<?,?>)v;o.write('{');for(Map.Entry<?,?> e:m.entrySet()){if(!(e.getKey() instanceof String))throw bad("key");byte[] k=((String)e.getKey()).getBytes(StandardCharsets.UTF_8);integer(o,k.length);o.write(k,0,k.length);encodeValue(o,e.getValue());}o.write('}');return;}
        if(v instanceof List){o.write('[');for(Object x:(List<?>)v)encodeValue(o,x);o.write(']');return;} throw bad("type "+v.getClass());
    }
    private static void integer(ByteArrayOutputStream o,long n){if(n>=0&&n<256){o.write('U');o.write((int)n);return;}if(n>=-128&&n<128){o.write('i');o.write((int)n);return;}if(n>=-32768&&n<32768){o.write('I');put(o,'>',2,n);return;}if(n>=Integer.MIN_VALUE&&n<=Integer.MAX_VALUE){o.write('l');put(o,'>',4,n);return;}o.write('L');put(o,'>',8,n);}
    private static void put(ByteArrayOutputStream o,char ignored,int size,long n){for(int i=size-1;i>=0;i--)o.write((int)(n>>>(i*8)));}
    private static IllegalArgumentException bad(String s){return new IllegalArgumentException("UBJSON: "+s);}

    private static final class Decoder { final byte[] b; int p; int depth; Decoder(byte[] b){this.b=b;} boolean done(){return p==b.length;}
        Object value(){int m=next();switch(m){case 'Z':return null;case 'T':return true;case 'F':return false;case 'i':return (long)(byte)next();case 'U':return (long)next();case 'I':return signed(2);case 'l':return signed(4);case 'L':return signed(8);case 'C':return text(1);case 'S':return text(length());case '[':return container(false);case '{':return container(true);default:throw bad("marker "+m);}}
        private Object container(boolean object){if(++depth>16)throw bad("nesting depth");try{return object?object():array();}finally{depth--;}}
        private Object array(){if(peek('$')){next();if(next()!='U')throw bad("byte type");if(next()!='#')throw bad("byte count");int n=length();if(n<0||p+n>b.length)throw bad("byte bounds");byte[] out=Arrays.copyOfRange(b,p,p+n);p+=n;return out;}List<Object> a=new ArrayList<>();while(!peek(']')){if(done())throw bad("array eof");a.add(value());}next();return a;}
        private Object object(){Map<String,Object> m=new LinkedHashMap<>();while(!peek('}')){int n=length();String k=text(n);if(m.containsKey(k))throw bad("duplicate key");m.put(k,value());}next();return m;}
        private int length(){Object n=value();if(!(n instanceof Long)||((Long)n)<0||((Long)n)>MAX_BYTES)throw bad("length");return ((Long)n).intValue();}
        private long signed(int n){if(p+n>b.length)throw bad("integer eof");long x=0;for(int i=0;i<n;i++)x=(x<<8)|(b[p++]&255);if(n<8&&((x&(1L<<(n*8-1)))!=0))x|=(-1L)<<(n*8);return x;}
        private String text(int n){if(n<0||p+n>b.length)throw bad("string bounds");try{String s=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b,p,n)).toString();p+=n;return s;}catch(CharacterCodingException e){throw bad("invalid UTF-8");}}
        private int next(){if(done())throw bad("eof");return b[p++]&255;}private boolean peek(char c){return !done()&&b[p]==(byte)c;}
    }
}
