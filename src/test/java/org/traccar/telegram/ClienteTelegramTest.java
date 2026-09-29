package org.traccar.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        config.setString(Keys.NOTIFICATOR_TELEGRAM_BASE_URL, "http://falso/bot");
        return new ClienteTelegram(config, client, client, new ObjectMapper());
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
        var result = cliente(200, "{\"ok\":true,\"result\":[{\"update_id\":5}]}").leer(0, 25);
        assertEquals(5, result.get(0).path("update_id").asLong());
        assertTrue(cuerpos.get(0).toString().contains("\"timeout\":25"));
    }
}
