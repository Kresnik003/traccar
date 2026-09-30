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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.client.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import java.util.List;

/**
 * What Nordés says to Telegram outside Traccar's notifications: messages with
 * buttons, the answer to a button and the reading of the bot's inbox.
 *
 * Synchronous on purpose: the callers are its own thread (the inbox) or a
 * scheduled task, never the Netty thread. Two clients: Traccar's shared one,
 * which gives up after 5 s, to send; one of its own with 35 s to read, because
 * getUpdates waits up to 25 s for something to arrive.
 */
@Singleton
public class ClienteTelegram {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClienteTelegram.class);

    private static final int ESPERA_LECTURA = 35_000;

    public record Boton(String texto, String dato) {
    }

    /** What getUpdates answered: the updates, or why there are none. */
    public record Lectura(int estado, JsonNode updates, String descripcion) {
        public boolean ok() {
            return estado >= 200 && estado < 300;
        }
    }

    private final Client enviarCliente;
    private final Client leerCliente;
    private final ObjectMapper objectMapper;
    private final String base;
    private volatile String nombreBot;

    @Inject
    public ClienteTelegram(
            Config config, Client client, Provider<ClientBuilder> clientBuilderProvider, ObjectMapper objectMapper) {
        this(config, client, clientBuilderProvider.get()
                .property(ClientProperties.READ_TIMEOUT, ESPERA_LECTURA).build(), objectMapper);
    }

    ClienteTelegram(Config config, Client enviarCliente, Client leerCliente, ObjectMapper objectMapper) {
        String url = config.getString(Keys.NOTIFICATOR_TELEGRAM_BASE_URL);
        this.base = (url != null ? url : "https://api.telegram.org/bot")
                + config.getString(Keys.NOTIFICATOR_TELEGRAM_KEY) + "/";
        this.enviarCliente = enviarCliente;
        this.leerCliente = leerCliente;
        this.objectMapper = objectMapper;
    }

    public RespuestaTelegram enviar(String chatId, String texto, List<Boton> botones) {
        ObjectNode cuerpo = objectMapper.createObjectNode();
        cuerpo.put("chat_id", chatId);
        cuerpo.put("text", texto);
        cuerpo.put("disable_web_page_preview", true);
        if (!botones.isEmpty()) {
            ArrayNode fila = cuerpo.putObject("reply_markup").putArray("inline_keyboard").addArray();
            botones.forEach(boton -> fila.addObject()
                    .put("text", boton.texto()).put("callback_data", boton.dato()));
        }
        return llamar(enviarCliente, "sendMessage", cuerpo);
    }

    public void responderBoton(String callbackId, String texto) {
        ObjectNode cuerpo = objectMapper.createObjectNode();
        cuerpo.put("callback_query_id", callbackId);
        cuerpo.put("text", texto);
        llamar(enviarCliente, "answerCallbackQuery", cuerpo);
    }

    /**
     * What arrived from {@code desde} on, waiting up to {@code espera} s. A refusal
     * is reported, not turned into an empty inbox: found in review on 2026-09-29,
     * a 409 (a webhook, or a second reader with the real token) or a 401 read as
     * "no messages", forever and without a line in the log, while sign-ups and
     * "I'm fine" stopped working.
     */
    public Lectura leer(long desde, int espera) {
        ObjectNode cuerpo = objectMapper.createObjectNode();
        if (desde != 0) {
            // Without an offset Telegram answers from the oldest update nobody has
            // confirmed: BuzonTelegram explains why an idle inbox reads that way.
            cuerpo.put("offset", desde);
        }
        cuerpo.put("timeout", espera);
        cuerpo.putArray("allowed_updates").add("message").add("callback_query");
        try (Response response = leerCliente.target(base + "getUpdates").request()
                .post(Entity.json(cuerpo.toString()))) {
            JsonNode json = objectMapper.readTree(response.readEntity(String.class));
            int estado = response.getStatus();
            if (estado < 200 || estado >= 300) {
                String descripcion = json.path("description").asText(null);
                LOGGER.warn("Telegram getUpdates answered HTTP {}: {}", estado, descripcion);
                return new Lectura(estado, objectMapper.createArrayNode(), descripcion);
            }
            if (!json.path("ok").asBoolean(false) || !json.path("result").isArray()) {
                // A 2xx that is not Telegram's answer - ok:false, a proxy's page -
                // is no reading. Taken for "no messages" it kept the watchdog quiet
                // while nothing was read.
                LOGGER.warn("Telegram getUpdates answered HTTP {} without a result", estado);
                return new Lectura(0, objectMapper.createArrayNode(), "HTTP " + estado + " without a result");
            }
            return new Lectura(estado, json.path("result"), null);
        } catch (Exception e) {
            // The token is in the URL: the exception itself is never logged.
            LOGGER.warn("Telegram getUpdates failed: {}", e.getClass().getSimpleName());
            return new Lectura(0, objectMapper.createArrayNode(), e.getClass().getSimpleName());
        }
    }

    /** The bot's username, for the t.me links. Null while Telegram does not answer. */
    public String nombreDelBot() {
        if (nombreBot == null) {
            try (Response response = enviarCliente.target(base + "getMe").request().post(Entity.json("{}"))) {
                nombreBot = objectMapper.readTree(response.readEntity(String.class))
                        .path("result").path("username").asText(null);
            } catch (Exception e) {
                LOGGER.warn("Telegram getMe failed: {}", e.getClass().getSimpleName());
            }
        }
        return nombreBot;
    }

    private RespuestaTelegram llamar(Client client, String metodo, ObjectNode cuerpo) {
        try (Response response = client.target(base + metodo).request().post(Entity.json(cuerpo.toString()))) {
            String descripcion = null;
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                descripcion = objectMapper.readTree(response.readEntity(String.class))
                        .path("description").asText(null);
                LOGGER.warn("Telegram {} answered HTTP {}: {}", metodo, response.getStatus(), descripcion);
            }
            return new RespuestaTelegram(response.getStatus(), descripcion);
        } catch (Exception e) {
            LOGGER.warn("Telegram {} failed: {}", metodo, e.getClass().getSimpleName());
            return new RespuestaTelegram(0, e.getClass().getSimpleName());
        }
    }

}
