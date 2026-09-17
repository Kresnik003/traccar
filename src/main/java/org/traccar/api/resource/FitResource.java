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

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.FitWriter;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.model.UserRestrictions;
import org.traccar.storage.StorageException;

import java.util.Date;
import java.util.Iterator;
import java.util.stream.Stream;

/**
 * A device's track over a period as a FIT activity, next to Traccar's own GPX.
 *
 * It is a resource of its own on positions/fit rather than a method added to
 * PositionResource: the more specific path wins, and a Traccar file stays
 * untouched. Same checks as the GPX export: permission on the device, and the
 * reports restriction.
 */
@Path("positions/fit")
public class FitResource extends BaseResource {

    @Inject
    private Config config;

    @GET
    @Produces("application/vnd.ant.fit")
    public Response get(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("from") Date from, @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        FitWriter writer = new FitWriter(deviceId);
        try (Stream<Position> positions = PositionUtil.getPositionsStream(
                storage, deviceId, from, to, config.getInteger(Keys.REPORT_MAX_POSITIONS))) {
            for (Iterator<Position> iterator = positions.iterator(); iterator.hasNext();) {
                Position position = iterator.next();
                writer.addRecord(position.getFixTime().getTime(), position.getLatitude(),
                        position.getLongitude(), position.getAltitude(), position.getSpeed());
            }
        }
        StreamingOutput stream = output -> {
            try {
                writer.write(output, System.currentTimeMillis());
            } catch (RuntimeException e) {
                throw new WebApplicationException(e);
            }
        };
        return Response.ok(stream)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=positions.fit").build();
    }

}
