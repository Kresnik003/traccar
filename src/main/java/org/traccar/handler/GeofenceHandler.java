/*
 * Copyright 2023 - 2025 Anton Tananaev (anton@traccar.org)
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
import org.traccar.config.Keys;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.helper.model.Fijacion;
import org.traccar.helper.model.GeofenceUtil;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.List;

public class GeofenceHandler extends BasePositionHandler {

    private final CacheManager cacheManager;

    @Inject
    public GeofenceHandler(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public void onPosition(Position position, Callback callback) {

        // A position without a fix says nothing about where the device is. Computing
        // geofences from its coordinates puts the device outside every geofence, and
        // GeofenceEventHandler then reports an exit that never happened. A sleeping
        // tracker that heartbeats without a fix would leave and re-enter its geofence
        // several times a day. Carry over the last known geofences instead, the same
        // way DistanceHandler and OutdatedHandler carry over the last known fix.
        //
        // "Without a fix" is Fijacion.tiene(): the valid flag alone let through 0,0
        // marked valid and cell tower estimates, which are off by enough to leave
        // a garage geofence.
        if (!Fijacion.tiene(position)
                && AttributeUtil.lookup(
                        cacheManager, Keys.EVENT_IGNORE_INVALID_POSITIONS, position.getDeviceId())) {
            Position last = cacheManager.getPosition(position.getDeviceId());
            if (last != null && last.getGeofenceIds() != null) {
                position.setGeofenceIds(last.getGeofenceIds());
            }
            callback.processed(false);
            return;
        }

        List<Long> geofenceIds = GeofenceUtil.getCurrentGeofences(cacheManager, position);
        if (!geofenceIds.isEmpty()) {
            position.setGeofenceIds(geofenceIds);
        }
        callback.processed(false);
    }

}
