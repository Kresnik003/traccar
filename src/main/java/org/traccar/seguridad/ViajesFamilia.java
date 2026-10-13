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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.EnlacesDirectos;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.DistanceCalculator;
import org.traccar.helper.model.Fijacion;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.model.Viaje;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;
import org.traccar.telegram.ClienteTelegram;
import org.traccar.telegram.FirmaBoton;
import org.traccar.telegram.ReceptorBoton;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * "Has left" and "has arrived" for the rider's emergency contacts
 * (improvement 115). The departure is seen on the positions, as they arrive;
 * the arrival, and the twelve hours after which a forgotten trip closes, by
 * TaskViajes from what is in the database.
 *
 * A DEPARTURE IS ARMED, THEN FIRED. A position with a fix inside one of the
 * rider's places arms it; two positions with a fix outside, with the ignition
 * on and riding, at least 15 s apart and with nothing slower in between, fire
 * it. Heartbeats without a fix (0,0) change nothing, a fix drifting out of the
 * garage does not ride, a slow way out stays armed until the bike picks up
 * speed, and one fast sample of a manoeuvre is not riding out.
 *
 * THE ARMING DOES NOT OUTLIVE ITS REASON. It goes when the bike is taken away
 * with the ignition off (a tow truck, a van), when the rider has no places any
 * more, and when the place it was armed from is no longer one of the rider's
 * (another person now rides it). It lives in memory; after a restart it is
 * rebuilt once per bike from the last position with a fix in the database,
 * before the first decision (found in the second review on 2026-09-29: a
 * restart overnight lost the first departure of the morning).
 *
 * Like the crash guard, it sends through an executor - alPosicion runs on the
 * Netty thread - and does nothing while the bot's inbox is off.
 */
@Singleton
public class ViajesFamilia implements ReceptorBoton {

    private static final Logger LOGGER = LoggerFactory.getLogger(ViajesFamilia.class);

    private static final long VENTANA_QUIETA = 30 * 60_000L;

    private static final int POSICIONES_MAXIMAS = 500;

    /** How many of the last positions are read to rebuild the arming after a restart. */
    private static final int PREVIAS_MAXIMAS = 50;

    private final boolean activo;
    private final Storage storage;
    private final ClienteTelegram cliente;
    private final EnlacesDirectos enlaces;
    private final Destinatarios destinatarios;
    private final SitiosPropios sitios;
    private final FirmaBoton firma;
    private final Executor envios;

    /**
     * A departure armed from {@code sitio}, where the bike was last seen at
     * {@code latitud}/{@code longitud}, and the fix time of the first riding
     * sample outside, if one has been seen.
     */
    private record Armado(Sitio sitio, double latitud, double longitud, Long primera) {
        Armado con(Long otra) {
            return new Armado(sitio, latitud, longitud, otra);
        }
    }

    /** Per device, the departure armed, if any. */
    private final Map<Long, Armado> armados = new ConcurrentHashMap<>();

    /** The devices whose arming has been looked for in the database since the start. */
    private final Set<Long> reconstruidos = ConcurrentHashMap.newKeySet();

    @Inject
    public ViajesFamilia(
            Config config, Storage storage, ClienteTelegram cliente, EnlacesDirectos enlaces,
            Destinatarios destinatarios, SitiosPropios sitios, ExecutorService executorService) {
        this(config.getBoolean(Keys.TELEGRAM_BUZON), storage, cliente, enlaces, destinatarios, sitios,
                new FirmaBoton(config.getString(Keys.NOTIFICATOR_TELEGRAM_KEY, "")), executorService);
    }

    ViajesFamilia(
            boolean activo, Storage storage, ClienteTelegram cliente, EnlacesDirectos enlaces,
            Destinatarios destinatarios, SitiosPropios sitios, FirmaBoton firma, Executor envios) {
        this.activo = activo;
        this.storage = storage;
        this.cliente = cliente;
        this.enlaces = enlaces;
        this.destinatarios = destinatarios;
        this.sitios = sitios;
        this.firma = firma;
        this.envios = envios;
    }

    @Override
    public String accion() {
        return "v";
    }

    /** A new position of a device. Runs on the Netty thread: it never throws. */
    public void alPosicion(Position position) {
        if (!activo) {
            return;
        }
        try {
            if (!Fijacion.tiene(position)) {
                return;
            }
            long deviceId = position.getDeviceId();
            List<Sitio> propios = sitios.de(deviceId);
            if (propios.isEmpty()) {
                armados.remove(deviceId);
                return;
            }
            Optional<Sitio> ahora = ReglasViaje.dentro(propios, position.getLatitude(), position.getLongitude());
            if (ahora.isPresent()) {
                armados.put(deviceId, new Armado(ahora.get(), position.getLatitude(), position.getLongitude(), null));
                reconstruidos.add(deviceId);
                return;
            }
            Armado armado = armado(deviceId, position, propios);
            if (armado == null) {
                return;
            }
            boolean contacto = position.getBoolean(Position.KEY_IGNITION);
            if (!ReglasViaje.rodandoFuera(ahora, contacto, position.getSpeed())) {
                double metros = DistanceCalculator.distance(
                        armado.latitud(), armado.longitud(), position.getLatitude(), position.getLongitude());
                if (ReglasViaje.seLaLlevan(contacto, metros)) {
                    armados.remove(deviceId);
                } else if (armado.primera() != null) {
                    armados.put(deviceId, armado.con(null));
                }
                return;
            }
            long cuando = position.getFixTime().getTime();
            Long primera = armado.primera();
            if (primera == null || !ReglasViaje.confirmaSalida(primera, cuando)) {
                if (primera == null || cuando - primera > ReglasViaje.VENTANA_SALIDA || cuando < primera) {
                    armados.put(deviceId, armado.con(cuando));
                }
                return;
            }
            armados.remove(deviceId);
            if (abierto(deviceId, System.currentTimeMillis()) != null) {
                return;
            }
            List<Destinatarios.Destinatario> contactos = destinatarios.contactos(deviceId);
            if (contactos.isEmpty()) {
                return;
            }
            abrir(deviceId, armado.sitio().nombre(), contactos);
        } catch (Exception e) {
            LOGGER.warn("Trip check failed for device {}", position.getDeviceId(), e);
        }
    }

    /**
     * The departure armed for the device, if it still holds: from a place that
     * is still one of the rider's. The first time a device is seen since the
     * start, it is looked for in the database.
     */
    private Armado armado(long deviceId, Position position, List<Sitio> propios) {
        Armado guardado = armados.get(deviceId);
        if (guardado != null) {
            if (propios.stream().noneMatch(sitio -> sitio.id() == guardado.sitio().id())) {
                armados.remove(deviceId);
                return null;
            }
            return guardado;
        }
        Armado armado = null;
        if (reconstruidos.add(deviceId)) {
            try {
                armado = reconstruir(deviceId, position, propios);
                if (armado != null) {
                    armados.put(deviceId, armado);
                }
            } catch (StorageException e) {
                reconstruidos.remove(deviceId);
                LOGGER.warn("Trip arming of device {} could not be rebuilt", deviceId, e);
            }
        }
        return armado;
    }

    /** From the last position with a fix before this one: armed if it was inside a place. */
    private Armado reconstruir(long deviceId, Position position, List<Sitio> propios) throws StorageException {
        Position previa;
        try (var stream = storage.getObjectsStream(Position.class, new Request(new Columns.All(),
                new Condition.And(
                        new Condition.Equals("deviceId", deviceId),
                        new Condition.Compare("fixTime", "<", position.getFixTime())),
                new Order("fixTime", true, PREVIAS_MAXIMAS)))) {
            previa = stream.filter(Fijacion::tiene).max(Comparator.comparing(Position::getFixTime)).orElse(null);
        }
        if (previa == null) {
            return null;
        }
        return ReglasViaje.dentro(propios, previa.getLatitude(), previa.getLongitude())
                .map(sitio -> new Armado(sitio, previa.getLatitude(), previa.getLongitude(), null))
                .orElse(null);
    }

    private void abrir(long deviceId, String origen, List<Destinatarios.Destinatario> contactos)
            throws StorageException {
        Date inicio = new Date();
        Viaje viaje = new Viaje();
        viaje.setDeviceId(deviceId);
        viaje.setInicio(inicio);
        viaje.setOrigen(origen);
        viaje.setId(storage.addObject(viaje, new Request(new Columns.Exclude("id"))));
        var enlace = enlaces.enlaceDeEquipo(
                deviceId, new Date(inicio.getTime() + ReglasViaje.DURACION_MAXIMA), "viaje", viaje.getId());
        if (enlace.isPresent()) {
            viaje.setEnlace(enlace.get().url());
            viaje.setShareUserId(enlace.get().shareUserId());
            storage.updateObject(viaje, new Request(
                    new Columns.Include("enlace", "shareUserId"), new Condition.Equals("id", viaje.getId())));
        }
        String texto = Mensajes.salida(destinatarios.persona(deviceId), origen, moto(deviceId), viaje.getEnlace());
        Optional<String> motorista = destinatarios.chatDelMotorista(deviceId);
        List<ClienteTelegram.Boton> boton = List.of(
                new ClienteTelegram.Boton("He llegado", firma.firmar(accion(), viaje.getId())));
        envios.execute(() -> {
            contactos.forEach(contacto -> cliente.enviar(contacto.chatId(), texto, List.of()));
            motorista.ifPresent(chat -> cliente.enviar(chat, Mensajes.alDuenoViajeAbierto(), boton));
        });
    }

    /** The button and the check close the same trip from two threads: one at a time, reading it again. */
    @Override
    public synchronized String pulsado(long id, String chatId) {
        try {
            Viaje viaje = leer(id);
            if (viaje == null) {
                return "Este viaje ya no existe.";
            }
            if (!chatId.equals(destinatarios.chatDelMotorista(viaje.getDeviceId()).orElse(null))) {
                return "Este botón no es tuyo.";
            }
            if (viaje.getFin() != null) {
                return "Este viaje ya estaba cerrado.";
            }
            cerrar(viaje, "boton", null, true);
            return "Hecho: hemos avisado de que has llegado.";
        } catch (StorageException e) {
            LOGGER.warn("Trip button failed", e);
            return "No se ha podido guardar. Vuelve a pulsar.";
        }
    }

    /** Called every 30 s by TaskViajes: arrivals and forgotten trips. */
    public void revisar(long ahora) {
        if (!activo) {
            return;
        }
        try {
            for (Viaje viaje : recientes(ahora)) {
                if (viaje.getFin() != null) {
                    continue;
                }
                try {
                    revisar(viaje.getId(), ahora);
                } catch (Exception e) {
                    LOGGER.warn("Trip {} failed", viaje.getId(), e);
                }
            }
        } catch (StorageException e) {
            LOGGER.warn("Trips could not be read", e);
        }
    }

    private synchronized void revisar(long id, long ahora) throws StorageException {
        Viaje viaje = leer(id);
        if (viaje == null || viaje.getFin() != null) {
            return;
        }
        if (ReglasViaje.caducado(viaje.getInicio(), ahora)) {
            cerrar(viaje, "caducado", null, false);
            return;
        }
        long desde = Math.max(viaje.getInicio().getTime(), ahora - VENTANA_QUIETA);
        List<Position> recientes;
        // By fix time, which has an index, with the same room as the crash guard;
        // the device time orders them here.
        try (var stream = storage.getObjectsStream(Position.class, new Request(new Columns.All(),
                new Condition.And(
                        new Condition.Equals("deviceId", viaje.getDeviceId()),
                        new Condition.Between("fixTime",
                                new Date(desde - GuardianGolpes.MARGEN_FIJACION), new Date(ahora))),
                new Order("fixTime", true, POSICIONES_MAXIMAS)))) {
            recientes = stream
                    .filter(p -> p.getDeviceTime() != null && p.getDeviceTime().getTime() >= desde)
                    .sorted(Comparator.comparing(Position::getDeviceTime).reversed())
                    .toList();
        }
        if (recientes.isEmpty()) {
            return;
        }
        Position ultima = recientes.get(0);
        Position conFijacion = recientes.stream().filter(Fijacion::tiene).findFirst().orElse(null);
        Long quieta = ReglasViaje.quietaDesde(recientes);
        if (conFijacion == null || quieta == null) {
            return;
        }
        Optional<Sitio> donde = ReglasViaje.dentro(
                sitios.de(viaje.getDeviceId()), conFijacion.getLatitude(), conFijacion.getLongitude());
        if (ReglasViaje.esLlegada(donde, ultima.getBoolean(Position.KEY_IGNITION), quieta, ahora)) {
            cerrar(viaje, "sitio", donde.get().nombre(), true);
        }
    }

    private void cerrar(Viaje viaje, String motivo, String sitio, boolean avisar) throws StorageException {
        viaje.setFin(new Date());
        viaje.setMotivoFin(motivo);
        storage.updateObject(viaje, new Request(
                new Columns.Include("fin", "motivoFin"), new Condition.Equals("id", viaje.getId())));
        if (viaje.getShareUserId() > 0) {
            enlaces.cortar(viaje.getShareUserId());
        }
        if (avisar) {
            String texto = Mensajes.llegada(destinatarios.persona(viaje.getDeviceId()), sitio);
            List<Destinatarios.Destinatario> contactos = destinatarios.contactos(viaje.getDeviceId());
            envios.execute(() -> contactos.forEach(contacto -> cliente.enviar(contacto.chatId(), texto, List.of())));
        }
    }

    /**
     * The trips that may still be open. The storage query language has no IS
     * NULL, so the ones started in the last day are read and the closed ones
     * skipped here: a trip closes itself after twelve hours anyway.
     */
    private List<Viaje> recientes(long ahora) throws StorageException {
        return storage.getObjects(Viaje.class, new Request(new Columns.All(),
                new Condition.Compare("inicio", ">", new Date(ahora - 2 * ReglasViaje.DURACION_MAXIMA))));
    }

    private Viaje abierto(long deviceId, long ahora) throws StorageException {
        return recientes(ahora).stream()
                .filter(viaje -> viaje.getDeviceId() == deviceId && viaje.getFin() == null)
                .findFirst().orElse(null);
    }

    private Viaje leer(long id) throws StorageException {
        return storage.getObject(Viaje.class, new Request(new Columns.All(), new Condition.Equals("id", id)));
    }

    private String moto(long deviceId) throws StorageException {
        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", deviceId)));
        return device != null ? device.getName() : "vehículo";
    }

}
