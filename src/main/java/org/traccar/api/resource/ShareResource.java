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

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Date;

import org.traccar.api.BaseResource;
import org.traccar.api.EnlacesDirectos;
import org.traccar.model.BaseModel;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.User;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("share")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ShareResource extends BaseResource {


    @Inject
    private EnlacesDirectos enlacesDirectos;

    private String share(User user, Class<? extends BaseModel> clazz, long id, Date expiration)
            throws StorageException, GeneralSecurityException, IOException {
        return enlacesDirectos.compartir(user, clazz, id, expiration).token();
    }

    @Path("device")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @POST
    public String shareDevice(
            @FormParam("deviceId") long deviceId,
            @FormParam("expiration") Date expiration) throws StorageException, GeneralSecurityException, IOException {
        return share(permissionsService.getUser(getUserId()), Device.class, deviceId, expiration);
    }

    @Path("group")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @POST
    public String shareGroup(
            @FormParam("groupId") long groupId,
            @FormParam("expiration") Date expiration) throws StorageException, GeneralSecurityException, IOException {
        return share(permissionsService.getUser(getUserId()), Group.class, groupId, expiration);
    }

}
