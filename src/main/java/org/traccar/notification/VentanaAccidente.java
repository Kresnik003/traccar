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
package org.traccar.notification;

import org.traccar.model.Event;
import org.traccar.model.Position;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One accident, one notification (improvement 9).
 *
 * An FT device reports a crash in pieces: the average and maximum vectors
 * (AVL 1429/1432) first, the crash trace (AVL 257) from 15 seconds to a few
 * minutes later. Each piece raised its own accident alarm, and two rules
 * covering accidents made it twice as many. The grouping window of 60 s caught
 * some of them and then sent a «+N» summary: for a crash, three messages in a
 * row read as three crashes.
 *
 * The first accident of a device goes out at once, as always. Any other
 * accident of that device, to that user and channel, within the window is
 * absorbed - whatever rule it came through - and no summary follows. The
 * events themselves are still stored for the reports and the inbox.
 */
public final class VentanaAccidente {

    static final long VENTANA = 10 * 60 * 1000L;

    private final Map<String, Long> enviados = new ConcurrentHashMap<>();

    public static boolean esAccidente(Event event) {
        if (!Event.TYPE_ALARM.equals(event.getType())) {
            return false;
        }
        String alarmas = event.getString(Position.KEY_ALARM);
        return alarmas != null && Arrays.stream(alarmas.split(",")).map(String::trim).anyMatch("accident"::equals);
    }

    /**
     * Whether this accident notification should go out now. The first of a
     * crash does; the rest of the same crash, within the window, do not.
     */
    boolean enviar(long userId, String canal, long deviceId, long ahora) {
        String clave = userId + ":" + canal + ":" + deviceId;
        Long anterior = enviados.get(clave);
        if (anterior != null && ahora - anterior < VENTANA) {
            return false;
        }
        enviados.put(clave, ahora);
        // Old entries are useless: keep the map from growing forever.
        if (enviados.size() > 1000) {
            enviados.values().removeIf(t -> ahora - t >= VENTANA);
        }
        return true;
    }

}
