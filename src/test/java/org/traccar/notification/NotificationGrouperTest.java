package org.traccar.notification;

import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Event;
import org.traccar.model.Notification;
import org.traccar.model.Server;
import org.traccar.model.User;
import org.traccar.notificators.Notificator;
import org.traccar.session.cache.CacheManager;

import java.io.IOException;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    /**
     * A summary that cannot be delivered goes to the retry queue, like any
     * other notification. Until 2026-09-19 it was logged and lost.
     */
    @Test
    public void aFailedSummaryIsQueued() throws Exception {
        testSummary(false);
    }

    @Test
    public void aDeliveredSummaryIsNotQueued() throws Exception {
        testSummary(true);
    }

    private void testSummary(boolean delivered) throws Exception {
        Config config = mock(Config.class);
        when(config.getLong(Keys.NOTIFICATOR_GROUP_WINDOW)).thenReturn(60L);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getServer()).thenReturn(new Server());
        NotificationFormatter formatter = mock(NotificationFormatter.class);
        when(formatter.formatMessage(any(), any(), any(), any()))
                .thenReturn(new NotificationMessage(null, "Test: alarma", null, false));
        ColaAvisos cola = mock(ColaAvisos.class);
        NotificationGrouper grouper = new NotificationGrouper(config, formatter, cacheManager, cola);

        Notificator notificator = mock(Notificator.class);
        when(notificator.sendAsync(any(Notification.class), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(notificator.sendAsync(any(User.class), any(NotificationMessage.class), any(), any()))
                .thenReturn(delivered
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(new IOException("Telegram answered HTTP 429")));

        Notification notification = new Notification();
        notification.setId(3);
        User user = new User();
        user.setId(7);
        Event first = new Event("alarm", 1);
        first.setId(10);
        Event repeat = new Event("alarm", 1);
        repeat.setId(11);

        grouper.send("telegram", notificator, notification, user, first, null).get();
        grouper.send("telegram", notificator, notification, user, repeat, null).get();
        // The first went out; the repeat is held for the summary.
        verify(notificator, never()).sendAsync(any(User.class), any(NotificationMessage.class), any(), any());

        grouper.flush(System.currentTimeMillis() + 120_000);

        verify(notificator).sendAsync(any(User.class), any(NotificationMessage.class), eq(repeat), isNull());
        if (delivered) {
            verify(cola, never()).encolar(any(), any(), any(), any(), any(), any());
        } else {
            verify(cola).encolar(eq(notification), eq(user), eq(repeat), isNull(), eq("telegram"), any());
        }
    }

}
