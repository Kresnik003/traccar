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
package org.traccar.database;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;

/**
 * Every notification a user was sent, so that it can be read again later and
 * marked as read (improvement 93).
 *
 * Until now a notification lived only in the page that was open when it
 * arrived: reload it and it was gone, and there was no telling what had been
 * seen. The events themselves are kept by Traccar, but not WHICH of them were
 * sent to WHOM - that depends on the notifications, their devices and groups,
 * their calendars and alarm filters at the moment the event happened. So it is
 * written down right there, when NotificationManager decides to send it,
 * instead of being rebuilt afterwards with rules that may have changed since.
 *
 * One row per user and event, however many notifications or channels matched:
 * the inbox lists what happened, not how many ways it was announced.
 */
@Singleton
public class BandejaAvisos {

    private static final Logger LOGGER = LoggerFactory.getLogger(BandejaAvisos.class);

    private final DataSource dataSource;

    @Inject
    public BandejaAvisos(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Write down that this event was sent to this user.
     *
     * Never throws: this runs in the middle of sending the notification, and
     * an inbox that cannot be written must not stop the alert from going out.
     *
     * @return true if a new row was written
     */
    public boolean anotar(long userId, long eventId) {
        // An event that could not be saved has no id, and a row pointing to
        // nothing would fail the foreign key anyway.
        if (userId <= 0 || eventId <= 0) {
            return false;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO nx_avisos (userid, eventid, creado) VALUES (?, ?, ?)")) {
            statement.setLong(1, userId);
            statement.setLong(2, eventId);
            statement.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            statement.executeUpdate();
            return true;
        } catch (SQLIntegrityConstraintViolationException e) {
            // Already there: the same event matched a second notification of the
            // same user. The first row is the one that counts.
            return false;
        } catch (SQLException e) {
            LOGGER.warn("Could not record notification of event {} for user {}", eventId, userId, e);
            return false;
        }
    }

}
