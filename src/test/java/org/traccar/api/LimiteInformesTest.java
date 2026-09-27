package org.traccar.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LimiteInformesTest {

    private static final long T0 = 1_000_000L;

    @AfterEach
    public void limpiar() {
        LimiteInformes.olvidar();
    }

    @Test
    public void testDejaLaRafagaYFrenaLaSiguiente() {
        for (int i = 0; i < LimiteInformes.CAPACIDAD; i++) {
            assertEquals(0, LimiteInformes.gastar(1, T0));
        }
        long espera = LimiteInformes.gastar(1, T0);
        assertTrue(espera >= 1 && espera <= LimiteInformes.REPOSICION_MS / 1000, "espera " + espera);
    }

    @Test
    public void testSeReponeConElTiempo() {
        for (int i = 0; i < LimiteInformes.CAPACIDAD; i++) {
            LimiteInformes.gastar(1, T0);
        }
        assertEquals(0, LimiteInformes.gastar(1, T0 + LimiteInformes.REPOSICION_MS));
        assertTrue(LimiteInformes.gastar(1, T0 + LimiteInformes.REPOSICION_MS) > 0);
    }

    @Test
    public void testCadaUsuarioTieneSuCubo() {
        for (int i = 0; i < LimiteInformes.CAPACIDAD; i++) {
            LimiteInformes.gastar(1, T0);
        }
        assertEquals(0, LimiteInformes.gastar(2, T0));
    }

    @Test
    public void testCadaUsuarioTieneSusTurnos() {
        var turnos = LimiteInformes.turnos(1);
        assertEquals(LimiteInformes.SIMULTANEOS, turnos.availablePermits());
        assertTrue(turnos.tryAcquire(LimiteInformes.SIMULTANEOS));
        assertFalse(turnos.tryAcquire());
        assertEquals(LimiteInformes.SIMULTANEOS, LimiteInformes.turnos(2).availablePermits());
        assertSame(turnos, LimiteInformes.turnos(1));
    }

}
