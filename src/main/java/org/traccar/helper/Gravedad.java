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
package org.traccar.helper;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * The severity of an alarm, stored with the event (improvement 11).
 *
 * It used to be worked out only in the web, so nothing else - a report, a
 * filter, a query - could use it. Now every alarm event carries
 * {@code gravedad}. THE LISTS ARE THE SAME AS {@code src/fleet/util.js} IN THE
 * WEB, and a test there compares them with this file: two tables that drift
 * apart would paint one alarm red and report it as minor.
 *
 * Traccar sends a comma separated list when a frame carries several alarms;
 * the worst one wins ({@code tow,powerCut} is a theft, not a «media»).
 */
public final class Gravedad {

    public static final String ATRIBUTO = "gravedad";

    // Someone is taking or tampering with the vehicle.
    static final Set<String> CRITICA = Set.of(
            "sos", "tow", "tampering", "removing", "jamming", "accident",
            "powerCut", "powercut", "gpsAntennaCut", "unaccompaniedMotion");

    // Something is happening to it and it is worth a look.
    static final Set<String> ALTA = Set.of(
            "vibration", "movement", "shock", "fallDown", "overspeed", "lowPower", "lowBattery");

    private static final List<String> ORDEN = List.of("critica", "alta", "media");

    private Gravedad() {
    }

    private static String deUna(String alarma) {
        if (CRITICA.contains(alarma)) {
            return "critica";
        }
        return ALTA.contains(alarma) ? "alta" : "media";
    }

    /** The worst severity in the list, or null if there is no alarm. */
    public static String de(String alarmas) {
        if (alarmas == null) {
            return null;
        }
        List<String> gravedades = Arrays.stream(alarmas.split(","))
                .map(String::trim).filter(a -> !a.isEmpty()).map(Gravedad::deUna).toList();
        return ORDEN.stream().filter(gravedades::contains).findFirst().orElse(null);
    }

}
