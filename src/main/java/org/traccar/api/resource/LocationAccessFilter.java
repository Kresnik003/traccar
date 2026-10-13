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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.security.UserPrincipal;
import org.traccar.helper.LocationAccessLog;
import org.traccar.helper.model.DeviceUtil;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * Writes to nx_accesos who read the location history of which device.
 *
 * It lives in the resource package, and not with the security filters, only
 * because Jersey scans this package for providers: registering it anywhere else
 * would mean patching WebServer, one more Traccar file that an upgrade can
 * conflict with.
 *
 * Writes happen off the request thread. A history request already waits on the
 * database; making it also wait for its own audit row would slow down exactly
 * the screens people use to look back at a ride.
 *
 * On an installation without the Nordés schema the table does not exist. The
 * first failure switches the log off with one warning, instead of writing a
 * stack trace for every report anyone opens.
 */
@Provider
public class LocationAccessFilter implements ContainerResponseFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocationAccessFilter.class);

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "location-access-log");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicBoolean DISABLED = new AtomicBoolean();

    private static final LocationAccessLog.Repeats REPEATS =
            new LocationAccessLog.Repeats(TimeUnit.MINUTES.toMillis(10), 10_000);

    @Inject
    private DataSource dataSource;

    @Inject
    private Storage storage;

    @Context
    private HttpServletRequest servletRequest;

    private Set<Long> query(Connection connection, String sql, long id) throws SQLException {
        Set<Long> result = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(rows.getLong(1));
                }
            }
        }
        return result;
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (DISABLED.get() || response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL
                || !(request.getSecurityContext().getUserPrincipal() instanceof UserPrincipal principal)) {
            return;
        }
        LocationAccessLog.Access access = LocationAccessLog.classify(
                request.getMethod(), request.getUriInfo().getPath(),
                name -> request.getUriInfo().getQueryParameters().get(name));
        if (access == null) {
            return;
        }
        long userId = principal.getUserId();
        String address = servletRequest != null ? servletRequest.getRemoteAddr() : null;
        WRITER.execute(() -> write(access, userId, address));
    }

    private void write(LocationAccessLog.Access access, long userId, String address) {
        try (Connection connection = dataSource.getConnection()) {
            /*
             * Only what the user could actually read. A report asked for a device
             * the user cannot see answers 200 with nothing in it; logging the ids
             * as requested would record a look that never happened. The reports
             * choose their devices with this same call, so the log and the report
             * agree on what was read.
             */
            Set<Long> devices = new LinkedHashSet<>();
            if (access.exportedUserId() != null) {
                devices.addAll(query(connection,
                        "SELECT deviceid FROM tc_user_device WHERE userid = ?", access.exportedUserId()));
            } else if (access.silentlyFiltered()) {
                for (Device device : DeviceUtil.getAccessibleDevices(
                        storage, userId, access.deviceIds(), access.groupIds())) {
                    devices.add(device.getId());
                }
            } else {
                // Not through getAccessibleDevices: it only counts devices linked
                // to the user, and an administrator reads every device without
                // being linked to any. Filtering here hid exactly the reads by
                // administrators, which are the ones this log exists for.
                devices.addAll(access.deviceIds());
            }
            long now = System.currentTimeMillis();
            devices.removeIf(deviceId -> !REPEATS.first(userId, deviceId, access, now));
            if (devices.isEmpty()) {
                return;
            }
            String name = null;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT name FROM tc_users WHERE id = ?")) {
                statement.setLong(1, userId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) {
                        name = rows.getString(1);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO nx_accesos (userid, usuario, deviceid, que, desde, hasta, direccion, momento)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                // The moment goes from here and not from the column default: the
                // driver converts timestamps to the connection time zone and the
                // database default does not, and the two ended up hours apart.
                Timestamp moment = new Timestamp(now);
                for (long deviceId : devices) {
                    statement.setLong(1, userId);
                    statement.setString(2, name);
                    statement.setLong(3, deviceId);
                    statement.setString(4, access.what());
                    statement.setTimestamp(5, access.from() != null ? new Timestamp(access.from().getTime()) : null);
                    statement.setTimestamp(6, access.to() != null ? new Timestamp(access.to().getTime()) : null);
                    statement.setString(7, address);
                    statement.setTimestamp(8, moment);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        } catch (SQLException | StorageException e) {
            if (e.getMessage() != null && e.getMessage().contains("nx_accesos")
                    && e.getMessage().toLowerCase().contains("exist")) {
                if (DISABLED.compareAndSet(false, true)) {
                    LOGGER.warn("No nx_accesos table: location access log switched off");
                }
            } else {
                LOGGER.warn("Failed to write location access", e);
            }
        }
    }

}
