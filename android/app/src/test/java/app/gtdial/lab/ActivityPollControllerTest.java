package app.gtdial.lab;

import org.junit.Test;
import static org.junit.Assert.*;

public class ActivityPollControllerTest {
    @Test public void schedulesFirstPollAfterReadyAndThenSixtySecondsAfterSuccess() {
        ActivityPollController p=new ActivityPollController();
        assertTrue(p.onReady());
        assertEquals(0L,p.nextDelayMs());
        assertTrue(p.onSuccess());
        assertEquals(60_000L,p.nextDelayMs());
    }
    @Test public void failureDoesNotRetryAndExclusiveWorkCancelsGeneration() {
        ActivityPollController p=new ActivityPollController();
        p.onReady(); long before=p.generation();
        p.onExclusiveStart();
        assertFalse(p.isScheduled()); assertTrue(p.generation()>before);
        assertFalse(p.onFailure()); assertFalse(p.isScheduled());
    }
}
