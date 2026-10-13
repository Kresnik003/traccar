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
package org.traccar.seguridad;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.model.User;
import org.traccar.storage.DatabaseStorage;
import org.traccar.storage.Storage;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who rides a bike, asked to a real database with Traccar's own permission query:
 * a mock only answers what the test expects, and the fault found in review on
 * 2026-09-30 was precisely which query was asked (direct links only).
 *
 * The bench of the Ducati: Juan (2) sees it through the group "Motos" (10),
 * Esther (6) is linked to it directly. The FMT100 (9) is in "Garaje" (11), a
 * group inside "Motos".
 */
public class DestinatariosBaseRealTest {

    private JdbcDataSource dataSource;
    private Destinatarios destinatarios;

    private void sql(String... sentencias) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String sentencia : sentencias) {
                statement.execute(sentencia);
            }
        }
    }

    @BeforeEach
    public void preparar() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:motoristas;DB_CLOSE_DELAY=-1");
        sql("DROP ALL OBJECTS",
                "CREATE TABLE tc_users (id INT PRIMARY KEY, name VARCHAR(128), attributes VARCHAR(4000),"
                        + " administrator BOOLEAN DEFAULT FALSE, disabled BOOLEAN DEFAULT FALSE,"
                        + " temporary BOOLEAN DEFAULT FALSE)",
                "CREATE TABLE tc_groups (id INT PRIMARY KEY, name VARCHAR(128), groupid INT,"
                        + " attributes VARCHAR(4000))",
                "CREATE TABLE tc_devices (id INT PRIMARY KEY, name VARCHAR(128), groupid INT,"
                        + " attributes VARCHAR(4000))",
                "CREATE TABLE tc_user_device (userid INT, deviceid INT)",
                "CREATE TABLE tc_user_group (userid INT, groupid INT)",
                "CREATE TABLE nx_motoristas (id BIGINT AUTO_INCREMENT PRIMARY KEY, deviceid INT NOT NULL UNIQUE,"
                        + " userid INT NOT NULL, desde TIMESTAMP NOT NULL)",
                "INSERT INTO tc_users (id, name, attributes) VALUES"
                        + " (2, 'Juan', '{\"telegramChatId\":\"900\"}'),"
                        + " (6, 'Esther', '{\"telegramChatId\":\"906\"}'),"
                        + " (1, 'Admin', '{}')",
                "UPDATE tc_users SET administrator = TRUE WHERE id = 1",
                "INSERT INTO tc_groups (id, name, groupid, attributes) VALUES (10, 'Motos', NULL, '{}'),"
                        + " (11, 'Garaje', 10, '{}')",
                "INSERT INTO tc_devices (id, name, groupid, attributes) VALUES (8, 'Ducati', 10, '{}'),"
                        + " (9, 'FMT100', 11, '{}')",
                "INSERT INTO tc_user_group (userid, groupid) VALUES (2, 10)",
                "INSERT INTO tc_user_device (userid, deviceid) VALUES (6, 8)");
        Storage storage = new DatabaseStorage(new Config(), dataSource, new ObjectMapper());
        destinatarios = new Destinatarios(storage);
    }

    private Optional<Long> motorista(long deviceId) throws Exception {
        return destinatarios.motorista(deviceId).map(User::getId);
    }

    @Test
    public void theOwnerThroughAGroupAndAnotherDirectlyIsNobodyUntilOneChooses() throws Exception {
        // Direct links only would have made Esther the rider of Juan's bike.
        assertEquals(Optional.empty(), motorista(8));
        destinatarios.elegir(8, 2);
        assertEquals(Optional.of(2L), motorista(8));
        destinatarios.elegir(8, 6);
        assertEquals(Optional.of(6L), motorista(8));
    }

    @Test
    public void aBikeInANestedGroupIsSeenByWhoSeesTheParentGroup() throws Exception {
        assertEquals(Optional.of(2L), motorista(9));
        assertTrue(destinatarios.puedeLlevarla(9, 2));
        assertFalse(destinatarios.puedeLlevarla(9, 6));
    }

    @Test
    public void aChoiceThatNoLongerSeesTheBikeFallsBack() throws Exception {
        destinatarios.elegir(8, 2);
        sql("DELETE FROM tc_user_group WHERE userid = 2");
        assertEquals(Optional.of(6L), motorista(8));
        destinatarios.elegir(8, 6);
        sql("UPDATE tc_users SET disabled = TRUE WHERE id = 6", "INSERT INTO tc_user_group VALUES (2, 10)");
        assertEquals(Optional.of(2L), motorista(8));
    }

    @Test
    public void choosingTwiceKeepsOneRowPerBike() throws Exception {
        destinatarios.elegir(8, 2);
        destinatarios.elegir(8, 6);
        destinatarios.elegir(8, 2);
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var filas = statement.executeQuery("SELECT COUNT(*) FROM nx_motoristas WHERE deviceid = 8")) {
            filas.next();
            assertEquals(1, filas.getInt(1));
        }
    }

    @Test
    public void anAdministratorWhoIsNotLinkedDoesNotCount() throws Exception {
        // Traccar lets an administrator see every bike; riding it needs a link.
        assertFalse(destinatarios.puedeLlevarla(8, 1));
        sql("INSERT INTO tc_user_device VALUES (1, 9)");
        assertEquals(Optional.empty(), motorista(9));
    }

}
