package org.traccar.api.resource;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AvisoResourceTest {

    @Test
    public void testLimite() {
        assertEquals(AvisoResource.LIMITE_POR_DEFECTO, AvisoResource.limite(null));
        assertEquals(AvisoResource.LIMITE_POR_DEFECTO, AvisoResource.limite(0));
        assertEquals(AvisoResource.LIMITE_POR_DEFECTO, AvisoResource.limite(-5));
        assertEquals(20, AvisoResource.limite(20));
        assertEquals(AvisoResource.LIMITE_MAXIMO, AvisoResource.limite(100000));
    }

    @Test
    public void testNoLeidosSoloDeLoQueSeVe() {
        // Device 2 was sold: its unread notifications do not count any more.
        Map<Long, Long> porEquipo = Map.of(1L, 3L, 2L, 5L, 3L, 1L);
        assertEquals(4, AvisoResource.noLeidos(porEquipo, id -> id != 2));
        assertEquals(9, AvisoResource.noLeidos(porEquipo, id -> true));
        assertEquals(0, AvisoResource.noLeidos(porEquipo, id -> false));
    }

}
