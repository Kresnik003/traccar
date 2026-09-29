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
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Guardia;
import org.traccar.model.Position;
import org.traccar.notification.VentanaAccidente;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;
import org.traccar.telegram.ClienteTelegram;
import org.traccar.telegram.FirmaBoton;
import org.traccar.telegram.ReceptorBoton;
import org.traccar.telegram.RespuestaTelegram;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * The crash guard (improvement 114). A crash while riding asks the rider on
 * Telegram whether they are fine; if nobody answers in two minutes and the bike
 * has not moved again, the emergency contacts and the people of the ride in
 * progress are warned, with a live link.
 *
 * alEvento runs on the Netty thread while the event is being saved: it never
 * throws and never waits. The two minutes are counted by TaskGuardias, which
 * reads the guards from the database, so a restart does not lose one.
 */
@Singleton
public class GuardianGolpes implements ReceptorBoton {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardianGolpes.class);

    /** Retries of a message that did not go out: 30 s, 2 min, 10 min. */
    static final long[] ESPERAS_REINTENTO = {30_000, 120_000, 600_000};

    /** How long the link sent after a crash lets people follow the bike. */
    static final long DURACION_ENLACE = 24 * 3_600_000L;

    /** Frames of the same crash keep arriving for minutes: one guard per crash. */
    private static final long MISMO_GOLPE = 10 * 60_000L;

    private static final int POSICIONES_MAXIMAS = 200;

    private record Pendiente(String chatId, String texto, Long contactoId, int intento, long cuando) {
    }

    private final Storage storage;
    private final ClienteTelegram cliente;
    private final EnlacesDirectos enlaces;
    private final Destinatarios destinatarios;
    private final FirmaBoton firma;
    private final List<Pendiente> pendientes = new ArrayList<>();

    @Inject
    public GuardianGolpes(
            Config config, Storage storage, ClienteTelegram cliente, EnlacesDirectos enlaces,
            Destinatarios destinatarios) {
        this(storage, cliente, enlaces, destinatarios,
                new FirmaBoton(config.getString(Keys.NOTIFICATOR_TELEGRAM_KEY, "")));
    }

    GuardianGolpes(
            Storage storage, ClienteTelegram cliente, EnlacesDirectos enlaces, Destinatarios destinatarios,
            FirmaBoton firma) {
        this.storage = storage;
        this.cliente = cliente;
        this.enlaces = enlaces;
        this.destinatarios = destinatarios;
        this.firma = firma;
    }

    @Override
    public String accion() {
        return "g";
    }

    public void alEvento(Event event, Position position) {
        try {
            if (position == null || !VentanaAccidente.esAccidente(event)) {
                return;
            }
            long ahora = System.currentTimeMillis();
            Date golpe = position.getDeviceTime();
            if (golpe == null || !ReglasGolpe.reciente(golpe, ahora) || mismoGolpe(event.getDeviceId(), golpe)) {
                return;
            }
            List<Position> previas;
            try (var stream = storage.getObjectsStream(Position.class, new Request(new Columns.All(),
                    new Condition.And(
                            new Condition.Equals("deviceId", event.getDeviceId()),
                            new Condition.Between("deviceTime",
                                    new Date(golpe.getTime() - ReglasGolpe.ESPERA), golpe)),
                    new Order("deviceTime", true, POSICIONES_MAXIMAS)))) {
                previas = stream.toList();
            }
            if (!ReglasGolpe.ibaEnMarcha(previas, golpe)) {
                return;
            }
            Guardia guardia = new Guardia();
            guardia.setDeviceId(event.getDeviceId());
            guardia.setEventId(event.getId());
            guardia.setPositionId(position.getId());
            guardia.setGolpe(golpe);
            guardia.setLimite(new Date(golpe.getTime() + ReglasGolpe.ESPERA));
            guardia.setLatitud(position.getLatitude());
            guardia.setLongitud(position.getLongitude());
            guardia.setEstado(Guardia.ESPERANDO);
            guardia.setId(storage.addObject(guardia, new Request(new Columns.Exclude("id"))));
            String texto = Mensajes.alDuenoTrasGolpe(moto(event.getDeviceId()));
            for (String chat : destinatarios.chatsDelDueno(event.getDeviceId())) {
                cliente.enviar(chat, texto,
                        List.of(new ClienteTelegram.Boton("Estoy bien", firma.firmar(accion(), guardia.getId()))));
            }
        } catch (Exception e) {
            LOGGER.warn("Crash guard failed for device {}", event.getDeviceId(), e);
        }
    }

    /** A guard of this bike still waiting, or opened by a crash of the last few minutes. */
    private boolean mismoGolpe(long deviceId, Date golpe) throws StorageException {
        for (Guardia guardia : storage.getObjects(Guardia.class, new Request(new Columns.All(),
                new Condition.Equals("deviceid", deviceId), new Order("golpe", true, 5)))) {
            if (Guardia.ESPERANDO.equals(guardia.getEstado())
                    || Math.abs(golpe.getTime() - guardia.getGolpe().getTime()) < MISMO_GOLPE) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String pulsado(long id, String chatId) {
        try {
            Guardia guardia = storage.getObject(Guardia.class, new Request(
                    new Columns.All(), new Condition.Equals("id", id)));
            if (guardia == null) {
                return "Este aviso ya no existe.";
            }
            if (!destinatarios.chatsDelDueno(guardia.getDeviceId()).contains(chatId)) {
                return "Este botón no es tuyo.";
            }
            if (Guardia.AVISADA.equals(guardia.getEstado())) {
                return "Tus contactos ya han sido avisados. Diles tú que estás bien.";
            }
            if (!Guardia.ESPERANDO.equals(guardia.getEstado())) {
                return "Ya estaba cancelado: no avisaremos a nadie.";
            }
            guardia.setEstado(Guardia.CANCELADA_BOTON);
            actualizar(guardia);
            return "Perfecto: no avisaremos a nadie.";
        } catch (StorageException e) {
            LOGGER.warn("Crash guard button failed", e);
            return "No se ha podido guardar. Vuelve a pulsar.";
        }
    }

    /** Called every few seconds by TaskGuardias. */
    public void revisar(long ahora) {
        try {
            for (Guardia guardia : storage.getObjects(Guardia.class, new Request(
                    new Columns.All(), new Condition.Equals("estado", Guardia.ESPERANDO)))) {
                try {
                    revisar(guardia, ahora);
                } catch (Exception e) {
                    LOGGER.warn("Crash guard {} failed", guardia.getId(), e);
                }
            }
        } catch (StorageException e) {
            LOGGER.warn("Crash guards could not be read", e);
        }
        reintentar(ahora);
    }

    private void revisar(Guardia guardia, long ahora) throws StorageException {
        List<Position> despues;
        try (var stream = storage.getObjectsStream(Position.class, new Request(new Columns.All(),
                new Condition.And(
                        new Condition.Equals("deviceId", guardia.getDeviceId()),
                        new Condition.Between("deviceTime", guardia.getGolpe(), new Date(ahora))),
                new Order("deviceTime", false, POSICIONES_MAXIMAS)))) {
            despues = stream.filter(p -> p.getDeviceTime() != null
                    && p.getDeviceTime().after(guardia.getGolpe())).toList();
        }
        if (ReglasGolpe.seHaMovido(guardia.getLatitud(), guardia.getLongitud(), despues)) {
            guardia.setEstado(Guardia.CANCELADA_MOVIMIENTO);
            actualizar(guardia);
        } else if (ahora >= guardia.getLimite().getTime()) {
            avisar(guardia, ahora);
        }
    }

    private void avisar(Guardia guardia, long ahora) throws StorageException {
        long deviceId = guardia.getDeviceId();
        var enlace = enlaces.enlaceDeEquipo(deviceId, new Date(guardia.getGolpe().getTime() + DURACION_ENLACE));
        guardia.setEstado(Guardia.AVISADA);
        guardia.setEnlace(enlace.map(EnlacesDirectos.Enlace::url).orElse(null));
        // Written before sending: a restart in the middle must not warn everybody twice.
        actualizar(guardia);
        Position position = storage.getObject(Position.class, new Request(
                new Columns.All(), new Condition.Equals("id", guardia.getPositionId())));
        String lugar = Mensajes.lugar(
                position != null ? position.getAddress() : null, guardia.getLatitud(), guardia.getLongitud());
        String persona = destinatarios.persona(deviceId);
        String moto = moto(deviceId);
        for (Destinatarios.Destinatario destinatario : destinatarios.de(deviceId)) {
            String texto = destinatario.quedada() == null
                    ? Mensajes.aContacto(persona, moto, guardia.getGolpe(), lugar, guardia.getEnlace())
                    : Mensajes.aCompanero(destinatario.quedada(), persona, moto, guardia.getGolpe(), lugar,
                            guardia.getEnlace());
            enviar(new Pendiente(destinatario.chatId(), texto, destinatario.contactoId(), 0, ahora), ahora);
        }
    }

    private void enviar(Pendiente pendiente, long ahora) {
        RespuestaTelegram respuesta = cliente.enviar(pendiente.chatId(), pendiente.texto(), List.of());
        if (respuesta.ok()) {
            return;
        }
        if (respuesta.rechazado()) {
            if (pendiente.contactoId() != null) {
                try {
                    destinatarios.rechazar(pendiente.contactoId());
                } catch (StorageException e) {
                    LOGGER.warn("Contact {} could not be marked as refused", pendiente.contactoId(), e);
                }
            }
            return;
        }
        if (pendiente.intento() < ESPERAS_REINTENTO.length) {
            synchronized (pendientes) {
                pendientes.add(new Pendiente(pendiente.chatId(), pendiente.texto(), pendiente.contactoId(),
                        pendiente.intento() + 1, ahora + ESPERAS_REINTENTO[pendiente.intento()]));
            }
        } else {
            LOGGER.warn("Safety message to chat {} given up after {} retries", pendiente.chatId(),
                    ESPERAS_REINTENTO.length);
        }
    }

    private void reintentar(long ahora) {
        List<Pendiente> vencidos = new ArrayList<>();
        synchronized (pendientes) {
            for (Iterator<Pendiente> iterator = pendientes.iterator(); iterator.hasNext();) {
                Pendiente pendiente = iterator.next();
                if (pendiente.cuando() <= ahora) {
                    vencidos.add(pendiente);
                    iterator.remove();
                }
            }
        }
        vencidos.forEach(pendiente -> enviar(pendiente, ahora));
    }

    private void actualizar(Guardia guardia) throws StorageException {
        storage.updateObject(guardia, new Request(
                new Columns.Include("estado", "enlace"), new Condition.Equals("id", guardia.getId())));
    }

    private String moto(long deviceId) throws StorageException {
        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", deviceId)));
        return device != null ? device.getName() : "moto";
    }

}
