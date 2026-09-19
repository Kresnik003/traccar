package org.traccar.handler.events;

import org.junit.jupiter.api.Test;
import org.traccar.BaseTest;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A heartbeat without a fix says nothing about movement.
 *
 * The new trip logic measures distance, and a 0,0 heartbeat between two real
 * positions reads as a 5,000 km jump: it opened a phantom trip on a parked bike
 * on 2026-09-08 and closed it sixteen hours later with a false "stopped".
 */
public class MotionEventHandlerNoFixTest extends BaseTest {

    private Device device;

    private MotionEventHandler handler(boolean ignoreInvalid, List<Position> cached) {
        device = mock(Device.class);
        when(device.getAttributes()).thenReturn(new HashMap<>());
        when(device.getId()).thenReturn(1L);
        var config = mock(Config.class);
        when(config.getString(Keys.EVENT_IGNORE_INVALID_POSITIONS.getKey()))
                .thenReturn(String.valueOf(ignoreInvalid));
        when(config.getBoolean(Keys.REPORT_TRIP_NEW_LOGIC)).thenReturn(true);
        var cacheManager = mock(CacheManager.class);
        when(cacheManager.getConfig()).thenReturn(config);
        when(cacheManager.getObject(any(), anyLong())).thenReturn(device);
        when(cacheManager.getPositions(anyLong())).thenReturn(new ArrayDeque<>(cached));
        return new MotionEventHandler(config, cacheManager, mock(Storage.class));
    }

    private Position position(boolean valid, long seconds) {
        Position position = new Position();
        position.setDeviceId(1);
        position.setValid(valid);
        position.setFixTime(new Date(seconds * 1000));
        position.setLatitude(valid ? 43.48 : 0);
        position.setLongitude(valid ? -8.20 : 0);
        return position;
    }

    @Test
    public void aHeartbeatWithoutAFixRaisesNoEvent() {
        MotionEventHandler handler = handler(true, new ArrayList<>());
        List<Event> events = new ArrayList<>();

        handler.onPosition(position(false, 60), events::add);

        assertTrue(events.isEmpty(), "no fix, no motion, no event");
        // The device state is left exactly as it was. (Until 2026-09-19 this
        // checked that the state was not even read; a heartbeat now reads it,
        // because with a trip open it can close it - NoFixPipelineTest.)
        verify(device, never()).setMotionStreak(anyBoolean());
        verify(device, never()).setMotionTime(any());
    }

    @Test
    public void switchedOffTheHeartbeatIsProcessedLikeStock() {
        MotionEventHandler handler = handler(false, new ArrayList<>());
        List<Event> events = new ArrayList<>();

        handler.onPosition(position(false, 60), events::add);

        // Stock Traccar walks the state machine with it. What matters here is
        // only that the guard is the thing making the difference.
        verify(device).getMotionStreak();
        assertTrue(events.isEmpty());
    }

}
