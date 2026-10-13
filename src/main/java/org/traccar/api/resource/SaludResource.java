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
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.HealthEvaluator;
import org.traccar.helper.HealthEvaluator.Check;
import org.traccar.model.Device;
import org.traccar.model.Server;
import org.traccar.model.User;
import org.traccar.schedule.TaskPositionRetention;
import org.traccar.session.ConnectionManager;
import org.traccar.session.FleetMetrics;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Server health for administrators, as results rather than raw numbers.
 *
 * Administrators only. The first idea was to publish a summary in the server
 * attributes, which the web app already reads - but /api/server returns its
 * attributes without authentication, so memory and disk would have been
 * visible to anyone opening the login page.
 *
 * What depends on reading the log (computed attribute errors, for instance)
 * stays in the daily health script: parsing a rotating log on every request
 * is the wrong place for it.
 */
@Path("salud")
@Produces(MediaType.APPLICATION_JSON)
public class SaludResource extends BaseResource {

    @Inject
    private Config config;

    @Inject
    private FleetMetrics fleetMetrics;

    @Inject
    private ConnectionManager connectionManager;

    @Inject
    private DataSource dataSource;

    /**
     * Oldest position per device. MySQL answers this from the (deviceid,
     * fixtime) index reading one entry per device - checked with EXPLAIN,
     * "Using index for group-by" - so its cost follows the number of devices,
     * not the size of the history.
     */
    private List<HealthEvaluator.DeviceOldest> oldestPerDevice() throws SQLException {
        List<HealthEvaluator.DeviceOldest> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT deviceid, MIN(fixtime) FROM tc_positions GROUP BY deviceid");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                if (rows.getTimestamp(2) != null) {
                    result.add(new HealthEvaluator.DeviceOldest(rows.getLong(1), rows.getTimestamp(2).getTime()));
                }
            }
        }
        return result;
    }

    private long oldestOtherThan(long deviceId, long positionId, boolean skipRides) {
        if (skipRides) {
            try {
                return oldest(deviceId, positionId,
                        " AND NOT EXISTS (" + TaskPositionRetention.IN_RIDE + ")");
            } catch (SQLException e) {
                // Without the ride tables the plain reading still has to answer:
                // a -1 here would make the history look clean.
            }
        }
        try {
            return oldest(deviceId, positionId, "");
        } catch (SQLException e) {
            return -1;
        }
    }

    /**
     * The first position of a device by fixtime, walking the (deviceid, fixtime)
     * index and stopping at the first one that matches. A MIN() with the ride
     * condition would read every position of the device instead.
     */
    private long oldest(long deviceId, long positionId, String condition) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT fixtime FROM tc_positions WHERE deviceid = ? AND id <> ?" + condition
                             + " ORDER BY fixtime LIMIT 1")) {
            statement.setLong(1, deviceId);
            statement.setLong(2, positionId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getTimestamp(1) != null ? rows.getTimestamp(1).getTime() : -1;
            }
        }
    }

    private static String read(String file) {
        try {
            return Files.readString(java.nio.file.Path.of(file));
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private static long systemUptime() {
        String text = read("/proc/uptime");
        if (text != null) {
            try {
                return (long) Double.parseDouble(text.trim().split("\\s+")[0]);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
    }

    @GET
    public Map<String, Object> get() throws StorageException {
        permissionsService.checkAdmin(getUserId());
        List<Check> checks = new ArrayList<>();

        long start = System.currentTimeMillis();
        try {
            storage.getObject(Server.class, new Request(new Columns.Include("id")));
            long ms = System.currentTimeMillis() - start;
            checks.add(new Check("base", ms > 1000 ? HealthEvaluator.WARNING : HealthEvaluator.OK,
                    ms + " ms", ms > 1000 ? "Responde, pero lenta" : null));
        } catch (StorageException e) {
            checks.add(new Check("base", HealthEvaluator.FAILURE, "sin respuesta", e.getMessage()));
        }

        checks.add(HealthEvaluator.memory(HealthEvaluator.memoryUsed(read("/proc/meminfo"))));

        File root = new File("/");
        checks.add(HealthEvaluator.disk(root.getTotalSpace(), root.getUsableSpace()));

        var os = ManagementFactory.getOperatingSystemMXBean();
        checks.add(HealthEvaluator.load(os.getSystemLoadAverage(), os.getAvailableProcessors()));

        long uptime = systemUptime();
        checks.add(HealthEvaluator.uptime(uptime));

        long lastStored = fleetMetrics.getLastStoredAt();
        long jvmUptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        checks.add(HealthEvaluator.storing(
                connectionManager.getDeviceSessionCount(),
                lastStored > 0 ? (System.currentTimeMillis() - lastStored) / 1000 : -1,
                jvmUptime));

        int silentHours = config.getInteger(Keys.WEB_HEALTH_SILENT_HOURS);
        long limit = System.currentTimeMillis() - silentHours * 3600000L;
        List<String> silent = new ArrayList<>();
        Map<Long, Long> lastPositions = new HashMap<>();
        for (Device device : storage.getObjects(Device.class, new Request(new Columns.All()))) {
            lastPositions.put(device.getId(), device.getPositionId());
            // Disabled devices, and those marked to be ignored (test units that
            // are switched off on purpose), are not a fault.
            if (!device.getDisabled() && !device.getBoolean("ignorarSalud")
                    && device.getLastUpdate() != null && device.getLastUpdate().getTime() < limit) {
                silent.add(device.getName());
            }
        }
        checks.add(HealthEvaluator.silentDevices(silent, silentHours));

        /*
         * What is kept on purpose however old it is does not count for the
         * pruning check: a device's last known position, or a bike stored for a
         * year would report the pruning as broken; and, with
         * database.rideRetentionDays longer than the general retention, the
         * positions of a group ride.
         */
        int retention = config.getInteger(Keys.DATABASE_POSITION_RETENTION_DAYS);
        boolean skipRides = retention > 0 && config.getInteger(Keys.DATABASE_RIDE_RETENTION_DAYS) > retention;
        long oldestDays;
        try {
            long oldest = HealthEvaluator.oldestCounting(oldestPerDevice(),
                    deviceId -> oldestOtherThan(deviceId, lastPositions.getOrDefault(deviceId, 0L), skipRides));
            oldestDays = oldest >= 0 ? (System.currentTimeMillis() - oldest) / 86400000L : -1;
        } catch (SQLException e) {
            oldestDays = -1;
        }
        checks.add(HealthEvaluator.history(oldestDays,
                retention > 0 ? retention : config.getInteger(Keys.WEB_HEALTH_RETENTION_DAYS)));

        List<String> withoutSecondFactor = new ArrayList<>();
        for (User user : storage.getObjects(User.class, new Request(new Columns.All()))) {
            if (user.getAdministrator() && !user.getDisabled()
                    && (user.getTotpKey() == null || user.getTotpKey().isEmpty())) {
                withoutSecondFactor.add(user.getName());
            }
        }
        long deviceCount = storage.getObjects(Device.class, new Request(new Columns.Include("id"))).size();
        checks.add(HealthEvaluator.quota("equiposCupo", deviceCount,
                config.getInteger(Keys.INSTALLATION_MAX_DEVICES)));
        checks.add(HealthEvaluator.quota("rutasCupo", contarFilas("nx_rutas"),
                config.getInteger(Keys.INSTALLATION_MAX_ROUTES)));

        checks.add(HealthEvaluator.secondFactor(
                permissionsService.getServer().getBoolean(Keys.WEB_TOTP_ENABLE.getKey()), withoutSecondFactor));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("estado", HealthEvaluator.worst(checks));
        result.put("momento", new Date());
        result.put("comprobaciones", checks);
        return result;
    }

    private long contarFilas(String tabla) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + tabla);
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }
}
