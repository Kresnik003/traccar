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
import org.traccar.model.Position;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * When a trip starts and ends (improvement 115), without the database.
 *
 * A trip starts when the bike leaves one of the rider's places with the
 * ignition on: warming it up in the garage does not count. It ends at one of
 * those places after two minutes still and off, so a stop to refuel does not
 * count either; anywhere else the rider closes it with a button.
 */
public final class ReglasViaje {

    public static final long QUIETA = 120_000;

    public static final long DURACION_MAXIMA = 12 * 3_600_000L;

    private static final double QUIETA_KNOTS = UnitsConverter.knotsFromKph(3);

    private static final double SALIDA_KNOTS = UnitsConverter.knotsFromKph(15);

    private ReglasViaje() {
    }

    public static Optional<Sitio> dentro(List<Sitio> sitios, double latitud, double longitud) {
        return sitios.stream().filter(sitio -> sitio.geometria().containsPoint(latitud, longitud)).findFirst();
    }

    /**
     * A departure: the place the bike was last seen in (with a fix) is behind it,
     * and it is riding. Found in review on 2026-09-29: "outside with the ignition
     * on" was enough, and a fix drifting out of the garage while the engine warmed
     * up told the contacts that the rider had left. Riding is what tells the two
     * apart; a slow way out stays armed until the bike picks up speed.
     */
    public static boolean esSalida(Optional<Sitio> armado, Optional<Sitio> ahora, boolean contacto, double nudos) {
        return armado.isPresent() && ahora.isEmpty() && contacto && nudos >= SALIDA_KNOTS;
    }

    public static boolean esLlegada(Optional<Sitio> donde, boolean contacto, long quietaDesde, long ahora) {
        return donde.isPresent() && !contacto && ahora - quietaDesde >= QUIETA;
    }

    public static boolean caducado(Date inicio, long ahora) {
        return ahora - inicio.getTime() >= DURACION_MAXIMA;
    }

    /**
     * Since when the bike has been still and off: the device time of the first
     * still position after the last one that moved. Null if the newest position
     * is not still. {@code recientes} goes newest first.
     */
    public static Long quietaDesde(List<Position> recientes) {
        Long desde = null;
        for (Position position : recientes) {
            if (position.getBoolean(Position.KEY_IGNITION) || position.getSpeed() > QUIETA_KNOTS) {
                break;
            }
            desde = position.getDeviceTime().getTime();
        }
        return desde;
    }

}
