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
package org.traccar.notification;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.AvisoPendiente;
import org.traccar.model.Event;
import org.traccar.model.Notification;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import java.util.Date;

/**
 * The door into the queue of notifications that could not be delivered.
 *
 * It used to live inside NotificationManager, which left out the one other
 * place that sends: the summaries of NotificationGrouper. A summary that failed
 * was logged and lost, the exact behaviour the queue was written to end. Both
 * now come through here.
 *
 * Only identifiers are stored, never the formatted text: the queue must not
 * become a plain text log of where people were. That has a price for a
 * summary: what is retried is its latest event, and the "(+N avisos iguales)"
 * count is not in the retry. The alert arrives; the count does not.
 *
 * Failing to enqueue is logged and swallowed - a broken queue must not stop
 * the remaining channels.
 */
@Singleton
public class ColaAvisos {

    private static final Logger LOGGER = LoggerFactory.getLogger(ColaAvisos.class);

    private static final int ERROR_MAXIMO = 512;

    private final Storage storage;
    private final int reintentos;
    private final long esperaBase;
    private final long esperaTope;

    @Inject
    public ColaAvisos(Config config, Storage storage) {
        this.storage = storage;
        reintentos = config.getInteger(Keys.NOTIFICATOR_RETRY_ATTEMPTS);
        esperaBase = config.getLong(Keys.NOTIFICATOR_RETRY_DELAY);
        esperaTope = config.getLong(Keys.NOTIFICATOR_RETRY_MAX_DELAY);
    }

    /**
     * Remember a delivery that failed, so the retry task can try it again.
     */
    public void encolar(
            Notification notification, User user, Event event, Position position,
            String notificator, Throwable causa) {
        if (reintentos <= 0) {
            return;
        }
        if (FalloDefinitivo.es(causa)) {
            LOGGER.warn("Notification not queued, a retry would fail the same way evento={} usuario={} canal={}"
                    + " motivo=definitivo", event.getId(), user.getId(), notificator);
            return;
        }
        try {
            AvisoPendiente pendiente = new AvisoPendiente();
            pendiente.setEventId(event.getId());
            pendiente.setPositionId(position != null ? position.getId() : 0);
            pendiente.setUserId(user.getId());
            pendiente.setNotificationId(notification.getId());
            pendiente.setNotificator(notificator);
            // One attempt made: the original send. The retries come on top.
            pendiente.setIntentos(1);
            Date ahora = new Date();
            pendiente.setCreado(ahora);
            pendiente.setProximo(new Date(ahora.getTime()
                    + EsperaReintento.espera(1, esperaBase, esperaTope)));
            pendiente.setError(EsperaReintento.motivo(causa, ERROR_MAXIMO));
            storage.addObject(pendiente, new Request(new Columns.Exclude("id")));
        } catch (Exception e) {
            LOGGER.warn("Could not queue the failed notification", e);
        }
    }

}
