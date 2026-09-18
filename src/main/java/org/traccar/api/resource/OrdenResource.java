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

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.traccar.api.BaseResource;
import org.traccar.model.Device;
import org.traccar.model.Orden;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.List;

/**
 * The commands sent to a device and whether the device answered.
 *
 * PERMISSION IS CHECKED ON THE DEVICE, not on the rows. A command says who did
 * what to whose bike, so it is exactly as private as the bike's position: only
 * somebody who can already see the device can see its commands.
 */
@Path("ordenes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrdenResource extends BaseResource {

    /** Without a cap, a device with years of commands would answer with all of them. */
    private static final int LIMITE = 100;

    @GET
    public List<Orden> get(@QueryParam("deviceId") long deviceId) throws Exception {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        return storage.getObjects(Orden.class, new Request(
                new Columns.All(),
                new Condition.Equals("deviceid", deviceId),
                new Order("enviado", true, LIMITE)));
    }

}
