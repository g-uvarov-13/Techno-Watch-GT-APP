package app.gtdial.lab;

import org.junit.Test;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class NotificationSessionProtocolTest {
    @Test public void startsWithBoundedReadBeforeAnyWrite(){
        NotificationSessionProtocol protocol=new NotificationSessionProtocol(frame->true, prior->true, message->{},success->{},new NotificationSessionProtocol.Scheduler(){public void schedule(Runnable r,long ms){}public void cancel(Runnable r){}},512);
        byte[] first=protocol.start(new NotificationRequest("org.telegram.messenger","Title","Text",7));
        assertNotNull(first);
        assertTrue(new String(first,java.nio.charset.StandardCharsets.UTF_8).contains("svc_systemstorage_get"));
        assertFalse(new String(first,java.nio.charset.StandardCharsets.UTF_8).contains("svc_notification_push"));
    }
    @Test public void readsBackSettingBeforeSendingAndIgnoresLateFrame(){
        List<byte[]> writes=new ArrayList<>();List<Boolean> ends=new ArrayList<>();List<Integer> backups=new ArrayList<>();
        NotificationSessionProtocol protocol=new NotificationSessionProtocol(frame->{writes.add(frame);return true;},prior->{backups.add(prior);return true;},message->{},ends::add,new NotificationSessionProtocol.Scheduler(){public void schedule(Runnable r,long ms){}public void cancel(Runnable r){}},512);
        protocol.start(new NotificationRequest("org.telegram.messenger","Hi","Text",7));
        protocol.onChunk(response(1,"{\"notificationEnable\":0}"));
        assertEquals(Collections.singletonList(0),backups);
        assertTrue(new String(writes.get(1),StandardCharsets.UTF_8).contains("svc_systemstorage_set"));
        protocol.onChunk(response(1,"{\"notificationEnable\":0}"));
        assertEquals(2,writes.size());
        protocol.onChunk(response(2,""));
        protocol.onChunk(response(3,"{\"notificationEnable\":1}"));
        assertTrue(new String(writes.get(3),StandardCharsets.UTF_8).contains("svc_notification_push"));
        protocol.onChunk(response(4,""));
        assertEquals(Collections.singletonList(true),ends);
        protocol.start(new NotificationRequest("org.telegram.messenger","Next","Text",8));
        assertTrue(new String(writes.get(4),StandardCharsets.UTF_8).contains("svc_notification_push"));
    }
    @Test public void malformedSettingStopsWithoutWrite(){
        List<byte[]> writes=new ArrayList<>();List<Boolean> ends=new ArrayList<>();
        NotificationSessionProtocol protocol=new NotificationSessionProtocol(frame->{writes.add(frame);return true;},prior->true,message->{},ends::add,new NotificationSessionProtocol.Scheduler(){public void schedule(Runnable r,long ms){}public void cancel(Runnable r){}},512);
        protocol.start(new NotificationRequest("org.telegram.messenger","Hi","Text",7));
        protocol.onChunk(response(1,"{\"notificationEnable\":2}"));
        assertEquals(1,writes.size());assertEquals(Collections.singletonList(false),ends);assertTrue(protocol.isFailed());
    }
    @Test public void timeoutIsTerminalAndHasNoFollowup(){
        List<byte[]> writes=new ArrayList<>();List<Boolean> ends=new ArrayList<>();final Runnable[] timer={null};
        NotificationSessionProtocol protocol=new NotificationSessionProtocol(frame->{writes.add(frame);return true;},prior->true,message->{},ends::add,new NotificationSessionProtocol.Scheduler(){public void schedule(Runnable r,long ms){timer[0]=r;}public void cancel(Runnable r){if(timer[0]==r)timer[0]=null;}},512);
        protocol.start(new NotificationRequest("org.telegram.messenger","Hi","Text",7));
        Runnable fired=timer[0];assertNotNull(fired);fired.run();
        protocol.onChunk(response(1,"{\"notificationEnable\":1}"));
        assertEquals(1,writes.size());assertEquals(Collections.singletonList(false),ends);assertTrue(protocol.isFailed());
    }
    private static byte[] response(int id,String text){byte[] payload=text.getBytes(StandardCharsets.UTF_8);int n=13+payload.length;byte[] f=new byte[n];f[0]=(byte)0xfc;f[1]=(byte)(n>>>8);f[2]=(byte)n;f[5]=1;f[6]=1;f[8]=(byte)id;f[9]=0x50;System.arraycopy(payload,0,f,10,payload.length);int c=0xffff;for(int i=1;i<n-3;i++){c^=f[i]&255;for(int j=0;j<8;j++)c=(c&1)!=0?(c>>>1)^0xa001:c>>>1;}f[n-3]=(byte)(c>>>8);f[n-2]=(byte)c;f[n-1]=(byte)0xcf;return f;}
}
