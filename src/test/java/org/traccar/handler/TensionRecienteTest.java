package org.traccar.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TensionRecienteTest {

    @Test
    public void testRafagaDeAccidente() {
        TensionReciente t = new TensionReciente();
        long ahora = 1_000_000_000L;
        assertNull(t.completar(8, 12.87, ahora), "la trama con tensión no necesita nada");
        assertEquals(12.87, t.completar(8, null, ahora + 20_000), "la del choque, 20 s después, la hereda");
        assertEquals(12.87, t.completar(8, null, ahora + 25_000), "y la traza, que es de la misma ráfaga");
    }

    @Test
    public void testNoSeInventa() {
        TensionReciente t = new TensionReciente();
        long ahora = 1_000_000_000L;
        assertNull(t.completar(8, null, ahora), "sin ninguna lectura antes, nada");
        t.completar(8, 12.5, ahora);
        assertNull(t.completar(8, null, ahora + TensionReciente.VIGENCIA + 1), "una lectura vieja no vale");
        assertNull(t.completar(9, null, ahora + 1000), "la de otra moto tampoco");
        assertNull(t.completar(8, null, ahora - 5000), "una trama anterior a la lectura no la hereda");
    }

}
