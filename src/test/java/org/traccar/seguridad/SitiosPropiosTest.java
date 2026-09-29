package org.traccar.seguridad;

import org.junit.jupiter.api.Test;
import org.traccar.model.Geofence;
import org.traccar.model.User;
import org.traccar.storage.Storage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rider's places are their geofences marked sitio, not linked to the bike:
 * linking would fire the geofence notifications at every arrival home.
 */
public class SitiosPropiosTest {

    private static Geofence geocerca(long id, String nombre, boolean sitio) {
        Geofence geofence = new Geofence();
        geofence.setId(id);
        geofence.setName(nombre);
        geofence.setArea("CIRCLE (43 -8, 100)");
        if (sitio) {
            geofence.set("sitio", true);
        }
        return geofence;
    }

    @Test
    public void onlyTheGeofencesMarkedAsPlacesOfRealUsers() throws Exception {
        Storage storage = mock(Storage.class);
        User dueno = new User();
        dueno.setId(2);
        User temporal = new User();
        temporal.setId(50);
        temporal.setTemporary(true);
        when(storage.getObjects(eq(User.class), any())).thenReturn(List.of(dueno, temporal));
        when(storage.getObjects(eq(Geofence.class), any())).thenReturn(
                List.of(geocerca(1, "Casa", true), geocerca(2, "Circuito de Jerez", false)));
        SitiosPropios sitios = new SitiosPropios(storage);
        assertEquals(List.of("Casa"), sitios.de(8).stream().map(Sitio::nombre).toList());
        sitios.de(8);
        // One user asked once: the second call comes from the cache.
        verify(storage, times(1)).getObjects(eq(Geofence.class), any());
    }

}
