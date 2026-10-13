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

import org.traccar.api.BaseResource;
import org.traccar.model.Device;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Who read the location history of a device, and when.
 *
 * Anyone who can see a device can see who looked at its history: it is the
 * question a member asks about their own bike. The address the request came
 * from is shown to administrators only; it identifies the person who looked,
 * and the member asking does not need it.
 */
@Path("accesos")
@Produces(MediaType.APPLICATION_JSON)
public class AccesoResource extends BaseResource {

    private static final int LIMIT = 500;

    @Inject
    private DataSource dataSource;

    @GET
    public List<Map<String, Object>> get(@QueryParam("deviceId") long deviceId) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        boolean admin = !permissionsService.notAdmin(getUserId());
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT momento, userid, usuario, que, desde, hasta, direccion FROM nx_accesos"
                             + " WHERE deviceid = ? ORDER BY momento DESC LIMIT " + LIMIT)) {
            statement.setLong(1, deviceId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("momento", rows.getTimestamp(1));
                    row.put("userId", rows.getLong(2));
                    row.put("usuario", rows.getString(3));
                    row.put("que", rows.getString(4));
                    row.put("desde", rows.getTimestamp(5));
                    row.put("hasta", rows.getTimestamp(6));
                    if (admin) {
                        row.put("direccion", rows.getString(7));
                    }
                    result.add(row);
                }
            }
        } catch (SQLException e) {
            throw new StorageException(e);
        }
        return result;
    }

}
