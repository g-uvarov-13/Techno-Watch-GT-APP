package app.gtdial.lab;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class NotificationSessionBridgeTest {
    @Test public void forwardsOnlyToTheCurrentSessionAndAcceptsNull(){
        AtomicInteger first=new AtomicInteger();
        AtomicInteger second=new AtomicInteger();
        NotificationSessionBridge.clear(null);
        assertFalse(NotificationSessionBridge.signal());
        Runnable firstSignal=first::incrementAndGet;
        Runnable secondSignal=second::incrementAndGet;
        NotificationSessionBridge.install(firstSignal);
        assertTrue(NotificationSessionBridge.signal());
        NotificationSessionBridge.install(secondSignal);
        NotificationSessionBridge.clear(firstSignal);
        assertTrue(NotificationSessionBridge.signal());
        NotificationSessionBridge.clear(secondSignal);
        assertFalse(NotificationSessionBridge.signal());
        assertEquals(1,first.get());
        assertEquals(1,second.get());
    }
}
