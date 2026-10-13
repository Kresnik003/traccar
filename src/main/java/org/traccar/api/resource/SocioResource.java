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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.traccar.api.BaseResource;
import org.traccar.helper.CsvLine;
import org.traccar.model.Device;
import org.traccar.model.Quedada;
import org.traccar.model.QuedadaUsuario;
import org.traccar.model.Ruta;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;

/**
 * Everything the server holds about one member, in one file they can keep.
 *
 * The right of access and portability: data in a structured, commonly used,
 * machine-readable form. A ZIP with JSON for the records and CSV for the
 * tracks, which any spreadsheet opens.
 *
 * WHAT COUNTS AS THE MEMBER'S DATA. The devices linked to the member directly,
 * not every device they can see. A member of a club sees other members' bikes
 * through a group; those tracks are the other members' data, and handing them
 * over in an export would be the very leak the isolation tests guard against.
 *
 * Tracks are streamed row by row from the database, so a year of positions
 * does not have to fit in memory before the first byte is sent.
 *
 * The member themselves, an administrator, or a manager of that member.
 */
@Path("socios")
public class SocioResource extends BaseResource {

    @Inject
    private DataSource dataSource;

    @Inject
    private ObjectMapper objectMapper;

    private List<Long> ownDevices(long userId) throws SQLException {
        List<Long> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT deviceid FROM tc_user_device WHERE userid = ? ORDER BY deviceid")) {
            statement.setLong(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(rows.getLong(1));
                }
            }
        }
        return result;
    }

    private void entry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private void json(ZipOutputStream zip, String name, Object value) throws IOException {
        entry(zip, name, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }

    /**
     * Streams a query into a CSV entry. MySQL Connector/J only streams rows
     * with a forward-only, read-only statement and a fetch size of
     * Integer.MIN_VALUE; with any other fetch size it reads the whole result
     * into memory first.
     */
    private void csv(ZipOutputStream zip, String name, String header, String sql, long deviceId)
            throws IOException, SQLException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(header.getBytes(StandardCharsets.UTF_8));
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(Integer.MIN_VALUE);
            statement.setLong(1, deviceId);
            try (ResultSet rows = statement.executeQuery()) {
                int columns = rows.getMetaData().getColumnCount();
                Object[] values = new Object[columns];
                while (rows.next()) {
                    for (int i = 0; i < columns; i++) {
                        values[i] = rows.getObject(i + 1);
                    }
                    zip.write(CsvLine.of(values).getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        zip.closeEntry();
    }

    @Path("{id}/datos")
    @GET
    @Produces("application/zip")
    public Response export(@PathParam("id") long userId) throws StorageException {
        permissionsService.checkUser(getUserId(), userId);
        User user = storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("id", userId)));
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        StreamingOutput body = (OutputStream output) -> {
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                entry(zip, "LEEME.txt", ("""
                        Datos de %s guardados en este servidor.

                        usuario.json     la cuenta (sin contraseña ni clave del segundo factor)
                        equipos.json     los equipos enlazados directamente a esta cuenta
                        posiciones-N.csv el recorrido de cada uno de esos equipos
                        eventos-N.csv    los avisos y sucesos de cada uno
                        rutas.json       las rutas de esta cuenta
                        quedadas.json    las quedadas que ha organizado o a las que está apuntada

                        No se incluyen los equipos que esta cuenta ve a través de un grupo:
                        esos recorridos son datos de otras personas.
                        """).formatted(user.getName()).getBytes(StandardCharsets.UTF_8));

                ObjectNode account = objectMapper.valueToTree(user);
                account.remove(List.of("totpKey", "password", "hashedPassword", "salt"));
                json(zip, "usuario.json", account);

                List<Long> devices = ownDevices(userId);
                List<Device> deviceRecords = new ArrayList<>();
                for (long deviceId : devices) {
                    Device device = storage.getObject(Device.class, new Request(
                            new Columns.All(), new Condition.Equals("id", deviceId)));
                    if (device != null) {
                        deviceRecords.add(device);
                    }
                }
                json(zip, "equipos.json", deviceRecords);

                for (Device device : deviceRecords) {
                    csv(zip, "posiciones-" + device.getId() + ".csv",
                            CsvLine.of("fecha", "latitud", "longitud", "altitud", "velocidad_nudos",
                                    "rumbo", "direccion", "atributos"),
                            "SELECT fixtime, latitude, longitude, altitude, speed, course, address, attributes"
                                    + " FROM tc_positions WHERE deviceid = ? ORDER BY fixtime",
                            device.getId());
                    csv(zip, "eventos-" + device.getId() + ".csv",
                            CsvLine.of("fecha", "tipo", "atributos"),
                            "SELECT eventtime, type, attributes FROM tc_events WHERE deviceid = ? ORDER BY eventtime",
                            device.getId());
                }

                json(zip, "rutas.json", storage.getObjects(Ruta.class, new Request(
                        new Columns.All(), new Condition.Equals("creadorId", userId))));

                List<Quedada> meets = new ArrayList<>(storage.getObjects(Quedada.class, new Request(
                        new Columns.All(), new Condition.Equals("creadorId", userId))));
                for (QuedadaUsuario link : storage.getObjects(QuedadaUsuario.class, new Request(
                        new Columns.Exclude("id"), new Condition.Equals("userId", userId)))) {
                    if (meets.stream().noneMatch(q -> q.getId() == link.getQuedadaId())) {
                        Quedada meet = storage.getObject(Quedada.class, new Request(
                                new Columns.All(), new Condition.Equals("id", link.getQuedadaId())));
                        if (meet != null) {
                            meets.add(meet);
                        }
                    }
                }
                json(zip, "quedadas.json", meets);
            } catch (SQLException | StorageException e) {
                throw new IOException(e);
            }
        };

        return Response.ok(body)
                .header("Content-Disposition", "attachment; filename=\"datos-socio-" + userId + ".zip\"")
                .build();
    }

}
