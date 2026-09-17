package org.traccar.helper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HealthEvaluatorTest {

    private static final String MEMINFO = """
            MemTotal:         976896 kB
            MemFree:           81040 kB
            MemAvailable:     341916 kB
            Buffers:           12345 kB
            """;

    /**
     * MemFree would say 92 % and raise the alarm on a healthy machine: Linux
     * fills free memory with cache. MemAvailable says 65 %.
     */
    @Test
    public void testMemoryUsesAvailableNotFree() {
        assertEquals(65, Math.round(HealthEvaluator.memoryUsed(MEMINFO)));
        assertEquals(HealthEvaluator.OK, HealthEvaluator.memory(HealthEvaluator.memoryUsed(MEMINFO)).estado());
    }

    @Test
    public void testMemoryUnreadable() {
        assertEquals(-1, HealthEvaluator.memoryUsed(null));
        assertEquals(-1, HealthEvaluator.memoryUsed("MemTotal: 100 kB\n"));
        assertEquals(-1, HealthEvaluator.memoryUsed("MemTotal: 100 kB\nMemAvailable: 900 kB\n"));
        assertEquals(HealthEvaluator.WARNING, HealthEvaluator.memory(-1).estado());
    }

    /** The same thresholds as MemoriaAlta and MemoriaCritica. */
    @Test
    public void testMemoryThresholds() {
        assertEquals(HealthEvaluator.OK, HealthEvaluator.memory(85).estado());
        assertEquals(HealthEvaluator.WARNING, HealthEvaluator.memory(85.1).estado());
        assertEquals(HealthEvaluator.FAILURE, HealthEvaluator.memory(95.1).estado());
    }

    @Test
    public void testDisk() {
        assertEquals(HealthEvaluator.OK, HealthEvaluator.disk(100, 21).estado());
        assertEquals(HealthEvaluator.WARNING, HealthEvaluator.disk(100, 20).estado());
        assertEquals(HealthEvaluator.FAILURE, HealthEvaluator.disk(100, 10).estado());
        assertEquals(HealthEvaluator.WARNING, HealthEvaluator.disk(0, 0).estado());
    }

    @Test
    public void testLoadIsPerCore() {
        // A load of 6 on 8 cores is fine; on 2 cores it is a queue.
        assertEquals(HealthEvaluator.OK, HealthEvaluator.load(6, 8).estado());
        assertEquals(HealthEvaluator.FAILURE, HealthEvaluator.load(6, 2).estado());
        assertEquals(HealthEvaluator.WARNING, HealthEvaluator.load(3, 2).estado());
    }

    /**
     * The check the rest of the list cannot make. Nothing stored with nothing
     * connected is a quiet night; nothing stored with devices connected is the
     * fault.
     */
    @Test
    public void testStoring() {
        assertEquals(HealthEvaluator.OK, HealthEvaluator.storing(0, 99999, 99999).estado());
        assertEquals(HealthEvaluator.OK, HealthEvaluator.storing(2, 60, 99999).estado());
        assertEquals(HealthEvaluator.FAILURE, HealthEvaluator.storing(2, 2400, 99999).estado());
        // Never stored anything since start, with devices on for an hour.
        assertEquals(HealthEvaluator.FAILURE, HealthEvaluator.storing(2, -1, 3600).estado());
        // Never stored, but the server only started a minute ago.
        assertEquals(HealthEvaluator.OK, HealthEvaluator.storing(2, -1, 60).estado());
    }

    /** The pruning that ran every week and deleted nothing. */
    @Test
    public void testHistoryCatchesPruningThatDoesNothing() {
        assertEquals(HealthEvaluator.OK, HealthEvaluator.history(365, 365).estado());
        assertEquals(HealthEvaluator.WARNING, HealthEvaluator.history(400, 365).estado());
        assertEquals(HealthEvaluator.OK, HealthEvaluator.history(400, 0).estado());
        assertEquals(HealthEvaluator.OK, HealthEvaluator.history(-1, 365).estado());
    }

    @Test
    public void testUptime() {
        assertEquals(HealthEvaluator.WARNING, HealthEvaluator.uptime(120).estado());
        assertEquals(HealthEvaluator.OK, HealthEvaluator.uptime(90000).estado());
    }

    @Test
    public void testSilentDevicesNamed() {
        var check = HealthEvaluator.silentDevices(List.of("RSV4", "R1"), 26);
        assertEquals(HealthEvaluator.WARNING, check.estado());
        assertTrue(check.detalle().contains("RSV4") && check.detalle().contains("R1"));
        assertEquals(HealthEvaluator.OK, HealthEvaluator.silentDevices(List.of(), 26).estado());
    }

    private static final long DAY = 86400000L;

    /** A bike left in a garage for a year: its only old position is its last known one. */
    @Test
    public void testOldestIgnoresLastKnownPosition() {
        var perDevice = List.of(
                new HealthEvaluator.DeviceOldest(1, 400 * DAY),
                new HealthEvaluator.DeviceOldest(2, 900 * DAY));
        // Device 1's oldest is its last known position; its next one is recent.
        long oldest = HealthEvaluator.oldestCounting(perDevice, id -> id == 1 ? 950 * DAY : 900 * DAY);
        assertEquals(900 * DAY, oldest);
    }

    @Test
    public void testOldestFindsTheRealOne() {
        var perDevice = List.of(
                new HealthEvaluator.DeviceOldest(1, 100 * DAY),
                new HealthEvaluator.DeviceOldest(2, 500 * DAY));
        assertEquals(100 * DAY, HealthEvaluator.oldestCounting(perDevice, id -> id == 1 ? 100 * DAY : 500 * DAY));
    }

    /** Stops as soon as no remaining device can be older than what was found. */
    @Test
    public void testOldestStopsEarly() {
        var asked = new ArrayList<Long>();
        var perDevice = List.of(
                new HealthEvaluator.DeviceOldest(1, 100 * DAY),
                new HealthEvaluator.DeviceOldest(2, 200 * DAY),
                new HealthEvaluator.DeviceOldest(3, 300 * DAY));
        HealthEvaluator.oldestCounting(perDevice, id -> {
            asked.add(id);
            return 100 * DAY;
        });
        assertEquals(List.of(1L), asked);
    }

    @Test
    public void testOldestNothingCounts() {
        assertEquals(-1, HealthEvaluator.oldestCounting(List.of(), id -> -1));
        assertEquals(-1, HealthEvaluator.oldestCounting(
                List.of(new HealthEvaluator.DeviceOldest(1, 5 * DAY)), id -> -1));
    }

    @Test
    public void testValuesUseDecimalComma() {
        assertEquals("0,18", HealthEvaluator.load(1.44, 8).valor());
        assertEquals("65 %", HealthEvaluator.memory(64.9).valor());
    }

    @Test
    public void testSecondFactor() {
        assertEquals(HealthEvaluator.WARNING, HealthEvaluator.secondFactor(false, List.of()).estado());
        var check = HealthEvaluator.secondFactor(true, List.of("Admin del club"));
        assertEquals(HealthEvaluator.WARNING, check.estado());
        assertTrue(check.detalle().contains("Admin del club"));
        assertEquals(HealthEvaluator.OK, HealthEvaluator.secondFactor(true, List.of()).estado());
    }

    @Test
    public void testQuotaWarnsBeforeItHurts() {
        assertEquals(HealthEvaluator.OK, HealthEvaluator.quota("equiposCupo", 500, 0).estado());
        assertEquals(HealthEvaluator.OK, HealthEvaluator.quota("equiposCupo", 70, 100).estado());
        assertEquals(HealthEvaluator.WARNING, HealthEvaluator.quota("equiposCupo", 80, 100).estado());
        assertEquals(HealthEvaluator.FAILURE, HealthEvaluator.quota("equiposCupo", 100, 100).estado());
        assertEquals("100 de 100", HealthEvaluator.quota("equiposCupo", 100, 100).valor());
    }

    @Test
    public void testWorstWins() {
        assertEquals(HealthEvaluator.FAILURE, HealthEvaluator.worst(List.of(
                HealthEvaluator.memory(10), HealthEvaluator.disk(100, 10), HealthEvaluator.load(1, 8))));
        assertEquals(HealthEvaluator.WARNING, HealthEvaluator.worst(List.of(
                HealthEvaluator.memory(10), HealthEvaluator.memory(90))));
        assertEquals(HealthEvaluator.OK, HealthEvaluator.worst(List.of()));
    }

}
