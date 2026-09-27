package org.traccar.api.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BloqueoCuentasTest {

    private static final long T0 = 1_000_000L;

    @Test
    public void testCuatroFallosNoBloquean() {
        var bloqueo = new BloqueoCuentas();
        for (int i = 0; i < BloqueoCuentas.MAX_FALLOS - 1; i++) {
            bloqueo.fallo("a@b.es", T0 + i);
        }
        assertEquals(0, bloqueo.segundosBloqueada("a@b.es", T0 + 10));
    }

    @Test
    public void testElQuintoBloqueaQuinceMinutos() {
        var bloqueo = new BloqueoCuentas();
        for (int i = 0; i < BloqueoCuentas.MAX_FALLOS; i++) {
            bloqueo.fallo("a@b.es", T0);
        }
        assertEquals(15 * 60, bloqueo.segundosBloqueada("a@b.es", T0));
        assertEquals(1, bloqueo.segundosBloqueada("a@b.es", T0 + BloqueoCuentas.BLOQUEO_MS - 1));
        assertEquals(0, bloqueo.segundosBloqueada("a@b.es", T0 + BloqueoCuentas.BLOQUEO_MS));
        assertEquals(0, bloqueo.segundosBloqueada("otra@b.es", T0));
    }

    @Test
    public void testFallosEspaciadosNoSeAcumulan() {
        var bloqueo = new BloqueoCuentas();
        for (int i = 0; i < BloqueoCuentas.MAX_FALLOS * 2; i++) {
            bloqueo.fallo("a@b.es", T0 + i * (BloqueoCuentas.VENTANA_MS / 2 + 1));
        }
        assertEquals(0, bloqueo.segundosBloqueada("a@b.es", T0 + BloqueoCuentas.VENTANA_MS * 5));
    }

    @Test
    public void testUnAciertoBorraLosFallos() {
        var bloqueo = new BloqueoCuentas();
        for (int i = 0; i < BloqueoCuentas.MAX_FALLOS - 1; i++) {
            bloqueo.fallo("a@b.es", T0);
        }
        bloqueo.acierto("a@b.es");
        bloqueo.fallo("a@b.es", T0);
        assertEquals(0, bloqueo.segundosBloqueada("a@b.es", T0));
    }

    @Test
    public void testFallarDuranteElBloqueoNoLoAlarga() {
        var bloqueo = new BloqueoCuentas();
        for (int i = 0; i < BloqueoCuentas.MAX_FALLOS; i++) {
            bloqueo.fallo("a@b.es", T0);
        }
        bloqueo.fallo("a@b.es", T0 + 60_000);
        assertTrue(bloqueo.segundosBloqueada("a@b.es", T0 + 60_000) <= 14 * 60);
    }

}
