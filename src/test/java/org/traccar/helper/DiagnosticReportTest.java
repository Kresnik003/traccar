package org.traccar.helper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DiagnosticReportTest {

    /** Lines of the shape Traccar really writes, with made-up values. */
    @Test
    public void testLogLinesLoseWhatPointsAtSomebody() {
        String unknown = DiagnosticReport.redact(
                "2026-09-17 09:48:45  WARN: Unknown device - 359999123456789 (203.0.113.7)");
        assertEquals("2026-09-17 09:48:45  WARN: Unknown device - [identificador] ([ip])", unknown);

        String lock = DiagnosticReport.redact(
                "2026-09-17 10:01:02  WARN: Protocol not allowed for device - qa-moto (osmand, 2001:db8::1)");
        assertFalse(lock.contains("qa-moto"), lock);
        assertFalse(lock.contains("2001:db8"), lock);

        String limit = DiagnosticReport.redact(
                "2026-09-17 09:54:03  WARN: Connection limit reached for 192.0.2.44 (10 per minute)");
        assertEquals("2026-09-17 09:54:03  WARN: Connection limit reached for [ip] (10 per minute)", limit);
    }

    @Test
    public void testEmailsNumbersAndHex() {
        String line = DiagnosticReport.redact(
                "mail to socio.uno+club@example.org chat 123456789 frame 000f333539393939313233343536373839");
        assertFalse(line.contains("example.org"), line);
        assertFalse(line.contains("123456789"), line);
        assertFalse(line.contains("000f3335"), line);
    }

    /** What is not personal has to survive, or the report says nothing. */
    @Test
    public void testTimesAndCodeSurvive() {
        String line = "2026-09-17 09:36:17 ERROR: Broken pipe - IOException (StreamWriter:55 < WebServer:131)";
        assertEquals(line, DiagnosticReport.redact(line));
        assertEquals("Unsafe::staticFieldBase", DiagnosticReport.redact("Unsafe::staticFieldBase"));
    }

    @Test
    public void testOnlyTheLastProblems() {
        List<String> log = List.of(
                "2026-09-17 09:00:00  INFO: connected 198.51.100.1",
                "2026-09-17 09:00:01  WARN: uno",
                "2026-09-17 09:00:02  INFO: nada",
                "2026-09-17 09:00:03 ERROR: dos",
                "2026-09-17 09:00:04  WARN: tres");
        assertEquals(List.of("2026-09-17 09:00:03 ERROR: dos", "2026-09-17 09:00:04  WARN: tres"),
                DiagnosticReport.lastProblems(log, 2));
    }

    @Test
    public void testCredentialsOnlySayIfTheyAreSet() {
        assertEquals("(puesta)", DiagnosticReport.configValue("web.metricsToken", "abc"));
        assertEquals("(puesta)", DiagnosticReport.configValue("database.url", "jdbc:mysql://h/db?password=x"));
        assertEquals("(sin poner)", DiagnosticReport.configValue("server.protocolLock", null));
        assertEquals("true", DiagnosticReport.configValue("server.protocolLock", "true"));
        assertTrue(DiagnosticReport.configValue("protocols.enable", "teltonika,osmand").contains("osmand"));
    }

    /**
     * A marker has to be able to say no. HealthEvaluator is from 0005 and 0020
     * only added quota() to it: the class alone said "yes" to 0020 on any jar.
     */
    @Test
    public void testPatchMarkerChecksTheMember() {
        ClassLoader loader = getClass().getClassLoader();
        assertTrue(DiagnosticReport.present("org.traccar.helper.HealthEvaluator", loader));
        assertTrue(DiagnosticReport.present("org.traccar.helper.HealthEvaluator#quota", loader));
        assertFalse(DiagnosticReport.present("org.traccar.helper.HealthEvaluator#noSuchMember", loader));
        assertFalse(DiagnosticReport.present("org.traccar.helper.NoSuchClass", loader));
        assertFalse(DiagnosticReport.present("org.traccar.helper.NoSuchClass#quota", loader));
        assertEquals("HealthEvaluator.quota", DiagnosticReport.markerName("org.traccar.helper.HealthEvaluator#quota"));
        assertEquals("FitWriter", DiagnosticReport.markerName("org.traccar.helper.FitWriter"));
    }

}
