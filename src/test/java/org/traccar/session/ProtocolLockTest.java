package org.traccar.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProtocolLockTest {

    /** The hole itself: a Teltonika identifier typed into the OsmAnd port. */
    @Test
    public void testLockedToTheLastProtocol() {
        assertTrue(ProtocolLock.allowed("teltonika", null, "teltonika", true));
        assertFalse(ProtocolLock.allowed("osmand", null, "teltonika", true));
    }

    /** Off by default: an installation that does not ask for it behaves as before. */
    @Test
    public void testNothingChangesWithoutTheLock() {
        assertTrue(ProtocolLock.allowed("osmand", null, "teltonika", false));
        assertTrue(ProtocolLock.allowed("osmand", "", "teltonika", false));
    }

    /** A device that has never reported can only be decided by its first report. */
    @Test
    public void testFirstReportIsAccepted() {
        assertTrue(ProtocolLock.allowed("osmand", null, null, true));
        assertTrue(ProtocolLock.allowed("teltonika", " ", "", true));
    }

    /** The attribute applies with or without the lock, and replaces the last protocol. */
    @Test
    public void testAttributeWins() {
        assertFalse(ProtocolLock.allowed("osmand", "teltonika", null, false));
        assertFalse(ProtocolLock.allowed("osmand", "teltonika", "osmand", true));
        assertTrue(ProtocolLock.allowed("osmand", " Teltonika , osmand ", "teltonika", true));
    }

    @Test
    public void testNamesAreWholeNames() {
        assertFalse(ProtocolLock.allowed("osm", "osmand", null, false));
        assertFalse(ProtocolLock.allowed("osmand", "osmand2", null, false));
    }

}
