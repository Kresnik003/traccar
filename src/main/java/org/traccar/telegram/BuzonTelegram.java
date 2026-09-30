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
import org.traccar.model.User;
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
 * messages. It does not run on a secondary node, and chats-telegram.sh does
 * not read getUpdates while telegram.buzon is on.
 *
 * The offset is sent only right after a batch, to confirm it; an idle inbox
 * reads without one, from the oldest update nobody has confirmed. After a week
 * without updates Telegram may number the next one at random, below the last:
 * asked with the old offset it would never come - an "I'm fine" lost, and the
 * family of somebody who is fine warned. Found in review on 2026-09-30.
 */
@Singleton
public class BuzonTelegram implements LifecycleObject {

    private static final Logger LOGGER = LoggerFactory.getLogger(BuzonTelegram.class);

    private static final int ESPERA = 25;

    /** What one getUpdates returns at most (Telegram's default limit). */
    static final int LOTE = 100;

    /** How the inbox waits after a failed read: {@code Thread::sleep}, except in tests. */
    interface Dormir {
        void dormir(long ms) throws InterruptedException;
    }

    private final boolean activo;
    private final Storage storage;
    private final ClienteTelegram cliente;
    private final Destinatarios destinatarios;
    private final FirmaBoton firma;
    private final Dormir dormir;
    private final Map<String, ReceptorBoton> receptores = new HashMap<>();
    private volatile boolean corriendo;
    private volatile long ultimaLectura;
    private Thread hilo;

    @Inject
    public BuzonTelegram(
            Config config, Storage storage, ClienteTelegram cliente, Destinatarios destinatarios,
            GuardianGolpes guardianGolpes, ViajesFamilia viajesFamilia) {
        this(config.getBoolean(Keys.TELEGRAM_BUZON) && !config.getBoolean(Keys.BROADCAST_SECONDARY),
                storage, cliente, destinatarios,
                new FirmaBoton(config.getString(Keys.NOTIFICATOR_TELEGRAM_KEY, "")),
                List.of(guardianGolpes, viajesFamilia), Thread::sleep);
    }

    BuzonTelegram(
            Storage storage, ClienteTelegram cliente, Destinatarios destinatarios, FirmaBoton firma,
            List<ReceptorBoton> receptores) {
        this(false, storage, cliente, destinatarios, firma, receptores, Thread::sleep);
    }

    BuzonTelegram(
            boolean activo, Storage storage, ClienteTelegram cliente, Destinatarios destinatarios,
            FirmaBoton firma, List<ReceptorBoton> receptores, Dormir dormir) {
        this.activo = activo;
        this.storage = storage;
        this.cliente = cliente;
        this.destinatarios = destinatarios;
        this.firma = firma;
        this.dormir = dormir;
        receptores.forEach(receptor -> this.receptores.put(receptor.accion(), receptor));
    }

    public boolean activo() {
        return activo;
    }

    /** When the inbox last read the bot's updates, or zero if it never did (VigiaSeguridad). */
    public long ultimaLectura() {
        return ultimaLectura;
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
     * One past the last update already handled, so a batch handled right before
     * a restart is not handled again. Only the very first start - no row yet -
     * asks Telegram to forget what somebody wrote to the bot before today, and
     * while Telegram does not answer that it returns null: until 2026-09-30 a
     * failed first start read from 0 and handled the whole backlog. After that,
     * what arrives while Traccar is stopped is read when it comes back.
     */
    Long inicio() {
        Long guardado = leerUltimo();
        if (guardado != null) {
            return guardado;
        }
        // offset -1 returns only the newest update and forgets the rest.
        ClienteTelegram.Lectura lectura = cliente.leer(-1, 0);
        if (!lectura.ok()) {
            return null;
        }
        long desde = siguiente(lectura.updates());
        guardarUltimo(desde);
        return desde;
    }

    private void bucle() {
        int fallos = 0;
        try {
            Long procesados = null;
            while (corriendo && procesados == null) {
                procesados = inicio();
                if (procesados == null) {
                    fallos += 1;
                    dormir.dormir(esperaTrasFallos(fallos));
                }
            }
            fallos = 0;
            long confirmar = 0;
            while (corriendo) {
                try {
                    ClienteTelegram.Lectura lectura = cliente.leer(confirmar, ESPERA);
                    if (!lectura.ok()) {
                        // Refused or unreachable: logged by the client, and waited on
                        // longer each time instead of a loop at full speed.
                        fallos += 1;
                        dormir.dormir(esperaTrasFallos(fallos));
                        continue;
                    }
                    fallos = 0;
                    ultimaLectura = System.currentTimeMillis();
                    confirmar = procesar(lectura.updates(), procesados);
                    if (confirmar != 0 && confirmar != procesados) {
                        procesados = confirmar;
                        guardarUltimo(procesados);
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("Telegram inbox error", e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 1 s, 2 s, 4 s, then 8 s for as long as it lasts: no longer, so an "I'm fine"
     * pressed while Telegram was failing is read seconds after it comes back, not
     * after the two minutes of the countdown (a minute until 2026-09-30).
     */
    static long esperaTrasFallos(int fallos) {
        return 1_000L << Math.min(Math.max(fallos - 1, 0), 3);
    }

    private static long siguiente(JsonNode updates) {
        long siguiente = 0;
        for (JsonNode update : updates) {
            siguiente = Math.max(siguiente, update.path("update_id").asLong() + 1);
        }
        return siguiente;
    }

    /**
     * Handles a batch and returns the offset that confirms it - one past its
     * newest update - or 0 if it was empty. An update just below what was
     * already handled is a batch handled before a restart and never confirmed,
     * and is skipped; one far below is new, numbered at random by Telegram.
     */
    long procesar(JsonNode updates, long procesados) {
        long siguiente = 0;
        for (JsonNode update : updates) {
            Actualizacion actualizacion = Actualizacion.leer(update);
            siguiente = Math.max(siguiente, actualizacion.updateId() + 1);
            if (actualizacion.updateId() < procesados && procesados - actualizacion.updateId() <= LOTE) {
                continue;
            }
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
        if (contacto != null && contacto.getConfirmado() != null && alta.chatId().equals(contacto.getChatId())) {
            // The same sign-up read twice (handled right before a restart, never
            // confirmed to Telegram): already welcomed, nothing to say again.
            return;
        }
        if (contacto == null || !CodigosAlta.vale(contacto, new Date())) {
            cliente.enviar(alta.chatId(), Mensajes.codigoNoValido(), List.of());
            return;
        }
        Date ahora = new Date();
        contacto.setChatId(alta.chatId());
        contacto.setConfirmado(ahora);
        // The code is spent: it expires now, and its hash stays only so that the
        // same sign-up read twice is recognised. After /baja it does not come back.
        contacto.setCaduca(ahora);
        contacto.setRechazado(null);
        storage.updateObject(contacto, new Request(
                new Columns.Include("chatId", "confirmado", "caduca", "rechazado"),
                new Condition.Equals("id", contacto.getId())));
        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", contacto.getDeviceId())));
        String moto = device != null ? device.getName() : "moto";
        String persona = destinatarios.motorista(contacto.getDeviceId())
                .map(User::getName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(null);
        // Without a rider nobody is named: "your contact" read like a person.
        cliente.enviar(alta.chatId(), persona != null
                ? Mensajes.bienvenida(persona, moto) : Mensajes.bienvenidaDeMoto(moto), List.of());
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
