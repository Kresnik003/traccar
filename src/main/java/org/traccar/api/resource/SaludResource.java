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
import jakarta.ws.rs.core.MediaType;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.HealthEvaluator;
import org.traccar.helper.HealthEvaluator.Check;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.model.Server;
import org.traccar.session.ConnectionManager;
import org.traccar.session.FleetMetrics;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        for (Device device : storage.getObjects(Device.class, new Request(new Columns.All()))) {
            // Disabled devices, and those marked to be ignored (test units that
            // are switched off on purpose), are not a fault.
            if (!device.getDisabled() && !device.getBoolean("ignorarSalud")
                    && device.getLastUpdate() != null && device.getLastUpdate().getTime() < limit) {
                silent.add(device.getName());
            }
        }
        checks.add(HealthEvaluator.silentDevices(silent, silentHours));

        /*
         * The oldest position by primary key, not MIN(fixtime). The index on
         * positions is (deviceid, fixtime), so a global MIN(fixtime) scans the
         * table; ordering by id reads one row. Ids grow with arrival, which is
         * close enough to age for a check that looks at days.
         */
        Position oldest = storage.getObject(Position.class, new Request(
                new Columns.Include("fixTime"), new Order("id", false, 1)));
        long oldestDays = oldest != null && oldest.getFixTime() != null
                ? (new Date().getTime() - oldest.getFixTime().getTime()) / 86400000L : -1;
        checks.add(HealthEvaluator.history(oldestDays, config.getInteger(Keys.WEB_HEALTH_RETENTION_DAYS)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("estado", HealthEvaluator.worst(checks));
        result.put("momento", new Date());
        result.put("comprobaciones", checks);
        return result;
    }

}
