package app.gtdial.lab;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConnectionSnapshotTest {
    @Test public void operationMessageDoesNotChangeReadyConnectionPhase() {
        ConnectionSnapshot ready = ConnectionSnapshot.ready("GT01 готов к диагностике");

        ConnectionSnapshot activity = ready.withOperationMessage("Активность получена");

        assertTrue(activity.isReady());
        assertEquals("GT01 готов к диагностике", activity.connectionMessage());
        assertEquals("Активность получена", activity.operationMessage());
    }

    @Test public void disconnectedSnapshotClearsReadinessAndOperationMessage() {
        ConnectionSnapshot disconnected = ConnectionSnapshot.ready("GT01 готов к диагностике")
                .disconnected("Часы отключились");

        assertFalse(disconnected.isReady());
        assertEquals("Часы отключились", disconnected.connectionMessage());
        assertEquals(null, disconnected.operationMessage());
    }
}
