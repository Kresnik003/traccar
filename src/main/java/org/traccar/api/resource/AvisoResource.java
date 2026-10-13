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
package org.traccar.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.traccar.api.BaseResource;
import org.traccar.model.Device;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;
import javax.sql.DataSource;

/**
 * The inbox: every notification the user was sent, read or not (improvement 93).
 *
 * ONLY WHAT THE USER CAN STILL SEE. A notification says where a bike was and
 * what happened to it. If the user lost access to the device since - the bike
 * was sold, the member left the club - its old notifications stop showing, the
 * same as its positions do. The rows stay, and come back if access does.
 *
 * Marking as read goes "up to the last one seen", not "everything": a
 * notification that arrived after the list was drawn is one nobody has read.
 */
@Path("avisos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AvisoResource extends BaseResource {

    static final int LIMITE_POR_DEFECTO = 50;

    /** Without a cap, years of notifications would come back in one answer. */
    static final int LIMITE_MAXIMO = 200;

    @Inject
    private DataSource dataSource;

    @Inject
    private ObjectMapper objectMapper;

    static int limite(Integer pedido) {
        if (pedido == null || pedido <= 0) {
            return LIMITE_POR_DEFECTO;
        }
        return Math.min(pedido, LIMITE_MAXIMO);
    }

    /**
     * The unread count, over the devices the user can still see.
     *
     * @param porEquipo unread notifications per device id
     */
    static long noLeidos(Map<Long, Long> porEquipo, LongPredicate puedeVer) {
        return porEquipo.entrySet().stream()
                .filter(entry -> puedeVer.test(entry.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    private LongPredicate permisos() {
        long userId = getUserId();
        Map<Long, Boolean> vistos = new HashMap<>();
        return deviceId -> vistos.computeIfAbsent(deviceId, id -> {
            try {
                permissionsService.checkPermission(Device.class, userId, id);
                return true;
            } catch (SecurityException | StorageException e) {
                return false;
            }
        });
    }

    private boolean temporal() throws StorageException {
        return permissionsService.getUser(getUserId()).getTemporary();
    }

    private long contar(LongPredicate puedeVer) throws SQLException {
        Map<Long, Long> porEquipo = new HashMap<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT e.deviceid, COUNT(*) FROM nx_avisos a JOIN tc_events e ON e.id = a.eventid"
                             + " WHERE a.userid = ? AND a.leido IS NULL GROUP BY e.deviceid")) {
            statement.setLong(1, getUserId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    porEquipo.put(rows.getLong(1), rows.getLong(2));
                }
            }
        }
        return noLeidos(porEquipo, puedeVer);
    }

    private Object atributos(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * The newest notifications first.
     *
     * @param antes only notifications older than this id, to page backwards:
     *              pass the {@code siguiente} of the previous answer
     */
    @GET
    public Map<String, Object> get(
            @QueryParam("limite") Integer limite, @QueryParam("antes") Long antes) throws StorageException {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> avisos = new ArrayList<>();
        if (temporal()) {
            result.put("noLeidos", 0);
            result.put("avisos", avisos);
            result.put("siguiente", null);
            return result;
        }
        int tope = limite(limite);
        LongPredicate puedeVer = permisos();
        Long ultimo = null;
        int leidas = 0;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT a.id, a.creado, a.leido, e.id, e.type, e.eventtime, e.deviceid, e.positionid,"
                             + " e.geofenceid, e.maintenanceid, e.attributes"
                             + " FROM nx_avisos a JOIN tc_events e ON e.id = a.eventid"
                             + " WHERE a.userid = ?" + (antes != null ? " AND a.id < ?" : "")
                             + " ORDER BY a.id DESC LIMIT ?")) {
            int i = 1;
            statement.setLong(i++, getUserId());
            if (antes != null) {
                statement.setLong(i++, antes);
            }
            statement.setInt(i, tope);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    leidas++;
                    ultimo = rows.getLong(1);
                    long deviceId = rows.getLong(7);
                    if (!puedeVer.test(deviceId)) {
                        continue;
                    }
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("id", rows.getLong(4));
                    event.put("type", rows.getString(5));
                    event.put("eventTime", rows.getTimestamp(6));
                    event.put("deviceId", deviceId);
                    event.put("positionId", rows.getLong(8));
                    event.put("geofenceId", rows.getLong(9));
                    event.put("maintenanceId", rows.getLong(10));
                    event.put("attributes", atributos(rows.getString(11)));
                    Map<String, Object> aviso = new LinkedHashMap<>();
                    aviso.put("id", rows.getLong(1));
                    aviso.put("creado", rows.getTimestamp(2));
                    aviso.put("leido", rows.getTimestamp(3));
                    aviso.put("event", event);
                    avisos.add(aviso);
                }
            }
            result.put("noLeidos", contar(puedeVer));
        } catch (SQLException e) {
            throw new StorageException(e);
        }
        result.put("avisos", avisos);
        // The cursor is the last row READ, not the last one returned: rows of a
        // device the user no longer sees are skipped, and paging from the last
        // returned one would ask for them again.
        result.put("siguiente", leidas == tope ? ultimo : null);
        return result;
    }

    @Path("cuenta")
    @GET
    public Map<String, Object> cuenta() throws StorageException {
        if (temporal()) {
            return Map.of("noLeidos", 0);
        }
        try {
            return Map.of("noLeidos", contar(permisos()));
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    private void marcar(long id, boolean leido) throws StorageException {
        if (temporal()) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        try (Connection connection = dataSource.getConnection()) {
            // Only the user's own rows. Someone else's id answers the same as
            // one that does not exist, so ids cannot be probed.
            long deviceId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT e.deviceid FROM nx_avisos a JOIN tc_events e ON e.id = a.eventid"
                            + " WHERE a.id = ? AND a.userid = ?")) {
                statement.setLong(1, id);
                statement.setLong(2, getUserId());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new WebApplicationException(Response.Status.NOT_FOUND);
                    }
                    deviceId = rows.getLong(1);
                }
            }
            if (!permisos().test(deviceId)) {
                throw new WebApplicationException(Response.Status.NOT_FOUND);
            }
            try (PreparedStatement statement = connection.prepareStatement(leido
                    ? "UPDATE nx_avisos SET leido = ? WHERE id = ? AND userid = ? AND leido IS NULL"
                    : "UPDATE nx_avisos SET leido = NULL WHERE id = ? AND userid = ?")) {
                int i = 1;
                if (leido) {
                    statement.setTimestamp(i++, new Timestamp(System.currentTimeMillis()));
                }
                statement.setLong(i++, id);
                statement.setLong(i, getUserId());
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    @Path("{id}/leido")
    @POST
    public Response leer(@PathParam("id") long id) throws StorageException {
        marcar(id, true);
        return Response.noContent().build();
    }

    @Path("{id}/leido")
    @DELETE
    public Response desleer(@PathParam("id") long id) throws StorageException {
        marcar(id, false);
        return Response.noContent().build();
    }

    /**
     * Mark as read everything up to the newest notification the user has seen.
     *
     * @param hasta the id of the newest notification in the list on screen.
     *              Required: without it, one that arrived a second ago would be
     *              marked as read without ever having been shown.
     */
    @Path("leidos")
    @POST
    public Map<String, Object> leerTodos(@QueryParam("hasta") Long hasta) throws StorageException {
        if (temporal()) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        if (hasta == null || hasta <= 0) {
            throw new WebApplicationException(Response.Status.BAD_REQUEST);
        }
        LongPredicate puedeVer = permisos();
        List<Long> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT a.id, e.deviceid FROM nx_avisos a JOIN tc_events e ON e.id = a.eventid"
                            + " WHERE a.userid = ? AND a.leido IS NULL AND a.id <= ?")) {
                statement.setLong(1, getUserId());
                statement.setLong(2, hasta);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        if (puedeVer.test(rows.getLong(2))) {
                            ids.add(rows.getLong(1));
                        }
                    }
                }
            }
            Timestamp ahora = new Timestamp(System.currentTimeMillis());
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE nx_avisos SET leido = ? WHERE id = ? AND userid = ? AND leido IS NULL")) {
                for (long id : ids) {
                    statement.setTimestamp(1, ahora);
                    statement.setLong(2, id);
                    statement.setLong(3, getUserId());
                    statement.addBatch();
                }
                if (!ids.isEmpty()) {
                    statement.executeBatch();
                }
            }
            return Map.of("marcados", ids.size(), "noLeidos", contar(puedeVer));
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

}
