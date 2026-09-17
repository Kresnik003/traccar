package org.traccar.notification;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NotificationWindowTest {

    private static final long MINUTE = 60_000;

    /** The first one is never held: a crash alarm cannot wait for a window. */
    @Test
    public void testFirstGoesOutAtOnce() {
        var window = new NotificationWindow<String>(MINUTE);
        assertTrue(window.offer("user:web:bike:alarm:accident", 0, "a"));
    }

    @Test
    public void testRepeatsAreHeldAndSummarised() {
        var window = new NotificationWindow<String>(MINUTE);
        assertTrue(window.offer("k", 0, "1"));
        assertFalse(window.offer("k", 10_000, "2"));
        assertFalse(window.offer("k", 20_000, "3"));
        assertEquals(List.of(), window.flush(30_000), "nothing before the window closes");
        List<NotificationWindow.Summary<String>> summaries = window.flush(MINUTE);
        assertEquals(1, summaries.size());
        assertEquals(2, summaries.get(0).count());
        assertEquals(10_000, summaries.get(0).first());
        assertEquals(20_000, summaries.get(0).last());
        assertEquals("3", summaries.get(0).latest());
        assertEquals(0, window.openWindows());
    }

    /** Another bike, or another kind of alarm, never waits behind the first. */
    @Test
    public void testOtherKeysAreIndependent() {
        var window = new NotificationWindow<String>(MINUTE);
        assertTrue(window.offer("user:web:bikeA:alarm:accident", 0, "a"));
        assertTrue(window.offer("user:web:bikeB:alarm:accident", 1_000, "b"));
        assertTrue(window.offer("user:web:bikeA:alarm:sos", 2_000, "c"));
    }

    /** A single event in a window leaves no summary behind. */
    @Test
    public void testNoSummaryWithoutRepeats() {
        var window = new NotificationWindow<String>(MINUTE);
        window.offer("k", 0, "1");
        assertEquals(List.of(), window.flush(MINUTE * 2));
    }

    /**
     * If the next event arrives after the window closed but before the flush
     * ran, it goes out as the first of a new window and the held ones are not
     * lost: they leave with the next flush.
     */
    @Test
    public void testNothingIsLostBetweenWindows() {
        var window = new NotificationWindow<String>(MINUTE);
        window.offer("k", 0, "1");
        window.offer("k", 30_000, "2");
        assertTrue(window.offer("k", MINUTE + 5_000, "3"));
        List<NotificationWindow.Summary<String>> summaries = window.flush(MINUTE + 6_000);
        assertEquals(1, summaries.size());
        assertEquals("2", summaries.get(0).latest());
        assertEquals(1, window.openWindows());
    }

}
