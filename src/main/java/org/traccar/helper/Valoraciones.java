/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.helper;

import org.traccar.model.Valoracion;

import java.util.regex.Pattern;

/**
 * The rules a site rating has to obey, kept out of the resource so they can be
 * tested without a database behind them.
 */
public final class Valoraciones {

    /**
     * An OpenStreetMap id as the site file serves it: node/123456, way/987,
     * relation/55. Anything else is refused rather than stored, because these
     * strings come from a request body and end up in a query.
     */
    private static final Pattern SITIO = Pattern.compile("(node|way|relation)/\\d{1,20}");

    private Valoraciones() {
    }

    /** Whether a string names a site this can store a rating for. */
    public static boolean sitioValido(String sitio) {
        return sitio != null && SITIO.matcher(sitio.trim()).matches();
    }

    /** The site id to store, or an error naming what a site id looks like. */
    public static String sitioLimpio(String sitio) {
        if (!sitioValido(sitio)) {
            throw new IllegalArgumentException(
                    "A site is an OpenStreetMap id, like node/123456; got: " + sitio);
        }
        return sitio.trim();
    }

    /** Whether a score is on the scale people are shown. */
    public static boolean estrellasValidas(int estrellas) {
        return estrellas >= Valoracion.MIN_ESTRELLAS && estrellas <= Valoracion.MAX_ESTRELLAS;
    }

    /** Whether a comment fits the column it is stored in. */
    public static boolean comentarioValido(String comentario) {
        return comentario == null || comentario.length() <= Valoracion.MAX_COMENTARIO;
    }

    /**
     * The average shown on the map, to one decimal.
     *
     * <p>Rounded here and not in the client so that every screen shows the same
     * number: a site sitting on 3.25 must not be 3.3 on the map and 3.2 in the
     * card.
     *
     * @param suma the stars added up
     * @param cuantas how many people rated it
     * @return the average, or 0 when nobody has
     */
    public static double media(int suma, int cuantas) {
        if (cuantas <= 0) {
            return 0;
        }
        return Math.round(suma * 10.0 / cuantas) / 10.0;
    }
}
