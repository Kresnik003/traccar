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

    @Test
    public void testSoloUnaAlarmaEsUnAccidente() {
        // Otro tipo de evento con el atributo de alarma no es un choque.
        Event encendido = new Event(Event.TYPE_IGNITION_ON, 8);
        encendido.set(Position.KEY_ALARM, "accident");
        assertFalse(VentanaAccidente.esAccidente(encendido));
        // Y una alarma sin el atributo, tampoco (ni revienta).
        assertFalse(VentanaAccidente.esAccidente(new Event(Event.TYPE_ALARM, 8)));
    }

    @Test
    public void testLaVentanaAcabaALosDiezMinutosJustos() {
        VentanaAccidente v = new VentanaAccidente();
        long t = 1_000_000L;
        assertTrue(v.enviar(6, "telegram", 8, t));
        assertTrue(v.enviar(6, "telegram", 8, t + VentanaAccidente.VENTANA), "a los 10 min justos, es otro choque");
    }

    @Test
    public void testLimpiarNoOlvidaUnChoqueEnCurso() {
        // Con más de 1000 recordados se tiran los viejos, nunca uno de hace un segundo.
        VentanaAccidente v = new VentanaAccidente();
        long t = 1_000_000L;
        for (int moto = 0; moto <= 1000; moto++) {
            assertTrue(v.enviar(6, "telegram", moto, t));
        }
        assertFalse(v.enviar(6, "telegram", 0, t + 1_000), "el mismo choque, un segundo después");
    }

}
