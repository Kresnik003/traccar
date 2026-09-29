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
import org.traccar.model.Geofence;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The places of a bike's riders: their geofences with the attribute
 * {@code sitio: true}. They are read here, and not linked to the bike, because
 * a geofence linked to the bike fires the geofence notifications, and the rider
 * would be told every time they get home. Kept five minutes: this is asked for
 * every position.
 */
@Singleton
public class SitiosPropios {

    private static final long VIGENCIA = 5 * 60_000L;

    private record Guardado(long cuando, List<Sitio> sitios) {
    }

    private final Storage storage;
    private final Destinatarios destinatarios;
    private final LongSupplier reloj;
    private final Map<Long, Guardado> cache = new ConcurrentHashMap<>();

    @Inject
    public SitiosPropios(Storage storage, Destinatarios destinatarios) {
        this(storage, destinatarios, System::currentTimeMillis);
    }

    /** With the clock in the tests' hands: the five minutes are tested, not waited for. */
    SitiosPropios(Storage storage, Destinatarios destinatarios, LongSupplier reloj) {
        this.storage = storage;
        this.destinatarios = destinatarios;
        this.reloj = reloj;
    }

    /** The rider changed: the next position reads the places again. */
    public void olvidar(long deviceId) {
        cache.remove(deviceId);
    }

    public List<Sitio> de(long deviceId) throws StorageException {
        long ahora = reloj.getAsLong();
        Guardado guardado = cache.get(deviceId);
        if (guardado != null && ahora - guardado.cuando() < VIGENCIA) {
            return guardado.sitios();
        }
        List<Sitio> sitios = new ArrayList<>();
        // The rider's places only: another person who sees the bike has a home of
        // their own, and leaving it is not the rider leaving (found in review).
        Optional<User> motorista = destinatarios.motorista(deviceId);
        if (motorista.isPresent()) {
            for (Geofence geofence : storage.getObjects(Geofence.class, new Request(new Columns.All(),
                    new Condition.Permission(User.class, motorista.get().getId(), Geofence.class)))) {
                if (!geofence.getBoolean("sitio")) {
                    continue;
                }
                try {
                    sitios.add(new Sitio(geofence.getId(), geofence.getName(), geofence.getGeometry()));
                } catch (RuntimeException e) {
                    // A geofence whose area does not parse is not a place; the others still are.
                    continue;
                }
            }
        }
        cache.put(deviceId, new Guardado(ahora, List.copyOf(sitios)));
        return sitios;
    }

}
