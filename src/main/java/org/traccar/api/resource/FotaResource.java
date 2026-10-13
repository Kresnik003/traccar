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

import org.traccar.api.BaseResource;
import org.traccar.helper.ClienteFota;
import org.traccar.model.Device;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/**
 * El estado en FOTA WEB de una moto que el usuario puede ver (mejora 6).
 *
 * 404 si el servidor no tiene token de FOTA WEB (la web entonces no enseña
 * nada); 204 si la moto no está en la cuenta de FOTA WEB; 502 si el portal de
 * Teltonika no contesta, para no confundirlo con lo anterior.
 */
@Path("fota")
@Produces(MediaType.APPLICATION_JSON)
public class FotaResource extends BaseResource {

    @Inject
    private ClienteFota clienteFota;

    @Path("{deviceId}")
    @GET
    public Response get(@PathParam("deviceId") long deviceId) throws Exception {
        if (!clienteFota.activo()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        Device device = storage.getObject(Device.class, new Request(
                new Columns.Include("uniqueId"), new Condition.Equals("id", deviceId)));
        Map<String, Object> estado;
        try {
            estado = clienteFota.estado(device != null ? device.getUniqueId() : null);
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_GATEWAY).entity("FOTA WEB no contesta").build();
        }
        return estado == null ? Response.noContent().build() : Response.ok(estado).build();
    }

}
