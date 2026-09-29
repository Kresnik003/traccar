package org.traccar.seguridad;

import org.junit.jupiter.api.Test;
import org.traccar.geofence.GeofenceCircle;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Position;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a trip starts and ends (improvement 115).
 */
public class ReglasViajeTest {

    private static final long T = 1_790_000_000_000L;

    private final Sitio casa;

    public ReglasViajeTest() throws Exception {
        casa = new Sitio(1, "Casa", new GeofenceCircle("CIRCLE (43 -8, 100)"));
    }

    private static Position pos(long msAntes, boolean contacto, double kph) {
        Position position = new Position();
        position.setDeviceTime(new Date(T - msAntes));
        position.set(Position.KEY_IGNITION, contacto);
        position.setSpeed(UnitsConverter.knotsFromKph(kph));
        return position;
    }

    @Test
    public void leavingHomeWithTheIgnitionOnIsADeparture() {
        assertTrue(ReglasViaje.esSalida(Optional.of(casa), Optional.empty(), true));
    }

    @Test
    public void warmingUpAtHomeIsNotADeparture() {
        assertFalse(ReglasViaje.esSalida(Optional.of(casa), Optional.of(casa), true));
    }

    @Test
    public void leavingWithoutIgnitionIsNotADeparture() {
        // Pushed, or on a tow truck: that is what the theft alarms are for.
        assertFalse(ReglasViaje.esSalida(Optional.of(casa), Optional.empty(), false));
        assertFalse(ReglasViaje.esSalida(Optional.empty(), Optional.empty(), true));
    }

    @Test
    public void arrivingIsTwoMinutesStillWithoutIgnitionAtOneOfYourPlaces() {
        assertTrue(ReglasViaje.esLlegada(Optional.of(casa), false, T - 120_000, T));
        assertFalse(ReglasViaje.esLlegada(Optional.of(casa), false, T - 60_000, T));
        assertFalse(ReglasViaje.esLlegada(Optional.empty(), false, T - 600_000, T));
        assertFalse(ReglasViaje.esLlegada(Optional.of(casa), true, T - 600_000, T));
    }

    @Test
    public void twelveHoursCloseTheTrip() {
        assertTrue(ReglasViaje.caducado(new Date(T - 12 * 3_600_000L), T));
        assertFalse(ReglasViaje.caducado(new Date(T - 11 * 3_600_000L), T));
    }

    @Test
    public void insideAPlace() {
        assertEquals(Optional.of(casa), ReglasViaje.dentro(List.of(casa), 43.0005, -8.0));
        assertEquals(Optional.empty(), ReglasViaje.dentro(List.of(casa), 43.01, -8.0));
    }

    @Test
    public void stillSinceTheFirstStillPositionAfterTheLastMove() {
        // Newest first: still for 3 min, riding 5 min ago.
        assertEquals(T - 180_000, ReglasViaje.quietaDesde(List.of(
                pos(0, false, 0), pos(60_000, false, 0), pos(180_000, false, 1), pos(300_000, true, 40))));
    }

    @Test
    public void aBikeStillRidingIsNotStill() {
        assertNull(ReglasViaje.quietaDesde(List.of(pos(0, true, 0), pos(60_000, false, 0))));
        assertNull(ReglasViaje.quietaDesde(List.of()));
    }

}
