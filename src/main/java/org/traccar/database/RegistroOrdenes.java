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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Orden;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
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
 *
 * A COMMAND STOPS WAITING. The first version let an unanswered command wait
 * forever, and "the oldest one waiting" then meant a command the device ignored
 * last month: every later answer was stamped one command too early, for good.
 * See {@link #elegir} for when a command can no longer be the one answered.
 */
@Singleton
public class RegistroOrdenes {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistroOrdenes.class);

    private static final int RESPUESTA_MAXIMA = 1024;

    /** The width of nx_ordenes.descripcion. A longer one failed the insert. */
    private static final int DESCRIPCION_MAXIMA = 512;

    /** The width of nx_ordenes.tipo. */
    private static final int TIPO_MAXIMO = 128;

    /**
     * How long after leaving for the device a command can still be answered. A
     * Teltonika device answers in seconds; ten minutes covers a slow mobile
     * network without letting a command the device ignored take the next answer.
     */
    static final long ESPERA_RESPUESTA = 10 * 60 * 1000L;

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
            // Cut to the columns: a Traccar command description can be 4000
            // characters, and a longer one than the column lost the receipt.
            orden.setTipo(recortar(tipo, TIPO_MAXIMO));
            orden.setDescripcion(recortar(descripcion, DESCRIPCION_MAXIMA));
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
            List<Orden> recientes = storage.getObjects(Orden.class, new Request(
                    new Columns.All(),
                    new Condition.Equals("deviceid", deviceId),
                    new Order("enviado", true, VENTANA)));
            Orden orden = elegir(recientes, System.currentTimeMillis(), ESPERA_RESPUESTA);
            if (orden == null) {
                // An answer with nothing waiting is normal: the device restarted,
                // or somebody sent the command outside Nordes. It is not an error.
                return false;
            }
            orden.setEntregado(new Date());
            orden.setRespuesta(recortar(respuesta, RESPUESTA_MAXIMA));
            storage.updateObject(orden, new Request(
                    new Columns.Include("entregado", "respuesta"),
                    new Condition.Equals("id", orden.getId())));
            return true;
        } catch (Exception e) {
            LOGGER.warn("Could not record the answer from device {}", deviceId, e);
            return false;
        }
    }

    /**
     * Which of the recent commands of a device an answer arriving now belongs to.
     *
     * Still the oldest one waiting, but only among those that can still be
     * answered:
     *
     *   - Answers come back in order. A command older than one already answered
     *     was skipped by the device and will never be answered.
     *   - A command stops waiting {@code espera} after it left for the device. A
     *     command sent to a connected device left when it was sent. A queued one
     *     left, at the latest, when a later command was sent to the device while
     *     connected: Traccar sends the queue as soon as the device connects. A
     *     queued command with nothing sent live after it may still be in the
     *     queue, so it keeps waiting.
     *
     * Two commands sent in the same second are told apart by id, the order in
     * which they were written. Sorting by time alone kept whatever order the
     * database returned, newest first, and the answer went to the newer one.
     *
     * @param recientes the last commands of one device, in any order
     * @return the command to stamp, or null if none can be the one answered
     */
    static Orden elegir(List<Orden> recientes, long ahora, long espera) {
        List<Orden> ordenadas = recientes.stream()
                .sorted(Comparator.comparing(Orden::getEnviado).thenComparingLong(Orden::getId))
                .toList();
        int desde = 0;
        for (int i = 0; i < ordenadas.size(); i++) {
            if (ordenadas.get(i).getEntregado() != null) {
                desde = i + 1;
            }
        }
        for (int i = desde; i < ordenadas.size(); i++) {
            Orden orden = ordenadas.get(i);
            Long salida = salida(ordenadas, i);
            if (salida == null || ahora - salida <= espera) {
                return orden;
            }
        }
        return null;
    }

    /** When the command at {@code i} left for the device, or null if it may not have yet. */
    private static Long salida(List<Orden> ordenadas, int i) {
        Orden orden = ordenadas.get(i);
        if (!orden.getEncolada()) {
            return orden.getEnviado().getTime();
        }
        for (int j = i + 1; j < ordenadas.size(); j++) {
            if (!ordenadas.get(j).getEncolada()) {
                return ordenadas.get(j).getEnviado().getTime();
            }
        }
        return null;
    }

    private static String recortar(String texto, int maximo) {
        if (texto == null) {
            return null;
        }
        return texto.length() > maximo ? texto.substring(0, maximo) : texto;
    }

}
