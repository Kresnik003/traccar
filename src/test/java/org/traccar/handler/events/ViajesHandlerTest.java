package org.traccar.handler.events;

import org.junit.jupiter.api.Test;
import org.traccar.model.Position;
import org.traccar.seguridad.ViajesFamilia;
import org.traccar.session.cache.CacheManager;

import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every position of a bike reaches the trips of improvement 115.
 */
public class ViajesHandlerTest {

    private static Position posicion(long ms) {
        Position position = new Position();
        position.setDeviceId(8);
        position.setDeviceTime(new Date(ms));
        position.setFixTime(new Date(ms));
        return position;
    }

    @Test
    public void everyPositionGoesToTheTrips() {
        CacheManager cacheManager = mock(CacheManager.class);
        ViajesFamilia viajes = mock(ViajesFamilia.class);
        BaseEventHandler.Callback callback = mock(BaseEventHandler.Callback.class);
        Position anterior = posicion(1_000);
        Position position = posicion(2_000);
        when(cacheManager.getPosition(8)).thenReturn(anterior);
        new ViajesHandler(cacheManager, viajes).onPosition(position, callback);
        verify(viajes).alPosicion(position);
        // A departure is a Telegram message to the contacts, not an event.
        verify(callback, never()).eventDetected(any());
    }

}
