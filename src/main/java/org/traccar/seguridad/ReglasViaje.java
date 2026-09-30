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
 * A trip starts when the bike rides out of one of the rider's places:
 * warming it up in the garage does not count, and neither does one fast sample
 * of a manoeuvre at the edge of the place. It ends at one of those places after
 * two minutes still and off, so a stop to refuel does not count either;
 * anywhere else the rider closes it with a button.
 */
public final class ReglasViaje {

    public static final long QUIETA = 120_000;

    public static final long DURACION_MAXIMA = 12 * 3_600_000L;

    private static final double QUIETA_KNOTS = UnitsConverter.knotsFromKph(3);

    private static final double SALIDA_KNOTS = UnitsConverter.knotsFromKph(15);

    /** How far apart, by fix time, the two riding samples of a departure are at least. */
    public static final long SEPARACION_SALIDA = 15_000;

    /** How long the first riding sample waits for the second before it is a new first one. */
    public static final long VENTANA_SALIDA = 5 * 60_000L;

    /**
     * How far from where it was last seen inside a place the bike must be, with
     * the ignition off, to be taken away: towed, or in a van. The GPS of the
     * real Ducati drifts up to ~80 m in its parking; this is well beyond that.
     */
    public static final double LLEVADA_METROS = 300;

    private ReglasViaje() {
    }

    public static Optional<Sitio> dentro(List<Sitio> sitios, double latitud, double longitud) {
        return sitios.stream().filter(sitio -> sitio.geometria().containsPoint(latitud, longitud)).findFirst();
    }

    /**
     * A sample that may be part of a departure: outside every place, with the
     * ignition on, riding. Found in review on 2026-09-29: "outside with the
     * ignition on" was enough, and a fix drifting out of the garage while the
     * engine warmed up told the contacts that the rider had left.
     */
    public static boolean rodandoFuera(Optional<Sitio> ahora, boolean contacto, double nudos) {
        return ahora.isEmpty() && contacto && nudos >= SALIDA_KNOTS;
    }

    /**
     * Whether a riding sample at {@code ahora} confirms the first one, at
     * {@code primera} (fix times), with nothing but riding outside in between:
     * at least 15 s later and at most five minutes. Found in the second review,
     * with the real Ducati: one sample at 16 km/h, 74 m from where it sleeps, was
     * a manoeuvre - 34 s later it was parked again, off - and it fired a
     * departure. Riding out is riding for a while.
     */
    public static boolean confirmaSalida(long primera, long ahora) {
        long pasado = ahora - primera;
        return pasado >= SEPARACION_SALIDA && pasado <= VENTANA_SALIDA;
    }

    /**
     * Whether the bike is being taken away rather than ridden: with the ignition
     * off, far from where it was last seen inside a place. It disarms the
     * departure, or the day it is ridden again from the workshop the contacts
     * would read that the rider has left home.
     */
    public static boolean seLaLlevan(boolean contacto, double metros) {
        return !contacto && metros > LLEVADA_METROS;
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
