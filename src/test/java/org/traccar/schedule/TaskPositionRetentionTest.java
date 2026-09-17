package org.traccar.schedule;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
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
