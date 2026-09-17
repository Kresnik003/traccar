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
import org.traccar.model.Ruta;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.List;

/**
 * A user's own routes.
 *
 * This does not extend BaseObjectResource on purpose. That class resolves
 * visibility through the permission tables, and a route has no permission
 * table: it has one owner, in creadorId. Bolting a permission table on would
 * add a second, weaker answer to a question that already has an exact one.
 *
 * Every read and every write filters on the owner, and it does so in the query
 * rather than after it. Loading a row and then checking who owns it works until
 * someone adds a code path that forgets the check; filtering in the SQL means
 * there is no row to forget about.
 */
@Path("rutas")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RutaResource extends BaseResource {

    private Ruta propia(long id) throws StorageException {
        Ruta ruta = storage.getObject(Ruta.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("id", id),
                        new Condition.Equals("creadorId", getUserId()))));
        if (ruta == null) {
            /*
             * The same answer whether the route does not exist or belongs to
             * somebody else. Telling the two apart would turn the id into an
             * oracle: walk the numbers and you learn how many routes the club
             * has and which ids are taken, without ever reading one.
             */
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        return ruta;
    }

    @GET
    public List<Ruta> get() throws StorageException {
        return storage.getObjects(Ruta.class, new Request(
                new Columns.All(),
                new Condition.Equals("creadorId", getUserId())));
    }

    @Path("{id}")
    @GET
    public Ruta getSingle(@PathParam("id") long id) throws StorageException {
        return propia(id);
    }

    @POST
    public Response add(Ruta entity) throws StorageException {
        permissionsService.checkEdit(getUserId(), entity, true, false);
        /*
         * The owner is taken from the session, never from the body. Trusting
         * the body here would let anyone file a route under someone else's
         * name, which is the whole authorisation model in one field.
         */
        entity.setCreadorId(getUserId());
        entity.setSubida(new Date());
        entity.setId(storage.addObject(entity, new Request(new Columns.Exclude("id"))));
        return Response.ok(entity).build();
    }

    @Path("{id}")
    @PUT
    public Response update(@PathParam("id") long id, Ruta entity) throws StorageException {
        Ruta actual = propia(id);
        entity.setId(id);
        entity.setCreadorId(actual.getCreadorId());
        entity.setSubida(actual.getSubida());
        storage.updateObject(entity, new Request(
                new Columns.Exclude("id"),
                new Condition.Equals("id", id)));
        return Response.ok(entity).build();
    }

    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws StorageException {
        propia(id);
        storage.removeObject(Ruta.class, new Request(new Condition.Equals("id", id)));
        return Response.noContent().build();
    }

    /**
     * Shares a route by copying it to another user.
     *
     * SHARING DUPLICATES, IT NEVER MOVES. The recipient gets a row of their
     * own: they can rename it, trim it or delete it and the original never
     * notices, and the other way round. That is also what stops an author from
     * editing their route and silently changing the terrain of every meet
     * pointing at it.
     *
     * origenautor is copied rather than referenced so it survives that account
     * being deleted, and origenid is ON DELETE SET NULL so that if the original
     * goes, the copy is still yours and only the trail is lost.
     */
    @Path("{id}/compartir/{userId}")
    @POST
    public Response compartir(
            @PathParam("id") long id, @PathParam("userId") long userId) throws StorageException {

        Ruta origen = propia(id);

        User destinatario = storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("id", userId)));
        if (destinatario == null) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }

        User autor = storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("id", getUserId())));

        Ruta copia = new Ruta();
        copia.setNombre(origen.getNombre());
        copia.setDescripcion(origen.getDescripcion());
        copia.setTrazado(origen.getTrazado());
        copia.setDistancia(origen.getDistancia());
        copia.setCentrolat(origen.getCentrolat());
        copia.setCentrolon(origen.getCentrolon());
        copia.setAlcance(origen.getAlcance());
        copia.setCreadorId(userId);
        copia.setSubida(new Date());
        copia.setOrigenId(origen.getId());
        copia.setOrigenautor(autor != null ? autor.getName() : null);
        copia.setId(storage.addObject(copia, new Request(new Columns.Exclude("id"))));

        return Response.ok(copia).build();
    }

}
