/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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
import org.traccar.helper.model.UserUtil;
import org.traccar.model.Event;
import org.traccar.model.Notification;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notificators.Notificator;
import org.traccar.session.cache.CacheManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Folds repeated notifications into one summary (improvement 58).
 *
 * The night of the tests there were 61 alarms, one message each. With
 * notificator.groupWindow set, the first notification of a kind goes out at
 * once, as always; repeats of the same kind, for the same user, channel,
 * notification and device, are held until the window closes and then leave as
 * one message that says how many there were and between which times.
 *
 * Off by default. A notificator that cannot send a ready-made message (the
 * web one formats its own) receives the latest event instead, marked with how
 * many it stands for.
 */
@Singleton
public class NotificationGrouper {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationGrouper.class);

    public static final String ATTRIBUTE_COUNT = "agrupados";

    record Held(String type, Notificator notificator, Notification notification, User user, Event event,
                Position position) {
    }

    private final long windowMillis;
    private final NotificationWindow<Held> window;
    private final NotificationFormatter formatter;
    private final CacheManager cacheManager;
    private final ColaAvisos colaAvisos;

    @Inject
    public NotificationGrouper(
            Config config, NotificationFormatter formatter, CacheManager cacheManager, ColaAvisos colaAvisos) {
        this.windowMillis = TimeUnit.SECONDS.toMillis(config.getLong(Keys.NOTIFICATOR_GROUP_WINDOW));
        this.window = new NotificationWindow<>(windowMillis);
        this.formatter = formatter;
        this.cacheManager = cacheManager;
        this.colaAvisos = colaAvisos;
        if (windowMillis > 0) {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "notification-grouper");
                thread.setDaemon(true);
                return thread;
            });
            long period = Math.max(1000, Math.min(5000, windowMillis / 4));
            executor.scheduleAtFixedRate(this::flush, period, period, TimeUnit.MILLISECONDS);
        }
    }

    static String key(String type, Notification notification, User user, Event event) {
        return user.getId() + ":" + type + ":" + notification.getId() + ":" + event.getDeviceId() + ":"
                + event.getType() + ":" + event.getString(Position.KEY_ALARM, "") + ":" + event.getGeofenceId();
    }

    private final VentanaAccidente accidentes = new VentanaAccidente();

    public CompletableFuture<Void> send(
            String type, Notificator notificator, Notification notification, User user, Event event,
            Position position) {
        // One accident, one notification (improvement 9): the pieces of the same
        // crash that arrive later are absorbed, not summarised.
        if (VentanaAccidente.esAccidente(event)) {
            if (accidentes.enviar(user.getId(), type, event.getDeviceId(), System.currentTimeMillis())) {
                return notificator.sendAsync(notification, user, event, position);
            }
            LOGGER.info("Accident of device {} already notified to user {} on {}, absorbed",
                    event.getDeviceId(), user.getId(), type);
            return CompletableFuture.completedFuture(null);
        }
        if (windowMillis <= 0
                || window.offer(key(type, notification, user, event), System.currentTimeMillis(),
                        new Held(type, notificator, notification, user, event, position))) {
            return notificator.sendAsync(notification, user, event, position);
        }
        return CompletableFuture.completedFuture(null);
    }

    static String prefix(int count, long first, long last, TimeZone timeZone) {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm");
        format.setTimeZone(timeZone);
        String from = format.format(new Date(first));
        String to = format.format(new Date(last));
        String times = from.equals(to) ? "a las " + from : "entre las " + from + " y las " + to;
        return count == 1
                ? "(+1 aviso igual " + times + ") "
                : "(+" + count + " avisos iguales " + times + ") ";
    }

    private void flush() {
        flush(System.currentTimeMillis());
    }

    void flush(long now) {
        for (var summary : window.flush(now)) {
            Held held = summary.latest();
            try {
                TimeZone timeZone = UserUtil.getTimezone(cacheManager.getServer(), held.user());
                String prefix = prefix(summary.count(), summary.first(), summary.last(), timeZone);
                NotificationMessage message;
                try {
                    message = formatter.formatMessage(held.notification(), held.user(), held.event(), held.position());
                } catch (RuntimeException e) {
                    message = null;
                }
                CompletableFuture<Void> sent;
                try {
                    if (message == null) {
                        throw new UnsupportedOperationException();
                    }
                    sent = held.notificator().sendAsync(held.user(), new NotificationMessage(
                            message.subject() != null ? prefix + message.subject() : null,
                            message.digest() != null ? prefix + message.digest() : null,
                            message.body() != null ? prefix + message.body() : null,
                            message.priority()), held.event(), held.position());
                } catch (UnsupportedOperationException e) {
                    Event copy = new Event();
                    copy.setId(held.event().getId());
                    copy.setDeviceId(held.event().getDeviceId());
                    copy.setType(held.event().getType());
                    copy.setEventTime(held.event().getEventTime());
                    copy.setPositionId(held.event().getPositionId());
                    copy.setGeofenceId(held.event().getGeofenceId());
                    copy.setMaintenanceId(held.event().getMaintenanceId());
                    copy.getAttributes().putAll(held.event().getAttributes());
                    copy.set(ATTRIBUTE_COUNT, summary.count());
                    sent = held.notificator().sendAsync(held.notification(), held.user(), copy, held.position());
                }
                sent.exceptionally(throwable -> {
                    LOGGER.warn("Grouped notification failed", throwable);
                    // A summary is a notification too. It used to be logged and
                    // lost here while a single alert that failed was queued.
                    queue(held, throwable);
                    return null;
                });
                LOGGER.info("Grouped notification: {} held for user {} on {}",
                        summary.count(), held.user().getId(), held.type());
            } catch (RuntimeException e) {
                LOGGER.warn("Grouped notification failed", e);
                queue(held, e);
            }
        }
    }

    private void queue(Held held, Throwable cause) {
        if (colaAvisos != null) {
            colaAvisos.encolar(held.notification(), held.user(), held.event(), held.position(), held.type(), cause);
        }
    }

}
