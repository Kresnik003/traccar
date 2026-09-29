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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.EnlacesDirectos;
import org.traccar.config.Config;
import org.traccar.config.Keys;
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

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * "Has left" and "has arrived" for the rider's emergency contacts
 * (improvement 115). The departure is seen on the positions, as they arrive;
 * the arrival, and the twelve hours after which a forgotten trip closes, by
 * TaskViajes from what is in the database, so a restart loses nothing.
 */
@Singleton
public class ViajesFamilia implements ReceptorBoton {

    private static final Logger LOGGER = LoggerFactory.getLogger(ViajesFamilia.class);

    private static final long VENTANA_QUIETA = 30 * 60_000L;

    private static final int POSICIONES_MAXIMAS = 200;

    private final Storage storage;
    private final ClienteTelegram cliente;
    private final EnlacesDirectos enlaces;
    private final Destinatarios destinatarios;
    private final SitiosPropios sitios;
    private final FirmaBoton firma;

    @Inject
    public ViajesFamilia(
            Config config, Storage storage, ClienteTelegram cliente, EnlacesDirectos enlaces,
            Destinatarios destinatarios, SitiosPropios sitios) {
        this(storage, cliente, enlaces, destinatarios, sitios,
                new FirmaBoton(config.getString(Keys.NOTIFICATOR_TELEGRAM_KEY, "")));
    }

    ViajesFamilia(
            Storage storage, ClienteTelegram cliente, EnlacesDirectos enlaces, Destinatarios destinatarios,
            SitiosPropios sitios, FirmaBoton firma) {
        this.storage = storage;
        this.cliente = cliente;
        this.enlaces = enlaces;
        this.destinatarios = destinatarios;
        this.sitios = sitios;
        this.firma = firma;
    }

    @Override
    public String accion() {
        return "v";
    }

    /** A new position of a device. Runs on the Netty thread: it never throws. */
    public void alPosicion(Position position, Position anterior) {
        try {
            if (anterior == null || !Fijacion.tiene(position)) {
                return;
            }
            long deviceId = position.getDeviceId();
            List<Sitio> propios = sitios.de(deviceId);
            if (propios.isEmpty()) {
                return;
            }
            Optional<Sitio> antes = ReglasViaje.dentro(propios, anterior.getLatitude(), anterior.getLongitude());
            Optional<Sitio> ahora = ReglasViaje.dentro(propios, position.getLatitude(), position.getLongitude());
            if (!ReglasViaje.esSalida(antes, ahora, position.getBoolean(Position.KEY_IGNITION))
                    || abierto(deviceId, System.currentTimeMillis()) != null) {
                return;
            }
            List<Destinatarios.Destinatario> contactos = destinatarios.contactos(deviceId);
            if (contactos.isEmpty()) {
                return;
            }
            abrir(deviceId, antes.get().nombre(), contactos);
        } catch (Exception e) {
            LOGGER.warn("Trip check failed for device {}", position.getDeviceId(), e);
        }
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
        contactos.forEach(contacto -> cliente.enviar(contacto.chatId(), texto, List.of()));
        for (String chat : destinatarios.chatsDelDueno(deviceId)) {
            cliente.enviar(chat, Mensajes.alDuenoViajeAbierto(),
                    List.of(new ClienteTelegram.Boton("He llegado", firma.firmar(accion(), viaje.getId()))));
        }
    }

    @Override
    public String pulsado(long id, String chatId) {
        try {
            Viaje viaje = storage.getObject(Viaje.class, new Request(
                    new Columns.All(), new Condition.Equals("id", id)));
            if (viaje == null) {
                return "Este viaje ya no existe.";
            }
            if (!destinatarios.chatsDelDueno(viaje.getDeviceId()).contains(chatId)) {
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
        try {
            for (Viaje viaje : recientes(ahora)) {
                if (viaje.getFin() != null) {
                    continue;
                }
                try {
                    revisar(viaje, ahora);
                } catch (Exception e) {
                    LOGGER.warn("Trip {} failed", viaje.getId(), e);
                }
            }
        } catch (StorageException e) {
            LOGGER.warn("Trips could not be read", e);
        }
    }

    private void revisar(Viaje viaje, long ahora) throws StorageException {
        if (ReglasViaje.caducado(viaje.getInicio(), ahora)) {
            cerrar(viaje, "caducado", null, false);
            return;
        }
        long desde = Math.max(viaje.getInicio().getTime(), ahora - VENTANA_QUIETA);
        List<Position> recientes;
        try (var stream = storage.getObjectsStream(Position.class, new Request(new Columns.All(),
                new Condition.And(
                        new Condition.Equals("deviceId", viaje.getDeviceId()),
                        new Condition.Between("deviceTime", new Date(desde), new Date(ahora))),
                new Order("deviceTime", true, POSICIONES_MAXIMAS)))) {
            recientes = stream.toList();
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
            destinatarios.contactos(viaje.getDeviceId())
                    .forEach(contacto -> cliente.enviar(contacto.chatId(), texto, List.of()));
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

    private String moto(long deviceId) throws StorageException {
        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", deviceId)));
        return device != null ? device.getName() : "moto";
    }

}
