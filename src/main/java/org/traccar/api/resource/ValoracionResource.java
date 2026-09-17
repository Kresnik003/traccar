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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.helper.Valoraciones;
import org.traccar.model.User;
import org.traccar.model.Valoracion;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the club thinks of the places on the map.
 *
 * <p>OpenStreetMap gives the facts about a campsite - showers, water, whether it
 * charges - but no opinions, and none can be borrowed: Park4Night publishes no
 * API, and Google's terms require their ratings to be shown on a Google map and
 * forbid storing them, while this product has a map of its own. So these are the
 * club's own, which are also the ones worth reading.
 *
 * <p>PRIVACY IS THE DESIGN CONSTRAINT HERE. A rating says "I was there", which
 * is location data about a person, and members are deliberately kept from seeing
 * each other's whereabouts. So:
 *
 * <ul>
 *   <li>the average and the count are always shared - they identify nobody;
 *   <li>a name is attached only when its author asked for it ({@code publica}),
 *       which defaults to false;
 *   <li>a member always sees their own rating in full, however it was filed.
 * </ul>
 *
 * <p>Ratings are shared across the installation, which is one club: the
 * installer creates one, and the quotas are per installation. A site is
 * identified by its OpenStreetMap id and is not a foreign key, because the site
 * list is regenerated from an OSM extract.
 */
@Path("valoraciones")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ValoracionResource extends BaseResource {

    /**
     * What a site looks like on the map: how it scores and how many said so.
     * Never names anyone, so it can be handed to everybody.
     */
    public record Resumen(String sitio, double media, int cuantas) {
    }

    /**
     * One opinion, as another member may read it. {@code autor} is null unless
     * that person chose to sign it, and {@code mia} tells the client which row
     * it may edit.
     *
     * <p>{@code publica} is NOT the same as "autor is set", and conflating the
     * two is a privacy bug: a member always gets their own name back so their
     * rating does not look lost, so a form that reads the name as "this is
     * signed" would re-publish, under their real name, a rating they had filed
     * anonymously. The flag says what its author actually asked for.
     */
    public record Opinion(
            long id, String sitio, int estrellas, String comentario,
            String autor, boolean publica, Date visitado, Date creada, boolean mia) {
    }

    /** What the form sends. Kept apart from the entity so that a body can never carry a userId. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Envio(String sitio, int estrellas, String comentario, boolean publica, Date visitado) {
    }

    private String nombreDe(long userId) throws StorageException {
        User user = storage.getObject(User.class, new Request(
                new Columns.Include("name"), new Condition.Equals("id", userId)));
        return user != null ? user.getName() : null;
    }


    /**
     * Every site the club has rated, with its average. Deliberately one request
     * with no parameters: the map would otherwise ask per site, and a club rates
     * dozens of places out of the thousands on the map, so the whole thing is
     * small and the client merges it against the site file it already has.
     */
    @GET
    public List<Resumen> get() throws StorageException {
        var todas = storage.getObjects(Valoracion.class, new Request(new Columns.All()));
        Map<String, int[]> suma = new HashMap<>();
        for (Valoracion v : todas) {
            int[] par = suma.computeIfAbsent(v.getSitio(), clave -> new int[2]);
            par[0] += v.getEstrellas();
            par[1] += 1;
        }
        List<Resumen> salida = new ArrayList<>();
        suma.forEach((sitio, par) -> salida.add(
                new Resumen(sitio, Valoraciones.media(par[0], par[1]), par[1])));
        return salida;
    }

    /**
     * This member's own ratings, so a screen can tell what is already rated without
     * asking site by site.
     *
     * <p>It exists for the card that offers to rate a place after stopping there: it
     * needs to leave out what this person already rated, and doing that with one
     * request per candidate site would be a request per row.
     */
    @Path("mias")
    @GET
    public List<Valoracion> mias() throws StorageException {
        return storage.getObjects(Valoracion.class, new Request(
                new Columns.All(), new Condition.Equals("userId", getUserId())));
    }

    /** The opinions on one site, with a name only where its author asked for one. */
    @Path("opiniones")
    @GET
    public List<Opinion> opiniones(@QueryParam("sitio") String sitio) throws StorageException {
        String clave = Valoraciones.sitioLimpio(sitio);
        var filas = storage.getObjects(Valoracion.class, new Request(
                new Columns.All(), new Condition.Equals("sitio", clave)));
        List<Opinion> salida = new ArrayList<>();
        for (Valoracion v : filas) {
            boolean mia = v.getUserId() == getUserId();
            // The name is attached only when its author asked for it. Their own
            // row is the exception: hiding it from them would make it look lost.
            String autor = v.getPublica() || mia ? nombreDe(v.getUserId()) : null;
            salida.add(new Opinion(
                    v.getId(), v.getSitio(), v.getEstrellas(), v.getComentario(),
                    autor, v.getPublica(), v.getVisitado(), v.getCreada(), mia));
        }
        return salida;
    }

    /**
     * Files or corrects this member's rating of a site. There is one row per
     * person and site - rating again is correcting, not piling on - and the
     * database enforces it too, not just this method.
     */
    @PUT
    public Response guardar(Envio envio) throws StorageException {
        String clave = Valoraciones.sitioLimpio(envio.sitio());
        if (!Valoraciones.estrellasValidas(envio.estrellas())) {
            throw new IllegalArgumentException(
                    "A rating is between " + Valoracion.MIN_ESTRELLAS + " and " + Valoracion.MAX_ESTRELLAS + " stars");
        }
        String comentario = envio.comentario();
        if (!Valoraciones.comentarioValido(comentario)) {
            throw new IllegalArgumentException("The comment is longer than " + Valoracion.MAX_COMENTARIO);
        }

        Valoracion existente = storage.getObject(Valoracion.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("sitio", clave),
                        // The author comes from the session, never from the body:
                        // trusting the body would let anyone file a rating under
                        // somebody else's name.
                        new Condition.Equals("userId", getUserId()))));

        Valoracion fila = existente != null ? existente : new Valoracion();
        fila.setSitio(clave);
        fila.setUserId(getUserId());
        fila.setEstrellas(envio.estrellas());
        fila.setComentario(comentario);
        fila.setPublica(envio.publica());
        fila.setVisitado(envio.visitado());
        if (existente != null) {
            fila.setModificada(new Date());
            storage.updateObject(fila, new Request(
                    new Columns.Exclude("id", "creada"), new Condition.Equals("id", fila.getId())));
        } else {
            fila.setCreada(new Date());
            fila.setId(storage.addObject(fila, new Request(new Columns.Exclude("id"))));
        }
        return Response.ok(fila).build();
    }

    /** Removes this member's own rating of a site, and only their own. */
    @DELETE
    public Response borrar(@QueryParam("sitio") String sitio) throws StorageException {
        String clave = Valoraciones.sitioLimpio(sitio);
        Valoracion mia = storage.getObject(Valoracion.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("sitio", clave),
                        new Condition.Equals("userId", getUserId()))));
        if (mia == null) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        // Filtered by owner in the query, not after it: there is no row here to
        // forget to check.
        storage.removeObject(Valoracion.class, new Request(new Condition.Equals("id", mia.getId())));
        return Response.noContent().build();
    }
}
