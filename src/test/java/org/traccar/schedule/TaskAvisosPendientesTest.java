package org.traccar.schedule;

import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.AvisoPendiente;
import org.traccar.model.Event;
import org.traccar.model.Notification;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notification.NotificatorManager;
import org.traccar.notificators.Notificator;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The retry task, run against a queue of one row.
 *
 * Found in review on 2026-09-19: notificator.retryAttempts = N gave N - 1
 * retries, a member disabled while the alert waited still got it, and every
 * run read the whole backlog to use 25 rows of it.
 */
public class TaskAvisosPendientesTest {

    private final List<String> sends = new ArrayList<>();
    private boolean removed;
    private Request queueRequest;

    private final User user = new User();
    private final Notification notification = new Notification();

    private TaskAvisosPendientes task(int retryAttempts, String blocked, boolean deliver, AvisoPendiente row)
            throws Exception {
        Config config = mock(Config.class);
        when(config.getInteger(Keys.NOTIFICATOR_RETRY_ATTEMPTS)).thenReturn(retryAttempts);
        when(config.getLong(Keys.NOTIFICATOR_RETRY_DELAY)).thenReturn(60000L);
        when(config.getLong(Keys.NOTIFICATOR_RETRY_MAX_DELAY)).thenReturn(3600000L);
        when(config.getString(Keys.NOTIFICATION_BLOCK_USERS)).thenReturn(blocked);

        user.setId(7);
        notification.setId(3);
        notification.setNotificators("telegram,mail");
        Event event = new Event("alarm", 1);
        event.setId(11);

        Storage storage = mock(Storage.class);
        when(storage.getObjects(eq(AvisoPendiente.class), any())).thenAnswer(call -> {
            queueRequest = call.getArgument(1);
            return removed ? List.of() : List.of(row);
        });
        when(storage.getObject(eq(Event.class), any())).thenReturn(event);
        when(storage.getObject(eq(User.class), any())).thenReturn(user);
        when(storage.getObject(eq(Notification.class), any())).thenReturn(notification);
        when(storage.getObject(eq(Position.class), any())).thenReturn(null);
        doAnswer(call -> {
            removed = true;
            return null;
        }).when(storage).removeObject(eq(AvisoPendiente.class), any());

        Notificator notificator = mock(Notificator.class);
        when(notificator.sendAsync(any(Notification.class), any(), any(), any())).thenAnswer(call -> {
            sends.add("telegram");
            return deliver
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(new IOException("Telegram answered HTTP 429"));
        });
        NotificatorManager notificatorManager = mock(NotificatorManager.class);
        when(notificatorManager.getNotificator(anyString())).thenReturn(notificator);

        return new TaskAvisosPendientes(config, storage, mock(CacheManager.class), notificatorManager);
    }

    private AvisoPendiente row() {
        // As ColaAvisos writes it: one attempt made, the original send.
        AvisoPendiente row = new AvisoPendiente();
        row.setId(5);
        row.setEventId(11);
        row.setUserId(7);
        row.setNotificationId(3);
        row.setNotificator("telegram");
        row.setIntentos(1);
        row.setProximo(new Date(0));
        return row;
    }

    /** Runs the task until the row leaves the queue; how many retries were made. */
    private int retriesUntilGivenUp(int retryAttempts) throws Exception {
        TaskAvisosPendientes task = task(retryAttempts, null, false, row());
        for (int run = 0; run < 100 && !removed; run++) {
            task.run();
        }
        assertTrue(removed, "the row has to leave the queue in the end");
        return sends.size();
    }

    @Test
    public void oneRetryMeansOne() throws Exception {
        assertEquals(1, retriesUntilGivenUp(1));
    }

    @Test
    public void threeRetriesMeanThree() throws Exception {
        assertEquals(3, retriesUntilGivenUp(3));
    }

    @Test
    public void aDeliveredRetryLeavesTheQueue() throws Exception {
        task(3, null, true, row()).run();
        assertEquals(1, sends.size());
        assertTrue(removed);
    }

    @Test
    public void aDisabledUserGetsNothing() throws Exception {
        TaskAvisosPendientes task = task(3, null, true, row());
        user.setDisabled(true);
        task.run();
        assertEquals(List.of(), sends);
        assertTrue(removed, "and the row is dropped, not kept for later");
    }

    @Test
    public void aBlockedUserGetsNothing() throws Exception {
        task(3, "9,7", true, row()).run();
        assertEquals(List.of(), sends);
        assertTrue(removed);
    }

    @Test
    public void aChannelTakenOffTheRuleIsNotRetried() throws Exception {
        TaskAvisosPendientes task = task(3, null, true, row());
        notification.setNotificators("mail");
        task.run();
        assertEquals(List.of(), sends);
        assertTrue(removed);
    }

    @Test
    public void onlyOneBatchIsRead() throws Exception {
        task(3, null, true, row()).run();
        assertEquals(25, queueRequest.getOrder().getLimit());
        assertEquals("proximo", queueRequest.getOrder().getColumn());
    }

}
