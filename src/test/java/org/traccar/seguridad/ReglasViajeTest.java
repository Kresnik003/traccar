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

    private static double kmh(double valor) {
        return UnitsConverter.knotsFromKph(valor);
    }

    @Test
    public void ridingOutsideWithTheIgnitionOnMayBeADeparture() {
        assertTrue(ReglasViaje.rodandoFuera(Optional.empty(), true, kmh(30)));
    }

    @Test
    public void warmingUpAtHomeIsNotADeparture() {
        assertFalse(ReglasViaje.rodandoFuera(Optional.of(casa), true, kmh(0)));
    }

    @Test
    public void theGpsDriftingOutOfTheGarageIsNotADeparture() {
        // Found in review (29/9): without static navigation the fix wanders while
        // the engine warms up, and one position outside a small place was "left".
        assertFalse(ReglasViaje.rodandoFuera(Optional.empty(), true, kmh(3)));
    }

    @Test
    public void leavingWithoutIgnitionIsNotADeparture() {
        // Pushed, or on a tow truck: that is what the theft alarms are for.
        assertFalse(ReglasViaje.rodandoFuera(Optional.empty(), false, kmh(30)));
    }

    @Test
    public void theSecondRidingSampleConfirmsFromFifteenSecondsToFiveMinutes() {
        // The real Ducati on 13/9: 16 km/h at 20:13:15, 19 km/h at 20:13:24 and
        // 23 km/h at 20:13:26, then parked again, off. Nine and eleven seconds.
        assertFalse(ReglasViaje.confirmaSalida(T, T + 9_000));
        assertFalse(ReglasViaje.confirmaSalida(T, T + 14_999));
        assertTrue(ReglasViaje.confirmaSalida(T, T + 15_000));
        assertTrue(ReglasViaje.confirmaSalida(T, T + 5 * 60_000));
        assertFalse(ReglasViaje.confirmaSalida(T, T + 5 * 60_000 + 1));
        assertFalse(ReglasViaje.confirmaSalida(T, T - 20_000));
    }

    @Test
    public void farAwayWithTheIgnitionOffIsBeingTakenAway() {
        assertTrue(ReglasViaje.seLaLlevan(false, 301));
        // The drift of the real parking reaches ~80 m.
        assertFalse(ReglasViaje.seLaLlevan(false, 300));
        assertFalse(ReglasViaje.seLaLlevan(false, 80));
        // Ridden away slowly, with the ignition on: still armed.
        assertFalse(ReglasViaje.seLaLlevan(true, 5_000));
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

    @Test
    public void fifteenKmhIsRidingAndJustBelowIsNot() {
        assertTrue(ReglasViaje.rodandoFuera(Optional.empty(), true, kmh(15)));
        assertFalse(ReglasViaje.rodandoFuera(Optional.empty(), true, kmh(14.9)));
    }

    @Test
    public void ridingAcrossTheSamePlaceIsNotLeavingIt() {
        // A big place (an industrial estate, a campus): riding inside it at 30 km/h is not having left.
        assertFalse(ReglasViaje.rodandoFuera(Optional.of(casa), true, kmh(30)));
    }

}
