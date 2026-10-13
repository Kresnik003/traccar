package org.traccar.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What Nordés says to Telegram outside Traccar's notifications: messages with
 * buttons (the "I'm fine" of a crash, "I've arrived" of a trip) and a refusal
 * that must not be retried.
 */
public class ClienteTelegramTest {

    private Client client;
    private final List<String> urls = new ArrayList<>();
    private final List<Object> cuerpos = new ArrayList<>();

    private ClienteTelegram cliente(int estado, String cuerpo) {
        return cliente(estado, cuerpo, "http://falso/bot");
    }

    private ClienteTelegram cliente(int estado, String cuerpo, String base) {
        client = mock(Client.class);
        WebTarget target = mock(WebTarget.class);
        Invocation.Builder builder = mock(Invocation.Builder.class);
        Response response = mock(Response.class);
        when(client.target(anyString())).thenAnswer(i -> {
            urls.add(i.getArgument(0));
            return target;
        });
        when(target.request()).thenReturn(builder);
        when(builder.post(any(Entity.class))).thenAnswer(i -> {
            cuerpos.add(((Entity<?>) i.getArgument(0)).getEntity());
            return response;
        });
        when(response.getStatus()).thenReturn(estado);
        when(response.readEntity(String.class)).thenReturn(cuerpo);
        Config config = new Config();
        config.setString(Keys.NOTIFICATOR_TELEGRAM_KEY, "123:abc");
        if (base != null) {
            config.setString(Keys.NOTIFICATOR_TELEGRAM_BASE_URL, base);
        }
        return new ClienteTelegram(config, client, client, new ObjectMapper());
    }

    /** What the client writes to Traccar's log while {@code accion} runs. */
    private static List<String> registro(Runnable accion) {
        Logger logger = Logger.getLogger(ClienteTelegram.class.getName());
        List<String> lineas = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord linea) {
                lineas.add(linea.getMessage()
                        + (linea.getParameters() != null ? Arrays.toString(linea.getParameters()) : "")
                        + (linea.getThrown() != null ? linea.getThrown().toString() : ""));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            accion.run();
        } finally {
            logger.removeHandler(handler);
        }
        return lineas;
    }

    @Test
    public void aMessageWithAButtonCarriesTheInlineKeyboard() {
        var respuesta = cliente(200, "{\"ok\":true}").enviar("42", "hola",
                List.of(new ClienteTelegram.Boton("Estoy bien", "g:7:firma")));
        assertTrue(respuesta.ok());
        assertEquals("http://falso/bot123:abc/sendMessage", urls.get(0));
        String json = cuerpos.get(0).toString();
        assertTrue(json.contains("\"inline_keyboard\""), json);
        assertTrue(json.contains("\"callback_data\":\"g:7:firma\""), json);
        assertTrue(json.contains("\"chat_id\":\"42\""), json);
    }

    @Test
    public void aMessageWithoutButtonsHasNoKeyboard() {
        cliente(200, "{\"ok\":true}").enviar("42", "hola", List.of());
        assertFalse(cuerpos.get(0).toString().contains("reply_markup"));
    }

    @Test
    public void aBlockedBotIsARefusal() {
        var respuesta = cliente(403, "{\"ok\":false,\"description\":\"Forbidden: bot was blocked by the user\"}")
                .enviar("42", "hola", List.of());
        assertFalse(respuesta.ok());
        assertTrue(respuesta.rechazado());
        assertEquals("Forbidden: bot was blocked by the user", respuesta.descripcion());
    }

    @Test
    public void aTooManyRequestsIsNotARefusal() {
        var respuesta = cliente(429, "{\"ok\":false,\"description\":\"Too Many Requests\"}")
                .enviar("42", "hola", List.of());
        assertFalse(respuesta.ok());
        assertFalse(respuesta.rechazado());
    }

    @Test
    public void aNetworkFailureDoesNotReachTheCaller() {
        ClienteTelegram c = cliente(200, "{}");
        when(client.target(anyString())).thenThrow(new ProcessingException("sin red"));
        var respuesta = c.enviar("42", "hola", List.of());
        assertFalse(respuesta.ok());
        assertFalse(respuesta.rechazado());
    }

    @Test
    public void theBotNameComesFromGetMeAndIsKept() {
        ClienteTelegram c = cliente(200, "{\"ok\":true,\"result\":{\"username\":\"NordesBot\"}}");
        assertEquals("NordesBot", c.nombreDelBot());
        assertEquals("NordesBot", c.nombreDelBot());
        assertEquals(1, urls.size());
        assertTrue(urls.get(0).endsWith("/getMe"));
    }

    @Test
    public void readingReturnsTheResultArray() {
        var lectura = cliente(200, "{\"ok\":true,\"result\":[{\"update_id\":5}]}").leer(0, 25);
        assertTrue(lectura.ok());
        assertEquals(5, lectura.updates().get(0).path("update_id").asLong());
        assertTrue(cuerpos.get(0).toString().contains("\"timeout\":25"));
    }

    @Test
    public void aRefusedReadIsReportedNotTakenForAnEmptyInbox() {
        // Found in review (29/9): a 409 (a webhook, or a second reader) or a 401
        // came back as "no messages", forever and without a line in the log.
        var lectura = cliente(409, "{\"ok\":false,\"description\":\"Conflict: terminated by other getUpdates request\"}")
                .leer(0, 25);
        assertFalse(lectura.ok());
        assertEquals(409, lectura.estado());
        assertEquals("Conflict: terminated by other getUpdates request", lectura.descripcion());
        assertEquals(0, lectura.updates().size());
    }

    @Test
    public void anIdleReadGoesWithoutAnOffset() {
        // 0 means "from the oldest update nobody confirmed"; any other value is
        // sent as it is, -1 (forget all but the newest) included.
        ClienteTelegram c = cliente(200, "{\"ok\":true,\"result\":[]}");
        c.leer(0, 25);
        c.leer(7, 25);
        c.leer(-1, 0);
        assertFalse(cuerpos.get(0).toString().contains("offset"), cuerpos.get(0).toString());
        assertTrue(cuerpos.get(1).toString().contains("\"offset\":7"), cuerpos.get(1).toString());
        assertTrue(cuerpos.get(2).toString().contains("\"offset\":-1"), cuerpos.get(2).toString());
    }

    @Test
    public void a2xxThatIsNotTelegramsAnswerIsNoReading() {
        // Taken for "no messages" it would keep BuzonTelegramSinLeer quiet while
        // nothing is being read.
        for (String cuerpo : new String[] {
                "{\"ok\":false,\"result\":[{\"update_id\":5}]}",
                "{\"ok\":true}",
                "{\"result\":[]}",
                "{\"ok\":true,\"result\":{}}",
        }) {
            var lectura = cliente(200, cuerpo).leer(0, 25);
            assertFalse(lectura.ok(), cuerpo);
            assertEquals(0, lectura.updates().size(), cuerpo);
            assertTrue(lectura.updates().isArray(), cuerpo);
        }
    }

    @Test
    public void withoutABaseUrlItTalksToTelegramItself() {
        cliente(200, "{\"ok\":true}", null).enviar("42", "hola", List.of());
        assertEquals("https://api.telegram.org/bot123:abc/sendMessage", urls.get(0));
    }

    @Test
    public void onlyA2xxReadIsAnInbox() {
        // 199 and 300 are refusals: no updates, and the reason is kept.
        for (int estado : new int[] {199, 300}) {
            var lectura = cliente(estado, "{\"ok\":false,\"description\":\"motivo\",\"result\":[{\"update_id\":5}]}")
                    .leer(0, 25);
            assertFalse(lectura.ok(), "HTTP " + estado);
            assertEquals(0, lectura.updates().size(), "HTTP " + estado);
            assertEquals("motivo", lectura.descripcion(), "HTTP " + estado);
        }
        for (int estado : new int[] {200, 299}) {
            var lectura = cliente(estado, "{\"ok\":true,\"result\":[{\"update_id\":5}]}").leer(0, 25);
            assertTrue(lectura.ok(), "HTTP " + estado);
            assertEquals(5, lectura.updates().get(0).path("update_id").asLong(), "HTTP " + estado);
            assertNull(lectura.descripcion(), "HTTP " + estado);
        }
    }

    @Test
    public void theDescriptionExplainsAFailureAndOnlyAFailure() {
        String cuerpo = "{\"ok\":false,\"description\":\"motivo\"}";
        for (int estado : new int[] {199, 300}) {
            var respuesta = cliente(estado, cuerpo).enviar("42", "hola", List.of());
            assertFalse(respuesta.ok(), "HTTP " + estado);
            assertEquals("motivo", respuesta.descripcion(), "HTTP " + estado);
        }
        for (int estado : new int[] {200, 299}) {
            var respuesta = cliente(estado, cuerpo).enviar("42", "hola", List.of());
            assertTrue(respuesta.ok(), "HTTP " + estado);
            assertNull(respuesta.descripcion(), "HTTP " + estado);
        }
    }

    @Test
    public void okMeansA2xxAndNothingElse() {
        for (int estado : new int[] {0, 199, 300, 403, 409}) {
            assertFalse(new ClienteTelegram.Lectura(estado, null, null).ok(), "HTTP " + estado);
            assertFalse(new RespuestaTelegram(estado, null).ok(), "HTTP " + estado);
        }
        for (int estado : new int[] {200, 204, 299}) {
            assertTrue(new ClienteTelegram.Lectura(estado, null, null).ok(), "HTTP " + estado);
            assertTrue(new RespuestaTelegram(estado, null).ok(), "HTTP " + estado);
        }
    }

    @Test
    public void aFailedReadIsReportedAndNeverShowsTheToken() {
        // The token travels in the URL, and a network exception may quote it.
        ClienteTelegram c = cliente(200, "{}");
        when(client.target(anyString())).thenThrow(
                new ProcessingException("POST http://falso/bot123:abc/getUpdates: Connection refused"));
        List<ClienteTelegram.Lectura> lecturas = new ArrayList<>();
        List<String> lineas = registro(() -> lecturas.add(c.leer(7, 25)));
        var lectura = lecturas.get(0);
        assertFalse(lectura.ok());
        assertEquals(0, lectura.estado());
        // An empty list, not null: the inbox walks it.
        assertTrue(lectura.updates().isArray());
        assertEquals(0, lectura.updates().size());
        assertEquals("ProcessingException", lectura.descripcion());
        assertFalse(lineas.isEmpty(), "the failure did not reach the log");
        lineas.forEach(linea -> assertFalse(linea.contains("123:abc"), linea));
    }

    @Test
    public void aFailedSendNeverShowsTheTokenEither() {
        ClienteTelegram c = cliente(200, "{}");
        when(client.target(anyString())).thenThrow(
                new ProcessingException("POST http://falso/bot123:abc/sendMessage: Connection refused"));
        List<RespuestaTelegram> respuestas = new ArrayList<>();
        List<String> lineas = registro(() -> respuestas.add(c.enviar("42", "hola", List.of())));
        assertEquals("ProcessingException", respuestas.get(0).descripcion());
        assertFalse(lineas.isEmpty(), "the failure did not reach the log");
        lineas.forEach(linea -> assertFalse(linea.contains("123:abc"), linea));
    }

}
