package org.traccar.seguridad;

import org.junit.jupiter.api.Test;
import org.traccar.telegram.BuzonTelegram;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The safety chain as Prometheus sees it. Found on 2026-09-29: the tests prove
 * the code, but nothing would tell that the inbox stopped reading ("I'm fine"
 * dead, the family warned about somebody who is fine) or that the countdown
 * stopped (a crash nobody is told about). Neither makes a sound on its own.
 */
public class VigiaSeguridadTest {

    private static final long ARRANQUE = 1_790_000_000_000L;

    private final GuardianGolpes guardian = mock(GuardianGolpes.class);
    private final BuzonTelegram buzon = mock(BuzonTelegram.class);
    private long ahora = ARRANQUE;

    private VigiaSeguridad vigia() {
        return new VigiaSeguridad(guardian, buzon, () -> ahora);
    }

    private void encendido() {
        when(guardian.activo()).thenReturn(true);
        when(buzon.activo()).thenReturn(true);
        when(guardian.vencidas(anyLong())).thenReturn(OptionalInt.of(0));
    }

    /** The sample of a metric, or null when it is not there. */
    private static String valor(String texto, String metrica) {
        for (String linea : texto.split("\n")) {
            if (linea.startsWith(metrica + " ")) {
                return linea.substring(metrica.length() + 1);
            }
        }
        return null;
    }

    @Test
    public void allIsWellWhenEverythingRuns() {
        encendido();
        VigiaSeguridad vigia = vigia();
        ahora = ARRANQUE + 600_000;
        when(guardian.ultimaRevision()).thenReturn(ahora - 4_000);
        when(buzon.ultimaLectura()).thenReturn(ahora - 12_000);
        String texto = vigia.render();
        assertEquals("1", valor(texto, "nordes_seguridad_activa"));
        assertEquals("4", valor(texto, "nordes_guardias_revision_segundos"));
        assertEquals("0", valor(texto, "nordes_guardias_vencidas"));
        assertEquals("0", valor(texto, "nordes_avisos_perdidos_total"));
        assertEquals("12", valor(texto, "nordes_buzon_sin_leer_segundos"));
        assertTrue(texto.contains("# TYPE nordes_avisos_perdidos_total counter\n"), texto);
        assertTrue(texto.contains("# TYPE nordes_buzon_sin_leer_segundos gauge\n"), texto);
        assertTrue(texto.contains("# HELP nordes_guardias_vencidas "), texto);
    }

    @Test
    public void beforeTheFirstPassItCountsFromTheStartNotFrom1970() {
        encendido();
        VigiaSeguridad vigia = vigia();
        ahora = ARRANQUE + 45_000;
        String texto = vigia.render();
        assertEquals("45", valor(texto, "nordes_guardias_revision_segundos"));
        assertEquals("45", valor(texto, "nordes_buzon_sin_leer_segundos"));
    }

    @Test
    public void aStoppedChainShows() {
        encendido();
        VigiaSeguridad vigia = vigia();
        ahora = ARRANQUE + 3_600_000;
        when(guardian.ultimaRevision()).thenReturn(ahora - 300_000);
        when(guardian.vencidas(ahora)).thenReturn(OptionalInt.of(2));
        when(guardian.avisosPerdidos()).thenReturn(3L);
        when(buzon.ultimaLectura()).thenReturn(ahora - 900_000);
        String texto = vigia.render();
        assertEquals("300", valor(texto, "nordes_guardias_revision_segundos"));
        assertEquals("2", valor(texto, "nordes_guardias_vencidas"));
        assertEquals("3", valor(texto, "nordes_avisos_perdidos_total"));
        assertEquals("900", valor(texto, "nordes_buzon_sin_leer_segundos"));
    }

    @Test
    public void withoutTheDatabaseTheOverdueCountIsLeftOutNotZero() {
        encendido();
        when(guardian.vencidas(anyLong())).thenReturn(OptionalInt.empty());
        String texto = vigia().render();
        assertEquals(null, valor(texto, "nordes_guardias_vencidas"));
        assertFalse(texto.contains("nordes_guardias_vencidas"), texto);
    }

    @Test
    public void switchedOffItOnlySaysSo() {
        String texto = vigia().render();
        assertEquals("0", valor(texto, "nordes_seguridad_activa"));
        assertEquals(null, valor(texto, "nordes_guardias_revision_segundos"));
        assertEquals(null, valor(texto, "nordes_guardias_vencidas"));
        assertEquals(null, valor(texto, "nordes_avisos_perdidos_total"));
        assertEquals(null, valor(texto, "nordes_buzon_sin_leer_segundos"));
    }

    @Test
    public void aNodeWithoutTheInboxLeavesItOut() {
        encendido();
        when(buzon.activo()).thenReturn(false);
        String texto = vigia().render();
        assertEquals("1", valor(texto, "nordes_seguridad_activa"));
        assertEquals(null, valor(texto, "nordes_buzon_sin_leer_segundos"));
    }

    @Test
    public void aClockThatWentBackIsZeroNotNegative() {
        encendido();
        VigiaSeguridad vigia = vigia();
        ahora = ARRANQUE + 60_000;
        when(guardian.ultimaRevision()).thenReturn(ahora + 5_000);
        assertEquals("0", valor(vigia.render(), "nordes_guardias_revision_segundos"));
    }

}
