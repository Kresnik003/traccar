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
package org.traccar.quedadas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.model.Quedada;
import org.traccar.model.QuedadaEquipo;
import org.traccar.model.QuedadaUsuario;
import org.traccar.model.Ruta;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;
import org.traccar.telegram.ClienteTelegram;
import org.traccar.telegram.RespuestaTelegram;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The call to ride (improvement 116) and "I need to stop" (112).
 *
 * The public link shows only what the organizer chose to publish - name, time,
 * meeting point, briefing, the route and how many are going - and never who:
 * no names, no trackers, no positions. Signing up needs an account on the
 * server and a tracker the caller already sees in Traccar, because a rider
 * without a tracker here would add nothing to a ride that is followed by its
 * trackers, and a sign-up without an account is an identity nobody checked.
 *
 * Signing up puts the caller's own tracker in the ride and lets the caller
 * watch the ride. It does not give access to anybody else's tracker: the web
 * shows positions through Traccar's own permissions, as before.
 */
@Singleton
public class Convocatorias {

    private static final Logger LOGGER = LoggerFactory.getLogger(Convocatorias.class);

    /** 16 random bytes in base64url: 22 characters, 128 bits. */
    static final Pattern FORMATO = Pattern.compile("[A-Za-z0-9_-]{22}");
    static final int MAXIMO_BRIEFING = 2000;
    static final int MAXIMO_SALIDA = 128;
    /** One "I need to stop" per rider and ride every two minutes. */
    static final long ENTRE_PARADAS = 120_000;
    /** Retries of a stop message: it is urgent, so seconds, not minutes. */
    static final long[] ESPERAS_REINTENTO = {5_000, 15_000, 30_000};

    /** Permission of a user on a tracker, as Traccar decides it. Throws SecurityException if not. */
    public interface Permiso {
        void comprobar(long userId, long deviceId) throws StorageException;
    }

    public interface Esperar {
        void esperar(long ms) throws InterruptedException;
    }

    /** Another stop asked too soon. */
    public static class DemasiadoPronto extends RuntimeException {
        public DemasiadoPronto() {
            super("A stop was asked less than two minutes ago");
        }
    }

    public record Apuntado(long quedadaId, long deviceId, boolean nuevo) {
    }

    private final Storage storage;
    private final ClienteTelegram cliente;
    private final Executor envios;
    private final Esperar esperar;
    private final LongSupplier reloj;
    private final SecureRandom azar = new SecureRandom();
    private final Map<String, Long> ultimasParadas = new ConcurrentHashMap<>();

    @Inject
    public Convocatorias(Storage storage, ClienteTelegram cliente, ExecutorService executorService) {
        this(storage, cliente, executorService, Thread::sleep, System::currentTimeMillis);
    }

    Convocatorias(Storage storage, ClienteTelegram cliente, Executor envios, Esperar esperar, LongSupplier reloj) {
        this.storage = storage;
        this.cliente = cliente;
        this.envios = envios;
        this.esperar = esperar;
        this.reloj = reloj;
    }

    public String nuevoCodigo() {
        byte[] bytes = new byte[16];
        azar.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The briefing and the meeting point, checked before they are stored. */
    public static void validar(Quedada q) {
        if (q.getBriefing() != null && q.getBriefing().length() > MAXIMO_BRIEFING) {
            throw new IllegalArgumentException("briefing is longer than " + MAXIMO_BRIEFING);
        }
        if (q.getSalidanombre() != null && q.getSalidanombre().length() > MAXIMO_SALIDA) {
            throw new IllegalArgumentException("salidanombre is longer than " + MAXIMO_SALIDA);
        }
        double lat = q.getSalidalat();
        double lon = q.getSalidalon();
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            throw new IllegalArgumentException("salidalat/salidalon out of range");
        }
    }

    /** The ride behind a public code, while it has not finished. */
    private Optional<Quedada> porCodigo(String codigo) throws StorageException {
        if (codigo == null || !FORMATO.matcher(codigo).matches()) {
            return Optional.empty();
        }
        Quedada quedada = storage.getObject(Quedada.class, new Request(
                new Columns.All(), new Condition.Equals("convocatoria", codigo)));
        if (quedada == null || "terminada".equals(quedada.getEstado())) {
            return Optional.empty();
        }
        return Optional.of(quedada);
    }

    /** What the public link shows, or empty (404) for a wrong, renewed or finished code. */
    public Optional<Map<String, Object>> publica(String codigo) throws StorageException {
        Optional<Quedada> encontrada = porCodigo(codigo);
        if (encontrada.isEmpty()) {
            return Optional.empty();
        }
        Quedada q = encontrada.get();
        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("nombre", q.getNombre());
        resultado.put("modalidad", q.getModalidad());
        resultado.put("tipo", q.getTipo());
        resultado.put("estado", q.getEstado());
        resultado.put("inicio", q.getInicio());
        resultado.put("briefing", q.getBriefing());
        if (q.getSalidalat() != 0 || q.getSalidalon() != 0 || q.getSalidanombre() != null) {
            Map<String, Object> salida = new LinkedHashMap<>();
            salida.put("nombre", q.getSalidanombre());
            if (q.getSalidalat() != 0 || q.getSalidalon() != 0) {
                salida.put("lat", q.getSalidalat());
                salida.put("lon", q.getSalidalon());
            }
            resultado.put("salida", salida);
        }
        resultado.put("circuito", q.getCircuito());
        if ("ruta".equals(q.getTipo()) && q.getRutaId() > 0) {
            // The route of the ride is the organizer's own (checked when the ride
            // was saved), and publishing the ride publishes its route.
            Ruta ruta = storage.getObject(Ruta.class, new Request(
                    new Columns.All(), new Condition.Equals("id", q.getRutaId())));
            if (ruta != null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("nombre", ruta.getNombre());
                r.put("distancia", ruta.getDistancia());
                r.put("trazado", ruta.getTrazado());
                resultado.put("ruta", r);
            }
        }
        resultado.put("apuntados", storage.getObjects(QuedadaEquipo.class, new Request(
                new Columns.Exclude("id"), new Condition.Equals("quedadaId", q.getId()))).size());
        return Optional.of(resultado);
    }

    /**
     * Puts the caller's own tracker in the ride behind the code, and lets the
     * caller watch the ride. Twice is once. Empty for a wrong or finished code.
     */
    public synchronized Optional<Apuntado> apuntar(
            long userId, String codigo, long deviceId, Permiso permiso) throws StorageException {
        Optional<Quedada> encontrada = porCodigo(codigo);
        if (encontrada.isEmpty()) {
            return Optional.empty();
        }
        Quedada q = encontrada.get();
        // The tracker has to be the caller's: a ride must never be a way to put
        // somebody else's bike on show.
        permiso.comprobar(userId, deviceId);
        boolean ya = storage.getObject(QuedadaEquipo.class, new Request(
                new Columns.Exclude("id"), new Condition.And(
                        new Condition.Equals("quedadaId", q.getId()),
                        new Condition.Equals("deviceId", deviceId)))) != null;
        if (!ya) {
            QuedadaEquipo equipo = new QuedadaEquipo();
            equipo.setQuedadaId(q.getId());
            equipo.setDeviceId(deviceId);
            storage.addObject(equipo, new Request(new Columns.Exclude("id")));
        }
        if (userId != q.getCreadorId() && storage.getObject(QuedadaUsuario.class, new Request(
                new Columns.Exclude("id"), new Condition.And(
                        new Condition.Equals("quedadaId", q.getId()),
                        new Condition.Equals("userId", userId)))) == null) {
            QuedadaUsuario mira = new QuedadaUsuario();
            mira.setQuedadaId(q.getId());
            mira.setUserId(userId);
            storage.addObject(mira, new Request(new Columns.Exclude("id")));
        }
        return Optional.of(new Apuntado(q.getId(), deviceId, !ya));
    }

    /** The caller's trackers riding in this ride; empty if the caller rides in none. */
    private List<Long> equiposPropios(long userId, Quedada q, Permiso permiso) throws StorageException {
        List<Long> propios = new ArrayList<>();
        for (QuedadaEquipo equipo : storage.getObjects(QuedadaEquipo.class, new Request(
                new Columns.Exclude("id"), new Condition.Equals("quedadaId", q.getId())))) {
            try {
                permiso.comprobar(userId, equipo.getDeviceId());
                propios.add(equipo.getDeviceId());
            } catch (SecurityException e) {
                // Somebody else's bike in the same ride.
            }
        }
        return propios;
    }

    /** Whether the caller may ask the ride to stop: it is running and the caller rides in it. */
    public boolean puedePedirParada(long userId, Quedada q, Permiso permiso) throws StorageException {
        return "en_marcha".equals(q.getEstado()) && !equiposPropios(userId, q, permiso).isEmpty();
    }

    /**
     * "I need to stop": a Telegram to the organizer and everybody watching the
     * ride, except the caller. Sent off the request thread, with retries.
     * Returns how many were queued. SecurityException if the caller does not
     * ride in the running ride; DemasiadoPronto if asked again within two minutes.
     */
    public int parada(long userId, Quedada q, Permiso permiso) throws StorageException {
        List<Long> propios = equiposPropios(userId, q, permiso);
        if (!"en_marcha".equals(q.getEstado()) || propios.isEmpty()) {
            throw new SecurityException("Only a rider of a running ride can ask it to stop");
        }
        String clave = userId + ":" + q.getId();
        long ahora = reloj.getAsLong();
        Long antes = ultimasParadas.get(clave);
        if (antes != null && ahora - antes < ENTRE_PARADAS) {
            throw new DemasiadoPronto();
        }
        ultimasParadas.put(clave, ahora);

        User quien = storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("id", userId)));
        Device equipo = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", propios.get(0))));
        String texto = mensaje(q, quien, equipo);

        List<Long> usuarios = new ArrayList<>();
        usuarios.add(q.getCreadorId());
        for (QuedadaUsuario mira : storage.getObjects(QuedadaUsuario.class, new Request(
                new Columns.Exclude("id"), new Condition.Equals("quedadaId", q.getId())))) {
            usuarios.add(mira.getUserId());
        }
        String propio = quien == null ? null : quien.getString("telegramChatId");
        List<String> chats = new ArrayList<>();
        // Who asks is left out by chat, not by id: the same person can also be
        // the organizer, and a chat shared by two accounts would get it back.
        for (long id : usuarios.stream().distinct().toList()) {
            User user = storage.getObject(User.class, new Request(
                    new Columns.All(), new Condition.Equals("id", id)));
            String chat = user == null || user.getDisabled() ? null : user.getString("telegramChatId");
            if (chat != null && !chat.isBlank() && !chat.equals(propio) && !chats.contains(chat)) {
                chats.add(chat);
            }
        }
        for (String chat : chats) {
            envios.execute(() -> enviar(chat, texto));
        }
        return chats.size();
    }

    private String mensaje(Quedada q, User quien, Device equipo) throws StorageException {
        String nombre = quien != null && quien.getName() != null ? quien.getName() : "Un compañero";
        StringBuilder texto = new StringBuilder("🛑 ").append(nombre).append(" necesita parar");
        if (equipo != null) {
            texto.append(" (").append(equipo.getName()).append(")");
        }
        texto.append(" en la quedada «").append(q.getNombre()).append("».");
        Position ultima = equipo == null || equipo.getPositionId() == 0 ? null : storage.getObject(
                Position.class, new Request(new Columns.All(), new Condition.Equals("id", equipo.getPositionId())));
        if (ultima != null && ultima.getValid() && (ultima.getLatitude() != 0 || ultima.getLongitude() != 0)) {
            texto.append("\nEstá aquí: https://www.google.com/maps?q=")
                    .append(String.format(Locale.ROOT, "%.6f,%.6f", ultima.getLatitude(), ultima.getLongitude()));
        }
        return texto.toString();
    }

    private void enviar(String chat, String texto) {
        for (int intento = 0; ; intento++) {
            RespuestaTelegram respuesta;
            try {
                respuesta = cliente.enviar(chat, texto, List.of());
            } catch (RuntimeException e) {
                respuesta = new RespuestaTelegram(0, e.getMessage());
            }
            if (respuesta.ok() || respuesta.rechazado()) {
                return;
            }
            if (intento >= ESPERAS_REINTENTO.length) {
                LOGGER.warn("A stop message was given up after {} retries: {}",
                        ESPERAS_REINTENTO.length, respuesta.descripcion());
                return;
            }
            try {
                esperar.esperar(ESPERAS_REINTENTO[intento]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

}
