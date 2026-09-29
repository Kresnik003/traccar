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

import org.traccar.helper.DistanceCalculator;
import org.traccar.helper.UnitsConverter;
import org.traccar.helper.model.Fijacion;
import org.traccar.model.Position;

import java.util.Date;
import java.util.List;

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

    private static final double MARCHA_KNOTS = UnitsConverter.knotsFromKph(5);
    private static final double MOVIMIENTO_KNOTS = UnitsConverter.knotsFromKph(10);
    private static final double MOVIMIENTO_METROS = 50;

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

    /** Whether any later position, with a fix, shows the bike riding or away from the crash. */
    public static boolean seHaMovido(double latitud, double longitud, List<Position> despues) {
        return despues.stream()
                .filter(Fijacion::tiene)
                .anyMatch(p -> p.getSpeed() > MOVIMIENTO_KNOTS
                        || DistanceCalculator.distance(latitud, longitud, p.getLatitude(), p.getLongitude())
                        > MOVIMIENTO_METROS);
    }

    public static boolean reciente(Date golpe, long ahora) {
        return ahora - golpe.getTime() <= ANTIGUEDAD_MAXIMA;
    }

}
