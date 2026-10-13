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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * El estado de un equipo en FOTA WEB, el portal de Teltonika (mejora 6): qué
 * firmware y qué configuración lleva, cuándo se asomó y qué tareas tiene en
 * cola. Hasta ahora sólo se veía entrando en el portal, y así una tarea pudo
 * pasarse semanas en cola sin que nadie lo supiera.
 *
 * SÓLO LEE. El token de la API da poder para reprogramar la flota entera; aquí
 * no se hace nada más que GET, y el token no sale del servidor.
 *
 * Una consulta por equipo cada {@link #CADUCIDAD_MS} como mucho: el portal no
 * cambia tan deprisa y no es nuestro.
 *
 * De lo que devuelve Teltonika se quedan fuera el ICCID, el IMSI y el número de
 * teléfono de la tarjeta: nadie los necesita en la ficha de la moto.
 */
@Singleton
public class ClienteFota {

    public static final long CADUCIDAD_MS = 10 * 60 * 1000L;
    private static final int TAREAS = 5;
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Entrada(long hora, Map<String, Object> datos) {
    }

    private final String url;
    private final String token;
    private final HttpClient client;
    private final Map<String, Entrada> cache = new ConcurrentHashMap<>();

    @Inject
    public ClienteFota(Config config) {
        this.url = config.getString(Keys.FOTA_URL).replaceAll("/+$", "");
        this.token = config.getString(Keys.FOTA_TOKEN);
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean activo() {
        return token != null && !token.isBlank();
    }

    /** El estado del equipo con ese IMEI, o {@code null} si no está en la cuenta de FOTA WEB. */
    public Map<String, Object> estado(String imei) throws Exception {
        if (imei == null || !imei.matches("\\d{15}")) {
            return null;
        }
        long ahora = System.currentTimeMillis();
        Entrada entrada = cache.get(imei);
        if (entrada != null && ahora - entrada.hora() < CADUCIDAD_MS) {
            return entrada.datos();
        }
        HttpResponse<String> equipo = get("/devices/" + imei);
        Map<String, Object> datos = null;
        if (equipo.statusCode() == 200) {
            String consulta = "/tasks?device_imei=" + URLEncoder.encode(imei, StandardCharsets.UTF_8);
            HttpResponse<String> tareas = get(consulta);
            if (tareas.statusCode() != 200) {
                throw new IllegalStateException("FOTA WEB contestó " + tareas.statusCode() + " a las tareas");
            }
            datos = resumen(JSON.readTree(equipo.body()), JSON.readTree(tareas.body()));
        } else if (equipo.statusCode() != 404) {
            throw new IllegalStateException("FOTA WEB contestó " + equipo.statusCode());
        }
        cache.put(imei, new Entrada(ahora, datos));
        return datos;
    }

    private HttpResponse<String> get(String ruta) throws Exception {
        HttpRequest peticion = HttpRequest.newBuilder(URI.create(url + ruta))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .header("User-Agent", "Nordes")
                .GET()
                .build();
        return client.send(peticion, HttpResponse.BodyHandlers.ofString());
    }

    private static String texto(JsonNode nodo, String campo) {
        JsonNode valor = nodo.get(campo);
        return valor == null || valor.isNull() ? null : valor.asText();
    }

    private static Map<String, Object> tarea(JsonNode t) {
        Map<String, Object> tarea = new LinkedHashMap<>();
        tarea.put("tipo", texto(t, "type"));
        tarea.put("estado", texto(t, "status_name"));
        tarea.put("creada", texto(t, "created_at"));
        tarea.put("cambio", texto(t, "status_changed_at"));
        JsonNode fichero = t.get("file");
        tarea.put("fichero", fichero != null && fichero.isObject() ? texto(fichero, "name") : null);
        tarea.put("mensaje", texto(t, "message"));
        return tarea;
    }

    /** Lo que se enseña, a partir de las respuestas de /devices/{imei} y /tasks. Puro, para probarlo. */
    public static Map<String, Object> resumen(JsonNode equipo, JsonNode tareas) {
        JsonNode e = equipo.has("data") && equipo.get("data").isObject() ? equipo.get("data") : equipo;
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("modelo", texto(e, "model"));
        datos.put("firmware", texto(e, "current_firmware"));
        datos.put("configuracion", texto(e, "current_configuration"));
        datos.put("actividad", texto(e, "activity_status"));
        datos.put("visto", texto(e, "seen_at"));
        datos.put("cola", texto(e, "task_queue"));
        JsonNode siguiente = e.get("next_task");
        datos.put("siguiente", siguiente != null && siguiente.isObject() ? tarea(siguiente) : null);
        List<Map<String, Object>> recientes = new ArrayList<>();
        JsonNode lista = tareas.get("data");
        if (lista != null && lista.isArray()) {
            List<JsonNode> ordenadas = new ArrayList<>();
            lista.forEach(ordenadas::add);
            // Las más nuevas primero: la fecha va como «aaaa-mm-dd hh:mm:ss», que
            // se ordena bien como texto.
            ordenadas.sort((a, b) -> String.valueOf(texto(b, "created_at"))
                    .compareTo(String.valueOf(texto(a, "created_at"))));
            for (int i = 0; i < Math.min(TAREAS, ordenadas.size()); i++) {
                recientes.add(tarea(ordenadas.get(i)));
            }
        }
        datos.put("tareas", recientes);
        return datos;
    }

}
