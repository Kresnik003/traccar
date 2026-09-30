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
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;
import org.traccar.telegram.ClienteTelegram;
import org.traccar.telegram.FirmaBoton;
import org.traccar.telegram.RespuestaTelegram;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

    /**
     * Whether a position is what the query asks for. The guardian reads positions
     * by device and fix time: a window computed wrong reads nothing in the
     * database, and a mock that ignored the query kept every test green (PIT,
     * 29/9). Any other query fails the test, so a new one cannot slip by.
     */
    private static boolean cumple(Condition condition, Position position) {
        if (condition instanceof Condition.And and) {
            return cumple(and.getFirst(), position) && cumple(and.getSecond(), position);
        }
        if (condition instanceof Condition.Equals equals && equals.getColumn().equals("deviceId")) {
            return position.getDeviceId() == ((Number) equals.getValue()).longValue();
        }
        if (condition instanceof Condition.Between between && between.getColumn().equals("fixTime")) {
            return !position.getFixTime().before((Date) between.getFromValue())
                    && !position.getFixTime().after((Date) between.getToValue());
        }
        throw new IllegalArgumentException("Unexpected query: " + condition);
    }

    private final List<Runnable> encolados = new ArrayList<>();

    private GuardianGolpes guardian() {
        // Direct executor: the sends run at once, so each case can check them.
        return new GuardianGolpes(true, storage, cliente, enlaces, destinatarios, firma, Runnable::run);
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
        // Each case uses only the positions before the crash or only those after it,
        // and only those the query really reaches.
        when(storage.getObjectsStream(eq(Position.class), any())).thenAnswer(i -> {
            Request request = i.getArgument(1);
            return Stream.concat(previas.stream(), posteriores.stream())
                    .filter(position -> cumple(request.getCondition(), position));
        });
        when(storage.getObjects(eq(Guardia.class), any())).thenAnswer(i -> abiertas);
        // The countdown reads each guard again before acting on it.
        when(storage.getObject(eq(Guardia.class), any())).thenAnswer(i -> abiertas.isEmpty() ? null : abiertas.get(0));
        when(storage.addObject(any(Guardia.class), any())).thenReturn(7L);
        Device device = new Device();
        device.setName("Ducati");
        when(storage.getObject(eq(Device.class), any())).thenReturn(device);
        when(storage.getObject(eq(Position.class), any())).thenReturn(pos(T, false, 0, 43));
        when(cliente.enviar(anyString(), anyString(), anyList())).thenReturn(new RespuestaTelegram(200, null));
        when(destinatarios.chatDelMotorista(8)).thenReturn(Optional.of("900"));
        when(destinatarios.motorista(8)).thenReturn(Optional.of(new User()));
        when(destinatarios.persona(8)).thenReturn("Juan");
        when(destinatarios.de(8)).thenReturn(List.of(
                new Destinatarios.Destinatario("901", null, 1L),
                new Destinatarios.Destinatario("905", "Ruta del Eume", null)));
        when(enlaces.enlaceDeEquipo(eq(8L), any(), eq("golpe"), eq(7L))).thenReturn(
                Optional.of(new EnlacesDirectos.Enlace("https://n.es/?token=t", 60)));
    }

    @Test
    public void aCrashWhileRidingOpensAGuardAndAsksTheRider() throws Exception {
        previas.add(pos(T - 30_000, true, 50, 43));
        guardian().alEvento(accidente(), pos(T, false, 0, 43));
        ArgumentCaptor<Guardia> captor = ArgumentCaptor.forClass(Guardia.class);
        verify(storage).addObject(captor.capture(), any());
        assertEquals(Guardia.ESPERANDO, captor.getValue().getEstado());
        long limite = captor.getValue().getLimite().getTime();
        assertTrue(limite >= T + ReglasGolpe.ESPERA && limite <= System.currentTimeMillis() + ReglasGolpe.ESPERA);
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
        assertEquals("Perfecto: no avisaremos a nadie.", respuesta);
    }

    @Test
    public void somebodyElsesButtonDoesNotCancel() throws Exception {
        when(storage.getObject(eq(Guardia.class), any())).thenReturn(guardia(T));
        String respuesta = guardian().pulsado(7, "999");
        verify(storage, never()).updateObject(any(), any());
        assertEquals("Este botón no es tuyo.", respuesta);
    }

    @Test
    public void movingAgainCancelsWithoutWarningAnybody() throws Exception {
        abiertas.add(guardia(T - 100_000));
        posteriores.add(pos(T - 60_000, true, 30, 43.001));
        posteriores.add(pos(T - 40_000, true, 35, 43.002));
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
        assertEquals(Guardia.AVISADA, abiertas.get(0).getEstado());
        assertEquals(",901,905,", abiertas.get(0).getResueltos());
        verify(enlaces).enlaceDeEquipo(eq(8L), eq(new Date(T + 24 * 3_600_000L)), eq("golpe"), eq(7L));
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
        when(enlaces.enlaceDeEquipo(anyLong(), any(), any(), anyLong())).thenReturn(Optional.empty());
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        verify(cliente).enviar(eq("901"), contains("Posible accidente"), anyList());
    }

    @Test
    public void theRidersSlideRightAfterTheCrashDoesNotCancel() throws Exception {
        abiertas.add(guardia(T));
        posteriores.add(pos(T + 1_000, true, 40, 43.0003));
        posteriores.add(pos(T + 3_000, true, 20, 43.0005));
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        verify(cliente).enviar(eq("901"), contains("Posible accidente"), anyList());
    }

    @Test
    public void theWarningSaysWhereTheBikeLiesNotZeroZero() throws Exception {
        Guardia guardia = guardia(T);
        guardia.setLatitud(0);
        guardia.setLongitud(0);
        abiertas.add(guardia);
        Position golpe = pos(T, false, 0, 0);
        golpe.setValid(false);
        golpe.setLongitude(0);
        when(storage.getObject(eq(Position.class), any())).thenReturn(golpe);
        posteriores.add(pos(T + 40_000, false, 0, 43.47051));
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        verify(cliente).enviar(eq("901"), contains("43.47051, -8.00000"), anyList());
    }

    @Test
    public void theSendsLeaveTheCallersThread() throws Exception {
        // Found in review: the Telegram call ran on the Netty thread and held every device.
        previas.add(pos(T - 30_000, true, 50, 43));
        new GuardianGolpes(true, storage, cliente, enlaces, destinatarios, firma, encolados::add)
                .alEvento(accidente(), pos(T, false, 0, 43));
        verify(storage).addObject(any(Guardia.class), any());
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
        encolados.forEach(Runnable::run);
        verify(cliente).enviar(eq("900"), contains("golpe"), anyList());
    }

    @Test
    public void aDatabaseHiccupBeforeWarningLeavesTheGuardWaiting() throws Exception {
        // Found in review: AVISADA was saved before reading whom to warn.
        abiertas.add(guardia(T));
        when(destinatarios.de(8)).thenThrow(new StorageException("caída"));
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        verify(storage, never()).updateObject(any(), any());
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
    }

    @Test
    public void theCountdownReadsTheGuardAgainBeforeWarning() throws Exception {
        // Found in review: "I'm fine" pressed while the countdown was on the guard.
        abiertas.add(guardia(T));
        Guardia cancelada = guardia(T);
        cancelada.setEstado(Guardia.CANCELADA_BOTON);
        when(storage.getObject(eq(Guardia.class), any())).thenReturn(cancelada);
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
    }

    @Test
    public void withTheInboxOffNothingIsArmed() throws Exception {
        previas.add(pos(T - 30_000, true, 50, 43));
        new GuardianGolpes(false, storage, cliente, enlaces, destinatarios, firma, Runnable::run)
                .alEvento(accidente(), pos(T, false, 0, 43));
        verify(storage, never()).addObject(any(Guardia.class), any());
    }

    @Test
    public void withoutARiderNothingIsArmed() throws Exception {
        previas.add(pos(T - 30_000, true, 50, 43));
        when(destinatarios.motorista(8)).thenReturn(Optional.empty());
        guardian().alEvento(accidente(), pos(T, false, 0, 43));
        verify(storage, never()).addObject(any(Guardia.class), any());
    }

    @Test
    public void withNobodyToWarnTheRiderIsNotPromisedAWarning() throws Exception {
        previas.add(pos(T - 30_000, true, 50, 43));
        when(destinatarios.de(8)).thenReturn(List.of());
        guardian().alEvento(accidente(), pos(T, false, 0, 43));
        verify(storage, never()).addObject(any(Guardia.class), any());
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
    }

    // ---- What the guard keeps (PIT, 29/9: nothing checked the saved guard) ----

    @Test
    public void theGuardKeepsWhichBikeWhichCrashAndWhere() throws Exception {
        previas.add(pos(T - 30_000, true, 50, 43));
        Position golpe = pos(T, false, 0, 43.3);
        golpe.setId(321);
        guardian().alEvento(accidente(), golpe);
        ArgumentCaptor<Guardia> captor = ArgumentCaptor.forClass(Guardia.class);
        verify(storage).addObject(captor.capture(), any());
        Guardia guardia = captor.getValue();
        assertEquals(8, guardia.getDeviceId());
        assertEquals(55, guardia.getEventId());
        assertEquals(321, guardia.getPositionId());
        assertEquals(new Date(T), guardia.getGolpe());
        assertEquals(43.3, guardia.getLatitud());
        assertEquals(-8, guardia.getLongitud());
        assertEquals(Guardia.ESPERANDO, guardia.getEstado());
    }

    @Test
    public void aCrashFrameWithoutAFixDoesNotLeaveAPlaceInTheGuard() throws Exception {
        // The frame without a fix carries an old place: it must not be taken as the crash site.
        previas.add(pos(T - 30_000, true, 50, 43));
        Position golpe = pos(T, false, 0, 43.3);
        golpe.setValid(false);
        guardian().alEvento(accidente(), golpe);
        ArgumentCaptor<Guardia> captor = ArgumentCaptor.forClass(Guardia.class);
        verify(storage).addObject(captor.capture(), any());
        assertEquals(0, captor.getValue().getLatitud());
        assertEquals(0, captor.getValue().getLongitud());
    }

    @Test
    public void aCrashEventWithoutAPositionOpensNothing() throws Exception {
        previas.add(pos(T - 30_000, true, 50, 43));
        guardian().alEvento(accidente(), null);
        verify(storage, never()).addObject(any(Guardia.class), any());
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
    }

    @Test
    public void theCrashFrameAloneCanShowTheBikeWasRidden() throws Exception {
        // Records sent in batches: the crash frame may be the only proof of riding.
        guardian().alEvento(accidente(), pos(T, true, 45, 43));
        verify(storage).addObject(any(Guardia.class), any());
    }

    @Test
    public void ridingSeenOnlyInAFrameWithAnOldFixStillCounts() throws Exception {
        // A frame without a fix carries the fix time of its last good position: the
        // positions are read with 30 minutes of room so that one is not missed.
        Position enMarcha = pos(T - 30_000, true, 50, 43);
        enMarcha.setValid(false);
        enMarcha.setFixTime(new Date(T - 31 * 60_000));
        previas.add(enMarcha);
        guardian().alEvento(accidente(), pos(T, false, 0, 43));
        verify(storage).addObject(any(Guardia.class), any());
    }

    // ---- One guard per crash --------------------------------------------------

    private Guardia anterior(long golpe, String estado) {
        Guardia guardia = guardia(golpe);
        guardia.setId(6);
        guardia.setEstado(estado);
        return guardia;
    }

    @Test
    public void aFrameOfACrashAlreadyAnsweredOpensNothing() throws Exception {
        previas.add(pos(T - 90_000, true, 50, 43));
        abiertas.add(anterior(T - 60_000, Guardia.CANCELADA_BOTON));
        guardian().alEvento(accidente(), pos(T, false, 0, 43));
        verify(storage, never()).addObject(any(Guardia.class), any());
    }

    @Test
    public void anEarlierFrameOfTheSameCrashArrivingLateOpensNothing() throws Exception {
        previas.add(pos(T - 30_000, true, 50, 43));
        abiertas.add(anterior(T + 60_000, Guardia.AVISADA));
        guardian().alEvento(accidente(), pos(T, false, 0, 43));
        verify(storage, never()).addObject(any(Guardia.class), any());
    }

    @Test
    public void aCrashTenMinutesAfterTheLastOneIsANewOne() throws Exception {
        previas.add(pos(T - 30_000, true, 50, 43));
        abiertas.add(anterior(T - 600_000, Guardia.AVISADA));
        guardian().alEvento(accidente(), pos(T, false, 0, 43));
        verify(storage).addObject(any(Guardia.class), any());
    }

    @Test
    public void aGuardStillWaitingBlocksAnotherOneWhateverItsAge() throws Exception {
        previas.add(pos(T - 30_000, true, 50, 43));
        abiertas.add(anterior(T - 20 * 60_000, Guardia.ESPERANDO));
        guardian().alEvento(accidente(), pos(T, false, 0, 43));
        verify(storage, never()).addObject(any(Guardia.class), any());
    }

    // ---- A rider without Telegram ---------------------------------------------

    @Test
    public void aRiderWithoutTelegramIsNotAskedButTheContactsAreStillWarned() throws Exception {
        when(destinatarios.chatDelMotorista(8)).thenReturn(Optional.empty());
        previas.add(pos(T - 30_000, true, 50, 43));
        new GuardianGolpes(true, storage, cliente, enlaces, destinatarios, firma, encolados::add)
                .alEvento(accidente(), pos(T, false, 0, 43));
        ArgumentCaptor<Guardia> captor = ArgumentCaptor.forClass(Guardia.class);
        verify(storage).addObject(captor.capture(), any());
        assertTrue(encolados.isEmpty(), "nothing to send to a rider without Telegram");
        Guardia guardada = captor.getValue();
        abiertas.add(guardada);
        guardian().revisar(guardada.getLimite().getTime());
        verify(cliente).enviar(eq("901"), contains("Posible accidente"), anyList());
        verify(cliente, never()).enviar(eq("900"), anyString(), anyList());
    }

    // ---- The button: every answer, word for word ------------------------------

    @Test
    public void theButtonOfAGuardThatNoLongerExists() throws Exception {
        when(storage.getObject(eq(Guardia.class), any())).thenReturn(null);
        assertEquals("Este aviso ya no existe.", guardian().pulsado(7, "900"));
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void theButtonAfterTheWarningSaysTheContactsKnow() throws Exception {
        Guardia avisada = guardia(T);
        avisada.setEstado(Guardia.AVISADA);
        when(storage.getObject(eq(Guardia.class), any())).thenReturn(avisada);
        assertEquals("Tus contactos ya han sido avisados. Diles tú que estás bien.", guardian().pulsado(7, "900"));
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void theButtonOfAGuardAlreadyCancelled() throws Exception {
        for (String estado : List.of(Guardia.CANCELADA_MOVIMIENTO, Guardia.CANCELADA_BOTON)) {
            Guardia cancelada = guardia(T);
            cancelada.setEstado(estado);
            when(storage.getObject(eq(Guardia.class), any())).thenReturn(cancelada);
            assertEquals("Ya estaba cancelado: no avisaremos a nadie.", guardian().pulsado(7, "900"), estado);
        }
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void theButtonWhenTheDatabaseFailsAsksToPressAgain() throws Exception {
        when(storage.getObject(eq(Guardia.class), any())).thenReturn(guardia(T));
        doThrow(new StorageException("caída")).when(storage).updateObject(any(), any());
        assertEquals("No se ha podido guardar. Vuelve a pulsar.", guardian().pulsado(7, "900"));
        when(storage.getObject(eq(Guardia.class), any())).thenThrow(new StorageException("caída"));
        assertEquals("No se ha podido guardar. Vuelve a pulsar.", guardian().pulsado(7, "900"));
    }

    // ---- The countdown --------------------------------------------------------

    @Test
    public void withTheInboxOffTheCountdownDoesNothing() throws Exception {
        abiertas.add(guardia(T));
        new GuardianGolpes(false, storage, cliente, enlaces, destinatarios, firma, Runnable::run)
                .revisar(T + ReglasGolpe.ESPERA + 1_000);
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void atTheExactLimitTheContactsAreWarned() throws Exception {
        abiertas.add(guardia(T));
        guardian().revisar(T + ReglasGolpe.ESPERA);
        verify(cliente).enviar(eq("901"), contains("Posible accidente"), anyList());
    }

    @Test
    public void ridingBeforeTheCrashIsNotRidingOn() throws Exception {
        abiertas.add(guardia(T));
        posteriores.add(pos(T - 60_000, true, 50, 42.99));
        posteriores.add(pos(T - 40_000, true, 50, 42.995));
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        verify(cliente).enviar(eq("901"), contains("Posible accidente"), anyList());
    }

    @Test
    public void aFixBeforeTheCrashIsGivenAsTheLastPlaceKnownBeforeIt() throws Exception {
        // Found in review on 2026-09-30: with no fix in the crash frame nor after it,
        // the last fix before it was thrown away and the contact read "a place the
        // GPS has not fixed yet" when the street was known. It is given, and it
        // says what it is: never passed off as where the bike lies now.
        abiertas.add(guardia(T));
        Position golpe = pos(T, false, 0, 0);
        golpe.setLongitude(0);
        golpe.setValid(false);
        when(storage.getObject(eq(Position.class), any())).thenReturn(golpe);
        posteriores.add(pos(T - 90_000, true, 50, 43.0));
        posteriores.add(pos(T - 60_000, true, 50, 43.1));
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        ArgumentCaptor<String> texto = ArgumentCaptor.forClass(String.class);
        verify(cliente).enviar(eq("901"), texto.capture(), anyList());
        assertTrue(texto.getValue().contains("43.10000, -8.00000 (su última posición conocida antes del golpe)"),
                texto.getValue());
    }

    @Test
    public void aFixAfterTheCrashWinsOverTheOneBefore() throws Exception {
        abiertas.add(guardia(T));
        Position golpe = pos(T, false, 0, 0);
        golpe.setLongitude(0);
        golpe.setValid(false);
        when(storage.getObject(eq(Position.class), any())).thenReturn(golpe);
        posteriores.add(pos(T - 60_000, true, 50, 43.1));
        posteriores.add(pos(T + 40_000, false, 0, 43.2));
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        ArgumentCaptor<String> texto = ArgumentCaptor.forClass(String.class);
        verify(cliente).enviar(eq("901"), texto.capture(), anyList());
        assertTrue(texto.getValue().contains("43.20000, -8.00000."), texto.getValue());
        assertFalse(texto.getValue().contains("antes del golpe"), texto.getValue());
    }

    @Test
    public void withNoFixAtAllAndNoLinkNoLiveMapIsPromised() throws Exception {
        abiertas.add(guardia(T));
        Position golpe = pos(T, false, 0, 0);
        golpe.setLongitude(0);
        golpe.setValid(false);
        when(storage.getObject(eq(Position.class), any())).thenReturn(golpe);
        when(enlaces.enlaceDeEquipo(anyLong(), any(), any(), anyLong())).thenReturn(Optional.empty());
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        ArgumentCaptor<String> texto = ArgumentCaptor.forClass(String.class);
        verify(cliente).enviar(eq("901"), texto.capture(), anyList());
        assertTrue(texto.getValue().contains(Mensajes.lugarDesconocido(false) + "."), texto.getValue());
        assertFalse(texto.getValue().contains("directo"), texto.getValue());
    }

    @Test
    public void withNoFixAtAllTheLinkIsPointedTo() throws Exception {
        abiertas.add(guardia(T));
        Position golpe = pos(T, false, 0, 0);
        golpe.setLongitude(0);
        golpe.setValid(false);
        when(storage.getObject(eq(Position.class), any())).thenReturn(golpe);
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        verify(cliente).enviar(eq("901"), contains(Mensajes.lugarDesconocido(true)), anyList());
    }

    @Test
    public void aPositionWithoutDeviceTimeDoesNotStopTheWarning() throws Exception {
        abiertas.add(guardia(T));
        Position sinHora = pos(T + 10_000, false, 0, 43);
        sinHora.setDeviceTime(null);
        posteriores.add(sinHora);
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        verify(cliente).enviar(eq("901"), contains("Posible accidente"), anyList());
    }

    // ---- What each person reads -----------------------------------------------

    @Test
    public void theContactReadsTheContactTextAndTheRideMateTheRideText() throws Exception {
        abiertas.add(guardia(T));
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        ArgumentCaptor<String> alContacto = ArgumentCaptor.forClass(String.class);
        verify(cliente).enviar(eq("901"), alContacto.capture(), anyList());
        assertTrue(alContacto.getValue().startsWith("⚠️ Posible accidente de Juan con su Ducati"), alContacto.getValue());
        assertFalse(alContacto.getValue().contains("Compañero"), alContacto.getValue());
        ArgumentCaptor<String> alCompanero = ArgumentCaptor.forClass(String.class);
        verify(cliente).enviar(eq("905"), alCompanero.capture(), anyList());
        assertTrue(alCompanero.getValue().startsWith(
                "Compañero de la quedada «Ruta del Eume».\n⚠️ Posible accidente de Juan"), alCompanero.getValue());
    }

    @Test
    public void withoutTheDeviceItIsCalledVehiculo() throws Exception {
        when(storage.getObject(eq(Device.class), any())).thenReturn(null);
        previas.add(pos(T - 30_000, true, 50, 43));
        guardian().alEvento(accidente(), pos(T, false, 0, 43));
        verify(cliente).enviar(eq("900"), contains("golpe en tu vehículo."), anyList());
        abiertas.add(guardia(T));
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        verify(cliente).enviar(eq("901"), contains("con su vehículo,"), anyList());
    }

    // ---- Sending and retrying ---------------------------------------------------

    @Test
    public void aMessageThatWentOutIsNotSentAgain() throws Exception {
        abiertas.add(guardia(T));
        GuardianGolpes guardian = guardian();
        long limite = T + ReglasGolpe.ESPERA;
        for (long despues : new long[] {0, 31_000, 151_000, 780_000, 3_600_000}) {
            guardian.revisar(limite + despues);
        }
        verify(cliente, times(1)).enviar(eq("901"), anyString(), anyList());
        verify(cliente, times(1)).enviar(eq("905"), anyString(), anyList());
    }

    @Test
    public void theRetriesKeepTheirCalendarAndThenGiveUp() throws Exception {
        abiertas.add(guardia(T));
        when(cliente.enviar(eq("905"), anyString(), anyList())).thenReturn(new RespuestaTelegram(0, "red"));
        GuardianGolpes guardian = guardian();
        long t0 = T + ReglasGolpe.ESPERA;
        long t1 = t0 + 30_000;
        long t2 = t1 + 120_000;
        long t3 = t2 + 600_000;
        long[][] pasos = {{t0, 1}, {t1 - 1, 1}, {t1, 2}, {t2 - 1, 2}, {t2, 3}, {t3 - 1, 3}, {t3, 4}, {t3 + 3_600_000, 4}};
        for (long[] paso : pasos) {
            guardian.revisar(paso[0]);
            verify(cliente, times((int) paso[1])).enviar(eq("905"), anyString(), anyList());
        }
    }

    @Test
    public void aRideMateWhoBlockedTheBotIsNotMarkedAndDoesNotStopTheOthers() throws Exception {
        // The ride mate is not an emergency contact: there is nothing of theirs to mark.
        when(destinatarios.de(8)).thenReturn(List.of(
                new Destinatarios.Destinatario("905", "Ruta del Eume", null),
                new Destinatarios.Destinatario("901", null, 1L)));
        when(cliente.enviar(eq("905"), anyString(), anyList())).thenReturn(new RespuestaTelegram(403, "blocked"));
        abiertas.add(guardia(T));
        guardian().revisar(T + ReglasGolpe.ESPERA + 1_000);
        verify(destinatarios, never()).rechazar(anyLong());
        verify(cliente).enviar(eq("901"), contains("Posible accidente"), anyList());
    }

    // ---- The watchdog (29/9: the tests prove the code; this proves it RUNS) ----

    @Test
    public void aPassThatReadTheGuardsIsRecorded() throws Exception {
        GuardianGolpes guardian = guardian();
        assertEquals(0, guardian.ultimaRevision());
        guardian.revisar(T);
        assertEquals(T, guardian.ultimaRevision());
    }

    @Test
    public void aPassThatCouldNotReadTheGuardsIsNotRecorded() throws Exception {
        GuardianGolpes guardian = guardian();
        guardian.revisar(T);
        when(storage.getObjects(eq(Guardia.class), any())).thenThrow(new StorageException("caída"));
        guardian.revisar(T + 10_000);
        assertEquals(T, guardian.ultimaRevision());
    }

    @Test
    public void anInactiveGuardianRecordsNoPass() throws Exception {
        GuardianGolpes apagado = new GuardianGolpes(
                false, storage, cliente, enlaces, destinatarios, firma, Runnable::run);
        apagado.revisar(T);
        assertEquals(0, apagado.ultimaRevision());
        assertFalse(apagado.activo());
        assertTrue(guardian().activo());
    }

    @Test
    public void aMessageGivenUpAfterEveryRetryIsCounted() throws Exception {
        abiertas.add(guardia(T));
        when(cliente.enviar(eq("905"), anyString(), anyList())).thenReturn(new RespuestaTelegram(0, "red"));
        GuardianGolpes guardian = guardian();
        long t0 = T + ReglasGolpe.ESPERA;
        guardian.revisar(t0);
        guardian.revisar(t0 + 30_000);
        guardian.revisar(t0 + 150_000);
        assertEquals(0, guardian.avisosPerdidos());
        guardian.revisar(t0 + 750_000);
        assertEquals(1, guardian.avisosPerdidos());
        guardian.revisar(t0 + 3_600_000);
        assertEquals(1, guardian.avisosPerdidos());
    }

    @Test
    public void aContactWhoBlockedTheBotIsNotALostMessage() throws Exception {
        // Lo decidió el contacto, y la web ya lo enseña como rechazado.
        when(cliente.enviar(eq("901"), anyString(), anyList())).thenReturn(new RespuestaTelegram(403, "blocked"));
        abiertas.add(guardia(T));
        GuardianGolpes guardian = guardian();
        guardian.revisar(T + ReglasGolpe.ESPERA + 1_000);
        assertEquals(0, guardian.avisosPerdidos());
    }

    @Test
    public void theOverdueCountIsTheOneOfTheLastPass() throws Exception {
        // 30/9: counted by the countdown, never by the scrape (it asked the database).
        Guardia rota = guardia(T - ReglasGolpe.ESPERA - 61_000);
        abiertas.add(rota);
        when(destinatarios.de(8)).thenThrow(new StorageException("caída"));
        GuardianGolpes guardian = guardian();
        assertTrue(guardian.vencidas(T).isEmpty(), "before the first pass it is unknown, not zero");
        guardian.revisar(T);
        assertEquals(OptionalInt.of(1), guardian.vencidas(T));
        assertEquals(OptionalInt.of(1), guardian.vencidas(T + GuardianGolpes.FRESCURA));
        assertTrue(guardian.vencidas(T + GuardianGolpes.FRESCURA + 1).isEmpty(), "an old count is not published");
    }

    @Test
    public void aGuardWarnedInThisPassIsNotOverdue() throws Exception {
        abiertas.add(guardia(T - ReglasGolpe.ESPERA - 600_000));
        GuardianGolpes guardian = guardian();
        guardian.revisar(T);
        assertEquals(Guardia.AVISADA, abiertas.get(0).getEstado());
        assertEquals(OptionalInt.of(0), guardian.vencidas(T));
    }

    @Test
    public void theOverdueLimitsAreAMinuteWaitingAndFifteenWarning() throws Exception {
        when(destinatarios.de(8)).thenThrow(new StorageException("caída"));
        long[][] casos = {
            {61_000, 1}, {60_000, 0}, {0, 0},
        };
        for (long[] caso : casos) {
            abiertas.clear();
            abiertas.add(guardia(T - ReglasGolpe.ESPERA - caso[0]));
            GuardianGolpes guardian = guardian();
            guardian.revisar(T);
            assertEquals(OptionalInt.of((int) caso[1]), guardian.vencidas(T), "waiting " + caso[0]);
        }
        Guardia sinLimite = guardia(T);
        sinLimite.setLimite(null);
        sinLimite.setEstado(Guardia.AVISADA);
        abiertas.clear();
        abiertas.add(sinLimite);
        GuardianGolpes guardian = guardian();
        guardian.revisar(T);
        assertEquals(OptionalInt.of(0), guardian.vencidas(T), "a warned guard is never overdue");
    }

    @Test
    public void withoutTheDatabaseTheOverdueCountIsUnknownNotZero() throws Exception {
        GuardianGolpes guardian = guardian();
        guardian.revisar(T);
        when(storage.getObjects(eq(Guardia.class), any())).thenThrow(new StorageException("caída"));
        guardian.revisar(T + 70_000);
        assertTrue(guardian.vencidas(T + 70_000).isEmpty());
    }

    // ---- The question to the rider (30/9) ------------------------------------------

    @Test
    public void theQuestionIsRetriedWithinTheTwoMinutes() throws Exception {
        // Found in review on 2026-09-30: sent once, a 429 or a slow Telegram left the
        // rider without the button, and the family was warned about somebody fine.
        previas.add(pos(T - 30_000, true, 50, 43));
        when(cliente.enviar(eq("900"), anyString(), anyList())).thenReturn(
                new RespuestaTelegram(429, "too many"), new RespuestaTelegram(502, "bad gateway"),
                new RespuestaTelegram(200, null));
        GuardianGolpes guardian = guardian();
        guardian.alEvento(accidente(), pos(T, false, 0, 43));
        long ahora = System.currentTimeMillis();
        guardian.revisar(ahora + 4_000);
        verify(cliente, times(1)).enviar(eq("900"), anyString(), anyList());
        guardian.revisar(ahora + 10_000);
        verify(cliente, times(2)).enviar(eq("900"), anyString(), anyList());
        guardian.revisar(ahora + 30_000);
        verify(cliente, times(3)).enviar(eq("900"), contains("golpe en tu Ducati"), anyList());
        guardian.revisar(ahora + 90_000);
        verify(cliente, times(3)).enviar(eq("900"), anyString(), anyList());
        assertEquals(0, guardian.preguntasFallidas());
        assertTrue(ahora + 30_000 < T + ReglasGolpe.ESPERA + 30_000);
    }

    @Test
    public void aQuestionThatNeverArrivesIsCountedAndTheWarningStillGoesOut() throws Exception {
        previas.add(pos(T - 30_000, true, 50, 43));
        when(cliente.enviar(eq("900"), anyString(), anyList())).thenReturn(new RespuestaTelegram(0, "timeout"));
        GuardianGolpes guardian = guardian();
        guardian.alEvento(accidente(), pos(T, false, 0, 43));
        long ahora = System.currentTimeMillis();
        for (long t = ahora; t <= ahora + 100_000; t += 10_000) {
            guardian.revisar(t);
        }
        verify(cliente, times(1 + GuardianGolpes.ESPERAS_PREGUNTA.length)).enviar(eq("900"), anyString(), anyList());
        assertEquals(1, guardian.preguntasFallidas());
        assertEquals(0, guardian.avisosPerdidos());
    }

    @Test
    public void aRiderWhoBlockedTheBotIsCountedAtOnceAndNotRetried() throws Exception {
        previas.add(pos(T - 30_000, true, 50, 43));
        when(cliente.enviar(eq("900"), anyString(), anyList())).thenReturn(new RespuestaTelegram(403, "blocked"));
        GuardianGolpes guardian = guardian();
        guardian.alEvento(accidente(), pos(T, false, 0, 43));
        long ahora = System.currentTimeMillis();
        guardian.revisar(ahora + 60_000);
        verify(cliente, times(1)).enviar(eq("900"), anyString(), anyList());
        assertEquals(1, guardian.preguntasFallidas());
        verify(destinatarios, never()).rechazar(anyLong());
    }

    @Test
    public void aTelegramClientThatThrowsIsAFailedSendNotALostThread() throws Exception {
        previas.add(pos(T - 30_000, true, 50, 43));
        when(cliente.enviar(eq("900"), anyString(), anyList()))
                .thenThrow(new RuntimeException("read timed out"))
                .thenReturn(new RespuestaTelegram(200, null));
        GuardianGolpes guardian = guardian();
        guardian.alEvento(accidente(), pos(T, false, 0, 43));
        guardian.revisar(System.currentTimeMillis() + 10_000);
        verify(cliente, times(2)).enviar(eq("900"), anyString(), anyList());
        assertEquals(0, guardian.preguntasFallidas());
    }

}
