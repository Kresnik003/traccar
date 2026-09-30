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

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.model.Device;
import org.traccar.quedadas.Convocatorias;
import org.traccar.storage.StorageException;

import java.util.Map;

/**
 * The public side of a call to ride (improvement 116): what a WhatsApp link
 * opens, and signing up to it.
 *
 * Reading needs no session and shows only what the organizer published (see
 * Convocatorias). A wrong, renewed or finished code is a 404, the same for all
 * three, so the link says nothing about rides it does not open.
 */
@Path("convocatorias")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConvocatoriaResource extends BaseResource {

    public record Apuntarse(long deviceId) {
    }

    @Inject
    private Convocatorias convocatorias;

    @PermitAll
    @Path("{codigo}")
    @GET
    public Map<String, Object> get(@PathParam("codigo") String codigo) throws StorageException {
        return convocatorias.publica(codigo)
                .orElseThrow(() -> new WebApplicationException(Response.Status.NOT_FOUND));
    }

    /** Signing up needs a session: an account on the server and a tracker the caller sees. */
    @Path("{codigo}/apuntarme")
    @POST
    public Convocatorias.Apuntado apuntarme(
            @PathParam("codigo") String codigo, Apuntarse alta) throws StorageException {
        if (alta == null) {
            throw new IllegalArgumentException("deviceId is required");
        }
        return convocatorias.apuntar(getUserId(), codigo, alta.deviceId(),
                (userId, deviceId) -> permissionsService.checkPermission(Device.class, userId, deviceId))
                .orElseThrow(() -> new WebApplicationException(Response.Status.NOT_FOUND));
    }

}
