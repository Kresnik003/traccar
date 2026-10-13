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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * The privacy notice and who accepted which version of it (improvement 73).
 *
 * GET says the current version, whether this user accepted it and the real
 * retention periods of this server, so the notice shown in the web never
 * promises a period the server does not apply. POST records the acceptance
 * with the time of the server, never one sent by the browser. Administrators
 * can list who has and has not accepted the current version.
 */
@Path("consentimiento")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConsentimientoResource extends BaseResource {

    @Inject
    private Config config;

    @Inject
    private DataSource dataSource;

    @Context
    private HttpServletRequest request;

    private String version() {
        return config.getString(Keys.WEB_PRIVACY_VERSION);
    }

    private Timestamp accepted(long userId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT momento FROM nx_consentimientos WHERE userid = ? AND version = ?")) {
            statement.setLong(1, userId);
            statement.setString(2, version());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getTimestamp(1) : null;
            }
        }
    }

    private Map<String, Object> state(long userId) throws SQLException {
        Map<String, Object> retention = new LinkedHashMap<>();
        retention.put("posiciones", config.getInteger(Keys.DATABASE_POSITION_RETENTION_DAYS));
        retention.put("sinFijacion", config.getInteger(Keys.DATABASE_INVALID_POSITION_RETENTION_DAYS));
        retention.put("estados", config.getInteger(Keys.DATABASE_STATUS_EVENT_RETENTION_DAYS));
        retention.put("rodadas", config.getInteger(Keys.DATABASE_RIDE_RETENTION_DAYS));
        retention.put("accesos", config.getInteger(Keys.DATABASE_ACCESS_LOG_RETENTION_DAYS));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version());
        result.put("aceptado", accepted(userId));
        result.put("conservacion", retention);
        return result;
    }

    @GET
    public Map<String, Object> get() throws StorageException {
        try {
            return state(getUserId());
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    /**
     * Only the version is read. Anything else in the body - a date, above all -
     * is ignored rather than rejected, so that a forged time is simply not used.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Acceptance(String version) {
    }

    /**
     * The version has to be the current one. A web that loaded before the notice
     * changed would otherwise record the acceptance of a text it never showed.
     */
    @POST
    public Map<String, Object> accept(Acceptance acceptance) throws StorageException {
        // A shared link logs in as a temporary user. Whoever opens it is not the
        // owner of the data and cannot consent on their behalf.
        if (permissionsService.getUser(getUserId()).getTemporary()) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        if (acceptance == null || !version().equals(acceptance.version())) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("version", version())).build());
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO nx_consentimientos (userid, version, momento, direccion) VALUES (?, ?, ?, ?)")) {
            statement.setLong(1, getUserId());
            statement.setString(2, version());
            statement.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            statement.setString(4, request != null ? request.getRemoteAddr() : null);
            statement.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException e) {
            // Already accepted: the first time is the one that counts.
        } catch (SQLException e) {
            throw new StorageException(e);
        }
        try {
            return state(getUserId());
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    @Path("usuarios")
    @GET
    public List<Map<String, Object>> users() throws StorageException {
        permissionsService.checkAdmin(getUserId());
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT u.id, u.name, c.momento FROM tc_users u"
                             + " LEFT JOIN nx_consentimientos c ON c.userid = u.id AND c.version = ?"
                             + " ORDER BY c.momento IS NULL DESC, u.name")) {
            statement.setString(1, version());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("userId", rows.getLong(1));
                    row.put("nombre", rows.getString(2));
                    row.put("aceptado", rows.getTimestamp(3));
                    result.add(row);
                }
            }
        } catch (SQLException e) {
            throw new StorageException(e);
        }
        return result;
    }

}
