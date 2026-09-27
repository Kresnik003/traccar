package org.traccar.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LogJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode leer(String mensaje) throws Exception {
        String linea = LogJson.linea(0, "INFO", "org.traccar.X", mensaje, null);
        assertTrue(linea.endsWith(System.lineSeparator()));
        assertEquals(1, linea.split("\n").length, "una línea por registro");
        return JSON.readTree(linea);
    }

    @Test
    public void testLaSesionDeRedSaleEnCampos() throws Exception {
        var n = leer("[T1a2b3c4d: teltonika < 83.44.1.2] 000000000000003608010000");
        assertEquals("T1a2b3c4d", n.get("sesion").asText());
        assertEquals("teltonika", n.get("protocolo").asText());
        assertEquals("entra", n.get("sentido").asText());
        assertEquals("83.44.1.2", n.get("remoto").asText());
        assertEquals("1970-01-01T00:00:00Z", n.get("ts").asText());
    }

    @Test
    public void testLaPosicionDiceElEquipo() throws Exception {
        var n = leer("[T1a2b3c4d] id: 352093081234567, time: 2026-09-27 21:00:00, lat: 43.48");
        assertEquals("T1a2b3c4d", n.get("sesion").asText());
        assertEquals("352093081234567", n.get("equipo").asText());
        assertFalse(n.has("protocolo"));
    }

    @Test
    public void testElAvisoSeSigueConClaveValor() throws Exception {
        var n = leer("Event id: 352093081234567, time: 2026-09-27 21:00:00, type: alarm, notifications: 2"
                + " evento=123 tipo=alarm alarma=accident");
        assertEquals("352093081234567", n.get("equipo").asText());
        assertEquals("123", n.get("evento").asText());
        assertEquals("accident", n.get("alarma").asText());
        n = leer("Notification failed evento=123 usuario=7 canal=telegram");
        assertEquals("7", n.get("usuario").asText());
        assertEquals("telegram", n.get("canal").asText());
    }

    @Test
    public void testElTextoRaroNoRompeElJson() throws Exception {
        var n = leer("comillas \" barra \\ salto\nde línea \t y \u0001 control");
        assertEquals("comillas \" barra \\ salto\nde línea \t y \u0001 control", n.get("msg").asText());
    }

    @Test
    public void testElFormateadorEligeSegunLaClave() throws Exception {
        var registro = new LogRecord(Level.WARNING, "Notification failed evento=9 usuario=1 canal=mail");
        registro.setLoggerName("org.traccar.database.NotificationManager");
        registro.setThrown(new IllegalStateException("smtp caído"));
        var json = JSON.readTree(new Log.LogFormatter(false, true).format(registro));
        assertEquals("WARN", json.get("nivel").asText());
        assertEquals("9", json.get("evento").asText());
        assertTrue(json.get("error").asText().contains("smtp caído"));
        String texto = new Log.LogFormatter(false).format(registro);
        assertTrue(texto.matches("(?s)\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d  WARN: Notification failed.*"), texto);
    }

}
