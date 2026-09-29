package org.traccar.seguridad;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.api.EnlacesDirectos;
import org.traccar.geofence.GeofenceCircle;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.model.Viaje;
import org.traccar.storage.Storage;
import org.traccar.telegram.ClienteTelegram;
import org.traccar.telegram.FirmaBoton;
import org.traccar.telegram.RespuestaTelegram;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Has left" and "has arrived" for the family.
 */
public class ViajesFamiliaTest {

    private static final long T = System.currentTimeMillis();

    private Storage storage;
    private ClienteTelegram cliente;
    private EnlacesDirectos enlaces;
    private Destinatarios destinatarios;
    private SitiosPropios sitios;
    private final FirmaBoton firma = new FirmaBoton("s");
    private List<Viaje> abiertos;
    private List<Position> recientes;

    private static Position pos(long ms, double lat, boolean contacto, double kph) {
        Position position = new Position();
        position.setDeviceId(8);
        position.setDeviceTime(new Date(ms));
        position.setFixTime(new Date(ms));
        position.setLatitude(lat);
        position.setLongitude(-8);
        position.setValid(true);
        position.set(Position.KEY_IGNITION, contacto);
        position.setSpeed(UnitsConverter.knotsFromKph(kph));
        return position;
    }

    private Viaje viaje(long inicio) {
        Viaje viaje = new Viaje();
        viaje.setId(12);
        viaje.setDeviceId(8);
        viaje.setInicio(new Date(inicio));
        viaje.setOrigen("Casa");
        viaje.setShareUserId(60);
        return viaje;
    }

    private ViajesFamilia viajes() {
        return new ViajesFamilia(storage, cliente, enlaces, destinatarios, sitios, firma);
    }

    @BeforeEach
    public void preparar() throws Exception {
        storage = mock(Storage.class);
        cliente = mock(ClienteTelegram.class);
        enlaces = mock(EnlacesDirectos.class);
        destinatarios = mock(Destinatarios.class);
        sitios = mock(SitiosPropios.class);
        abiertos = new ArrayList<>();
        recientes = new ArrayList<>();
        when(sitios.de(8)).thenReturn(List.of(
                new Sitio(1, "Casa", new GeofenceCircle("CIRCLE (43 -8, 100)")),
                new Sitio(2, "Trabajo", new GeofenceCircle("CIRCLE (43.05 -8, 100)"))));
        when(storage.getObjects(eq(Viaje.class), any())).thenAnswer(i -> abiertos);
        when(storage.getObjectsStream(eq(Position.class), any())).thenAnswer(i -> recientes.stream());
        when(storage.addObject(any(Viaje.class), any())).thenReturn(12L);
        Device device = new Device();
        device.setName("Ducati");
        when(storage.getObject(eq(Device.class), any())).thenReturn(device);
        when(cliente.enviar(anyString(), anyString(), anyList())).thenReturn(new RespuestaTelegram(200, null));
        when(destinatarios.chatsDelDueno(8)).thenReturn(List.of("900"));
        when(destinatarios.persona(8)).thenReturn("Juan");
        when(destinatarios.contactos(8)).thenReturn(List.of(new Destinatarios.Destinatario("901", null, 1L)));
        when(enlaces.enlaceDeEquipo(eq(8L), any(), eq("viaje"), eq(12L))).thenReturn(
                Optional.of(new EnlacesDirectos.Enlace("https://n.es/?token=v", 60)));
    }

    @Test
    public void aDepartureOpensATripAndTellsTheContacts() throws Exception {
        viajes().alPosicion(pos(T, 43.01, true, 30), pos(T - 10_000, 43.0, true, 10));
        verify(storage).addObject(any(Viaje.class), any());
        verify(cliente).enviar(eq("901"), contains("ha salido de Casa"), anyList());
        verify(cliente).enviar(eq("901"), contains("https://n.es/?token=v"), anyList());
        ArgumentCaptor<List<ClienteTelegram.Boton>> botones = ArgumentCaptor.forClass(List.class);
        verify(cliente).enviar(eq("900"), anyString(), botones.capture());
        assertEquals("He llegado", botones.getValue().get(0).texto());
        assertEquals(Optional.of(new FirmaBoton.Dato("v", 12)), firma.comprobar(botones.getValue().get(0).dato()));
    }

    @Test
    public void anOpenTripDoesNotOpenAnother() throws Exception {
        abiertos.add(viaje(T - 60_000));
        viajes().alPosicion(pos(T, 43.01, true, 30), pos(T - 10_000, 43.0, true, 10));
        verify(storage, never()).addObject(any(Viaje.class), any());
    }

    @Test
    public void withoutContactsThereIsNoTrip() throws Exception {
        when(destinatarios.contactos(8)).thenReturn(List.of());
        viajes().alPosicion(pos(T, 43.01, true, 30), pos(T - 10_000, 43.0, true, 10));
        verify(storage, never()).addObject(any(Viaje.class), any());
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
    }

    @Test
    public void aPositionWithoutAFixDecidesNothing() throws Exception {
        Position sinFix = pos(T, 43.01, true, 30);
        sinFix.setValid(false);
        viajes().alPosicion(sinFix, pos(T - 10_000, 43.0, true, 10));
        verify(storage, never()).addObject(any(Viaje.class), any());
    }

    @Test
    public void theButtonClosesTellsAndCutsTheLink() throws Exception {
        when(storage.getObject(eq(Viaje.class), any())).thenReturn(viaje(T - 3_600_000));
        String respuesta = viajes().pulsado(12, "900");
        ArgumentCaptor<Viaje> captor = ArgumentCaptor.forClass(Viaje.class);
        verify(storage).updateObject(captor.capture(), any());
        assertNotNull(captor.getValue().getFin());
        assertEquals("boton", captor.getValue().getMotivoFin());
        verify(cliente).enviar(eq("901"), contains("Juan ha llegado bien."), anyList());
        verify(enlaces).cortar(60);
        assertTrue(respuesta.contains("hemos avisado"), respuesta);
    }

    @Test
    public void somebodyElsesButtonDoesNotClose() throws Exception {
        when(storage.getObject(eq(Viaje.class), any())).thenReturn(viaje(T - 3_600_000));
        viajes().pulsado(12, "999");
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void stayingStillAtOneOfYourPlacesIsAnArrival() throws Exception {
        abiertos.add(viaje(T - 3_600_000));
        // Newest first: at work, still and off for 3 minutes, riding before.
        recientes.add(pos(T, 43.05, false, 0));
        recientes.add(pos(T - 180_000, 43.05, false, 0));
        recientes.add(pos(T - 240_000, 43.04, true, 40));
        viajes().revisar(T);
        verify(cliente).enviar(eq("901"), contains("Juan ha llegado bien a Trabajo."), anyList());
        verify(enlaces).cortar(60);
    }

    @Test
    public void aShortStopIsNotAnArrival() throws Exception {
        abiertos.add(viaje(T - 3_600_000));
        recientes.add(pos(T, 43.05, false, 0));
        recientes.add(pos(T - 60_000, 43.04, true, 40));
        viajes().revisar(T);
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void stoppingOutsideYourPlacesIsNotAnArrival() throws Exception {
        abiertos.add(viaje(T - 3_600_000));
        recientes.add(pos(T, 43.2, false, 0));
        recientes.add(pos(T - 600_000, 43.2, false, 0));
        viajes().revisar(T);
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void twelveHoursCloseWithoutAMessage() throws Exception {
        abiertos.add(viaje(T - 12 * 3_600_000L - 1));
        viajes().revisar(T);
        ArgumentCaptor<Viaje> captor = ArgumentCaptor.forClass(Viaje.class);
        verify(storage).updateObject(captor.capture(), any());
        assertEquals("caducado", captor.getValue().getMotivoFin());
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
        verify(enlaces).cortar(60);
    }

    @Test
    public void aClosedTripIsNotReviewedAgain() throws Exception {
        Viaje cerrado = viaje(T - 3_600_000);
        cerrado.setFin(new Date(T - 60_000));
        abiertos.add(cerrado);
        recientes.add(pos(T, 43.05, false, 0));
        recientes.add(pos(T - 600_000, 43.05, false, 0));
        viajes().revisar(T);
        verify(storage, never()).updateObject(any(), any());
        verify(enlaces, never()).cortar(anyLong());
    }

}
