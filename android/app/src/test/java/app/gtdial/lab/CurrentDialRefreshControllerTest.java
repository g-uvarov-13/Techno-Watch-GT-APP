package app.gtdial.lab;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CurrentDialRefreshControllerTest {
    @Test public void keepsOneRefreshPendingUntilExclusiveOperationEnds() {
        CurrentDialRefreshController refresh = new CurrentDialRefreshController();

        refresh.request();
        assertFalse(refresh.takeIfIdle(false));
        assertTrue(refresh.isPending());
        assertTrue(refresh.takeIfIdle(true));
        assertFalse(refresh.isPending());
    }

    @Test public void disconnectCancelsPendingRefreshWithoutRetry() {
        CurrentDialRefreshController refresh = new CurrentDialRefreshController();

        refresh.request();
        refresh.onDisconnected();

        assertFalse(refresh.takeIfIdle(true));
        assertFalse(refresh.isPending());
    }
}
