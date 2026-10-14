package app.gtdial.lab;

import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationForwardingPolicyTest {
    private static NotificationRequest notice(String text){return new NotificationRequest("org.telegram.messenger","Alice",text,7);}
    @Test public void duplicateIsSuppressedAfterFirstItemWasDrained(){
        NotificationForwardingPolicy policy=new NotificationForwardingPolicy();
        assertTrue(policy.offer(notice("hello"),1000));
        assertNotNull(policy.poll(1001));
        assertFalse(policy.offer(notice("hello"),1002));
        assertTrue(policy.offer(notice("hello"),31_001));
    }
    @Test public void queueIsBoundedExpiresAndClears(){
        NotificationForwardingPolicy policy=new NotificationForwardingPolicy();
        for(int i=0;i<21;i++)assertTrue(policy.offer(notice("text"+i),1000+i));
        assertEquals("text1",policy.poll(1022).text);
        policy.clear();assertNull(policy.poll(1023));
        assertTrue(policy.offer(notice("later"),1000));assertNull(policy.poll(61_001));
    }
}
