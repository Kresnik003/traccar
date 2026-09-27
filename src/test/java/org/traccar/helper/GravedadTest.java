package org.traccar.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class GravedadTest {

    @Test
    public void testGravedad() {
        assertEquals("critica", Gravedad.de("accident"));
        assertEquals("alta", Gravedad.de("vibration"));
        assertEquals("media", Gravedad.de("hardBraking"));
        assertEquals("critica", Gravedad.de("tow,powerCut"), "un robo de verdad llega así");
        assertEquals("critica", Gravedad.de("vibration, tow"), "manda la peor, con espacios o sin ellos");
        assertNull(Gravedad.de(null));
        assertNull(Gravedad.de(",,"));
    }

}
