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
package org.traccar.database;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Orden;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * Records commands sent to a device and matches the device's answer back to one.
 *
 * HOW THE MATCH WORKS, AND WHY IT IS HONEST. A Teltonika reply carries no
 * command id: it comes back as a result string on a position. The answer is
 * given to the OLDEST command of that device still waiting.
 *
 * That looks fragile and is not, because of the device itself: it closes the
 * connection after answering a single command, so queueing two getparam makes
 * the second go out and never be answered. In practice a device never has more
 * than one command outstanding. If it ever did, oldest-first is what a person
 * reading the list would assume.
 *
 * Nothing here is allowed to break sending. A command that cannot be recorded
 * still leaves; losing the receipt is bad, not sending is worse.
 */
@Singleton
public class RegistroOrdenes {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistroOrdenes.class);

    private static final int RESPUESTA_MAXIMA = 1024;

    /** How many recent commands of a device to look at when an answer arrives. */
    private static final int VENTANA = 20;

    private final Storage storage;

    @Inject
    public RegistroOrdenes(Storage storage) {
        this.storage = storage;
    }

    /**
     * Write down that a command left.
     *
     * @param encolada whether the device was offline and Traccar queued it, which
     *                 is not the same as "sent and unanswered"
     */
    public void anotar(long deviceId, long userId, String tipo, String descripcion, boolean encolada) {
        try {
            Orden orden = new Orden();
            orden.setDeviceId(deviceId);
            orden.setUserId(userId);
            orden.setTipo(tipo);
            orden.setDescripcion(descripcion);
            orden.setEnviado(new Date());
            orden.setEncolada(encolada);
            storage.addObject(orden, new Request(new Columns.Exclude("id")));
        } catch (Exception e) {
            LOGGER.warn("Could not record the command sent to device {}", deviceId, e);
        }
    }

    /**
     * Stamp the device's answer on the command that was waiting for it.
     *
     * @return true if an outstanding command was found and stamped
     */
    public boolean contestar(long deviceId, String respuesta) {
        try {
            // The storage query language has no IS NULL, and adding one would
            // touch Traccar query classes for this alone. The last few commands
            // of the device are fetched instead and the unanswered ones filtered
            // here: the window is bounded, and the device never has more than one
            // command outstanding anyway.
            List<Orden> pendientes = storage.getObjects(Orden.class, new Request(
                    new Columns.All(),
                    new Condition.Equals("deviceid", deviceId),
                    new Order("enviado", true, VENTANA)))
                    .stream()
                    .filter(orden -> orden.getEntregado() == null)
                    .sorted(Comparator.comparing(Orden::getEnviado))
                    .toList();
            if (pendientes.isEmpty()) {
                // An answer with nothing waiting is normal: the device restarted,
                // or somebody sent the command outside Nordes. It is not an error.
                return false;
            }
            Orden orden = pendientes.get(0);
            orden.setEntregado(new Date());
            orden.setRespuesta(recortar(respuesta));
            storage.updateObject(orden, new Request(
                    new Columns.Include("entregado", "respuesta"),
                    new Condition.Equals("id", orden.getId())));
            return true;
        } catch (Exception e) {
            LOGGER.warn("Could not record the answer from device {}", deviceId, e);
            return false;
        }
    }

    private static String recortar(String texto) {
        if (texto == null) {
            return null;
        }
        return texto.length() > RESPUESTA_MAXIMA ? texto.substring(0, RESPUESTA_MAXIMA) : texto;
    }

}
