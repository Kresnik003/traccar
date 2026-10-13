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
package org.traccar.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.AvisoPendiente;
import org.traccar.model.Event;
import org.traccar.model.Notification;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notification.EsperaReintento;
import org.traccar.notification.FalloDefinitivo;
import org.traccar.notification.NotificatorManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tries again the notifications that could not be delivered.
 *
 * Runs often and does almost nothing: the queue is empty on a healthy day, and
 * the index on the due column means an empty queue costs one indexed lookup a
 * minute.
 *
 * WHAT IT DOES NOT DO is resend an alert that is already stale. A notification
 * held for hours because a channel was down is still worth sending - "your bike
 * was moved at 03:12" matters at 09:00 - but one whose event has been deleted is
 * not, and the foreign key takes those away on its own.
 */
public class TaskAvisosPendientes extends SingleScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskAvisosPendientes.class);

    private static final long PERIODO_SEGUNDOS = 60;

    /**
     * How many are taken per run. A channel that has been down for a day leaves
     * hundreds queued, and sending them all at once is how a rate limit turns a
     * recovery into a second outage.
     */
    private static final int POR_TANDA = 25;

    private static final int ERROR_MAXIMO = 512;

    private final Storage storage;
    private final CacheManager cacheManager;
    private final NotificatorManager notificatorManager;
    private final int reintentos;
    private final long esperaBase;
    private final long esperaTope;
    private final Set<Long> bloqueados = new HashSet<>();

    @Inject
    public TaskAvisosPendientes(
            Config config, Storage storage, CacheManager cacheManager,
            NotificatorManager notificatorManager) {
        this.storage = storage;
        this.cacheManager = cacheManager;
        this.notificatorManager = notificatorManager;
        reintentos = config.getInteger(Keys.NOTIFICATOR_RETRY_ATTEMPTS);
        esperaBase = config.getLong(Keys.NOTIFICATOR_RETRY_DELAY);
        esperaTope = config.getLong(Keys.NOTIFICATOR_RETRY_MAX_DELAY);
        // Read the same way NotificationManager reads it.
        String bloqueadosTexto = config.getString(Keys.NOTIFICATION_BLOCK_USERS);
        if (bloqueadosTexto != null) {
            for (String id : bloqueadosTexto.split(",")) {
                bloqueados.add(Long.parseLong(id));
            }
        }
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        if (reintentos > 0) {
            executor.scheduleAtFixedRate(this, PERIODO_SEGUNDOS, PERIODO_SEGUNDOS, TimeUnit.SECONDS);
        }
    }

    private boolean sigueQueriendolo(User user, Notification notification, String notificator) {
        return !user.getDisabled()
                && !bloqueados.contains(user.getId())
                && notification.getNotificatorsTypes().contains(notificator);
    }

    @Override
    public void run() {
        try {
            // Only one batch is read, oldest due first. Reading every due row and
            // stopping at the batch size in Java loaded the whole backlog of a day
            // long outage every minute to use 25 of them.
            List<AvisoPendiente> vencidos = storage.getObjects(AvisoPendiente.class, new Request(
                    new Columns.All(),
                    new Condition.Compare("proximo", "<=", new Date()),
                    new Order("proximo", false, POR_TANDA)));
            for (AvisoPendiente pendiente : vencidos) {
                intentar(pendiente);
            }
        } catch (Exception e) {
            // The task must survive anything: if it dies, nothing retries ever
            // again and the queue grows in silence.
            LOGGER.warn("Retrying notifications failed", e);
        }
    }

    private void intentar(AvisoPendiente pendiente) {
        try {
            Event event = storage.getObject(Event.class, new Request(
                    new Columns.All(), new Condition.Equals("id", pendiente.getEventId())));
            User user = storage.getObject(User.class, new Request(
                    new Columns.All(), new Condition.Equals("id", pendiente.getUserId())));
            Notification notification = storage.getObject(Notification.class, new Request(
                    new Columns.All(), new Condition.Equals("id", pendiente.getNotificationId())));
            if (event == null || user == null || notification == null) {
                // The event, the user or the rule is gone. There is nothing left
                // to deliver, and keeping the row would retry it forever.
                borrar(pendiente);
                return;
            }
            if (!sigueQueriendolo(user, notification, pendiente.getNotificator())) {
                // The same checks NotificationManager makes before the first send.
                // A member disabled, or taken off a channel, while an alert waited
                // in the queue must not get it anyway because it happened to wait.
                LOGGER.info("Dropping queued notification {}: user {} no longer receives it",
                        pendiente.getId(), user.getId());
                borrar(pendiente);
                return;
            }
            Position position = null;
            if (pendiente.getPositionId() > 0) {
                position = storage.getObject(Position.class, new Request(
                        new Columns.All(), new Condition.Equals("id", pendiente.getPositionId())));
            }
            // THE DEVICE HAS TO BE IN THE CACHE OR THE TEMPLATE DOES NOT RENDER.
            // NotificationFormatter reads it with cacheManager.getObject, and in a
            // background task nothing has loaded it, so $device is null and Velocity
            // leaves the literal: the retried mail arrived with the subject
            // "$esc.html($device.name): en movimiento" while the same alert sent the
            // normal way said "Test 4: en movimiento". NotificationManager pins it
            // the same way around its own sends; this is that pattern.
            //
            // Releasing right after the call is safe because sendAsync formats the
            // message synchronously and only the delivery is asynchronous.
            var key = new Object();
            try {
                cacheManager.addDevice(event.getDeviceId(), key);
                // Deliberately NOT through the grouper: this one already waited, and
                // holding it again for a window would be a second delay on top.
                notificatorManager.getNotificator(pendiente.getNotificator())
                        .sendAsync(notification, user, event, position)
                        .thenRun(() -> borrar(pendiente))
                        .exceptionally(throwable -> {
                            aplazar(pendiente, throwable);
                            return null;
                        });
            } finally {
                cacheManager.removeDevice(event.getDeviceId(), key);
            }
        } catch (Exception e) {
            aplazar(pendiente, e);
        }
    }

    private void aplazar(AvisoPendiente pendiente, Throwable causa) {
        try {
            int intentos = pendiente.getIntentos() + 1;
            if (FalloDefinitivo.es(causa)) {
                LOGGER.warn("Giving up on notification {}, a retry would fail the same way: {}",
                        pendiente.getId(), EsperaReintento.motivo(causa, ERROR_MAXIMO));
                borrar(pendiente);
                return;
            }
            if (!EsperaReintento.quedanReintentos(intentos, reintentos)) {
                LOGGER.warn("Giving up on notification {} after {} attempts: {}",
                        pendiente.getId(), intentos, EsperaReintento.motivo(causa, ERROR_MAXIMO));
                borrar(pendiente);
                return;
            }
            pendiente.setIntentos(intentos);
            pendiente.setProximo(new Date(System.currentTimeMillis()
                    + EsperaReintento.espera(intentos, esperaBase, esperaTope)));
            pendiente.setError(EsperaReintento.motivo(causa, ERROR_MAXIMO));
            storage.updateObject(pendiente, new Request(
                    new Columns.Include("intentos", "proximo", "error"),
                    new Condition.Equals("id", pendiente.getId())));
        } catch (Exception e) {
            LOGGER.warn("Could not reschedule notification {}", pendiente.getId(), e);
        }
    }

    private void borrar(AvisoPendiente pendiente) {
        try {
            storage.removeObject(AvisoPendiente.class, new Request(
                    new Condition.Equals("id", pendiente.getId())));
        } catch (Exception e) {
            LOGGER.warn("Could not remove notification {} from the queue", pendiente.getId(), e);
        }
    }

}
