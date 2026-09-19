/*
 * Copyright 2016 - 2026 Anton Tananaev (anton@traccar.org)
 * Copyright 2017 Andrey Kunitsyn (andrey@traccar.org)
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
package org.traccar.handler.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.helper.model.Fijacion;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.reports.common.TripsConfig;
import org.traccar.session.cache.CacheManager;
import org.traccar.session.state.MotionProcessor;
import org.traccar.session.state.MotionState;
import org.traccar.session.state.NewMotionProcessor;
import org.traccar.session.state.NewMotionState;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.stream.Collectors;

public class MotionEventHandler extends BasePositionEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MotionEventHandler.class);

    private final Config config;
    private final Storage storage;

    @Inject
    public MotionEventHandler(Config config, CacheManager cacheManager, Storage storage) {
        super(cacheManager);
        this.config = config;
        this.storage = storage;
    }

    @Override
    protected void onPosition(Position position, Position lastPosition, Callback callback) {

        long deviceId = position.getDeviceId();
        Device device = cacheManager.getObject(Device.class, deviceId);
        if (device == null) {
            return;
        }

        var attributeProvider = new AttributeUtil.CacheProvider(cacheManager, deviceId);

        // A position without a fix carries no information about movement: its
        // coordinates are 0,0, a cell tower estimate, or nothing at all. It must not
        // open a trip. What it still says is that TIME PASSED, and that is enough
        // to close one: see stillThere().
        //
        // (The comment that stood here until 2026-09-19 said that skipping these
        // positions kept a heartbeat at 0,0 from opening a trip. It did not: the
        // next position with a fix carried the 4,909 km leg in its distance,
        // measured by DistanceHandler against the heartbeat, and the stop gap rule
        // raised "moving" and "stopped" from it. DistanceHandler no longer measures
        // from a position without a fix.)
        boolean noFix = !Fijacion.tiene(position)
                && AttributeUtil.lookup(attributeProvider, Keys.EVENT_IGNORE_INVALID_POSITIONS);
        if (noFix && !(config.getBoolean(Keys.REPORT_TRIP_NEW_LOGIC) && device.getMotionStreak())) {
            return;
        }

        if (config.getBoolean(Keys.REPORT_TRIP_NEW_LOGIC)) {
            double minDistance = AttributeUtil.lookup(attributeProvider, Keys.REPORT_TRIP_MIN_DISTANCE);
            long minDuration = AttributeUtil.lookup(attributeProvider, Keys.REPORT_TRIP_MIN_DURATION) * 1000;
            long stopGap = AttributeUtil.lookup(attributeProvider, Keys.REPORT_TRIP_STOP_GAP) * 1000;
            boolean useIgnition = AttributeUtil.lookup(attributeProvider, Keys.REPORT_TRIP_USE_IGNITION);
            Position motionPosition = position;
            if (noFix) {
                motionPosition = stillThere(position, deviceId);
                if (motionPosition == null) {
                    return;
                }
            }
            handleNewLogic(device, motionPosition, minDistance, minDuration, stopGap, useIgnition, callback);
        } else {
            TripsConfig tripsConfig = new TripsConfig(attributeProvider);
            handleOldLogic(device, position, lastPosition, tripsConfig, callback);
        }
    }

    /**
     * A position without a fix, placed where the last fix was.
     *
     * A vehicle that loses the fix before its stop is detected - the typical case
     * is riding into an underground garage - sends only positions without a fix
     * from then on. Skipping them all left the trip open all night: no "stopped",
     * and, since the trip was still open, no "moving" when the bike left the next
     * day. Stock Traccar got this right by accident with trackers that repeat the
     * last coordinates when they lose the fix: the vehicle looks still, and it
     * stops. This does the same on purpose: the heartbeat stands at the last fix,
     * with no distance, and the stop is decided on time as usual.
     *
     * It can only close a trip, never open one, and only when the new trip logic
     * has a trip open. A long tunnel reads the same as a garage; that is the
     * price, and it is the stock behaviour for trackers that repeat coordinates.
     *
     * @return null if there is no fix to stand on
     */
    private Position stillThere(Position position, long deviceId) {
        Position lastFix = Fijacion.ultima(cacheManager.getPositions(deviceId));
        if (lastFix == null) {
            return null;
        }
        Position still = new Position(position.getProtocol());
        // The event points at the last fix, which is where the vehicle stopped;
        // the heartbeat's own row holds no place. The time is the heartbeat's.
        still.setId(lastFix.getId());
        still.setDeviceId(deviceId);
        still.setServerTime(position.getServerTime());
        still.setDeviceTime(position.getDeviceTime());
        still.setFixTime(position.getFixTime());
        still.setValid(true);
        still.setLatitude(lastFix.getLatitude());
        still.setLongitude(lastFix.getLongitude());
        still.set(Position.KEY_DISTANCE, 0.0);
        return still;
    }

    private void handleNewLogic(
            Device device, Position position,
            double minDistance, long minDuration, long stopGap, boolean useIgnition, Callback callback) {
        NewMotionState state = new NewMotionState();
        state.setMotionStreak(device.getMotionStreak());
        // The history the processor walks holds only positions with a fix: one
        // without, among real ones, is a leg of thousands of kilometres.
        state.setPositions(cacheManager.getPositions(device.getId()).stream()
                .filter(Fijacion::tiene)
                .collect(Collectors.toCollection(ArrayDeque::new)));
        if (device.hasAttribute("motionTime")) {
            // TODO temporary migration path
            state.setEventPosition(
                    new Date(((Number) device.getAttributes().remove("motionTime")).longValue()),
                    ((Number) device.getAttributes().remove("motionLat")).doubleValue(),
                    ((Number) device.getAttributes().remove("motionLon")).doubleValue());
            state.setChanged(true);
        } else if (device.getMotionTime() != null) {
            state.setEventPosition(
                    device.getMotionTime(),
                    device.getMotionLatitude(),
                    device.getMotionLongitude());
        } else {
            state.setEventPosition(position);
        }
        NewMotionProcessor.updateState(state, position, minDistance, minDuration, stopGap, useIgnition);
        if (state.isChanged()) {
            device.setMotionStreak(state.getMotionStreak());
            device.setMotionTime(state.getEventTime());
            device.setMotionLatitude(state.getEventLatitude());
            device.setMotionLongitude(state.getEventLongitude());
            try {
                storage.updateObject(device, new Request(
                        new Columns.Include(
                                "motionStreak", "motionTime", "motionLatitude", "motionLongitude",
                                "attributes"),
                        new Condition.Equals("id", device.getId())));
            } catch (StorageException e) {
                LOGGER.warn("Update device motion error", e);
            }
        }
        for (var event : state.getEvents()) {
            callback.eventDetected(event);
        }
    }

    private void handleOldLogic(
            Device device, Position position, Position lastPosition, TripsConfig tripsConfig, Callback callback) {
        MotionState state = MotionState.fromDevice(device);
        MotionProcessor.updateState(
                state, lastPosition, position, position.getBoolean(Position.KEY_MOTION), tripsConfig);
        if (state.isChanged()) {
            state.toDevice(device);
            try {
                storage.updateObject(device, new Request(
                        new Columns.Include(
                                "motionStreak", "motionState", "motionPositionId", "motionTime", "motionDistance"),
                        new Condition.Equals("id", device.getId())));
            } catch (StorageException e) {
                LOGGER.warn("Update device motion error", e);
            }
        }
        if (state.getEvent() != null) {
            callback.eventDetected(state.getEvent());
        }
    }

}
