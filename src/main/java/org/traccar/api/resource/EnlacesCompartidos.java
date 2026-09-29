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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.model.Device;
import org.traccar.model.ObjectOperation;
import org.traccar.model.User;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The «follow me» links a user has handed out, and how to take one back
 * (improvement 34).
 *
 * A link logs in as a temporary user that Traccar creates per owner and device
 * (email «owner:uniqueId»). Each link carries its own signed expiration, so it
 * dies on its own; what did not exist was a way to kill it before. Deleting
 * that temporary user does it: the tokens point to a user that is gone, and
 * every link of that device stops working at once.
 */
@Path("enlaces")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EnlacesCompartidos extends BaseResource {

    @Inject
    private CacheManager cacheManager;

    /** Whether a new link outlives the shared user it reuses. */
    public static boolean alargar(Date actual, Date nueva) {
        return nueva != null && (actual == null || actual.before(nueva));
    }

    /** The shared users of this owner: temporary, and named after the owner's email. */
    static boolean esSuyo(User owner, User share) {
        return share.getTemporary()
                && owner.getEmail() != null
                && share.getEmail() != null
                && share.getEmail().startsWith(owner.getEmail() + ":");
    }

    private User owner() throws StorageException {
        User owner = permissionsService.getUser(getUserId());
        if (owner.getTemporary()) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        return owner;
    }

    @GET
    public List<Map<String, Object>> get() throws StorageException {
        User owner = owner();
        List<Map<String, Object>> result = new ArrayList<>();
        Date ahora = new Date();
        for (User share : storage.getObjects(User.class, new Request(
                new Columns.All(), new Condition.Equals("temporary", true)))) {
            if (!esSuyo(owner, share)) {
                continue;
            }
            if (share.getExpirationTime() != null && share.getExpirationTime().before(ahora)) {
                continue;
            }
            List<Device> devices = storage.getObjects(Device.class, new Request(
                    new Columns.Include("id", "name"),
                    new Condition.Permission(User.class, share.getId(), Device.class)));
            Map<String, Object> enlace = new LinkedHashMap<>();
            enlace.put("id", share.getId());
            enlace.put("nombre", share.getName());
            enlace.put("caduca", share.getExpirationTime());
            enlace.put("deviceId", devices.isEmpty() ? null : devices.get(0).getId());
            result.add(enlace);
        }
        return result;
    }

    /** Revoke: every link of that device given out by this owner stops working now. */
    @Path("{id}")
    @DELETE
    public Response revocar(@PathParam("id") long id) throws Exception {
        User owner = owner();
        User share = storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        // Someone else's id answers the same as one that does not exist.
        if (share == null || !esSuyo(owner, share)) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        storage.removeObject(User.class, new Request(new Condition.Equals("id", id)));
        // As BaseObjectResource does: without it the cache keeps the user and
        // its link to the device, and whoever has the page open keeps getting
        // positions over the socket after the link was revoked.
        cacheManager.invalidateObject(true, User.class, id, ObjectOperation.DELETE);
        return Response.noContent().build();
    }

}
