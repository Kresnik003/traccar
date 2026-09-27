package org.traccar.notification;

import org.junit.jupiter.api.Test;
import org.traccar.model.Event;
import org.traccar.model.Position;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VentanaAccidenteTest {

    private static Event alarma(String cual) {
        Event event = new Event(Event.TYPE_ALARM, 8);
        event.set(Position.KEY_ALARM, cual);
        return event;
    }

    @Test
    public void testQueEsUnAccidente() {
        assertTrue(VentanaAccidente.esAccidente(alarma("accident")));
        assertTrue(VentanaAccidente.esAccidente(alarma("vibration,accident")));
        assertFalse(VentanaAccidente.esAccidente(alarma("sos")));
        assertFalse(VentanaAccidente.esAccidente(new Event(Event.TYPE_IGNITION_ON, 8)));
    }

    @Test
    public void testUnAccidenteUnAviso() {
        VentanaAccidente v = new VentanaAccidente();
        long t = 1_000_000L;
        assertTrue(v.enviar(6, "telegram", 8, t), "los vectores: sale");
        assertFalse(v.enviar(6, "telegram", 8, t + 20_000), "la traza, 20 s después: absorbida");
        assertFalse(v.enviar(6, "telegram", 8, t + 5 * 60_000), "a los 5 min, aún el mismo choque");
        assertTrue(v.enviar(6, "web", 8, t + 1_000), "otro canal avisa por su cuenta");
        assertTrue(v.enviar(7, "telegram", 8, t + 1_000), "otro usuario también");
        assertTrue(v.enviar(6, "telegram", 9, t + 1_000), "otra moto también");
        assertTrue(v.enviar(6, "telegram", 8, t + VentanaAccidente.VENTANA + 1), "pasada la ventana, es otro");
    }

}
