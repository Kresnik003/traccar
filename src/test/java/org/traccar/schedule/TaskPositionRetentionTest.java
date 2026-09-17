package org.traccar.schedule;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TaskPositionRetentionTest {

    @Test
    public void testStopsWhenNothingLeft() {
        int[] batches = {5000, 5000, 1200, 0, 999};
        AtomicInteger call = new AtomicInteger();
        long total = TaskPositionRetention.purge(() -> batches[call.getAndIncrement()], 100);
        assertEquals(11200, total);
        assertEquals(4, call.get());
    }

    /**
     * The failure the external script had: its loop waited for "0 rows
     * affected", the query failed instead, and it spun for weeks.
     */
    @Test
    public void testStopsOnFailure() {
        AtomicInteger call = new AtomicInteger();
        long total = TaskPositionRetention.purge(() -> {
            if (call.incrementAndGet() == 3) {
                throw new SQLException("gone");
            }
            return 5000;
        }, 100);
        assertEquals(10000, total);
        assertEquals(3, call.get());
    }

    @Test
    public void testOrphansAreTheDeletedDevices() {
        assertEquals(Set.of(9L), TaskPositionRetention.orphans(Set.of(1L, 2L, 3L, 9L), Set.of(1L, 2L, 3L), 0));
        assertEquals(Set.of(), TaskPositionRetention.orphans(Set.of(1L, 2L), Set.of(1L, 2L, 3L), 0));
    }

    /** A failed read of the devices must not turn every history into an orphan. */
    @Test
    public void testNoDevicesMeansNothingIsDeleted() {
        assertEquals(Set.of(), TaskPositionRetention.orphans(Set.of(1L, 2L, 3L), Set.of(), 0));
        assertEquals(Set.of(), TaskPositionRetention.orphans(Set.of(1L, 2L, 3L), Set.of(), 1000));
    }

    /** Half the fleet suddenly orphaned looks like a broken read, not like a decision. */
    @Test
    public void testTooManyOrphansMeansNothingIsDeleted() {
        var history = Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
        assertEquals(Set.of(), TaskPositionRetention.orphans(history, Set.of(1L, 2L, 3L, 4L, 5L, 6L), 0));
        // A few deleted devices among many is the normal case.
        assertEquals(Set.of(11L, 12L),
                TaskPositionRetention.orphans(history, Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), 0));
    }

    /** After deleting many devices on purpose, a limit allows the clean-up; above it, nothing. */
    @Test
    public void testLimitReplacesTheHalfGuard() {
        var history = Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
        var existing = Set.of(1L, 2L, 3L, 4L, 5L, 6L);
        assertEquals(6, TaskPositionRetention.orphans(history, existing, 10).size());
        assertEquals(Set.of(), TaskPositionRetention.orphans(history, existing, 5));
    }

    /** A database that always reports deleted rows must not keep the task running forever. */
    @Test
    public void testCeiling() {
        AtomicInteger call = new AtomicInteger();
        TaskPositionRetention.purge(() -> {
            call.incrementAndGet();
            return 5000;
        }, 7);
        assertEquals(7, call.get());
    }

}
