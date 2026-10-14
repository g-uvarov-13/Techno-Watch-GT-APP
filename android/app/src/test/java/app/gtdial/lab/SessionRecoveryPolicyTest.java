package app.gtdial.lab;

import org.junit.Test;
import static org.junit.Assert.*;

public class SessionRecoveryPolicyTest {
    @Test public void onlyAnUnusedIdleSessionWithPermissionAndAddressMayRecover(){
        assertTrue(SessionRecoveryPolicy.mayRecover(true,"AA:BB",SessionRecoveryPolicy.IDLE,false,true));
        assertFalse(SessionRecoveryPolicy.mayRecover(true,"AA:BB",SessionRecoveryPolicy.OPERATION,false,true));
        assertFalse(SessionRecoveryPolicy.mayRecover(true,"AA:BB",SessionRecoveryPolicy.STOPPED,false,true));
        assertFalse(SessionRecoveryPolicy.mayRecover(true,"AA:BB",SessionRecoveryPolicy.IDLE,true,true));
        assertFalse(SessionRecoveryPolicy.mayRecover(true,null,SessionRecoveryPolicy.IDLE,false,true));
        assertFalse(SessionRecoveryPolicy.mayRecover(true,"AA:BB",SessionRecoveryPolicy.IDLE,false,false));
    }
    @Test public void operationMarkerWinsUntilManualReconnectResetsIt(){
        assertEquals(SessionRecoveryPolicy.OPERATION,SessionRecoveryPolicy.beforeProtocolOperation(SessionRecoveryPolicy.IDLE));
        assertEquals(SessionRecoveryPolicy.IDLE,SessionRecoveryPolicy.manualReconnect());
        assertEquals(SessionRecoveryPolicy.STOPPED,SessionRecoveryPolicy.manualDisconnect());
    }
    @Test public void failedOperationStopsRecoveryWhileSuccessfulReadReturnsIdle(){
        assertEquals(SessionRecoveryPolicy.IDLE,SessionRecoveryPolicy.afterProtocolOperation(true));
        assertEquals(SessionRecoveryPolicy.STOPPED,SessionRecoveryPolicy.afterProtocolOperation(false));
        assertFalse(SessionRecoveryPolicy.mayRecover(true,"AA:BB",SessionRecoveryPolicy.afterProtocolOperation(false),false,true));
    }
}
