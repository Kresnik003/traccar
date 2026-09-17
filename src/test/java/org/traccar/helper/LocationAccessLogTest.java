package org.traccar.helper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LocationAccessLogTest {

    private static LocationAccessLog.Access classify(String method, String path, Map<String, List<String>> query) {
        return LocationAccessLog.classify(method, path, query::get);
    }

    private static final String FROM = "2026-09-01T00:00:00Z";
    private static final String TO = "2026-09-02T00:00:00Z";

    @Test
    public void testHistoryOfPositionsIsLogged() {
        var access = classify("GET", "positions",
                Map.of("deviceId", List.of("7"), "from", List.of(FROM), "to", List.of(TO)));
        assertNotNull(access);
        assertEquals("posiciones", access.what());
        assertEquals(List.of(7L), access.deviceIds());
        assertNotNull(access.from());
        assertNotNull(access.to());
    }

    /** The live position is asked for constantly and is not history. */
    @Test
    public void testLatestPositionIsNotLogged() {
        assertNull(classify("GET", "positions", Map.of("deviceId", List.of("7"))));
        assertNull(classify("GET", "positions", Map.of()));
    }

    @Test
    public void testReportsAndTheirSpreadsheets() {
        var route = classify("GET", "reports/route",
                Map.of("deviceId", List.of("7", "8"), "from", List.of(FROM), "to", List.of(TO)));
        assertEquals("informe:route", route.what());
        assertEquals(List.of(7L, 8L), route.deviceIds());
        var xlsx = classify("GET", "reports/trips/xlsx", Map.of("groupId", List.of("3"), "from", List.of(FROM)));
        assertEquals("informe:trips:xlsx", xlsx.what());
        assertEquals(List.of(3L), xlsx.groupIds());
    }

    @Test
    public void testExportAndFileFormats() {
        assertEquals(12L, classify("GET", "socios/12/datos", Map.of()).exportedUserId());
        assertEquals("posiciones:gpx", classify("GET", "/positions/gpx/",
                Map.of("deviceId", List.of("7"), "from", List.of(FROM))).what());
        assertEquals("posiciones:fit", classify("GET", "positions/fit",
                Map.of("deviceId", List.of("7"), "from", List.of(FROM))).what());
    }

    @Test
    public void testNothingElseIsLogged() {
        assertNull(classify("GET", "devices", Map.of()));
        assertNull(classify("DELETE", "positions",
                Map.of("deviceId", List.of("7"), "from", List.of(FROM))));
        assertNull(classify("GET", "reports/route", Map.of("from", List.of(FROM))));
        assertNull(classify("GET", "socios/abc/datos", Map.of()));
    }

    private static LocationAccessLog.Access route(String from, String to) {
        return classify("GET", "reports/route",
                Map.of("deviceId", List.of("7"), "from", List.of(from), "to", List.of(to)));
    }

    /** Opening the same panel again and again is one look, not a screenful. */
    @Test
    public void testRepeatsAreLoggedOncePerWindow() {
        var repeats = new LocationAccessLog.Repeats(600_000, 100);
        var access = route(FROM, "2026-09-02T10:00:00Z");
        var later = route(FROM, "2026-09-02T10:03:00Z");
        assertTrue(repeats.first(1, 7, access, 0));
        assertFalse(repeats.first(1, 7, later, 60_000));
        assertTrue(repeats.first(1, 7, later, 600_000));
    }

    @Test
    public void testDifferentLooksAreNotCollapsed() {
        var repeats = new LocationAccessLog.Repeats(600_000, 100);
        var access = route(FROM, TO);
        assertTrue(repeats.first(1, 7, access, 0));
        assertTrue(repeats.first(2, 7, access, 0));
        assertTrue(repeats.first(1, 8, access, 0));
        assertTrue(repeats.first(1, 7, route("2025-01-01T00:00:00Z", TO), 0));
        assertTrue(repeats.first(1, 7, classify("GET", "reports/trips",
                Map.of("deviceId", List.of("7"), "from", List.of(FROM), "to", List.of(TO))), 0));
    }

    /** Reports answer 200 without the devices the user cannot see; positions and exports refuse. */
    @Test
    public void testOnlyReportsFilterSilently() {
        assertTrue(route(FROM, TO).silentlyFiltered());
        assertFalse(classify("GET", "positions",
                Map.of("deviceId", List.of("7"), "from", List.of(FROM))).silentlyFiltered());
        assertFalse(classify("GET", "positions/fit",
                Map.of("deviceId", List.of("7"), "from", List.of(FROM))).silentlyFiltered());
        assertFalse(classify("GET", "socios/12/datos", Map.of()).silentlyFiltered());
    }

    @Test
    public void testBadValuesDoNotBreakIt() {
        var access = classify("GET", "reports/route",
                Map.of("deviceId", List.of("7", "nope"), "from", List.of("yesterday")));
        assertEquals(List.of(7L), access.deviceIds());
        assertNull(access.from());
    }

}
