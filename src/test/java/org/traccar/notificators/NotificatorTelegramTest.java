package org.traccar.notificators;

import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notification.EsperaReintento;
import org.traccar.notification.NotificationFormatter;
import org.traccar.notification.NotificationMessage;

import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A refusal from Telegram is a failed delivery.
 *
 * Until 2026-09-19 a 429 or a 5xx completed the send normally: the HTTP client
 * only fails when there is no answer at all. The retry queue, which listens for
 * failures, never saw a refused alert, and a queued alert refused again on its
 * retry was deleted as delivered.
 */
public class NotificatorTelegramTest {

    /** What each call to the API was, in order: "text" or "location". */
    private final List<String> calls = new ArrayList<>();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private NotificatorTelegram notificator(boolean sendLocation, int textStatus, int locationStatus) {
        Config config = mock(Config.class);
        when(config.getString(Keys.NOTIFICATOR_TELEGRAM_KEY)).thenReturn("TOKEN");
        when(config.getString(Keys.NOTIFICATOR_TELEGRAM_CHAT_ID)).thenReturn("42");
        when(config.getBoolean(Keys.NOTIFICATOR_TELEGRAM_SEND_LOCATION)).thenReturn(sendLocation);
        Client client = mock(Client.class);
        when(client.target(anyString())).thenAnswer(targetCall -> {
            String url = targetCall.getArgument(0);
            boolean location = url.endsWith("sendLocation");
            int status = location ? locationStatus : textStatus;
            WebTarget target = mock(WebTarget.class);
            Invocation.Builder builder = mock(Invocation.Builder.class);
            AsyncInvoker invoker = mock(AsyncInvoker.class);
            when(target.request()).thenReturn(builder);
            when(builder.async()).thenReturn(invoker);
            when(invoker.post(any(Entity.class), any(InvocationCallback.class))).thenAnswer(postCall -> {
                calls.add(location ? "location" : "text");
                Response response = mock(Response.class);
                when(response.getStatus()).thenReturn(status);
                ((InvocationCallback) postCall.getArgument(1)).completed(response);
                return null;
            });
            return target;
        });
        return new NotificatorTelegram(config, mock(NotificationFormatter.class), client, null);
    }

    private CompletableFuture<Void> send(NotificatorTelegram notificator) {
        Position position = new Position();
        position.setLatitude(43.48);
        position.setLongitude(-8.20);
        return notificator.sendAsync(
                new User(), new NotificationMessage(null, "Test: alarma", null, false), null, position);
    }

    @Test
    public void anAcceptedTextIsDelivered() throws Exception {
        CompletableFuture<Void> sent = send(notificator(false, 200, 200));
        sent.get();
        assertEquals(List.of("text"), calls);
    }

    @Test
    public void tooManyRequestsIsAFailure() {
        CompletableFuture<Void> sent = send(notificator(false, 429, 200));
        ExecutionException e = assertThrows(ExecutionException.class, sent::get);
        // What the queue stores as the reason: short, with the status in it.
        String reason = EsperaReintento.motivo(e, 512);
        assertTrue(reason.contains("429"), reason);
    }

    @Test
    public void aServerErrorIsAFailure() {
        assertThrows(ExecutionException.class, send(notificator(false, 502, 200))::get);
    }

    @Test
    public void aRefusedTextSendsNoLocation() {
        assertThrows(ExecutionException.class, send(notificator(true, 429, 200))::get);
        assertEquals(List.of("text"), calls);
    }

    @Test
    public void aRefusedLocationDoesNotFailADeliveredText() throws Exception {
        // If this failed the send, the retry would deliver the same text twice.
        send(notificator(true, 200, 429)).get();
        assertEquals(List.of("text", "location"), calls);
    }

}
