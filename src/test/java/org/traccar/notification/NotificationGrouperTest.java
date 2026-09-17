package org.traccar.notification;

import org.junit.jupiter.api.Test;

import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class NotificationGrouperTest {

    private static final TimeZone MADRID = TimeZone.getTimeZone("Europe/Madrid");

    /** 2026-09-08 19:49 and 20:18 UTC, which in Madrid are 21:49 and 22:18. */
    @Test
    public void testSummaryText() {
        assertEquals("(+60 avisos iguales entre las 21:49 y las 22:18) ",
                NotificationGrouper.prefix(60, 1788896940000L, 1788898680000L, MADRID));
        assertEquals("(+1 aviso igual a las 21:49) ",
                NotificationGrouper.prefix(1, 1788896940000L, 1788896940000L, MADRID));
    }

}
