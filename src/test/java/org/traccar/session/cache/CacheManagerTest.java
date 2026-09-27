package org.traccar.session.cache;

import org.junit.jupiter.api.Test;
import org.traccar.broadcast.BroadcastService;
import org.traccar.config.Config;
import org.traccar.model.Attribute;
import org.traccar.model.Device;
import org.traccar.model.Permission;
import org.traccar.model.Position;
import org.traccar.model.Server;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CacheManagerTest {

    @Test
    public void testReplacePositionWithSameFixTime() throws Exception {

        Storage storage = mock(Storage.class);
        BroadcastService broadcastService = mock(BroadcastService.class);
        Device device = new Device();
        device.setId(1);
        when(storage.getObject(eq(Server.class), any(Request.class))).thenReturn(new Server());
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(device);
        when(storage.getPermissions(eq(Device.class), any())).thenReturn(List.of());
        when(storage.getPermissions(eq(User.class), eq(Device.class))).thenReturn(List.of());
        doNothing().when(broadcastService).registerListener(any());

        CacheManager cacheManager = new CacheManager(new Config(), storage, broadcastService);

        long deviceId = 1;
        Object key = new Object();
        cacheManager.addDevice(deviceId, key);

        long fixTime = System.currentTimeMillis();

        Position first = new Position();
        first.setDeviceId(deviceId);
        first.setFixTime(new Date(fixTime));

        Position replacement = new Position();
        replacement.setDeviceId(deviceId);
        replacement.setFixTime(new Date(fixTime));

        cacheManager.updatePosition(first);
        cacheManager.updatePosition(replacement);

        assertSame(replacement, cacheManager.getPosition(deviceId));
    }

    private static Attribute attribute(long id, String expression) {
        Attribute attribute = new Attribute();
        attribute.setId(id);
        attribute.setAttribute("prueba");
        attribute.setExpression(expression);
        attribute.setType("string");
        return attribute;
    }

    @Test
    public void testReloadReadsWhatSqlChanged() throws Exception {

        Storage storage = mock(Storage.class);
        BroadcastService broadcastService = mock(BroadcastService.class);
        Device device = new Device();
        device.setId(1);
        List<Permission> links = new ArrayList<>(List.of(new Permission(Device.class, 1, Attribute.class, 10)));
        Attribute[] stored = {attribute(10, "'antes'")};
        when(storage.getObject(eq(Server.class), any(Request.class))).thenReturn(new Server());
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(device);
        when(storage.getObject(eq(Attribute.class), any(Request.class))).thenAnswer(i -> stored[0]);
        when(storage.getPermissions(eq(Device.class), any())).thenReturn(List.of());
        when(storage.getPermissions(eq(Device.class), eq(Attribute.class))).thenAnswer(i -> List.copyOf(links));
        when(storage.getPermissions(eq(User.class), eq(Device.class))).thenReturn(List.of());
        doNothing().when(broadcastService).registerListener(any());

        CacheManager cacheManager = new CacheManager(new Config(), storage, broadcastService);
        cacheManager.addDevice(1, new Object());
        Position position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date());
        cacheManager.updatePosition(position);

        assertEquals(List.of("'antes'"), cacheManager.getDeviceObjects(1, Attribute.class).stream()
                .map(Attribute::getExpression).toList());

        // What a SQL script does: the device now takes attribute 11, not 10.
        links.clear();
        links.add(new Permission(Device.class, 1, Attribute.class, 11));
        stored[0] = attribute(11, "'después'");
        assertEquals(List.of("'antes'"), cacheManager.getDeviceObjects(1, Attribute.class).stream()
                .map(Attribute::getExpression).toList(), "sin recargar, la caché sigue con lo viejo");

        assertEquals(1, cacheManager.reload());
        assertEquals(List.of(11L), cacheManager.getDeviceObjects(1, Attribute.class).stream()
                .map(Attribute::getId).toList());
        assertEquals("'después'", cacheManager.getObject(Attribute.class, 11).getExpression());
        assertSame(device, cacheManager.getObject(Device.class, 1));
        assertSame(position, cacheManager.getPosition(1), "las posiciones recientes se quedan");
    }

}
