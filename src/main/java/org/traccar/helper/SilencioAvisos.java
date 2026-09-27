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

import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Set;

/**
 * Silencing the notifications of one device (improvement 13).
 *
 * A notification marked "always" applies to every device and overrides the
 * links by group, so there was no way to quiet one bike - the one at the
 * mechanic's, the one lent for a weekend. Now a device attribute
 * {@code avisosSilenciados} holds either "siempre" or the ISO instant the
 * silence ends.
 *
 * WHAT IS NEVER SILENCED: an accident, an SOS, a fall and a power cut. These
 * are the ones the profiles already keep in the parent group so that no
 * profile can quiet them; a silence set for the workshop must not be the reason
 * a crash goes unannounced.
 *
 * A silenced notification is still written to the inbox: it is not sent, but
 * it is not lost either.
 */
public final class SilencioAvisos {

    public static final String ATRIBUTO = "avisosSilenciados";

    public static final String SIEMPRE = "siempre";

    static final Set<String> NUNCA = Set.of("accident", "sos", "fallDown", "powerCut");

    private SilencioAvisos() {
    }

    /** Whether a silence with this value is in force at {@code ahora}. */
    public static boolean vigente(Object valor, long ahora) {
        if (valor == null) {
            return false;
        }
        String texto = valor.toString().trim();
        if (texto.isEmpty()) {
            return false;
        }
        if (SIEMPRE.equals(texto)) {
            return true;
        }
        try {
            return ahora < Instant.parse(texto).toEpochMilli();
        } catch (DateTimeParseException e) {
            // Something that is neither "siempre" nor a date is not a silence: a
            // typo must not quiet a bike forever.
            return false;
        }
    }

    /** An event that goes out whatever the silence says. */
    public static boolean critico(Event event) {
        if (!Event.TYPE_ALARM.equals(event.getType())) {
            return false;
        }
        String alarmas = event.getString(Position.KEY_ALARM);
        return alarmas != null && Arrays.stream(alarmas.split(",")).map(String::trim).anyMatch(NUNCA::contains);
    }

    /** Whether this event of this device should not be sent now. */
    public static boolean callar(Device device, Event event, long ahora) {
        return device != null && !critico(event) && vigente(device.getAttributes().get(ATRIBUTO), ahora);
    }

}
