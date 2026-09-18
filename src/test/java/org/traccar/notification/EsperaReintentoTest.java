package org.traccar.notification;

import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EsperaReintentoTest {

    private static final long BASE = 60_000L;
    private static final long TOPE = 3_600_000L;

    @Test
    public void esperaDobleCadaVez() {
        assertEquals(BASE, EsperaReintento.espera(1, BASE, TOPE));
        assertEquals(2 * BASE, EsperaReintento.espera(2, BASE, TOPE));
        assertEquals(4 * BASE, EsperaReintento.espera(3, BASE, TOPE));
        assertEquals(8 * BASE, EsperaReintento.espera(4, BASE, TOPE));
    }

    @Test
    public void laEsperaTieneTope() {
        // Sin tope, el intento diez cae dias por delante y un aviso de
        // carretera que llega dias despues es peor que uno que no llega.
        assertEquals(TOPE, EsperaReintento.espera(10, BASE, TOPE));
        assertEquals(TOPE, EsperaReintento.espera(30, BASE, TOPE));
    }

    @Test
    public void nuncaDesbordaNiSaleNegativa() {
        // Doblando sin tope, un long se desborda sobre el intento 63 y vuelve
        // NEGATIVO: la fila quedaria vencida para siempre y la tarea giraria
        // sin parar. Aqui no puede pasar a ningun numero de intentos.
        for (int i = 1; i < 200; i++) {
            long espera = EsperaReintento.espera(i, BASE, TOPE);
            assertTrue(espera >= 0, "espera negativa en el intento " + i);
            assertTrue(espera <= TOPE, "espera por encima del tope en el intento " + i);
        }
    }

    @Test
    public void unIntentoImposibleNoRevientaLaCola() {
        assertEquals(BASE, EsperaReintento.espera(0, BASE, TOPE));
        assertEquals(BASE, EsperaReintento.espera(-5, BASE, TOPE));
    }

    @Test
    public void baseCeroEsSinEspera() {
        assertEquals(0, EsperaReintento.espera(3, 0, TOPE));
    }

    @Test
    public void cuandoSeDejaDeIntentar() {
        assertTrue(EsperaReintento.quedanIntentos(0, 5));
        assertTrue(EsperaReintento.quedanIntentos(4, 5));
        assertFalse(EsperaReintento.quedanIntentos(5, 5));
        assertFalse(EsperaReintento.quedanIntentos(9, 5));
    }

    @Test
    public void conMaximoCeroNoSeRindeNunca() {
        assertTrue(EsperaReintento.quedanIntentos(1000, 0));
        assertTrue(EsperaReintento.quedanIntentos(1000, -1));
    }

    @Test
    public void elMotivoEsUnaLineaYCabe() {
        Throwable e = new IllegalStateException("Telegram respondio 429\n\tat org.traccar.Foo");
        String motivo = EsperaReintento.motivo(e, 512);
        assertEquals("IllegalStateException: Telegram respondio 429", motivo);
    }

    @Test
    public void elMotivoSeRecortaAlLimite() {
        // Una traza entera serian kilobytes por fila, y ademas puede llevar
        // dentro la URL del canal con su token.
        Throwable e = new RuntimeException("x".repeat(900));
        assertEquals(64, EsperaReintento.motivo(e, 64).length());
    }

    @Test
    public void sinMensajeSeBuscaLaCausaQueLoTenga() {
        Throwable raiz = new SocketTimeoutException("connect timed out");
        Throwable envuelto = new RuntimeException(raiz);
        assertEquals("SocketTimeoutException: connect timed out",
                EsperaReintento.motivo(envuelto, 512));
    }

    @Test
    public void sinExcepcionNoSeInventaUnMotivo() {
        assertNull(EsperaReintento.motivo(null, 512));
    }

}
