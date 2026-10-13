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
package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.model.Device;
import org.traccar.model.Quedada;
import org.traccar.model.QuedadaEquipo;
import org.traccar.model.QuedadaUsuario;
import org.traccar.model.Ruta;
import org.traccar.model.User;
import org.traccar.quedadas.Convocatorias;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Group rides, who watches them and which trackers take part.
 *
 * Visibility has two levels. The creator (or an administrator) sees and
 * changes a ride; a user listed in nx_quedada_usuario sees it and its
 * participants but cannot change anything. Anyone else gets the same 404 as
 * for a ride that does not exist.
 *
 * Adding a tracker requires permission on that tracker. Without that check a
 * ride would be a way to reach any device on the server: put it in your ride,
 * and whatever the ride shows, it shows to you.
 *
 * Times belong to the environment that produced them, so once a ride has
 * started its environment (type, route, circuit and the frozen description of
 * the timing setup) can no longer change, and a finished ride cannot change at
 * all.
 */
@Path("quedadas")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QuedadaResource extends BaseResource {

    @Inject
    private DataSource dataSource;

    @Inject
    private Convocatorias convocatorias;

    private final Convocatorias.Permiso permiso =
            (userId, deviceId) -> permissionsService.checkPermission(Device.class, userId, deviceId);

    /**
     * What goes out to the caller: the public code only to its organizer (or an
     * administrator), and what the caller may do with the ride.
     */
    private Quedada paraElQueMira(Quedada quedada) throws StorageException {
        if (quedada.getCreadorId() != getUserId() && !esAdmin()) {
            quedada.setConvocatoria(null);
        }
        quedada.setCapacidades(Map.of(
                "parada", convocatorias.puedePedirParada(getUserId(), quedada, permiso)));
        return quedada;
    }

    private static final List<String> MODALIDADES = List.of("carrera", "motoclub", "rider");
    private static final List<String> TIPOS = List.of("ruta", "circuito", "libre");
    private static final List<String> ESTADOS = List.of("prevista", "en_marcha", "terminada");

    private boolean esAdmin() throws StorageException {
        return !permissionsService.notAdmin(getUserId());
    }

    private Quedada cargar(long id) throws StorageException {
        return storage.getObject(Quedada.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
    }

    private boolean mira(long quedadaId) throws StorageException {
        return storage.getObject(QuedadaUsuario.class, new Request(
                new Columns.Exclude("id"),
                new Condition.And(
                        new Condition.Equals("quedadaId", quedadaId),
                        new Condition.Equals("userId", getUserId())))) != null;
    }

    /** A ride the caller may see, or 404 - the same for foreign and missing. */
    private Quedada visible(long id) throws StorageException {
        Quedada quedada = cargar(id);
        if (quedada != null && (quedada.getCreadorId() == getUserId() || esAdmin() || mira(id))) {
            return quedada;
        }
        throw new WebApplicationException(Response.Status.NOT_FOUND);
    }

    /**
     * A ride the caller may change.
     *
     * A watcher already knows the ride exists, so for them a refusal is a 403
     * and not a 404: hiding what they can already see protects nothing.
     */
    private Quedada propia(long id) throws StorageException {
        Quedada quedada = visible(id);
        if (quedada.getCreadorId() != getUserId() && !esAdmin()) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        return quedada;
    }

    private static void noTerminada(Quedada quedada) {
        if ("terminada".equals(quedada.getEstado())) {
            throw new IllegalArgumentException("A finished ride cannot change");
        }
    }

    private void validar(Quedada q, long dueno) throws StorageException {
        if (q.getNombre() == null || q.getNombre().isBlank()) {
            throw new IllegalArgumentException("nombre is required");
        }
        if (!MODALIDADES.contains(q.getModalidad())) {
            throw new IllegalArgumentException("modalidad must be one of " + MODALIDADES);
        }
        if (!TIPOS.contains(q.getTipo())) {
            throw new IllegalArgumentException("tipo must be one of " + TIPOS);
        }
        if (!ESTADOS.contains(q.getEstado())) {
            throw new IllegalArgumentException("estado must be one of " + ESTADOS);
        }
        if (q.getInicio() == null) {
            throw new IllegalArgumentException("inicio is required");
        }
        Convocatorias.validar(q);
        if ("carrera".equals(q.getModalidad()) && !"circuito".equals(q.getTipo())) {
            throw new IllegalArgumentException("A race is held on a circuit");
        }
        boolean hayCircuito = q.getCircuito() != null && !q.getCircuito().isBlank();
        if ("circuito".equals(q.getTipo()) != hayCircuito) {
            throw new IllegalArgumentException("circuito goes with tipo circuito, and only with it");
        }
        if ("ruta".equals(q.getTipo())) {
            /*
             * The route has to be the owner's own. Pointing a ride at someone
             * else's route would both expose that route to the ride's watchers
             * and tell the caller that the id exists.
             */
            Ruta ruta = q.getRutaId() > 0 ? storage.getObject(Ruta.class, new Request(
                    new Columns.Include("id"),
                    new Condition.And(
                            new Condition.Equals("id", q.getRutaId()),
                            new Condition.Equals("creadorId", dueno)))) : null;
            if (ruta == null) {
                throw new IllegalArgumentException("rutaId must be a route of the ride's owner");
            }
        } else if (q.getRutaId() != 0) {
            throw new IllegalArgumentException("rutaId goes with tipo ruta, and only with it");
        }
    }

    @GET
    public List<Quedada> get() throws StorageException {
        List<Quedada> todas = new ArrayList<>();
        for (Quedada quedada : lista()) {
            todas.add(paraElQueMira(quedada));
        }
        return todas;
    }

    private List<Quedada> lista() throws StorageException {
        if (esAdmin()) {
            return storage.getObjects(Quedada.class, new Request(new Columns.All()));
        }
        List<Quedada> resultado = new ArrayList<>(storage.getObjects(Quedada.class, new Request(
                new Columns.All(), new Condition.Equals("creadorId", getUserId()))));
        for (QuedadaUsuario enlace : storage.getObjects(QuedadaUsuario.class, new Request(
                new Columns.Exclude("id"), new Condition.Equals("userId", getUserId())))) {
            if (resultado.stream().noneMatch(q -> q.getId() == enlace.getQuedadaId())) {
                Quedada quedada = cargar(enlace.getQuedadaId());
                if (quedada != null) {
                    resultado.add(quedada);
                }
            }
        }
        return resultado;
    }

    @Path("{id}")
    @GET
    public Quedada getSingle(@PathParam("id") long id) throws StorageException {
        return paraElQueMira(visible(id));
    }

    @POST
    public Response add(Quedada entity) throws StorageException {
        permissionsService.checkEdit(getUserId(), entity, true, false);
        // The owner comes from the session, never from the body.
        entity.setCreadorId(getUserId());
        // The public link is made on purpose (POST .../convocatoria), never
        // smuggled in with the body.
        entity.setConvocatoria(null);
        if (entity.getEstado() == null) {
            entity.setEstado("prevista");
        }
        validar(entity, getUserId());
        entity.setId(storage.addObject(entity, new Request(new Columns.Exclude("id"))));
        return Response.ok(entity).build();
    }

    @Path("{id}")
    @PUT
    public Response update(@PathParam("id") long id, Quedada entity) throws StorageException {
        Quedada actual = propia(id);
        noTerminada(actual);
        entity.setId(id);
        entity.setCreadorId(actual.getCreadorId());
        // Neither renewed nor removed by a plain save: a stale copy of the ride
        // in the browser would otherwise kill the link sent to the group.
        entity.setConvocatoria(actual.getConvocatoria());
        if (entity.getEstado() == null) {
            entity.setEstado(actual.getEstado());
        }
        if (ESTADOS.indexOf(entity.getEstado()) < ESTADOS.indexOf(actual.getEstado())) {
            throw new IllegalArgumentException("A ride does not go back to an earlier state");
        }
        if (!"prevista".equals(actual.getEstado())
                && (!Objects.equals(entity.getTipo(), actual.getTipo())
                || entity.getRutaId() != actual.getRutaId()
                || !Objects.equals(entity.getCircuito(), actual.getCircuito())
                || !Objects.equals(entity.getEntorno(), actual.getEntorno())
                || !Objects.equals(entity.getEntornohash(), actual.getEntornohash()))) {
            /*
             * Changing the ground under a ride that has already run would turn
             * its times into numbers from somewhere else. A different setup is
             * a different ride, with new times.
             */
            throw new IllegalArgumentException("The environment of a started ride cannot change");
        }
        validar(entity, actual.getCreadorId());
        storage.updateObject(entity, new Request(
                new Columns.Exclude("id"), new Condition.Equals("id", id)));
        return Response.ok(entity).build();
    }

    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws StorageException {
        propia(id);
        storage.removeObject(Quedada.class, new Request(new Condition.Equals("id", id)));
        return Response.noContent().build();
    }

    /**
     * Makes (or renews) the public link of a ride. The old code stops working:
     * that is how a leaked link is withdrawn.
     */
    @Path("{id}/convocatoria")
    @POST
    public Map<String, String> convocar(@PathParam("id") long id) throws StorageException {
        Quedada quedada = propia(id);
        noTerminada(quedada);
        quedada.setConvocatoria(convocatorias.nuevoCodigo());
        storage.updateObject(quedada, new Request(
                new Columns.Include("convocatoria"), new Condition.Equals("id", id)));
        return Map.of("codigo", quedada.getConvocatoria());
    }

    @Path("{id}/convocatoria")
    @DELETE
    public Response desconvocar(@PathParam("id") long id) throws StorageException {
        Quedada quedada = propia(id);
        quedada.setConvocatoria(null);
        storage.updateObject(quedada, new Request(
                new Columns.Include("convocatoria"), new Condition.Equals("id", id)));
        return Response.noContent().build();
    }

    /** "I need to stop" (improvement 112), from a rider of the running ride. */
    @Path("{id}/parada")
    @POST
    public Map<String, Integer> parada(@PathParam("id") long id) throws StorageException {
        Quedada quedada = visible(id);
        try {
            return Map.of("avisados", convocatorias.parada(getUserId(), quedada, permiso));
        } catch (Convocatorias.DemasiadoPronto e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.TOO_MANY_REQUESTS);
        } catch (SecurityException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.FORBIDDEN);
        }
    }

    @Path("{id}/participantes")
    @GET
    public Map<String, Object> participantes(@PathParam("id") long id) throws StorageException {
        visible(id);
        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("usuarios", storage.getObjects(QuedadaUsuario.class, new Request(
                new Columns.Exclude("id"), new Condition.Equals("quedadaId", id)))
                .stream().map(QuedadaUsuario::getUserId).toList());
        resultado.put("equipos", storage.getObjects(QuedadaEquipo.class, new Request(
                new Columns.Exclude("id"), new Condition.Equals("quedadaId", id)))
                .stream().map(e -> {
                    Map<String, Object> fila = new LinkedHashMap<>();
                    fila.put("deviceId", e.getDeviceId());
                    fila.put("dorsal", e.getDorsal());
                    return fila;
                }).toList());
        return resultado;
    }

    @Path("{id}/usuarios/{userId}")
    @POST
    public Response addUsuario(
            @PathParam("id") long id, @PathParam("userId") long userId) throws StorageException {
        noTerminada(propia(id));
        if (storage.getObject(User.class, new Request(
                new Columns.Include("id"), new Condition.Equals("id", userId))) == null) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        if (storage.getObject(QuedadaUsuario.class, new Request(
                new Columns.Exclude("id"),
                new Condition.And(
                        new Condition.Equals("quedadaId", id),
                        new Condition.Equals("userId", userId)))) == null) {
            QuedadaUsuario enlace = new QuedadaUsuario();
            enlace.setQuedadaId(id);
            enlace.setUserId(userId);
            storage.addObject(enlace, new Request(new Columns.Exclude("id")));
        }
        return Response.noContent().build();
    }

    @Path("{id}/usuarios/{userId}")
    @DELETE
    public Response removeUsuario(
            @PathParam("id") long id, @PathParam("userId") long userId) throws StorageException {
        noTerminada(propia(id));
        storage.removeObject(QuedadaUsuario.class, new Request(new Condition.And(
                new Condition.Equals("quedadaId", id),
                new Condition.Equals("userId", userId))));
        return Response.noContent().build();
    }

    @Path("{id}/equipos")
    @POST
    public Response addEquipo(@PathParam("id") long id, QuedadaEquipo entity) throws StorageException {
        noTerminada(propia(id));
        /*
         * Permission on the tracker, checked for the caller. This is the check
         * that keeps a ride from being a way into someone else's device.
         */
        permissionsService.checkPermission(Device.class, getUserId(), entity.getDeviceId());
        String dorsal = entity.getDorsal() == null || entity.getDorsal().isBlank()
                ? null : entity.getDorsal().trim();
        for (QuedadaEquipo existente : storage.getObjects(QuedadaEquipo.class, new Request(
                new Columns.Exclude("id"), new Condition.Equals("quedadaId", id)))) {
            if (existente.getDeviceId() == entity.getDeviceId()) {
                throw new IllegalArgumentException("That tracker is already in the ride");
            }
            if (dorsal != null && dorsal.equals(existente.getDorsal())) {
                // Two riders with the same number make the results unreadable.
                throw new IllegalArgumentException("Number " + dorsal + " is already taken");
            }
        }
        entity.setQuedadaId(id);
        entity.setDorsal(dorsal);
        storage.addObject(entity, new Request(new Columns.Exclude("id")));
        return Response.ok(entity).build();
    }

    @Path("{id}/equipos/{deviceId}")
    @DELETE
    public Response removeEquipo(
            @PathParam("id") long id, @PathParam("deviceId") long deviceId) throws StorageException {
        noTerminada(propia(id));
        storage.removeObject(QuedadaEquipo.class, new Request(new Condition.And(
                new Condition.Equals("quedadaId", id),
                new Condition.Equals("deviceId", deviceId))));
        return Response.noContent().build();
    }

    /**
     * The classification of a ride, as an ordered table (improvement 51).
     *
     * The times are worked out where the laps are known - from the positions,
     * with the circuit's finish line and door radius - and stored here so they
     * no longer live in whichever browser tab was open. Anyone who can see the
     * ride can read them; only the ride's owner writes them.
     */
    public record Resultado(long deviceId, String dorsal, int vueltas, Integer mejorMs,
                            Integer mejorNumero, Long totalMs, int puesto) {
    }

    @Path("{id}/resultados")
    @GET
    public List<Resultado> resultados(@PathParam("id") long id) throws StorageException {
        visible(id);
        List<Resultado> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT deviceid, dorsal, vueltas, mejorms, mejornumero, totalms, puesto"
                             + " FROM nx_resultados WHERE quedadaid = ? ORDER BY puesto")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new Resultado(
                            rows.getLong(1), rows.getString(2), rows.getInt(3),
                            (Integer) rows.getObject(4), (Integer) rows.getObject(5),
                            (Long) rows.getObject(6), rows.getInt(7)));
                }
            }
        } catch (SQLException e) {
            throw new StorageException(e);
        }
        return result;
    }

    /**
     * Stores the classification of a ride, replacing what was there.
     *
     * Only trackers that are in the ride count: a result for a device that
     * never took part would let a table name a bike the ride does not include.
     * The number is copied, not looked up, so a result read a year later keeps
     * the numbers of the day it was run.
     */
    @Path("{id}/resultados")
    @PUT
    public Response guardarResultados(
            @PathParam("id") long id, List<Resultado> resultados) throws StorageException {
        propia(id);
        Map<Long, String> enLaQuedada = new LinkedHashMap<>();
        for (QuedadaEquipo equipo : storage.getObjects(QuedadaEquipo.class, new Request(
                new Columns.Exclude("id"), new Condition.Equals("quedadaId", id)))) {
            enLaQuedada.put(equipo.getDeviceId(), equipo.getDorsal());
        }
        for (Resultado r : resultados) {
            if (!enLaQuedada.containsKey(r.deviceId())) {
                throw new IllegalArgumentException(
                        "Device " + r.deviceId() + " is not in the ride");
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM nx_resultados WHERE quedadaid = ?")) {
                statement.setLong(1, id);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO nx_resultados"
                            + " (quedadaid, deviceid, dorsal, vueltas, mejorms, mejornumero, totalms, puesto)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (Resultado r : resultados) {
                    statement.setLong(1, id);
                    statement.setLong(2, r.deviceId());
                    // The number frozen on the ride wins over one sent by the
                    // client, so the stored result cannot disagree with it.
                    statement.setString(3, enLaQuedada.get(r.deviceId()));
                    statement.setInt(4, r.vueltas());
                    setInteger(statement, 5, r.mejorMs());
                    setInteger(statement, 6, r.mejorNumero());
                    if (r.totalMs() != null) {
                        statement.setLong(7, r.totalMs());
                    } else {
                        statement.setNull(7, java.sql.Types.BIGINT);
                    }
                    statement.setInt(8, r.puesto());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        } catch (SQLException e) {
            throw new StorageException(e);
        }
        return Response.noContent().build();
    }

    private static void setInteger(PreparedStatement statement, int index, Integer value)
            throws SQLException {
        if (value != null) {
            statement.setInt(index, value);
        } else {
            statement.setNull(index, java.sql.Types.INTEGER);
        }
    }

}
