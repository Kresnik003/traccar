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
import org.traccar.api.BaseResource;
import org.traccar.model.Device;
import org.traccar.model.Quedada;
import org.traccar.model.QuedadaEquipo;
import org.traccar.model.QuedadaUsuario;
import org.traccar.model.Ruta;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        return visible(id);
    }

    @POST
    public Response add(Quedada entity) throws StorageException {
        permissionsService.checkEdit(getUserId(), entity, true, false);
        // The owner comes from the session, never from the body.
        entity.setCreadorId(getUserId());
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

}
