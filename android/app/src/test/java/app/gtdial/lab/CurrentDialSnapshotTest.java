package app.gtdial.lab;

import org.junit.Test;
import static org.junit.Assert.*;

public class CurrentDialSnapshotTest {
    @Test public void currentDialIdentityAcceptsCapturedPlainUtf8AndLegacyJson() throws Exception {
        assertEquals("com.gtdial.pipboy01",BleWatchSession.decodeCurrentDialIdentity("com.gtdial.pipboy01"));
        assertEquals("1909202601",BleWatchSession.decodeCurrentDialIdentity("{\"alias\":\"1909202601\"}"));
    }
    @Test public void unknownIdentityNeverPretendsToHavePreviewOrSensorValues() {
        CurrentDialSnapshot s=CurrentDialSnapshot.unknown("999", 42L);
        assertFalse(s.isKnown()); assertNull(s.catalogId); assertNull(s.previewAsset); assertNull(s.displayFields);
    }
}
