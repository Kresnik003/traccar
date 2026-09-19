package org.traccar.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.broadcast.BroadcastService;
import org.traccar.config.Config;
import org.traccar.handler.events.MotionEventHandler;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.Server;
import org.traccar.model.User;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Positions without a fix through the real handlers, in pipeline order.
 *
 * DistanceHandler, then the motion events, then the cache update that
 * PostProcessHandler makes, against a real CacheManager: the bugs found in
 * review on 2026-09-19 lived between these three, and a test of any one of
 * them alone passed.
 */
public class NoFixPipelineTest {

    private static final double LAT = 43.48;
    private static final double LON = -8.23;

    private Device device;
    private CacheManager cacheManager;
    private DistanceHandler distanceHandler;
    private MotionEventHandler motionEventHandler;
    private long nextId = 100;

    @BeforeEach
    public void setUp() throws Exception {
        Storage storage = mock(Storage.class);
        BroadcastService broadcastService = mock(BroadcastService.class);
        device = new Device();
        device.setId(1);
        when(storage.getObject(eq(Server.class), any(Request.class))).thenReturn(new Server());
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(device);
        when(storage.getPermissions(eq(Device.class), any())).thenReturn(List.of());
        when(storage.getPermissions(eq(User.class), eq(Device.class))).thenReturn(List.of());
        doNothing().when(broadcastService).registerListener(any());
        Config config = new Config();
        cacheManager = new CacheManager(config, storage, broadcastService);
        cacheManager.addDevice(1, new Object());
        distanceHandler = new DistanceHandler(config, cacheManager);
        motionEventHandler = new MotionEventHandler(config, cacheManager, storage);
    }

    private Position fix(long seconds, double latitude) {
        Position position = new Position("teltonika");
        position.setId(nextId++);
        position.setDeviceId(1);
        position.setValid(true);
        position.setLatitude(latitude);
        position.setLongitude(LON);
        position.setFixTime(new Date(seconds * 1000));
        position.setDeviceTime(position.getFixTime());
        position.setServerTime(position.getFixTime());
        return position;
    }

    /** What an FTC880 sends with no fix: valid false, 0,0. */
    private Position heartbeat(long seconds) {
        Position position = fix(seconds, 0);
        position.setValid(false);
        position.setLongitude(0);
        return position;
    }

    private List<Event> feed(Position position) {
        distanceHandler.onPosition(position, filtered -> { });
        List<Event> events = new ArrayList<>();
        motionEventHandler.onPosition(position, events::add);
        cacheManager.updatePosition(position);
        return events;
    }

    private static List<String> types(List<Event> events) {
        return events.stream().map(Event::getType).toList();
    }

    /** VF5: parked, one heartbeat at 0,0, the next fix an hour later. */
    @Test
    public void aHeartbeatAtZeroIsNotATrip() {
        List<Event> events = new ArrayList<>();
        events.addAll(feed(fix(0, LAT)));
        events.addAll(feed(fix(600, LAT)));
        events.addAll(feed(fix(1200, LAT)));
        Position heartbeat = heartbeat(1800);
        events.addAll(feed(heartbeat));
        Position back = fix(1800 + 3700, LAT);
        events.addAll(feed(back));

        assertEquals(0.0, heartbeat.getDouble(Position.KEY_DISTANCE));
        assertEquals(0.0, back.getDouble(Position.KEY_DISTANCE), 0.001);
        assertTrue(back.getDouble(Position.KEY_TOTAL_DISTANCE) < 1,
                "total distance jumped to " + back.getDouble(Position.KEY_TOTAL_DISTANCE));
        // Before the fix: deviceMoving and deviceStopped, a "DETENIDO" on Telegram.
        assertEquals(List.of(), types(events));
    }

    /** VL3: riding into an underground garage, losing the fix before the stop. */
    @Test
    public void aGarageWithoutFixStopsTheTripAndTheNextDayStartsOne() {
        List<Event> riding = new ArrayList<>();
        Position entrance = null;
        for (int i = 0; i <= 10; i++) {
            entrance = fix(30L * i, LAT + 0.004 * i);
            riding.addAll(feed(entrance));
        }
        assertEquals(List.of(Event.TYPE_DEVICE_MOVING), types(riding));
        double ridden = entrance.getDouble(Position.KEY_TOTAL_DISTANCE);

        List<Event> parked = new ArrayList<>();
        Position lastHeartbeat = null;
        for (int k = 1; k <= 30; k++) {
            lastHeartbeat = heartbeat(300 + 60L * k);
            parked.addAll(feed(lastHeartbeat));
        }
        assertEquals(List.of(Event.TYPE_DEVICE_STOPPED), types(parked),
                "a trip left open all night never says the bike stopped");
        Event stopped = parked.get(0);
        assertEquals(entrance.getId(), stopped.getPositionId(), "the stop is where the fix was lost");
        assertEquals(entrance.getLatitude(), device.getMotionLatitude(), 0.000001);
        assertEquals(ridden, lastHeartbeat.getDouble(Position.KEY_TOTAL_DISTANCE), 0.001);

        Position out = fix(300 + 36000, LAT + 0.04 + 0.02);
        List<Event> nextDay = feed(out);
        assertEquals(List.of(Event.TYPE_DEVICE_MOVING), types(nextDay));
        assertEquals(out.getId(), nextDay.get(0).getPositionId());
        // Measured from the garage entrance, not from 0,0 and not lost.
        double leg = out.getDouble(Position.KEY_DISTANCE);
        assertTrue(leg > 2000 && leg < 2500, "leg from the garage was " + leg);
        assertEquals(ridden + leg, out.getDouble(Position.KEY_TOTAL_DISTANCE), 0.001);
    }

    /** VF6: a cell tower estimate is not a fix. */
    @Test
    public void anApproximatePositionKeepsTheLastGeofences() {
        Position home = fix(0, LAT);
        home.setGeofenceIds(List.of(7L));
        cacheManager.updatePosition(home);

        Position estimate = fix(60, LAT + 0.01);
        estimate.set(Position.KEY_APPROXIMATE, true);
        new GeofenceHandler(cacheManager).onPosition(estimate, filtered -> { });

        assertEquals(List.of(7L), estimate.getGeofenceIds());
    }

}
