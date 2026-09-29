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

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.telegram.BuzonTelegram;

import java.util.OptionalInt;
import java.util.function.LongSupplier;

/**
 * The safety chain for Prometheus (2026-09-29). The tests prove the code; this
 * proves it RUNS. An inbox that stopped reading means "I'm fine" does nothing
 * and the family is warned about somebody who is fine; a countdown that
 * stopped means a crash nobody is told about. Neither makes a sound on its own.
 */
@Singleton
public class VigiaSeguridad {

    private final GuardianGolpes guardian;
    private final BuzonTelegram buzon;
    private final LongSupplier reloj;
    private final long arranque;

    @Inject
    public VigiaSeguridad(GuardianGolpes guardian, BuzonTelegram buzon) {
        this(guardian, buzon, System::currentTimeMillis);
    }

    VigiaSeguridad(GuardianGolpes guardian, BuzonTelegram buzon, LongSupplier reloj) {
        this.guardian = guardian;
        this.buzon = buzon;
        this.reloj = reloj;
        this.arranque = reloj.getAsLong();
    }

    public String render() {
        long ahora = reloj.getAsLong();
        StringBuilder out = new StringBuilder();
        metrica(out, "nordes_seguridad_activa", "gauge",
                "Whether the safety block runs: crash guard, trips and the Telegram inbox.",
                guardian.activo() ? 1 : 0);
        if (guardian.activo()) {
            metrica(out, "nordes_guardias_revision_segundos", "gauge",
                    "Seconds since the crash countdown last read its guards; it looks every 10 s.",
                    segundos(ahora, guardian.ultimaRevision()));
            // Left out, not zero, when the database does not answer: a zero would say all is well.
            OptionalInt vencidas = guardian.vencidas(ahora);
            if (vencidas.isPresent()) {
                metrica(out, "nordes_guardias_vencidas", "gauge",
                        "Crash guards a minute past their limit with nobody warned.", vencidas.getAsInt());
            }
            metrica(out, "nordes_avisos_perdidos_total", "counter",
                    "Crash warnings given up after every retry.", guardian.avisosPerdidos());
        }
        if (buzon.activo()) {
            metrica(out, "nordes_buzon_sin_leer_segundos", "gauge",
                    "Seconds since the Telegram inbox last read the bot's updates.",
                    segundos(ahora, buzon.ultimaLectura()));
        }
        return out.toString();
    }

    /** Counted from the start while it has not happened yet: zero means never, not 1970. */
    private long segundos(long ahora, long ultima) {
        return Math.max(0, ahora - Math.max(ultima, arranque)) / 1000;
    }

    private static void metrica(StringBuilder out, String nombre, String tipo, String ayuda, long valor) {
        out.append("# HELP ").append(nombre).append(' ').append(ayuda).append('\n');
        out.append("# TYPE ").append(nombre).append(' ').append(tipo).append('\n');
        out.append(nombre).append(' ').append(valor).append('\n');
    }

}
