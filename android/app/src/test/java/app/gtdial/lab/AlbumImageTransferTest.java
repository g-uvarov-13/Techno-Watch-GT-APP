package app.gtdial.lab;
import org.junit.Test; import java.security.MessageDigest; import java.util.*; import static org.junit.Assert.*;
public class AlbumImageTransferTest {
 static class T implements AlbumImageTransfer.Transport {List<AlbumImageTransfer.Request> q=new ArrayList<>();int terminal;boolean ok;public void request(AlbumImageTransfer.Request r){q.add(r);}public void log(String s){}public void terminal(boolean x){terminal++;ok=x;}}
 @Test public void rejectsUnsafeInputs(){try{new AlbumImageTransfer(new byte[1],"bad.png","00",new T());fail();}catch(IllegalArgumentException e){}}
 @Test public void shortWriteFailsAndNoFollowup(){byte[] b=new byte[10];T t=new T();AlbumImageTransfer x=new AlbumImageTransfer(b,"gtdl_1234567890123.png",sha(b),t);x.start();x.initialComplete();x.configComplete("{\"imageWatchFace\":{}}".getBytes());x.conventionComplete(true,1);x.finalAbsent();x.cacheOpened(4);x.writeResult(1);assertEquals(1,t.terminal);assertFalse(t.ok);assertEquals("write_by_offset",t.q.get(t.q.size()-1).operation);}
 private static String sha(byte[] b){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder s=new StringBuilder();for(byte x:d)s.append(String.format("%02x",x&255));return s.toString();}catch(Exception e){throw new AssertionError(e);}}
}
