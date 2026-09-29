package org.traccar.seguridad;

import org.junit.jupiter.api.Test;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Position;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a crash opens a guard and when the bike has moved again.
 */
public class ReglasGolpeTest {

    private static final long T = 1_790_000_000_000L;

    private static Position pos(long msAntes, boolean contacto, double kph, double lat, double lon, boolean fix) {
        Position position = new Position();
        position.setDeviceTime(new Date(T - msAntes));
        // Without a fix the FTC880 frame carries the fix time of an hour ago.
        position.setFixTime(fix ? new Date(T - msAntes) : new Date(T - 3_600_000));
        position.set(Position.KEY_IGNITION, contacto);
        position.setSpeed(UnitsConverter.knotsFromKph(kph));
        position.setLatitude(lat);
        position.setLongitude(lon);
        position.setValid(fix);
        return position;
    }

    @Test
    public void ridingIsIgnitionOrSpeed() {
        assertTrue(ReglasGolpe.ibaEnMarcha(List.of(pos(30_000, true, 0, 43, -8, true)), new Date(T)));
        assertTrue(ReglasGolpe.ibaEnMarcha(List.of(pos(30_000, false, 40, 43, -8, true)), new Date(T)));
    }

    @Test
    public void parkedIsNotRiding() {
        assertFalse(ReglasGolpe.ibaEnMarcha(List.of(pos(30_000, false, 0, 43, -8, true)), new Date(T)));
        assertFalse(ReglasGolpe.ibaEnMarcha(List.of(pos(30_000, false, 4, 43, -8, true)), new Date(T)));
        assertFalse(ReglasGolpe.ibaEnMarcha(List.of(), new Date(T)));
    }

    @Test
    public void ridingMoreThanTwoMinutesBeforeDoesNotCount() {
        assertFalse(ReglasGolpe.ibaEnMarcha(List.of(pos(180_000, true, 60, 43, -8, true)), new Date(T)));
    }

    @Test
    public void theWindowUsesTheDeviceTime() {
        assertTrue(ReglasGolpe.ibaEnMarcha(List.of(pos(20_000, true, 0, 43, -8, false)), new Date(T)));
    }

    private static Position despues(long msDespues, boolean contacto, double kph, boolean fix) {
        Position position = pos(-msDespues, contacto, kph, 43.0, -8.0, fix);
        return position;
    }

    @Test
    public void ridingAwayCancels() {
        assertTrue(ReglasGolpe.seHaMovido(new Date(T), List.of(
                despues(40_000, true, 30, true), despues(60_000, true, 35, true))));
    }

    @Test
    public void theSlideRightAfterTheCrashIsNotRiding() {
        // Found in review (29/9): the bike slides 1-3 s after a hard crash, still fast.
        assertFalse(ReglasGolpe.seHaMovido(new Date(T), List.of(
                despues(1_000, true, 40, true), despues(3_000, true, 20, true), despues(20_000, true, 15, true))));
    }

    @Test
    public void oneRidingPositionIsNotEnough() {
        assertFalse(ReglasGolpe.seHaMovido(new Date(T), List.of(despues(40_000, true, 30, true))));
        // Two, but too close together to call it riding on.
        assertFalse(ReglasGolpe.seHaMovido(new Date(T), List.of(
                despues(40_000, true, 30, true), despues(45_000, true, 30, true))));
    }

    @Test
    public void withoutIgnitionOrWithoutAFixItIsNotRiding() {
        assertFalse(ReglasGolpe.seHaMovido(new Date(T), List.of(
                despues(40_000, false, 30, true), despues(60_000, false, 30, true))));
        assertFalse(ReglasGolpe.seHaMovido(new Date(T), List.of(
                despues(40_000, true, 30, false), despues(60_000, true, 30, false))));
    }

    @Test
    public void crashTraceRecordsDoNotCount() {
        Position a = despues(40_000, true, 30, true);
        Position b = despues(60_000, true, 30, true);
        a.set("io257", "traza");
        b.set("io257", "traza");
        assertFalse(ReglasGolpe.seHaMovido(new Date(T), List.of(a, b)));
    }

    @Test
    public void aCrashFrameWithoutAFixDoesNotMakeTheBikeLookMoved() {
        // Found in review: the crash frame without a fix carries 0,0 or an old place;
        // the bike lying where it really is was taken for "far away, so it moved".
        Position lejos = despues(40_000, false, 0, true);
        lejos.setLatitude(43.5);
        Position quieta = despues(60_000, false, 0, true);
        quieta.setLatitude(43.5);
        assertFalse(ReglasGolpe.seHaMovido(new Date(T), List.of(lejos, quieta)));
    }

    @Test
    public void theBikeIsWhereItsLastFixSays() {
        Position golpe = pos(0, true, 0, 0, 0, false);
        Position primera = despues(10_000, false, 0, true);
        primera.setLatitude(43.1);
        Position ultima = despues(90_000, false, 0, true);
        ultima.setLatitude(43.2);
        Position sinFijacion = despues(100_000, false, 0, false);
        assertEquals(43.2, ReglasGolpe.dondeEsta(golpe, List.of(primera, ultima, sinFijacion)).get().getLatitude());
        assertTrue(ReglasGolpe.dondeEsta(golpe, List.of()).isEmpty());
        Position golpeConFijacion = pos(0, true, 0, 43.3, -8, true);
        assertEquals(43.3, ReglasGolpe.dondeEsta(golpeConFijacion, List.of()).get().getLatitude());
    }

    @Test
    public void theTwoMinutesStartWhenTheFrameArrivesIfItArrivesLate() {
        assertEquals(T + ReglasGolpe.ESPERA, ReglasGolpe.limite(new Date(T), T).getTime());
        // Arrived 110 s late: the rider still gets two minutes, not ten seconds.
        assertEquals(T + 110_000 + ReglasGolpe.ESPERA, ReglasGolpe.limite(new Date(T), T + 110_000).getTime());
    }

    @Test
    public void aLateCrashDoesNotOpenAGuard() {
        assertTrue(ReglasGolpe.reciente(new Date(T - 60_000), T));
        assertFalse(ReglasGolpe.reciente(new Date(T - 600_000), T));
    }

}
