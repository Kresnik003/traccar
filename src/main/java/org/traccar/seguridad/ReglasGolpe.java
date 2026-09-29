/*
 * Copyright 2026 Kresnik003 (juanilloferrol@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.seguridad;

import org.traccar.helper.UnitsConverter;
import org.traccar.helper.model.Fijacion;
import org.traccar.model.Position;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * The decisions of the crash guard (improvement 114), without the database.
 *
 * A guard opens only for a bike that was being ridden: a parked bike knocked
 * over is the classic false alarm, and it still warns the owner as before. And
 * it is cancelled as soon as the bike moves again, which is the best "I'm fine"
 * there is.
 */
public final class ReglasGolpe {

    /** How long the rider has to answer before the contacts are warned. */
    public static final long ESPERA = 120_000;

    /** A crash that reaches the server later than this is history, not an emergency. */
    public static final long ANTIGUEDAD_MAXIMA = 300_000;

    /**
     * What happens in the first seconds after an impact is the crash itself: the
     * bike slides, the FTC880 sends its crash trace. None of it is "riding on".
     */
    static final long ASENTAMIENTO = 30_000;

    /** Riding on is two riding positions this far apart, not one fast sample. */
    static final long SEPARACION = 15_000;

    /** The AVL element of the FTC880 crash trace records. */
    static final String TRAZA = "io257";

    private static final double MARCHA_KNOTS = UnitsConverter.knotsFromKph(5);
    private static final double MOVIMIENTO_KNOTS = UnitsConverter.knotsFromKph(10);

    private ReglasGolpe() {
    }

    /**
     * Whether the bike was being ridden in the two minutes before the crash. The
     * window uses the device time: an FTC880 frame without a fix carries the fix
     * time of the last good position, which can be an hour old.
     */
    public static boolean ibaEnMarcha(List<Position> previas, Date golpe) {
        long desde = golpe.getTime() - ESPERA;
        return previas.stream()
                .filter(p -> p.getDeviceTime() != null)
                .filter(p -> p.getDeviceTime().getTime() >= desde && p.getDeviceTime().getTime() <= golpe.getTime())
                .anyMatch(p -> p.getBoolean(Position.KEY_IGNITION) || p.getSpeed() > MARCHA_KNOTS);
    }

    /**
     * Whether the rider rode on after the crash: two positions with a fix, the
     * ignition on and above 10 km/h, at least 15 s apart, once the crash itself
     * is over (30 s) and leaving out the crash trace records.
     *
     * Found in review on 2026-09-29: the first version cancelled on ONE later
     * position faster than 10 km/h or 50 m away from the crash frame. The slide
     * of a hard crash is fast for a second or two, and a crash frame without a
     * fix carries 0,0 or an old place, so the bike lying where it really lies
     * looked "far away": the guard cancelled itself in exactly the crash where
     * nobody can press the button. Distance no longer counts; riding does.
     */
    public static boolean seHaMovido(Date golpe, List<Position> despues) {
        long desde = golpe.getTime() + ASENTAMIENTO;
        List<Long> enMarcha = despues.stream()
                .filter(p -> p.getDeviceTime() != null && p.getDeviceTime().getTime() >= desde)
                .filter(p -> !p.hasAttribute(TRAZA))
                .filter(Fijacion::tiene)
                .filter(p -> p.getBoolean(Position.KEY_IGNITION) && p.getSpeed() > MOVIMIENTO_KNOTS)
                .map(p -> p.getDeviceTime().getTime())
                .sorted()
                .toList();
        return !enMarcha.isEmpty() && enMarcha.get(enMarcha.size() - 1) - enMarcha.get(0) >= SEPARACION;
    }

    /**
     * Where the bike is for the message: its last position with a fix after the
     * crash, or the crash frame itself if that one had a fix. Empty if neither:
     * the message then says so instead of sending people to 0,0.
     */
    public static Optional<Position> dondeEsta(Position golpe, List<Position> despues) {
        Optional<Position> ultima = despues.stream()
                .filter(Fijacion::tiene)
                .filter(p -> p.getDeviceTime() != null)
                .max(Comparator.comparing(Position::getDeviceTime));
        if (ultima.isPresent()) {
            return ultima;
        }
        return golpe != null && Fijacion.tiene(golpe) ? Optional.of(golpe) : Optional.empty();
    }

    /**
     * When the contacts are warned. From the crash, or from when its frame
     * arrived if it arrived late: a frame that took 110 s to get here must still
     * leave the rider two minutes, as the message promises, not ten seconds.
     */
    public static Date limite(Date golpe, long llegada) {
        return new Date(Math.max(golpe.getTime(), llegada) + ESPERA);
    }

    public static boolean reciente(Date golpe, long ahora) {
        return ahora - golpe.getTime() <= ANTIGUEDAD_MAXIMA;
    }

}
