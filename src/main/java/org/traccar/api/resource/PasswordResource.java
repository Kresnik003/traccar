/*
 * Copyright 2021 - 2026 Anton Tananaev (anton@traccar.org)
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
import org.traccar.helper.ResetCode;
import org.traccar.mail.MailManager;
import org.traccar.model.User;
import org.traccar.notification.TextTemplateFormatter;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import javax.sql.DataSource;

@Path("password")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
public class PasswordResource extends BaseResource {

    @Inject
    private MailManager mailManager;

    @Inject
    private DataSource dataSource;

    @Inject
    private TextTemplateFormatter textTemplateFormatter;

    @Path("reset")
    @PermitAll
    @POST
    public Response reset(@FormParam("email") String email)
            throws StorageException, ExecutionException, InterruptedException, GeneralSecurityException, IOException {

        User user = storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("LOWER(email)", email.toLowerCase(Locale.ROOT))));
        if (user != null) {
            // A one-time code, not the login token prepareContext puts in the
            // context: that one opened a session, skipped the second factor and
            // stayed valid for a week after the password had changed.
            String code = ResetCode.generate();
            long now = System.currentTimeMillis();
            try (Connection connection = dataSource.getConnection()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM nx_recuperaciones WHERE userid = ? AND usada IS NULL")) {
                    statement.setLong(1, user.getId());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO nx_recuperaciones (userid, huella, creada, caduca) VALUES (?, ?, ?, ?)")) {
                    statement.setLong(1, user.getId());
                    statement.setString(2, ResetCode.hash(code));
                    statement.setTimestamp(3, new Timestamp(now));
                    statement.setTimestamp(4, new Timestamp(now + ResetCode.VALIDITY_MILLIS));
                    statement.executeUpdate();
                }
            } catch (SQLException e) {
                throw new StorageException(e);
            }
            var velocityContext = textTemplateFormatter.prepareContext(permissionsService.getServer(), user);
            velocityContext.put("token", code);
            var fullMessage = textTemplateFormatter.formatMessage(velocityContext, "passwordReset", false);
            mailManager.sendMessage(user, true, fullMessage.subject(), fullMessage.body()).get();
        }
        return Response.ok().build();
    }

    @Path("update")
    @PermitAll
    @POST
    public Response update(
            @FormParam("token") String token, @FormParam("password") String password)
            throws StorageException, GeneralSecurityException, IOException {

        if (!ResetCode.plausible(token)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        long userId = 0;
        long now = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT userid FROM nx_recuperaciones WHERE huella = ? AND usada IS NULL AND caduca > ?")) {
                statement.setString(1, ResetCode.hash(token));
                statement.setTimestamp(2, new Timestamp(now));
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) {
                        userId = rows.getLong(1);
                    }
                }
            }
            if (userId == 0) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            // Spent in the same statement that checks it was still unused, so
            // two requests at once cannot both use it.
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE nx_recuperaciones SET usada = ? WHERE huella = ? AND usada IS NULL")) {
                statement.setTimestamp(1, new Timestamp(now));
                statement.setString(2, ResetCode.hash(token));
                if (statement.executeUpdate() != 1) {
                    return Response.status(Response.Status.NOT_FOUND).build();
                }
            }
        } catch (SQLException e) {
            throw new StorageException(e);
        }
        User user = storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("id", userId)));
        if (user != null) {
            user.setPassword(password);
            storage.updateObject(user, new Request(
                    new Columns.Include("hashedPassword", "salt"),
                    new Condition.Equals("id", userId)));
            return Response.ok().build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

}
