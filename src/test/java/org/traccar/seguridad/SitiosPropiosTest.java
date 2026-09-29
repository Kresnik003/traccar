package org.traccar.seguridad;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.model.Geofence;
import org.traccar.model.User;
import org.traccar.storage.Storage;

import java.util.List;
import java.util.Optional;

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

    private static final long CINCO_MINUTOS = 5 * 60_000L;

    private Storage storage;
    private Destinatarios destinatarios;
    private final long[] reloj = {1_790_000_000_000L};

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

    private static List<String> nombres(List<Sitio> sitios) {
        return sitios.stream().map(Sitio::nombre).toList();
    }

    private SitiosPropios sitios() {
        return new SitiosPropios(storage, destinatarios, () -> reloj[0]);
    }

    @BeforeEach
    public void preparar() throws Exception {
        storage = mock(Storage.class);
        destinatarios = mock(Destinatarios.class);
        User motorista = new User();
        motorista.setId(2);
        when(destinatarios.motorista(8)).thenReturn(Optional.of(motorista));
    }

    @Test
    public void onlyTheRidersGeofencesMarkedAsPlaces() throws Exception {
        when(storage.getObjects(eq(Geofence.class), any())).thenReturn(
                List.of(geocerca(1, "Casa", true), geocerca(2, "Circuito de Jerez", false)));
        SitiosPropios sitios = new SitiosPropios(storage, destinatarios);
        assertEquals(List.of("Casa"), nombres(sitios.de(8)));
        sitios.de(8);
        // One user asked once: the second call comes from the cache.
        verify(storage, times(1)).getObjects(eq(Geofence.class), any());
    }

    @Test
    public void withoutARiderThereAreNoPlaces() throws Exception {
        when(destinatarios.motorista(8)).thenReturn(Optional.empty());
        assertEquals(List.of(), new SitiosPropios(storage, destinatarios).de(8));
    }

    @Test
    public void theCacheGivesTheSamePlacesForFiveMinutesAndThenReadsThemAgain() throws Exception {
        // The rider adds «Trabajo» a minute after the first read: it is seen when the five minutes are up,
        // not before and not never.
        when(storage.getObjects(eq(Geofence.class), any()))
                .thenReturn(List.of(geocerca(1, "Casa", true)))
                .thenReturn(List.of(geocerca(1, "Casa", true), geocerca(3, "Trabajo", true)));
        SitiosPropios sitios = sitios();
        assertEquals(List.of("Casa"), nombres(sitios.de(8)));
        reloj[0] += CINCO_MINUTOS - 1;
        assertEquals(List.of("Casa"), nombres(sitios.de(8)));
        verify(storage, times(1)).getObjects(eq(Geofence.class), any());
        reloj[0] += 1;
        assertEquals(List.of("Casa", "Trabajo"), nombres(sitios.de(8)));
        verify(storage, times(2)).getObjects(eq(Geofence.class), any());
    }

    @Test
    public void aChangeOfRiderReadsThePlacesAgainAtOnce() throws Exception {
        when(storage.getObjects(eq(Geofence.class), any())).thenReturn(List.of(geocerca(1, "Casa", true)));
        SitiosPropios sitios = sitios();
        sitios.de(8);
        sitios.olvidar(8);
        sitios.de(8);
        verify(storage, times(2)).getObjects(eq(Geofence.class), any());
    }

    @Test
    public void aPlaceWhoseAreaDoesNotParseIsSkippedAndTheOthersStay() throws Exception {
        Geofence mal = geocerca(1, "Casa", true);
        mal.setArea("CIRCLE (no es un número)");
        Geofence rara = geocerca(2, "Taller", true);
        rara.setArea("MULTIPOINT (43 -8)");
        when(storage.getObjects(eq(Geofence.class), any())).thenReturn(
                List.of(mal, rara, geocerca(3, "Trabajo", true)));
        assertEquals(List.of("Trabajo"), nombres(sitios().de(8)));
    }

}
