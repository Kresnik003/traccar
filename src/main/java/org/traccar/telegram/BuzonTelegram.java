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
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.LifecycleObject;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Contacto;
import org.traccar.model.Device;
import org.traccar.model.EstadoTelegram;
import org.traccar.seguridad.Destinatarios;
import org.traccar.seguridad.GuardianGolpes;
import org.traccar.seguridad.Mensajes;
import org.traccar.seguridad.ViajesFamilia;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The bot's inbox. Reads getUpdates in a thread of its own - a long poll that
 * would hold a thread of the scheduler for good - and handles sign-ups of
 * emergency contacts, "/baja" and the buttons of the safety messages.
 *
 * Only one reader per bot: two readers of getUpdates steal each other's
 * messages. It does not run on a secondary node, and scripts that read
 * getUpdates by hand (chats-telegram.sh) stop seeing messages while it runs.
 */
@Singleton
public class BuzonTelegram implements LifecycleObject {

    private static final Logger LOGGER = LoggerFactory.getLogger(BuzonTelegram.class);

    private static final int ESPERA = 25;

    private final boolean activo;
    private final Storage storage;
    private final ClienteTelegram cliente;
    private final Destinatarios destinatarios;
    private final FirmaBoton firma;
    private final Map<String, ReceptorBoton> receptores = new HashMap<>();
    private volatile boolean corriendo;
    private Thread hilo;

    @Inject
    public BuzonTelegram(
            Config config, Storage storage, ClienteTelegram cliente, Destinatarios destinatarios,
            GuardianGolpes guardianGolpes, ViajesFamilia viajesFamilia) {
        this(config.getBoolean(Keys.TELEGRAM_BUZON) && !config.getBoolean(Keys.BROADCAST_SECONDARY),
                storage, cliente, destinatarios,
                new FirmaBoton(config.getString(Keys.NOTIFICATOR_TELEGRAM_KEY, "")),
                List.of(guardianGolpes, viajesFamilia));
    }

    BuzonTelegram(
            Storage storage, ClienteTelegram cliente, Destinatarios destinatarios, FirmaBoton firma,
            List<ReceptorBoton> receptores) {
        this(false, storage, cliente, destinatarios, firma, receptores);
    }

    private BuzonTelegram(
            boolean activo, Storage storage, ClienteTelegram cliente, Destinatarios destinatarios,
            FirmaBoton firma, List<ReceptorBoton> receptores) {
        this.activo = activo;
        this.storage = storage;
        this.cliente = cliente;
        this.destinatarios = destinatarios;
        this.firma = firma;
        receptores.forEach(receptor -> this.receptores.put(receptor.accion(), receptor));
    }

    @Override
    public void start() {
        if (!activo) {
            return;
        }
        corriendo = true;
        hilo = new Thread(this::bucle, "telegram-buzon");
        hilo.setDaemon(true);
        hilo.start();
        LOGGER.info("Telegram inbox started");
    }

    @Override
    public void stop() throws InterruptedException {
        corriendo = false;
        if (hilo != null) {
            hilo.interrupt();
            hilo.join(5_000);
        }
    }

    /**
     * Where to start reading. Only the very first start - no row yet - skips
     * what somebody wrote to the bot before today; after that, what arrives
     * while Traccar is stopped is read when it comes back, even if the bot had
     * never received anything (offset 0).
     */
    long inicio() {
        Long guardado = leerUltimo();
        if (guardado != null) {
            return guardado;
        }
        // offset -1 returns only the newest update.
        long desde = procesarNada(cliente.leer(-1, 0));
        guardarUltimo(desde);
        return desde;
    }

    private void bucle() {
        long desde = inicio();
        while (corriendo) {
            try {
                long siguiente = procesar(cliente.leer(desde, ESPERA), desde);
                if (siguiente != desde) {
                    desde = siguiente;
                    guardarUltimo(desde);
                } else {
                    // An empty answer after a failure must not become a loop at full speed.
                    Thread.sleep(1_000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                LOGGER.warn("Telegram inbox error", e);
            }
        }
    }

    private static long procesarNada(JsonNode updates) {
        long siguiente = 0;
        for (JsonNode update : updates) {
            siguiente = Math.max(siguiente, update.path("update_id").asLong() + 1);
        }
        return siguiente;
    }

    /** Handles a batch and returns the next offset to ask for. */
    long procesar(JsonNode updates, long desde) {
        long siguiente = desde;
        for (JsonNode update : updates) {
            Actualizacion actualizacion = Actualizacion.leer(update);
            siguiente = Math.max(siguiente, actualizacion.updateId() + 1);
            try {
                switch (actualizacion) {
                    case Actualizacion.Alta alta -> alta(alta);
                    case Actualizacion.Baja baja -> baja(baja);
                    case Actualizacion.Pulsacion pulsacion -> pulsacion(pulsacion);
                    case Actualizacion.Otra otra -> {
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Telegram update {} failed", actualizacion.updateId(), e);
            }
        }
        return siguiente;
    }

    private void alta(Actualizacion.Alta alta) throws StorageException {
        Contacto contacto = storage.getObject(Contacto.class, new Request(
                new Columns.All(), new Condition.Equals("codigo", CodigosAlta.huella(alta.codigo()))));
        if (contacto == null || !CodigosAlta.vale(contacto, new Date())) {
            cliente.enviar(alta.chatId(), Mensajes.codigoNoValido(), List.of());
            return;
        }
        contacto.setChatId(alta.chatId());
        contacto.setConfirmado(new Date());
        contacto.setCodigo(null);
        contacto.setRechazado(null);
        storage.updateObject(contacto, new Request(
                new Columns.Include("chatId", "confirmado", "codigo", "rechazado"),
                new Condition.Equals("id", contacto.getId())));
        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", contacto.getDeviceId())));
        cliente.enviar(alta.chatId(), Mensajes.bienvenida(
                destinatarios.persona(contacto.getDeviceId()), device != null ? device.getName() : "moto"),
                List.of());
    }

    private void baja(Actualizacion.Baja baja) throws StorageException {
        for (Contacto contacto : storage.getObjects(Contacto.class, new Request(
                new Columns.All(), new Condition.Equals("chatId", baja.chatId())))) {
            contacto.setChatId(null);
            contacto.setConfirmado(null);
            storage.updateObject(contacto, new Request(
                    new Columns.Include("chatId", "confirmado"), new Condition.Equals("id", contacto.getId())));
        }
        cliente.enviar(baja.chatId(), Mensajes.baja(), List.of());
    }

    private void pulsacion(Actualizacion.Pulsacion pulsacion) {
        String respuesta = firma.comprobar(pulsacion.dato())
                .map(dato -> {
                    ReceptorBoton receptor = receptores.get(dato.accion());
                    return receptor != null ? receptor.pulsado(dato.id(), pulsacion.chatId()) : null;
                })
                .orElse(null);
        cliente.responderBoton(pulsacion.callbackId(), respuesta != null ? respuesta : "Este botón ya no sirve.");
    }

    /** The saved offset, or null if the inbox never ran here. */
    private Long leerUltimo() {
        try {
            EstadoTelegram estado = storage.getObject(EstadoTelegram.class, new Request(
                    new Columns.All(), new Condition.Equals("id", EstadoTelegram.FILA)));
            return estado != null ? estado.getUltimo() : null;
        } catch (StorageException e) {
            // Unknown is not "first start": reading from 0 repeats nothing that
            // Telegram has not already dropped, skipping could lose a sign-up.
            LOGGER.warn("Telegram inbox state could not be read", e);
            return 0L;
        }
    }

    private void guardarUltimo(long ultimo) {
        EstadoTelegram estado = new EstadoTelegram();
        estado.setId(EstadoTelegram.FILA);
        estado.setUltimo(ultimo);
        try {
            if (storage.getObject(EstadoTelegram.class, new Request(
                    new Columns.All(), new Condition.Equals("id", EstadoTelegram.FILA))) == null) {
                storage.addObject(estado, new Request(new Columns.All()));
            } else {
                storage.updateObject(estado, new Request(
                        new Columns.Include("ultimo"), new Condition.Equals("id", EstadoTelegram.FILA)));
            }
        } catch (StorageException e) {
            LOGGER.warn("Telegram inbox state could not be saved", e);
        }
    }

}
