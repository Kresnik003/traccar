package org.traccar.helper;

import org.junit.jupiter.api.Test;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SilencioAvisosTest {

    private static final long AHORA = Instant.parse("2026-09-27T18:00:00Z").toEpochMilli();

    private static Device moto(Object silencio) {
        Device device = new Device();
        if (silencio != null) {
            device.set(SilencioAvisos.ATRIBUTO, silencio.toString());
        }
        return device;
    }

    private static Event alarma(String cual) {
        Event event = new Event(Event.TYPE_ALARM, 1);
        event.set(Position.KEY_ALARM, cual);
        return event;
    }

    @Test
    public void testVigencia() {
        assertFalse(SilencioAvisos.vigente(null, AHORA));
        assertFalse(SilencioAvisos.vigente("", AHORA));
        assertTrue(SilencioAvisos.vigente("siempre", AHORA));
        assertTrue(SilencioAvisos.vigente("2026-09-27T19:00:00Z", AHORA));
        assertFalse(SilencioAvisos.vigente("2026-09-27T17:59:59Z", AHORA), "ya caducó");
        assertFalse(SilencioAvisos.vigente("mañana", AHORA), "una errata no silencia para siempre");
    }

    @Test
    public void testLoQueNuncaSeCalla() {
        assertTrue(SilencioAvisos.critico(alarma("accident")));
        assertTrue(SilencioAvisos.critico(alarma("vibration,sos")));
        assertTrue(SilencioAvisos.critico(alarma("powerCut")));
        assertFalse(SilencioAvisos.critico(alarma("vibration")));
        assertFalse(SilencioAvisos.critico(new Event(Event.TYPE_IGNITION_ON, 1)));
    }

    @Test
    public void testCallar() {
        Device taller = moto("siempre");
        assertTrue(SilencioAvisos.callar(taller, new Event(Event.TYPE_IGNITION_ON, 1), AHORA));
        assertTrue(SilencioAvisos.callar(taller, alarma("tow"), AHORA));
        assertFalse(SilencioAvisos.callar(taller, alarma("accident"), AHORA), "un choque suena siempre");
        assertFalse(SilencioAvisos.callar(moto(null), new Event(Event.TYPE_IGNITION_ON, 1), AHORA));
        assertFalse(SilencioAvisos.callar(null, alarma("tow"), AHORA));
    }

}
