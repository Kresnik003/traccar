/*
 * Copyright 2016 - 2025 Anton Tananaev (anton@traccar.org)
 * Copyright 2015 Amila Silva
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
package org.traccar.handler;

import jakarta.inject.Inject;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.DistanceCalculator;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.helper.model.Fijacion;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

public class DistanceHandler extends BasePositionHandler {

    private final CacheManager cacheManager;

    private final boolean filter;
    private final int minError;
    private final int maxError;

    @Inject
    public DistanceHandler(Config config, CacheManager cacheManager) {
        this.cacheManager = cacheManager;
        this.filter = config.getBoolean(Keys.COORDINATES_FILTER);
        this.minError = config.getInteger(Keys.COORDINATES_MIN_ERROR);
        this.maxError = config.getInteger(Keys.COORDINATES_MAX_ERROR);
    }

    @Override
    public void onPosition(Position position, Callback callback) {

        double distance = 0.0;
        if (position.hasAttribute(Position.KEY_DISTANCE)) {
            distance = position.getDouble(Position.KEY_DISTANCE);
        }
        double totalDistance;
        Position last = cacheManager.getPosition(position.getDeviceId());
        if (last != null) {
            totalDistance = last.getDouble(Position.KEY_TOTAL_DISTANCE);
            // NO FIX, NO DISTANCE, IN EITHER DIRECTION. A heartbeat at 0,0 after a
            // real position measured the jump to the Gulf of Guinea, and the next
            // real position measured the jump back: 4,909 km from Ferrol,
            // added to totalDistance, and read by the trip logic as a trip that
            // moved and stopped - a "stopped" alert on a parked bike. Excluding
            // those positions from the motion history (0029) did not help: the
            // distance was already written on the position.
            //
            // So a position without a fix measures nothing, and a position with a
            // fix measures from the last one that had a fix, not from the last one.
            boolean guard = AttributeUtil.lookup(
                    cacheManager, Keys.EVENT_IGNORE_INVALID_POSITIONS, position.getDeviceId());
            boolean fix = Fijacion.tiene(position);
            Position reference = guard ? Fijacion.ultima(cacheManager.getPositions(position.getDeviceId())) : last;
            if (!position.hasAttribute(Position.KEY_DISTANCE)) {
                distance = !guard || (fix && reference != null)
                        ? DistanceCalculator.distance(position, reference) : 0;
            }
            // The filter would copy the coordinates of the reference, and its
            // valid flag, onto a position without a fix: a heartbeat made valid.
            if (filter && reference != null && (!guard || fix)
                    && reference.getLatitude() != 0 && reference.getLongitude() != 0) {
                boolean satisfiesMin = minError == 0 || distance > minError;
                boolean satisfiesMax = maxError == 0 || distance < maxError;
                if (!satisfiesMin || !satisfiesMax) {
                    position.setValid(reference.getValid());
                    position.setLatitude(reference.getLatitude());
                    position.setLongitude(reference.getLongitude());
                    distance = 0;
                }
            }
        } else {
            totalDistance = 0.0;
        }
        position.set(Position.KEY_DISTANCE, distance);
        position.set(Position.KEY_TOTAL_DISTANCE, totalDistance + distance);

        callback.processed(false);
    }

}
