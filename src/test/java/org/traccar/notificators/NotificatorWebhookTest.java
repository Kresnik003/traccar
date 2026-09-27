package org.traccar.notificators;

import org.junit.jupiter.api.Test;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.notification.FalloDefinitivo;
import org.traccar.notification.NotificationMessage;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NotificatorWebhookTest {

    @Test
    public void testFirmaHmacSha256() throws Exception {
        // Vector conocido: HMAC-SHA256("key", "The quick brown fox jumps over the lazy dog").
        assertEquals("sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                NotificatorWebhook.firma("key",
                        "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCuerpo() {
        Event event = new Event(Event.TYPE_ALARM, 7);
        event.setId(123);
        event.setEventTime(new Date(0));
        event.set(Position.KEY_ALARM, "accident");
        event.set("gravedad", "critica");
        Position position = new Position();
        position.setLatitude(43.48);
        position.setLongitude(-8.2);
        position.setSpeed(10);
        position.setFixTime(new Date(1000));
        Device device = new Device();
        device.setName("Ducati");

        var cuerpo = NotificatorWebhook.cuerpo(
                new NotificationMessage("Asunto", "Texto corto", "<p>largo</p>", true), event, position, device, 2000);

        var evento = (Map<String, Object>) cuerpo.get("evento");
        assertEquals(123L, evento.get("id"));
        assertEquals("alarm", evento.get("tipo"));
        assertEquals("accident", evento.get("alarma"));
        assertEquals("critica", evento.get("gravedad"));
        assertEquals("1970-01-01T00:00:00Z", evento.get("hora"));
        assertEquals("Ducati", ((Map<String, Object>) cuerpo.get("equipo")).get("nombre"));
        var posicion = (Map<String, Object>) cuerpo.get("posicion");
        assertEquals(18.5, posicion.get("velocidadKmh"));
        assertEquals("Texto corto", ((Map<String, Object>) cuerpo.get("mensaje")).get("texto"));
        assertFalse(cuerpo.toString().contains("<p>"), "el cuerpo HTML del correo no va");
        assertEquals("1970-01-01T00:00:02Z", cuerpo.get("enviado"));
    }

    @Test
    public void testQueSeReintentaYQueNo() {
        assertDoesNotThrow(() -> NotificatorWebhook.comprobarRespuesta(204));
        for (int codigo : new int[] {404, 410, 401, 400}) {
            var e = assertThrows(IllegalStateException.class, () -> NotificatorWebhook.comprobarRespuesta(codigo));
            assertTrue(FalloDefinitivo.es(new CompletionException(e)), "definitivo: " + codigo);
        }
        for (int codigo : new int[] {500, 502, 503, 408, 429, 302}) {
            var e = assertThrows(IllegalStateException.class, () -> NotificatorWebhook.comprobarRespuesta(codigo));
            assertFalse(FalloDefinitivo.es(new CompletionException(e)), "se reintenta: " + codigo);
        }
        assertTrue(FalloDefinitivo.es(new CompletionException(
                assertThrows(IllegalArgumentException.class, () -> DestinoWebhook.comprobar(null)))));
    }

}
