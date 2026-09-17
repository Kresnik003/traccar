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
package org.traccar.schedule;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Deletes positions and events older than database.positionRetentionDays.
 *
 * Off by default. Keeping location history for a limited time is a legal
 * requirement for anyone storing it about other people, and until now it
 * depended on an external script - one that, in this project, ran every week
 * for months without deleting a single row.
 *
 * Two rules the deletion never breaks:
 *
 * - A device's last known position is kept, however old. A bike stored in a
 *   garage for a year still has to show where it was left.
 * - The loop has a ceiling. The external script looped until the database said
 *   no rows were affected; when the query failed that never happened, and two
 *   copies were found spinning for weeks.
 *
 * Deletes in small batches so that a large backlog does not hold locks on the
 * positions table for minutes while devices are trying to write to it. Uses
 * DELETE ... LIMIT, which is MySQL syntax.
 */
public class TaskPositionRetention extends SingleScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskPositionRetention.class);

    static final int BATCH = 5000;
    static final int MAX_BATCHES = 2000;
    private static final long INITIAL_DELAY_MINUTES = 2;

    private final Config config;
    private final DataSource dataSource;

    @Inject
    public TaskPositionRetention(Config config, DataSource dataSource) {
        this.config = config;
        this.dataSource = dataSource;
    }

    @FunctionalInterface
    interface BatchDelete {
        int delete() throws SQLException;
    }

    /**
     * Runs batches until one deletes nothing, one fails, or the ceiling is hit.
     *
     * @return rows deleted
     */
    static long purge(BatchDelete batch, int maxBatches) {
        long total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int deleted;
            try {
                deleted = batch.delete();
            } catch (SQLException e) {
                LOGGER.warn("Retention batch failed, stopping this run", e);
                break;
            }
            total += deleted;
            if (deleted == 0) {
                break;
            }
        }
        return total;
    }

    /**
     * Devices that have history but no longer exist.
     *
     * Deleting a device does not delete its positions and events: Traccar 6.3
     * dropped the foreign keys. For anyone who has to erase a person's data on
     * request, deleting the device and leaving its track behind is not erasing
     * anything.
     *
     * Two guards, because this deletes whole histories. An empty list of
     * existing devices deletes nothing: a failed or partial read must not turn
     * every track into an orphan. And if at least half of the devices with
     * history look orphaned, nothing is deleted either and a warning is logged;
     * that looks like a broken read or a restore gone wrong far more than like
     * a club deleting half its fleet.
     *
     * That second guard also stops a legitimate clean-up after deleting many
     * devices at once - a load test that created and removed three hundred did
     * exactly that on the test bench. A positive limit replaces it: up to that
     * many deleted devices are cleaned, and above it nothing is.
     */
    static Set<Long> orphans(Set<Long> withHistory, Set<Long> existing, int limit) {
        Set<Long> result = new HashSet<>();
        if (existing.isEmpty()) {
            return result;
        }
        for (long id : withHistory) {
            if (!existing.contains(id)) {
                result.add(id);
            }
        }
        boolean refuse = limit > 0
                ? result.size() > limit
                : result.size() > 5 && result.size() * 2 >= withHistory.size();
        if (refuse) {
            LOGGER.warn("{} of {} devices with history look orphaned; not deleting anything"
                    + " (database.deleteOrphanHistoryLimit is {})", result.size(), withHistory.size(), limit);
            return new HashSet<>();
        }
        return result;
    }

    private Set<Long> ids(String sql) throws SQLException {
        Set<Long> result = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(rows.getLong(1));
            }
        }
        return result;
    }

    private int deleteByDevice(String table, long deviceId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM " + table + " WHERE deviceid = ? LIMIT " + BATCH)) {
            statement.setLong(1, deviceId);
            return statement.executeUpdate();
        }
    }

    /**
     * Uses the (deviceid, fixtime) and (deviceid, eventtime) indexes: the
     * device ids come from a loose index scan and each deletion is a range on
     * one device. A single DELETE ... WHERE deviceid NOT IN (...) was measured
     * first and scans the whole table on every batch.
     */
    private void deleteOrphans() {
        Set<Long> withHistory;
        Set<Long> existing;
        try {
            withHistory = ids("SELECT DISTINCT deviceid FROM tc_positions");
            withHistory.addAll(ids("SELECT DISTINCT deviceid FROM tc_events"));
            existing = ids("SELECT id FROM tc_devices");
        } catch (SQLException e) {
            LOGGER.warn("Could not read devices for orphan history, skipping", e);
            return;
        }
        long positions = 0;
        long events = 0;
        Set<Long> orphaned = orphans(withHistory, existing,
                config.getInteger(Keys.DATABASE_DELETE_ORPHAN_HISTORY_LIMIT));
        for (long deviceId : orphaned) {
            positions += purge(() -> deleteByDevice("tc_positions", deviceId), MAX_BATCHES);
            events += purge(() -> deleteByDevice("tc_events", deviceId), MAX_BATCHES);
        }
        LOGGER.info("Orphan history: removed {} positions and {} events of {} deleted devices",
                positions, events, orphaned.size());
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        if (config.getInteger(Keys.DATABASE_POSITION_RETENTION_DAYS) > 0
                || config.getInteger(Keys.DATABASE_ACCESS_LOG_RETENTION_DAYS) > 0
                || config.getBoolean(Keys.DATABASE_DELETE_ORPHAN_HISTORY)) {
            executor.scheduleAtFixedRate(
                    this, INITIAL_DELAY_MINUTES, TimeUnit.DAYS.toMinutes(1), TimeUnit.MINUTES);
        }
    }

    private int execute(String sql, Timestamp cutoff) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, cutoff);
            return statement.executeUpdate();
        }
    }

    @Override
    public void run() {
        if (config.getBoolean(Keys.DATABASE_DELETE_ORPHAN_HISTORY)) {
            deleteOrphans();
        }
        int accessDays = config.getInteger(Keys.DATABASE_ACCESS_LOG_RETENTION_DAYS);
        if (accessDays > 0) {
            Timestamp accessCutoff = new Timestamp(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(accessDays));
            long accesses = purge(() -> execute(
                    "DELETE FROM nx_accesos WHERE momento < ? LIMIT " + BATCH, accessCutoff), MAX_BATCHES);
            LOGGER.info("Access log retention of {} days: removed {} entries", accessDays, accesses);
        }
        int days = config.getInteger(Keys.DATABASE_POSITION_RETENTION_DAYS);
        if (days <= 0) {
            return;
        }
        Timestamp cutoff = new Timestamp(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days));
        long positions = purge(() -> execute(
                "DELETE FROM tc_positions WHERE fixtime < ?"
                        + " AND id NOT IN (SELECT positionid FROM tc_devices WHERE positionid IS NOT NULL)"
                        + " LIMIT " + BATCH, cutoff), MAX_BATCHES);
        long events = purge(() -> execute(
                "DELETE FROM tc_events WHERE eventtime < ? LIMIT " + BATCH, cutoff), MAX_BATCHES);
        LOGGER.info("Retention of {} days: removed {} positions and {} events", days, positions, events);
    }

}
