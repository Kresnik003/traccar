package org.traccar.handler;

import org.junit.jupiter.api.Test;
import org.traccar.BaseTest;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A heartbeat without a fix must not move the device out of its geofences.
 *
 * Seen live on 2026-09-09 with a real bike parked in its garage: the tracker
 * lost GPS, reported 0,0, and the club got a "left the geofence" alert. And
 * again on 2026-09-18 in the twenty-device campaign, because the fix had never
 * become a patch.
 */
public class GeofenceHandlerTest extends BaseTest {

    private CacheManager cacheManager(boolean ignoreInvalid, Position last) {
        var device = mock(Device.class);
        when(device.getAttributes()).thenReturn(new HashMap<>());
        var config = mock(Config.class);
        when(config.getString(Keys.EVENT_IGNORE_INVALID_POSITIONS.getKey()))
                .thenReturn(String.valueOf(ignoreInvalid));
        when(config.getBoolean(Keys.EVENT_IGNORE_INVALID_POSITIONS)).thenReturn(ignoreInvalid);
        var cacheManager = mock(CacheManager.class);
        when(cacheManager.getConfig()).thenReturn(config);
        when(cacheManager.getObject(any(), anyLong())).thenReturn(device);
        when(cacheManager.getPosition(anyLong())).thenReturn(last);
        when(cacheManager.getDeviceObjects(anyLong(), eq(Geofence.class))).thenReturn(new HashSet<Geofence>());
        return cacheManager;
    }

    private Position position(boolean valid) {
        Position position = new Position();
        position.setDeviceId(1);
        position.setValid(valid);
        position.setLatitude(valid ? 43.48 : 0);
        position.setLongitude(valid ? -8.20 : 0);
        return position;
    }

    private final boolean[] processed = new boolean[1];

    private final BasePositionHandler.Callback callback = (filtered) -> processed[0] = true;

    @Test
    public void noFixKeepsTheLastGeofences() {
        Position last = position(true);
        last.setGeofenceIds(List.of(7L));
        GeofenceHandler handler = new GeofenceHandler(cacheManager(true, last));

        Position heartbeat = position(false);
        handler.onPosition(heartbeat, callback);

        assertEquals(List.of(7L), heartbeat.getGeofenceIds());
        assertTrue(processed[0], "the pipeline must always be let through");
    }

    @Test
    public void noFixWithNothingBeforeStaysEmpty() {
        GeofenceHandler handler = new GeofenceHandler(cacheManager(true, null));

        Position heartbeat = position(false);
        handler.onPosition(heartbeat, callback);

        assertNull(heartbeat.getGeofenceIds());
        assertTrue(processed[0]);
    }

    @Test
    public void switchedOffBehavesLikeStockTraccar() {
        Position last = position(true);
        last.setGeofenceIds(List.of(7L));
        GeofenceHandler handler = new GeofenceHandler(cacheManager(false, last));

        Position heartbeat = position(false);
        handler.onPosition(heartbeat, callback);

        // Stock behaviour: geofences recomputed from 0,0, which is inside none.
        assertNull(heartbeat.getGeofenceIds());
        assertTrue(processed[0]);
    }

    @Test
    public void aFixIsStillComputed() {
        Position last = position(true);
        last.setGeofenceIds(List.of(7L));
        GeofenceHandler handler = new GeofenceHandler(cacheManager(true, last));

        Position fix = position(true);
        handler.onPosition(fix, callback);

        // No geofences configured in the mock: computed, not carried over.
        assertNull(fix.getGeofenceIds());
        assertTrue(processed[0]);
    }

}
