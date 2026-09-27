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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Una línea de registro en JSON (mejora 37), para buscar con {@code jq} en vez
 * de leer texto a ojo. Además de hora, nivel, origen y mensaje, saca a campos
 * lo que ya viene dentro del texto:
 *
 * <ul>
 * <li>la sesión de red de Traccar, {@code [T1a2b3c4d: teltonika < 1.2.3.4]}: une la
 * conexión, cada trama y la posición decodificada;</li>
 * <li>el equipo de {@code id: 352093…,};</li>
 * <li>cualquier {@code clave=valor} de {@link #CLAVES}, que es como los mensajes de
 * eventos y avisos dicen de qué evento, equipo, usuario y canal hablan.</li>
 * </ul>
 *
 * Con eso, por qué no llegó una alarma es
 * {@code jq 'select(.evento=="123")'}: sale su creación, a quién se avisó, por
 * qué canal, y si se calló o falló.
 */
public final class LogJson {

    public static final String[] CLAVES = {"evento", "equipo", "usuario", "canal", "tipo", "alarma", "motivo"};

    private static final Pattern SESION = Pattern.compile(
            "^\\[(T[0-9a-f]+)(?:: (\\w+) ([<>]) ([^\\]]+))?\\]");
    private static final Pattern EQUIPO = Pattern.compile("\\bid: ([^,\\s]+),");
    private static final Pattern CLAVE_VALOR = Pattern.compile(
            "\\b(" + String.join("|", CLAVES) + ")=([^\\s,;]+)");

    private LogJson() {
    }

    public static String linea(long millis, String nivel, String origen, String mensaje, String error) {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("ts", Instant.ofEpochMilli(millis).toString());
        campos.put("nivel", nivel);
        campos.put("origen", origen);
        campos.put("msg", mensaje);
        if (mensaje != null) {
            Matcher sesion = SESION.matcher(mensaje);
            if (sesion.find()) {
                campos.put("sesion", sesion.group(1));
                if (sesion.group(2) != null) {
                    campos.put("protocolo", sesion.group(2));
                    campos.put("sentido", "<".equals(sesion.group(3)) ? "entra" : "sale");
                    campos.put("remoto", sesion.group(4));
                }
            }
            Matcher equipo = EQUIPO.matcher(mensaje);
            if (equipo.find()) {
                campos.put("equipo", equipo.group(1));
            }
            Matcher claveValor = CLAVE_VALOR.matcher(mensaje);
            while (claveValor.find()) {
                campos.putIfAbsent(claveValor.group(1), claveValor.group(2));
            }
        }
        if (error != null) {
            campos.put("error", error);
        }
        StringBuilder json = new StringBuilder("{");
        campos.forEach((clave, valor) -> {
            if (valor != null) {
                if (json.length() > 1) {
                    json.append(',');
                }
                json.append('"').append(clave).append("\":");
                escapar(json, valor);
            }
        });
        return json.append('}').append(System.lineSeparator()).toString();
    }

    static void escapar(StringBuilder json, String valor) {
        json.append('"');
        for (int i = 0; i < valor.length(); i++) {
            char c = valor.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }

}
