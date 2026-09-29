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
import org.traccar.api.BaseResource;
import org.traccar.model.Contacto;
import org.traccar.model.Device;
import org.traccar.model.UserRestrictions;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;
import org.traccar.telegram.ClienteTelegram;
import org.traccar.telegram.CodigosAlta;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The emergency contacts of a bike (improvement 113). Adding one returns the
 * t.me link that signs them up; the link is shown once and never again, because
 * only the hash of its code is kept.
 */
@Path("contactos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ContactoResource extends BaseResource {

    static final int MAXIMO = 5;

    private static final int NOMBRE_MAXIMO = 64;

    @Inject
    private ClienteTelegram clienteTelegram;

    public record Alta(long deviceId, String nombre) {
    }

    @GET
    public List<Map<String, Object>> get(@QueryParam("deviceId") long deviceId) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        return delEquipo(deviceId).stream().map(ContactoResource::publico).toList();
    }

    @POST
    public Map<String, Object> add(Alta alta) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), alta.deviceId());
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getReadonly);
        String nombre = alta.nombre() == null ? "" : alta.nombre().strip();
        if (nombre.isEmpty() || nombre.length() > NOMBRE_MAXIMO) {
            throw new IllegalArgumentException("El nombre del contacto va de 1 a " + NOMBRE_MAXIMO + " caracteres");
        }
        if (delEquipo(alta.deviceId()).size() >= MAXIMO) {
            throw new IllegalArgumentException("Esta moto ya tiene " + MAXIMO + " contactos");
        }
        String bot = clienteTelegram.nombreDelBot();
        if (bot == null) {
            throw new IllegalStateException("Telegram no contesta: no se puede crear el enlace ahora");
        }
        String codigo = CodigosAlta.nuevo();
        Date ahora = new Date();
        Contacto contacto = new Contacto();
        contacto.setDeviceId(alta.deviceId());
        contacto.setNombre(nombre);
        contacto.setCodigo(CodigosAlta.huella(codigo));
        contacto.setCreado(ahora);
        contacto.setCaduca(new Date(ahora.getTime() + CodigosAlta.DURACION));
        contacto.setId(storage.addObject(contacto, new Request(new Columns.Exclude("id"))));
        Map<String, Object> resultado = publico(contacto);
        resultado.put("enlace", "https://t.me/" + bot + "?start=" + codigo);
        return resultado;
    }

    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws StorageException {
        Contacto contacto = storage.getObject(Contacto.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (contacto == null) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        permissionsService.checkPermission(Device.class, getUserId(), contacto.getDeviceId());
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getReadonly);
        storage.removeObject(Contacto.class, new Request(new Condition.Equals("id", id)));
        return Response.noContent().build();
    }

    private List<Contacto> delEquipo(long deviceId) throws StorageException {
        return storage.getObjects(Contacto.class, new Request(
                new Columns.All(), new Condition.Equals("deviceid", deviceId), new Order("creado")));
    }

    /** What the web sees: never the chat nor the hash of the code. */
    static Map<String, Object> publico(Contacto contacto) {
        Map<String, Object> visto = new LinkedHashMap<>();
        visto.put("id", contacto.getId());
        visto.put("nombre", contacto.getNombre());
        visto.put("creado", contacto.getCreado());
        visto.put("caduca", contacto.getConfirmado() == null ? contacto.getCaduca() : null);
        visto.put("confirmado", contacto.getConfirmado());
        visto.put("rechazado", contacto.getRechazado());
        return visto;
    }

}
