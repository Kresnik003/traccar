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
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.DiagnosticReport;
import org.traccar.model.Server;
import org.traccar.storage.StorageException;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * What a club sends when something fails, in one text file (improvement 67).
 *
 * Administrators only. Counts, versions, schema, which Nordes patches are in
 * the running jar, the Nordes configuration and the last warnings of the log.
 * Nothing personal: no names, addresses, identifiers or email addresses, and
 * credentials only say whether they are set. The log lines go through
 * DiagnosticReport.redact().
 */
@Path("diagnostico")
public class DiagnosticoResource extends BaseResource {

    private static final int LOG_TAIL_BYTES = 2 * 1024 * 1024;
    private static final int LOG_PROBLEMS = 60;

    /**
     * One marker per patch: a class it added, or "class#member" when it only
     * added a member to a class that already existed. See
     * DiagnosticReport.present().
     */
    static final String[] PATCH_CLASSES = {
        "org.traccar.handler.network.ConnectionLimitHandler",
        "org.traccar.api.resource.RutaResource",
        "org.traccar.api.resource.QuedadaResource",
        "org.traccar.session.FleetMetrics",
        "org.traccar.api.resource.SaludResource",
        "org.traccar.schedule.TaskPositionRetention",
        "org.traccar.api.resource.SocioResource",
        "org.traccar.api.resource.LocationAccessFilter",
        "org.traccar.session.ProtocolLock",
        "org.traccar.helper.FitWriter",
        // Los parches posteriores al 0015. El 18/9/2026 el diagnostico decia que
        // faltaban parches que si estaban: la lista se quedo en el 0015 y nadie
        // la volvio a tocar. Solo entran clases nuevas: un parche que toca una
        // clase de serie (0021, 0029, los del decoder) no se distingue por nombre.
        "org.traccar.api.resource.ConsentimientoResource",
        "org.traccar.notification.NotificationGrouper",
        "org.traccar.helper.ResetCode",
        // 0020. HealthEvaluator es del 0005: la clase sola decia "si" en cualquier
        // jar que tuviera el 0005. El 0020 solo le anadio quota().
        "org.traccar.helper.HealthEvaluator#quota",
        "org.traccar.reports.common.ReportBudget",
        "org.traccar.api.resource.ValoracionResource",
        "org.traccar.model.AvisoPendiente",
        "org.traccar.api.resource.OrdenResource",
        "org.traccar.geocoder.GuardedGeocoder",
        // 0038, la bandeja de avisos.
        "org.traccar.api.resource.AvisoResource",
        // 0039, silenciar un equipo.
        "org.traccar.helper.SilencioAvisos",
    };

    private static final String[] CONFIG_KEYS = {
        "protocols.enable",
        "database.driver",
        "database.url",
        "database.registerUnknown",
        "server.connectionLimit",
        "server.protocolLock",
        "web.metricsToken",
        "web.healthSilentHours",
        "web.healthRetentionDays",
        "database.positionRetentionDays",
        "database.invalidPositionRetentionDays",
        "database.statusEventRetentionDays",
        "database.rideRetentionDays",
        "database.accessLogRetentionDays",
        "database.deleteOrphanHistory",
        "database.deleteOrphanHistoryLimit",
        "database.changelog",
    };

    private static final String[] TABLES = {
        "tc_positions", "tc_events", "tc_devices", "tc_users", "tc_groups", "nx_accesos", "nx_quedadas", "nx_rutas",
        "nx_avisos",
    };

    @Inject
    private Config config;

    @Inject
    private DataSource dataSource;

    private List<String> query(String sql, int columns) {
        List<String> lines = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                StringBuilder line = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    if (i > 1) {
                        line.append("  ");
                    }
                    line.append(rows.getString(i));
                }
                lines.add(line.toString());
            }
        } catch (SQLException e) {
            lines.add("no se pudo leer: " + DiagnosticReport.redact(String.valueOf(e.getMessage())));
        }
        return lines;
    }

    private List<String> logTail() {
        File file = new File(config.getString(Keys.LOGGER_FILE));
        try (RandomAccessFile log = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, log.length() - LOG_TAIL_BYTES);
            byte[] bytes = new byte[(int) (log.length() - start)];
            log.seek(start);
            log.readFully(bytes);
            List<String> lines = new ArrayList<>(Arrays.asList(new String(bytes, StandardCharsets.UTF_8).split("\n")));
            if (start > 0 && !lines.isEmpty()) {
                lines.remove(0);
            }
            return DiagnosticReport.lastProblems(lines, LOG_PROBLEMS);
        } catch (IOException | SecurityException e) {
            return List.of("no se pudo leer el log");
        }
    }

    @GET
    @Produces("text/plain; charset=utf-8")
    public Response get() throws StorageException {
        permissionsService.checkAdmin(getUserId());
        StringBuilder out = new StringBuilder();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String now = format.format(new Date());
        out.append("Nordés · informe de diagnóstico · ").append(now).append("\n");
        out.append("Sin datos personales: direcciones, identificadores y correos van tachados.\n\n");

        Runtime runtime = Runtime.getRuntime();
        File disk = new File(".");
        DiagnosticReport.section(out, "Versiones", List.of(
                "Traccar " + new Server().getVersion(),
                "Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")",
                "Sistema " + System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                "Procesadores " + runtime.availableProcessors(),
                "Encendido hace " + ManagementFactory.getRuntimeMXBean().getUptime() / 60000 + " min",
                "Memoria de Java " + (runtime.totalMemory() - runtime.freeMemory()) / 1048576 + " de "
                        + runtime.maxMemory() / 1048576 + " MB",
                "Disco libre " + disk.getUsableSpace() / 1048576 + " de " + disk.getTotalSpace() / 1048576 + " MB"));

        List<String> patches = new ArrayList<>();
        for (String marker : PATCH_CLASSES) {
            boolean present = DiagnosticReport.present(marker, getClass().getClassLoader());
            patches.add((present ? "sí  " : "NO  ") + DiagnosticReport.markerName(marker));
        }
        DiagnosticReport.section(out, "Parches de Nordés en este jar", patches);

        DiagnosticReport.section(out, "Esquema: últimos cambios aplicados", query(
                "SELECT dateexecuted, id, filename FROM DATABASECHANGELOG ORDER BY orderexecuted DESC LIMIT 12", 3));

        StringBuilder tables = new StringBuilder();
        for (String table : TABLES) {
            tables.append(tables.length() > 0 ? ", " : "").append('\'').append(table).append('\'');
        }
        DiagnosticReport.section(out, "Tablas (filas aproximadas, MB)", query(
                "SELECT table_name, table_rows, ROUND((data_length + index_length) / 1048576)"
                        + " FROM information_schema.tables WHERE table_schema = DATABASE()"
                        + " AND table_name IN (" + tables + ") ORDER BY table_name", 3));

        List<String> settings = new ArrayList<>();
        for (String key : CONFIG_KEYS) {
            settings.add(key + " = " + DiagnosticReport.configValue(key, config.getString(key)));
        }
        DiagnosticReport.section(out, "Configuración", settings);

        DiagnosticReport.section(out, "Últimos avisos y errores del log", logTail());

        return Response.ok(out.toString())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=nordes-diagnostico-" + now.substring(0, 10) + ".txt")
                .build();
    }

}
