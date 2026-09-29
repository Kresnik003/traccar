package org.traccar.seguridad;

import org.junit.jupiter.api.Test;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Position;

import java.util.Date;
import java.util.List;

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

    @Test
    public void movingIsSpeedOrDistanceWithAFix() {
        assertTrue(ReglasGolpe.seHaMovido(43, -8, List.of(pos(0, true, 15, 43, -8, true))));
        assertTrue(ReglasGolpe.seHaMovido(43, -8, List.of(pos(0, false, 0, 43.001, -8, true))));
    }

    @Test
    public void aBikeLyingThereHasNotMoved() {
        assertFalse(ReglasGolpe.seHaMovido(43, -8, List.of(pos(0, false, 3, 43.0002, -8, true))));
        assertFalse(ReglasGolpe.seHaMovido(43, -8, List.of()));
    }

    @Test
    public void withoutAFixNothingMoved() {
        assertFalse(ReglasGolpe.seHaMovido(43, -8, List.of(pos(0, false, 40, 43.01, -8, false))));
    }

    @Test
    public void aLateCrashDoesNotOpenAGuard() {
        assertTrue(ReglasGolpe.reciente(new Date(T - 60_000), T));
        assertFalse(ReglasGolpe.reciente(new Date(T - 600_000), T));
    }

}
