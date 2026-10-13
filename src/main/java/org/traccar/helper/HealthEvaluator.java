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

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.LongUnaryOperator;

/**
 * Turns raw readings into health results, with no I/O of its own.
 *
 * Every check reports a result, not the fact that it ran. The daily health
 * script this replaces exists because six faults once ran for weeks without a
 * single error: a pruning job that ran every week and deleted nothing, a
 * template that dropped every detail. A check that only proves it executed
 * would have passed all six.
 *
 * Thresholds match the Prometheus alert rules, so the screen and the alerts
 * never disagree about what counts as a problem.
 */
public final class HealthEvaluator {

    public static final String OK = "ok";
    public static final String WARNING = "aviso";
    public static final String FAILURE = "fallo";

    /*
     * Values are written with a decimal comma, like the rest of the interface
     * ("1,819 EUR/l"). Not through a Spanish locale: the Java runtime that
     * Traccar ships is trimmed to 25 modules and has no jdk.localedata, so
     * es-ES silently falls back to the root locale and prints "0.18" - while
     * the unit tests, run on a full JDK, pass. Formatted neutrally and the
     * point swapped by hand, which no runtime can undo.
     */
    private static String format(String pattern, double value) {
        return String.format(Locale.ROOT, pattern, value).replace('.', ',');
    }

    private HealthEvaluator() {
    }

    public record Check(String id, String estado, String valor, String detalle) {
    }

    private static final List<String> ORDER = List.of(OK, WARNING, FAILURE);

    public static String worst(List<Check> checks) {
        return checks.stream()
                .map(Check::estado)
                .max((a, b) -> ORDER.indexOf(a) - ORDER.indexOf(b))
                .orElse(OK);
    }

    /**
     * Memory in use, from /proc/meminfo.
     *
     * MemAvailable and not MemFree: Linux fills free memory with cache, so
     * MemFree reads as a near-full machine on a perfectly healthy one. It is
     * the same figure the MemoriaAlta alert uses.
     *
     * @return percentage in use, or -1 when it cannot be read
     */
    public static double memoryUsed(String meminfo) {
        if (meminfo == null) {
            return -1;
        }
        long total = -1;
        long available = -1;
        for (String line : meminfo.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2) {
                try {
                    if (parts[0].equals("MemTotal:")) {
                        total = Long.parseLong(parts[1]);
                    } else if (parts[0].equals("MemAvailable:")) {
                        available = Long.parseLong(parts[1]);
                    }
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        if (total <= 0 || available < 0 || available > total) {
            return -1;
        }
        return (1 - (double) available / total) * 100;
    }

    public static Check memory(double used) {
        if (used < 0) {
            return new Check("memoria", WARNING, "?", "No se pudo leer la memoria");
        }
        String valor = format("%.0f %%", used);
        if (used > 95) {
            return new Check("memoria", FAILURE, valor, "Traccar se queda sin sitio");
        }
        if (used > 85) {
            return new Check("memoria", WARNING, valor, "Mirar qué ha arrancado");
        }
        return new Check("memoria", OK, valor, null);
    }

    public static Check disk(long total, long usable) {
        if (total <= 0) {
            return new Check("disco", WARNING, "?", "No se pudo leer el disco");
        }
        double used = (1 - (double) usable / total) * 100;
        String valor = format("%.0f %%", used);
        if (used >= 90) {
            return new Check("disco", FAILURE, valor, "Casi lleno: la base deja de escribir");
        }
        if (used >= 80) {
            return new Check("disco", WARNING, valor, "Por encima del 80 %");
        }
        return new Check("disco", OK, valor, null);
    }

    /** Load average per core: above one, work is waiting for a processor. */
    public static Check load(double loadAverage, int cores) {
        if (loadAverage < 0 || cores <= 0) {
            return new Check("carga", OK, "?", "Este sistema no da la carga media");
        }
        double perCore = loadAverage / cores;
        String valor = format("%.2f", perCore);
        if (perCore > 2) {
            return new Check("carga", FAILURE, valor, "Hay trabajo esperando procesador");
        }
        if (perCore > 1) {
            return new Check("carga", WARNING, valor, "Procesador saturado");
        }
        return new Check("carga", OK, valor, null);
    }

    /** A machine that booted minutes ago may be one that crashed. */
    public static Check uptime(long seconds) {
        if (seconds < 0) {
            return new Check("encendido", OK, "?", null);
        }
        String valor = seconds >= 86400 ? (seconds / 86400) + " d" : (seconds / 3600) + " h";
        if (seconds < 600) {
            return new Check("encendido", WARNING, (seconds / 60) + " min", "Se ha reiniciado hace poco");
        }
        return new Check("encendido", OK, valor, null);
    }

    /**
     * Devices connected but nothing stored.
     *
     * The case every other check misses: server alive, database answering,
     * machine fine, and no position written for forty minutes. With nothing
     * connected, storing nothing is normal - the bikes are in the garage.
     */
    public static Check storing(int connected, long secondsSinceStored, long uptimeSeconds) {
        if (connected <= 0) {
            return new Check("guardado", OK, "sin equipos conectados", null);
        }
        long since = secondsSinceStored < 0 ? uptimeSeconds : secondsSinceStored;
        if (since >= 2400) {
            return new Check("guardado", FAILURE, (since / 60) + " min",
                    connected + " equipos conectados y ninguna posición guardada");
        }
        return new Check("guardado", OK, since < 60 ? "hace un momento" : (since / 60) + " min", null);
    }

    public record DeviceOldest(long deviceId, long oldestMillis) {
    }

    /**
     * The oldest position that counts for the pruning check, in milliseconds.
     *
     * A device's last known position is kept on purpose however old it is, so
     * it does not count. The cheap reading is the oldest position of each
     * device; only when that one might be the kept last position is the device
     * asked again for its oldest other position. Devices are visited oldest
     * first and the walk stops as soon as no device can beat the answer, so in
     * practice one or two extra lookups are made.
     *
     * An earlier version took the first rows by id, assuming ids grow with age.
     * They do not: a tracker that regains coverage uploads its stored records
     * with old times and new ids. The retention check on the bench caught it,
     * reporting a clean history with a 400-day-old position in the table.
     *
     * @param nonLastOldest oldest position of a device other than its last
     *                      known one, or -1 if it has none
     * @return oldest counting position, or -1 if there is none
     */
    public static long oldestCounting(List<DeviceOldest> perDevice, LongUnaryOperator nonLastOldest) {
        long best = -1;
        List<DeviceOldest> sorted = perDevice.stream()
                .sorted(Comparator.comparingLong(DeviceOldest::oldestMillis))
                .toList();
        for (DeviceOldest device : sorted) {
            if (best >= 0 && device.oldestMillis() >= best) {
                break;
            }
            long candidate = nonLastOldest.applyAsLong(device.deviceId());
            if (candidate >= 0 && (best < 0 || candidate < best)) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * A per-installation ceiling: how many devices, routes or rides a machine
     * holds before it struggles. Traccar limits per user; this is the total of
     * the box, the number that a club on a 1 GB server actually hits. Zero is no
     * ceiling. It warns at 80 % so there is time to act, and only reports a
     * failure at the ceiling itself - it never blocks, it tells.
     */
    public static Check quota(String id, long count, int ceiling) {
        if (ceiling <= 0) {
            return new Check(id, OK, count + " (sin tope)", null);
        }
        String valor = count + " de " + ceiling;
        if (count >= ceiling) {
            return new Check(id, FAILURE, valor, "En el tope de esta instalación");
        }
        if (count >= ceiling * 0.8) {
            return new Check(id, WARNING, valor, "Cerca del tope de esta instalación");
        }
        return new Check(id, OK, valor, null);
    }

    public static Check history(long oldestDays, int retentionDays) {
        if (oldestDays < 0) {
            return new Check("historico", OK, "vacío", null);
        }
        String valor = oldestDays + " d";
        if (retentionDays > 0 && oldestDays > retentionDays + 5) {
            return new Check("historico", WARNING, valor,
                    "Más antiguo que la retención de " + retentionDays + " d: la poda no está borrando");
        }
        return new Check("historico", OK, valor, null);
    }

    /**
     * Administrators without a second factor.
     *
     * The administrator of a club sees every member. The second factor is
     * already in Traccar; what was missing is being told that it is switched
     * off, or that an administrator never set it up. Nothing is forced: forcing
     * it would lock out an administrator who has no key yet, on a server with
     * nobody else to let them back in.
     */
    public static Check secondFactor(boolean enabled, List<String> administratorsWithout) {
        if (!enabled) {
            return new Check("segundofactor", WARNING, "apagado",
                    "El segundo factor está desactivado en el servidor");
        }
        if (!administratorsWithout.isEmpty()) {
            return new Check("segundofactor", WARNING, administratorsWithout.size() + " sin él",
                    "Administradores sin segundo factor: " + String.join(", ", administratorsWithout));
        }
        return new Check("segundofactor", OK, "todos los administradores", null);
    }

    public static Check silentDevices(List<String> names, int hours) {
        if (names.isEmpty()) {
            return new Check("equipos", OK, "todos reportan", null);
        }
        return new Check("equipos", WARNING, names.size() + " sin reportar",
                "Más de " + hours + " h: " + String.join(", ", names));
    }

}
