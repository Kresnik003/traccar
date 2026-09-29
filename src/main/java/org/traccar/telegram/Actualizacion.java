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
package org.traccar.telegram;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the bot's inbox understands of a Telegram update: a sign-up
 * ("/start code"), a "/baja" and a button press. Everything else is ignored.
 */
public sealed interface Actualizacion {

    long updateId();

    record Alta(long updateId, String chatId, String codigo, String nombre) implements Actualizacion {
    }

    record Baja(long updateId, String chatId) implements Actualizacion {
    }

    record Pulsacion(long updateId, String callbackId, String chatId, String dato) implements Actualizacion {
    }

    record Otra(long updateId) implements Actualizacion {
    }

    Pattern START = Pattern.compile("^/start ([A-Za-z0-9_-]{1,64})$");

    static Actualizacion leer(JsonNode update) {
        long id = update.path("update_id").asLong();
        JsonNode boton = update.path("callback_query");
        if (boton.hasNonNull("data")) {
            return new Pulsacion(id, boton.path("id").asText(),
                    boton.path("message").path("chat").path("id").asText(), boton.path("data").asText());
        }
        JsonNode mensaje = update.path("message");
        String texto = mensaje.path("text").asText("").strip();
        String chat = mensaje.path("chat").path("id").asText(null);
        if (chat == null) {
            return new Otra(id);
        }
        Matcher start = START.matcher(texto);
        if (start.matches()) {
            return new Alta(id, chat, start.group(1), mensaje.path("from").path("first_name").asText(null));
        }
        if (texto.equals("/baja")) {
            return new Baja(id, chat);
        }
        return new Otra(id);
    }

}
