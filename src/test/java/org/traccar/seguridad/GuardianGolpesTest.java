package org.traccar.seguridad;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.api.EnlacesDirectos;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Guardia;
import org.traccar.model.Position;
import org.traccar.storage.Storage;
import org.traccar.telegram.ClienteTelegram;
import org.traccar.telegram.FirmaBoton;
import org.traccar.telegram.RespuestaTelegram;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The crash guard: ask the rider, wait two minutes, warn the others unless the
 * rider answered or the bike moved again.
 */
public class GuardianGolpesTest {

    private static final long T = System.currentTimeMillis();

    private Storage storage;
    private ClienteTelegram cliente;
    private EnlacesDirectos enlaces;
    private Destinatarios destinatarios;
    private final FirmaBoton firma = new FirmaBoton("s");
    private List<Position> previas;
    private List<Position> posteriores;
    private List<Guardia> abiertas;

    private static Position pos(long ms, boolean contacto, double kph, double lat) {
        Position position = new Position();
        position.setId(100);
        position.setDeviceId(8);
        position.setDeviceTime(new Date(ms));
        position.setFixTime(new Date(ms));
        position.set(Position.KEY_IGNITION, contacto);
        position.setSpeed(UnitsConverter.knotsFromKph(kph));
        position.setLatitude(lat);
        position.setLongitude(-8);
        position.setValid(true);
        return position;
    }

    private static Event accidente() {
        Event event = new Event(Event.TYPE_ALARM, 8);
        event.set(Position.KEY_ALARM, Position.ALARM_ACCIDENT);
        event.setId(55);
        return event;
    }

    private Guardia guardia(long golpe) {
        Guardia guardia = new Guardia();
        guardia.setId(7);
        guardia.setDeviceId(8);
        guardia.setPositionId(100);
        guardia.setGolpe(new Date(golpe));
        guardia.setLimite(new Date(golpe + ReglasGolpe.ESPERA));
        guardia.setLatitud(43);
        guardia.setLongitud(-8);
        guardia.setEstado(Guardia.ESPERANDO);
        return guardia;
    }

    private GuardianGolpes guardian() {
        return new GuardianGolpes(storage, cliente, enlaces, destinatarios, firma);
    }

    @BeforeEach
    public void preparar() throws Exception {
        storage = mock(Storage.class);
        cliente = mock(ClienteTelegram.class);
        enlaces = mock(EnlacesDirectos.class);
        destinatarios = mock(Destinatarios.class);
        previas = new ArrayList<>();
        posteriores = new ArrayList<>();
        abiertas = new ArrayList<>();
        // Each case uses only the positions before the crash or only those after it.
        when(storage.getObjectsStream(eq(Position.class), any())).thenAnswer(i ->
                Stream.concat(previas.stream(), posteriores.stream()));
        when(storage.getObjects(eq(Guardia.class), any())).thenAnswer(i -> abiertas);
        when(storage.addObject(any(Guardia.class), any())).thenReturn(7L);
        Device device = new Device();
        device.setName("Ducati");
        when(storage.getObject(eq(Device.class), any())).thenReturn(device);
        when(storage.getObject(eq(Position.class), any())).thenReturn(pos(T, false, 0, 43));
        when(cliente.enviar(anyString(), anyString(), anyList())).thenReturn(new RespuestaTelegram(200, null));
        when(destinatarios.chatsDelDueno(8)).thenReturn(List.of("900"));
        when(destinatarios.persona(8)).thenReturn("Juan");
        when(destinatarios.de(8)).thenReturn(List.of(
                new Destinatarios.Destinatario("901", null, 1L),
                new Destinatarios.Destinatario("905", "Ruta del Eume", null)));
        when(enlaces.enlaceDeEquipo(eq(8L), any())).thenReturn(
                Optional.of(new EnlacesDirectos.Enlace("https://n.es/?token=t", 60)));
    }

    @Test
    public void aCrashWhileRidingOpensAGuardAndAsksTheRider() throws Exception {
        previas.add(pos(T - 30_000, true, 50, 43));
        guardian().alEvento(accidente(), pos(T, false, 0, 43));
        ArgumentCaptor<Guardia> captor = ArgumentCaptor.forClass(Guardia.class);
        verify(storage).addObject(captor.capture(), any());
        assertEquals(Guardia.ESPERANDO, captor.getValue().getEstado());
        assertEquals(T + ReglasGolpe.ESPERA, captor.getValue().getLimite().getTime());
        ArgumentCaptor<List<ClienteTelegram.Boton>> botones = ArgumentCaptor.forClass(List.class);
        verify(cliente).enviar(eq("900"), contains("golpe en tu Ducati"), botones.capture());
        assertEquals("Estoy bien", botones.getValue().get(0).texto());
        assertEquals(Optional.of(new FirmaBoton.Dato("g", 7)), firma.comprobar(botones.getValue().get(0).dato()));
    }

    @Test
    public void aParkedCrashOpensNothing() throws Exception {
        previas.add(pos(T - 30_000, false, 0, 43));
        guardian().alEvento(accidente(), pos(T, false, 0, 43));
        verify(storage, never()).addObject(any(Guardia.class), any());
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
    }

    @Test
    public void aLateCrashOpensNothing() throws Exception {
        long antes = T - 600_000;
        previas.add(pos(antes - 30_000, true, 50, 43));
        guardian().alEvento(accidente(), pos(antes, false, 0, 43));
        verify(storage, never()).addObject(any(Guardia.class), any());
    }

    @Test
    public void twoFramesOfTheSameCrashOpenOneGuard() throws Exception {
        previas.add(pos(T - 30_000, true, 50, 43));
        abiertas.add(guardia(T));
        guardian().alEvento(accidente(), pos(T, false, 0, 43));
        verify(storage, never()).addObject(any(Guardia.class), any());
    }

    @Test
    public void anotherAlarmIsNotACrash() throws Exception {
        previas.add(pos(T - 30_000, true, 50, 43));
        Event event = new Event(Event.TYPE_ALARM, 8);
        event.set(Position.KEY_ALARM, Position.ALARM_TOW);
        guardian().alEvento(event, pos(T, false, 0, 43));
        verify(storage, never()).addObject(any(Guardia.class), any());
    }

    @Test
    public void theButtonCancels() throws Exception {
        when(storage.getObject(eq(Guardia.class), any())).thenReturn(guardia(T));
        String respuesta = guardian().pulsado(7, "900");
        ArgumentCaptor<Guardia> captor = ArgumentCaptor.forClass(Guardia.class);
        verify(storage).updateObject(captor.capture(), any());
        assertEquals(Guardia.CANCELADA_BOTON, captor.getValue().getEstado());
        assertTrue(respuesta.contains("no avisaremos"), respuesta);
    }

    @Test
    public void somebodyElsesButtonDoesNotCancel() throws Exception {
        when(storage.getObject(eq(Guardia.class), any())).thenReturn(guardia(T));
        String respuesta = guardian().pulsado(7, "999");
        verify(storage, never()).updateObject(any(), any());
        assertTrue(respuesta.contains("no es tuyo"), respuesta);
    }

    @Test
    public void movingAgainCancelsWithoutWarningAnybody() throws Exception {
        abiertas.add(guardia(T - 60_000));
        posteriores.add(pos(T - 30_000, true, 30, 43.001));
        guardian().revisar(T + 200_000);
        ArgumentCaptor<Guardia> captor = ArgumentCaptor.forClass(Guardia.class);
        verify(storage).updateObject(captor.capture(), any());
        assertEquals(Guardia.CANCELADA_MOVIMIENTO, captor.getValue().getEstado());
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
    }

    @Test
    public void beforeTheLimitNobodyIsWarned() throws Exception {
        abiertas.add(guardia(T));
        guardian().revisar(T + ReglasGolpe.ESPERA - 1_000);
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void afterTheLimitEverybodyIsWarnedWithTheLink() throws Exception {
        abiertas.add(guardia(T));
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        verify(cliente).enviar(eq("901"), contains("https://n.es/?token=t"), anyList());
        verify(cliente).enviar(eq("905"), contains("Compañero de la quedada «Ruta del Eume»"), anyList());
        ArgumentCaptor<Guardia> captor = ArgumentCaptor.forClass(Guardia.class);
        verify(storage).updateObject(captor.capture(), any());
        assertEquals(Guardia.AVISADA, captor.getValue().getEstado());
        verify(enlaces).enlaceDeEquipo(eq(8L), eq(new Date(T + 24 * 3_600_000L)));
    }

    @Test
    public void aContactWhoBlockedTheBotIsMarkedAndNotRetried() throws Exception {
        abiertas.add(guardia(T));
        when(cliente.enviar(eq("901"), anyString(), anyList())).thenReturn(new RespuestaTelegram(403, "blocked"));
        GuardianGolpes guardian = guardian();
        guardian.revisar(T + ReglasGolpe.ESPERA + 1_000);
        verify(destinatarios).rechazar(1L);
        abiertas.clear();
        guardian.revisar(T + ReglasGolpe.ESPERA + 700_000);
        verify(cliente, times(1)).enviar(eq("901"), anyString(), anyList());
    }

    @Test
    public void aFailedSendIsRetriedThreeTimes() throws Exception {
        abiertas.add(guardia(T));
        when(cliente.enviar(eq("905"), anyString(), anyList())).thenReturn(new RespuestaTelegram(0, "red"));
        GuardianGolpes guardian = guardian();
        long limite = T + ReglasGolpe.ESPERA + 1_000;
        guardian.revisar(limite);
        abiertas.clear();
        guardian.revisar(limite + 31_000);
        guardian.revisar(limite + 31_000 + 121_000);
        guardian.revisar(limite + 31_000 + 121_000 + 601_000);
        guardian.revisar(limite + 3_600_000);
        verify(cliente, times(4)).enviar(eq("905"), anyString(), anyList());
    }

    @Test
    public void itSurvivesARestart() throws Exception {
        abiertas.add(guardia(T));
        // A new guardian, as after a restart: the guard is read from the database.
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        verify(cliente).enviar(eq("901"), anyString(), anyList());
    }

    @Test
    public void aBrokenDatabaseDoesNotBreakTheEvent() throws Exception {
        when(storage.getObjectsStream(eq(Position.class), any())).thenThrow(new RuntimeException("caída"));
        guardian().alEvento(accidente(), pos(T, false, 0, 43));
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
    }

    @Test
    public void noLinkStillWarns() throws Exception {
        abiertas.add(guardia(T));
        when(enlaces.enlaceDeEquipo(anyLong(), any())).thenReturn(Optional.empty());
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        verify(cliente).enviar(eq("901"), contains("Posible accidente"), anyList());
    }

}
