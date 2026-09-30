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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/**
 * The words of the safety messages. They reach people who do not use Nordés,
 * so they say who, what, when and where, and never promise what the service
 * does not do: Nordés does not call 112.
 *
 * The time is formatted with a fixed pattern and no month names: the Java that
 * runs Traccar has no Spanish locale data.
 */
public final class Mensajes {

    private static final ZoneId ZONA = ZoneId.of("Europe/Madrid");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZONA);

    private Mensajes() {
    }

    public static String alDuenoTrasGolpe(String moto) {
        return "Hemos detectado un golpe en tu " + moto + ". Si estás bien, pulsa el botón. "
                + "Si no, en 2 minutos avisaremos a tus contactos de emergencia.";
    }

    public static String lugar(String direccion, double latitud, double longitud) {
        if (direccion != null && !direccion.isBlank()) {
            return direccion.strip();
        }
        return String.format(Locale.ROOT, "%.5f, %.5f", latitud, longitud);
    }

    /** The last place known before the crash, when nothing after it has a fix. */
    public static String lugarAntesDelGolpe(String direccion, double latitud, double longitud) {
        return lugar(direccion, latitud, longitud) + " (su última posición conocida antes del golpe)";
    }

    /**
     * When no position of the bike has a fix. The live link is promised only if
     * the message carries one (found in review on 2026-09-30: a message without
     * a link sent people to look at a live location it did not include).
     */
    public static String lugarDesconocido(boolean conEnlace) {
        return conEnlace
                ? "un lugar que el GPS aún no ha fijado (mira la ubicación en directo)"
                : "un lugar que el GPS aún no ha fijado";
    }

    public static String aContacto(String persona, String moto, Date hora, String lugar, String enlace) {
        StringBuilder texto = new StringBuilder();
        texto.append("⚠️ Posible accidente de ").append(persona).append(" con su ").append(moto)
                .append(", a las ").append(HORA.format(hora.toInstant())).append(", en ").append(lugar)
                .append(". No ha respondido en 2 minutos.");
        if (enlace != null) {
            texto.append("\nUbicación en directo: ").append(enlace);
        }
        texto.append("\nNordés no avisa al 112: si no consigues hablar con ").append(persona)
                .append(", llama tú al 112.");
        return texto.toString();
    }

    public static String aCompanero(
            String quedada, String persona, String moto, Date hora, String lugar, String enlace) {
        return "Compañero de la quedada «" + quedada + "».\n" + aContacto(persona, moto, hora, lugar, enlace);
    }

    public static String salida(String persona, String sitio, String moto, String enlace) {
        StringBuilder texto = new StringBuilder();
        texto.append(persona).append(" ha salido de ").append(sitio).append(" con su ").append(moto).append('.');
        if (enlace != null) {
            texto.append("\nUbicación en directo: ").append(enlace);
        }
        texto.append("\nTe avisaremos cuando llegue.");
        return texto.toString();
    }

    public static String alDuenoViajeAbierto() {
        return "Viaje abierto: tus contactos te siguen hasta que llegues. Si llegas a un sitio que no es "
                + "uno de los tuyos, pulsa el botón.";
    }

    public static String llegada(String persona, String sitio) {
        return sitio != null ? persona + " ha llegado bien a " + sitio + "." : persona + " ha llegado bien.";
    }

    public static String bienvenida(String persona, String moto) {
        return "Hola. Ahora eres contacto de emergencia de " + persona + " (" + moto + ") en Nordés. "
                + "Te escribiremos aquí si detectamos un golpe y no responde, y cuando salga de viaje y llegue. "
                + "Nordés no avisa al 112. Para dejar de recibir avisos, escribe /baja.";
    }

    public static String codigoNoValido() {
        return "Este enlace ha caducado o ya se usó. Pide uno nuevo a quien te lo mandó.";
    }

    public static String baja() {
        return "Hecho. Ya no recibirás avisos de Nordés. Si quieres volver, pide un enlace nuevo.";
    }

}
