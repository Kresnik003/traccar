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
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;
import org.traccar.telegram.ClienteTelegram;
import org.traccar.telegram.FirmaBoton;
import org.traccar.telegram.RespuestaTelegram;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Has left" and "has arrived" for the family.
 *
 * The trips table and the positions answer as the database would: each query's
 * own condition decides which rows come back, and a row is a copy that only an
 * update changes. So a wrong time window, or a link that is never saved, shows
 * up as what a person would notice - a trip that never closes, a link that
 * keeps working after the arrival - and not only as the shape of a query.
 */
public class ViajesFamiliaTest {

    private static final long T = System.currentTimeMillis();

    private Storage storage;
    private ClienteTelegram cliente;
    private EnlacesDirectos enlaces;
    private Destinatarios destinatarios;
    private SitiosPropios sitios;
    private final FirmaBoton firma = new FirmaBoton("s");
    /** The rows of the trips table, open or not. */
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

    private static Viaje copia(Viaje viaje) {
        Viaje copia = new Viaje();
        copia.setId(viaje.getId());
        copia.setDeviceId(viaje.getDeviceId());
        copia.setInicio(viaje.getInicio());
        copia.setFin(viaje.getFin());
        copia.setMotivoFin(viaje.getMotivoFin());
        copia.setOrigen(viaje.getOrigen());
        copia.setShareUserId(viaje.getShareUserId());
        copia.setEnlace(viaje.getEnlace());
        return copia;
    }

    private static Object columna(Viaje viaje, String nombre) {
        return switch (nombre) {
            case "id" -> viaje.getId();
            case "deviceId" -> viaje.getDeviceId();
            case "inicio" -> viaje.getInicio();
            default -> throw new IllegalArgumentException("columna de viaje no prevista: " + nombre);
        };
    }

    private static Object columna(Position position, String nombre) {
        return switch (nombre) {
            case "deviceId" -> position.getDeviceId();
            case "fixTime" -> position.getFixTime();
            default -> throw new IllegalArgumentException("columna de posición no prevista: " + nombre);
        };
    }

    private static int comparar(Object valor, Object otro) {
        if (valor instanceof Date fecha) {
            return fecha.compareTo((Date) otro);
        }
        return Long.compare(((Number) valor).longValue(), ((Number) otro).longValue());
    }

    /** A condition of the storage query language, evaluated on one row as SQL would. */
    private static boolean cumple(Condition condicion, Function<String, Object> fila) {
        if (condicion == null) {
            return true;
        }
        if (condicion instanceof Condition.And ambas) {
            return cumple(ambas.getFirst(), fila) && cumple(ambas.getSecond(), fila);
        }
        if (condicion instanceof Condition.Between entre) {
            Object valor = fila.apply(entre.getColumn());
            return comparar(valor, entre.getFromValue()) >= 0 && comparar(valor, entre.getToValue()) <= 0;
        }
        if (condicion instanceof Condition.Compare comparacion) {
            int orden = comparar(fila.apply(comparacion.getColumn()), comparacion.getValue());
            return switch (comparacion.getOperator()) {
                case "=" -> orden == 0;
                case ">" -> orden > 0;
                case ">=" -> orden >= 0;
                case "<" -> orden < 0;
                case "<=" -> orden <= 0;
                default -> throw new IllegalArgumentException("operador no previsto: " + comparacion.getOperator());
            };
        }
        throw new IllegalArgumentException("condición no prevista: " + condicion.getClass().getSimpleName());
    }

    private List<Viaje> filas(Request request) {
        return abiertos.stream()
                .filter(viaje -> cumple(request.getCondition(), nombre -> columna(viaje, nombre)))
                .map(ViajesFamiliaTest::copia)
                .toList();
    }

    private void actualizar(Viaje cambios, Request request) {
        for (Viaje fila : abiertos) {
            if (!cumple(request.getCondition(), nombre -> columna(fila, nombre))) {
                continue;
            }
            for (String nombre : request.getColumns().getColumns(Viaje.class, "set")) {
                switch (nombre) {
                    case "enlace" -> fila.setEnlace(cambios.getEnlace());
                    case "shareUserId" -> fila.setShareUserId(cambios.getShareUserId());
                    case "fin" -> fila.setFin(cambios.getFin());
                    case "motivoFin" -> fila.setMotivoFin(cambios.getMotivoFin());
                    default -> throw new IllegalArgumentException("columna de viaje no prevista: " + nombre);
                }
            }
        }
    }

    private final List<Runnable> encolados = new ArrayList<>();

    private ViajesFamilia viajes() {
        return new ViajesFamilia(true, storage, cliente, enlaces, destinatarios, sitios, firma, Runnable::run);
    }

    /** At home with the engine on, then out riding: two samples 20 s apart. */
    private static void salir(ViajesFamilia viajes) {
        viajes.alPosicion(pos(T - 30_000, 43.0, true, 10));
        viajes.alPosicion(pos(T - 20_000, 43.005, true, 30));
        viajes.alPosicion(pos(T, 43.01, true, 30));
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
        when(storage.getObjects(eq(Viaje.class), any())).thenAnswer(i -> filas(i.getArgument(1)));
        // Closing reads the trip again first.
        when(storage.getObject(eq(Viaje.class), any())).thenAnswer(
                i -> filas(i.getArgument(1)).stream().findFirst().orElse(null));
        when(storage.getObjectsStream(eq(Position.class), any())).thenAnswer(i -> {
            Condition condicion = ((Request) i.getArgument(1)).getCondition();
            return recientes.stream().filter(p -> cumple(condicion, nombre -> columna(p, nombre)));
        });
        when(storage.addObject(any(Viaje.class), any())).thenAnswer(i -> {
            Viaje fila = copia(i.getArgument(0));
            fila.setId(12);
            abiertos.add(fila);
            return 12L;
        });
        doAnswer(i -> {
            actualizar(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(storage).updateObject(any(Viaje.class), any());
        Device device = new Device();
        device.setName("Ducati");
        when(storage.getObject(eq(Device.class), any())).thenReturn(device);
        when(cliente.enviar(anyString(), anyString(), anyList())).thenReturn(new RespuestaTelegram(200, null));
        when(destinatarios.chatDelMotorista(8)).thenReturn(Optional.of("900"));
        when(destinatarios.persona(8)).thenReturn("Juan");
        when(destinatarios.contactos(8)).thenReturn(List.of(new Destinatarios.Destinatario("901", null, 1L)));
        when(enlaces.enlaceDeEquipo(eq(8L), any(), eq("viaje"), eq(12L))).thenReturn(
                Optional.of(new EnlacesDirectos.Enlace("https://n.es/?token=v", 60)));
    }

    @Test
    public void aDepartureOpensATripAndTellsTheContacts() throws Exception {
        salir(viajes());
        verify(storage).addObject(any(Viaje.class), any());
        verify(cliente).enviar("901", "Juan ha salido de Casa con su Ducati.\nUbicación en directo: "
                + "https://n.es/?token=v\nTe avisaremos cuando llegue.", List.of());
        ArgumentCaptor<List<ClienteTelegram.Boton>> botones = ArgumentCaptor.forClass(List.class);
        verify(cliente).enviar(eq("900"), eq(Mensajes.alDuenoViajeAbierto()), botones.capture());
        assertEquals("He llegado", botones.getValue().get(0).texto());
        assertEquals(Optional.of(new FirmaBoton.Dato("v", 12)), firma.comprobar(botones.getValue().get(0).dato()));
    }

    @Test
    public void theTripIsStoredWithTheBikeTheStartAndThePlace() throws Exception {
        long antes = System.currentTimeMillis();
        salir(viajes());
        long despues = System.currentTimeMillis();
        assertEquals(1, abiertos.size());
        Viaje fila = abiertos.get(0);
        assertEquals(8, fila.getDeviceId());
        assertEquals("Casa", fila.getOrigen());
        assertTrue(fila.getInicio().getTime() >= antes && fila.getInicio().getTime() <= despues);
        assertNull(fila.getFin());
    }

    @Test
    public void theLinkTheContactsGetStopsWorkingWhenTheTripEnds() throws Exception {
        // Privacy: the link is saved with the trip, and closing the trip cuts THAT link. Unsaved, the family
        // could keep following the bike after "has arrived".
        ViajesFamilia viajes = viajes();
        salir(viajes);
        assertEquals(60, abiertos.get(0).getShareUserId());
        assertEquals("https://n.es/?token=v", abiertos.get(0).getEnlace());
        assertEquals("Hecho: hemos avisado de que has llegado.", viajes.pulsado(12, "900"));
        verify(enlaces).cortar(60);
    }

    @Test
    public void theLinkLastsTwelveHoursFromTheStart() throws Exception {
        salir(viajes());
        ArgumentCaptor<Date> caduca = ArgumentCaptor.forClass(Date.class);
        verify(enlaces).enlaceDeEquipo(eq(8L), caduca.capture(), eq("viaje"), eq(12L));
        assertEquals(abiertos.get(0).getInicio().getTime() + 12 * 3_600_000L, caduca.getValue().getTime());
    }

    @Test
    public void withSharingOffTheTripStillOpensAndTheMessageGoesWithoutALink() throws Exception {
        when(enlaces.enlaceDeEquipo(eq(8L), any(), eq("viaje"), eq(12L))).thenReturn(Optional.empty());
        salir(viajes());
        verify(cliente).enviar("901", "Juan ha salido de Casa con su Ducati.\nTe avisaremos cuando llegue.", List.of());
        verify(cliente).enviar(eq("900"), eq(Mensajes.alDuenoViajeAbierto()), anyList());
        assertEquals(0, abiertos.get(0).getShareUserId());
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void aVehicleThatIsGoneIsCalledVehiculo() throws Exception {
        when(storage.getObject(eq(Device.class), any())).thenReturn(null);
        salir(viajes());
        verify(cliente).enviar(eq("901"), startsWith("Juan ha salido de Casa con su vehículo."), anyList());
    }

    @Test
    public void anOpenTripDoesNotOpenAnother() throws Exception {
        abiertos.add(viaje(T - 60_000));
        salir(viajes());
        verify(storage, never()).addObject(any(Viaje.class), any());
    }

    @Test
    public void anotherBikesOpenTripDoesNotHideThisOnesDeparture() throws Exception {
        // Two bikes of the same rider: the one on the road does not keep the other in the garage.
        Viaje otra = viaje(T - 60_000);
        otra.setId(13);
        otra.setDeviceId(9);
        abiertos.add(otra);
        salir(viajes());
        verify(cliente).enviar(eq("901"), contains("ha salido de Casa"), anyList());
    }

    @Test
    public void aTripThatAlreadyEndedDoesNotHideTheNextDeparture() throws Exception {
        Viaje manana = viaje(T - 3_600_000);
        manana.setId(11);
        manana.setFin(new Date(T - 1_800_000));
        abiertos.add(manana);
        salir(viajes());
        verify(cliente).enviar(eq("901"), contains("ha salido de Casa"), anyList());
    }

    @Test
    public void withoutContactsThereIsNoTrip() throws Exception {
        when(destinatarios.contactos(8)).thenReturn(List.of());
        salir(viajes());
        verify(storage, never()).addObject(any(Viaje.class), any());
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
    }

    @Test
    public void aPositionWithoutAFixDecidesNothing() throws Exception {
        Position sinFix = pos(T, 43.01, true, 30);
        sinFix.setValid(false);
        ViajesFamilia viajes = viajes();
        viajes.alPosicion(pos(T - 30_000, 43.0, true, 10));
        viajes.alPosicion(pos(T - 20_000, 43.005, true, 30));
        // The second riding sample, but without a fix: it confirms nothing.
        viajes.alPosicion(sinFix);
        verify(storage, never()).addObject(any(Viaje.class), any());
    }

    @Test
    public void withoutPlacesAnymoreTheBikeNoLongerLeavesFromThem() throws Exception {
        ViajesFamilia viajes = viajes();
        viajes.alPosicion(pos(T - 30_000, 43.0, true, 10));
        viajes.alPosicion(pos(T - 20_000, 43.005, true, 30));
        // The rider deleted «Casa», or the bike changed hands to someone without places.
        when(sitios.de(8)).thenReturn(List.of());
        viajes.alPosicion(pos(T, 43.01, true, 30));
        // And the arming went with them: «Casa» back later does not make that ride a departure.
        when(sitios.de(8)).thenReturn(List.of(new Sitio(1, "Casa", new GeofenceCircle("CIRCLE (43 -8, 100)"))));
        viajes.alPosicion(pos(T + 20_000, 43.015, true, 30));
        viajes.alPosicion(pos(T + 40_000, 43.02, true, 30));
        verify(storage, never()).addObject(any(Viaje.class), any());
    }

    @Test
    public void aStorageFailureNeverReachesTheNettyThread() throws Exception {
        when(sitios.de(8)).thenThrow(new StorageException("caída"));
        assertDoesNotThrow(() -> viajes().alPosicion(pos(T, 43.01, true, 30)));
    }

    @Test
    public void theButtonClosesTellsAndCutsTheLink() throws Exception {
        when(storage.getObject(eq(Viaje.class), any())).thenReturn(viaje(T - 3_600_000));
        String respuesta = viajes().pulsado(12, "900");
        ArgumentCaptor<Viaje> captor = ArgumentCaptor.forClass(Viaje.class);
        verify(storage).updateObject(captor.capture(), any());
        assertNotNull(captor.getValue().getFin());
        assertEquals("boton", captor.getValue().getMotivoFin());
        verify(cliente).enviar("901", "Juan ha llegado bien.", List.of());
        verify(enlaces).cortar(60);
        assertEquals("Hecho: hemos avisado de que has llegado.", respuesta);
    }

    @Test
    public void somebodyElsesButtonDoesNotClose() throws Exception {
        when(storage.getObject(eq(Viaje.class), any())).thenReturn(viaje(T - 3_600_000));
        assertEquals("Este botón no es tuyo.", viajes().pulsado(12, "999"));
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void theButtonOfATripThatIsGoneSaysSo() throws Exception {
        assertEquals("Este viaje ya no existe.", viajes().pulsado(12, "900"));
        verify(storage, never()).updateObject(any(), any());
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
    }

    @Test
    public void theButtonOfATripAlreadyClosedChangesNothing() throws Exception {
        // The arrival closed it a moment ago: pressing now must not tell the family a second time.
        Viaje cerrado = viaje(T - 3_600_000);
        cerrado.setFin(new Date(T - 60_000));
        abiertos.add(cerrado);
        assertEquals("Este viaje ya estaba cerrado.", viajes().pulsado(12, "900"));
        verify(storage, never()).updateObject(any(), any());
        verify(enlaces, never()).cortar(anyLong());
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
    }

    @Test
    public void whenTheCloseCannotBeSavedTheRiderIsAskedToPressAgain() throws Exception {
        abiertos.add(viaje(T - 3_600_000));
        doThrow(new StorageException("caída")).when(storage).updateObject(any(Viaje.class), any());
        assertEquals("No se ha podido guardar. Vuelve a pulsar.", viajes().pulsado(12, "900"));
        verify(enlaces, never()).cortar(anyLong());
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
    }

    @Test
    public void aTripWithoutALinkHasNothingToCut() throws Exception {
        Viaje sinEnlace = viaje(T - 3_600_000);
        sinEnlace.setShareUserId(0);
        abiertos.add(sinEnlace);
        viajes().pulsado(12, "900");
        assertNotNull(abiertos.get(0).getFin());
        verify(enlaces, never()).cortar(anyLong());
    }

    @Test
    public void stayingStillAtOneOfYourPlacesIsAnArrival() throws Exception {
        abiertos.add(viaje(T - 3_600_000));
        // Newest first: at work, still and off for 3 minutes, riding before.
        recientes.add(pos(T, 43.05, false, 0));
        recientes.add(pos(T - 180_000, 43.05, false, 0));
        recientes.add(pos(T - 240_000, 43.04, true, 40));
        viajes().revisar(T);
        verify(cliente).enviar("901", "Juan ha llegado bien a Trabajo.", List.of());
        verify(enlaces).cortar(60);
        assertEquals("sitio", abiertos.get(0).getMotivoFin());
        assertNotNull(abiertos.get(0).getFin());
    }

    @Test
    public void theHalfHourWindowCountsFromItsFirstInstant() throws Exception {
        // A long trip: stillness is looked for in the last half hour, the position at its very start included.
        abiertos.add(viaje(T - 3_600_000));
        recientes.add(pos(T, 43.05, false, 0));
        recientes.add(pos(T - 30 * 60_000, 43.05, false, 0));
        viajes().revisar(T);
        verify(cliente).enviar("901", "Juan ha llegado bien a Trabajo.", List.of());
    }

    @Test
    public void theTimeParkedAtHomeBeforeLeavingIsNotTimeParkedAtTheDestination() throws Exception {
        // Left a minute ago, no coverage on the way, first position already at work: the five minutes still at
        // home before the start are not this trip's, so it has not been still at work for two minutes yet.
        abiertos.add(viaje(T - 60_000));
        recientes.add(pos(T, 43.05, false, 0));
        recientes.add(pos(T - 300_000, 43.0, false, 0));
        viajes().revisar(T);
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
        assertNull(abiertos.get(0).getFin());
    }

    @Test
    public void aRowWithoutDeviceTimeDoesNotStopTheArrival() throws Exception {
        abiertos.add(viaje(T - 3_600_000));
        recientes.add(pos(T, 43.05, false, 0));
        Position sinHora = pos(T - 60_000, 43.05, false, 0);
        sinHora.setDeviceTime(null);
        recientes.add(sinHora);
        recientes.add(pos(T - 180_000, 43.05, false, 0));
        viajes().revisar(T);
        verify(cliente).enviar("901", "Juan ha llegado bien a Trabajo.", List.of());
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
        // Skipped from the list itself: every 30 s, a day of closed trips is not read again one by one.
        verify(storage, never()).getObject(eq(Viaje.class), any());
    }

    @Test
    public void withTheInboxOffTheCheckTouchesNothing() throws Exception {
        abiertos.add(viaje(T - 3_600_000));
        recientes.add(pos(T, 43.05, false, 0));
        recientes.add(pos(T - 180_000, 43.05, false, 0));
        new ViajesFamilia(false, storage, cliente, enlaces, destinatarios, sitios, firma, Runnable::run).revisar(T);
        verify(storage, never()).getObjects(eq(Viaje.class), any());
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
        assertNull(abiertos.get(0).getFin());
    }

    @Test
    public void theGpsDriftingOutOfTheGarageDoesNotLeave() throws Exception {
        ViajesFamilia viajes = viajes();
        viajes.alPosicion(pos(T - 10_000, 43.0, true, 0));
        viajes.alPosicion(pos(T, 43.0015, true, 0));
        verify(storage, never()).addObject(any(Viaje.class), any());
    }

    @Test
    public void aSlowWayOutStaysArmedUntilRiding() throws Exception {
        ViajesFamilia viajes = viajes();
        viajes.alPosicion(pos(T - 20_000, 43.0, true, 5));
        viajes.alPosicion(pos(T - 10_000, 43.0015, true, 5));
        verify(storage, never()).addObject(any(Viaje.class), any());
        viajes.alPosicion(pos(T, 43.004, true, 35));
        viajes.alPosicion(pos(T + 15_000, 43.006, true, 35));
        verify(cliente).enviar(eq("901"), contains("ha salido de Casa"), anyList());
    }

    @Test
    public void heartbeatsAtZeroZeroDoNotBreakTheWayOut() throws Exception {
        // Found in review: the "before" position was the raw previous one, and a
        // heartbeat at 0,0 between home and the road lost the departure.
        ViajesFamilia viajes = viajes();
        viajes.alPosicion(pos(T - 20_000, 43.0, true, 0));
        Position latido = pos(T - 10_000, 0, false, 0);
        latido.setLongitude(0);
        latido.setValid(false);
        viajes.alPosicion(latido);
        viajes.alPosicion(pos(T, 43.01, true, 40));
        viajes.alPosicion(pos(T + 20_000, 43.015, true, 40));
        verify(storage).addObject(any(Viaje.class), any());
    }

    @Test
    public void theSendsLeaveTheCallersThread() throws Exception {
        ViajesFamilia viajes = new ViajesFamilia(
                true, storage, cliente, enlaces, destinatarios, sitios, firma, encolados::add);
        salir(viajes);
        verify(storage).addObject(any(Viaje.class), any());
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
        encolados.forEach(Runnable::run);
        verify(cliente).enviar(eq("901"), contains("ha salido de Casa"), anyList());
    }

    @Test
    public void withTheInboxOffThereAreNoTrips() throws Exception {
        salir(new ViajesFamilia(false, storage, cliente, enlaces, destinatarios, sitios, firma, Runnable::run));
        verify(storage, never()).addObject(any(Viaje.class), any());
    }

    @Test
    public void theArrivalReadsTheTripAgainBeforeClosing() throws Exception {
        // The button and the check close the same trip from two threads.
        abiertos.add(viaje(T - 3_600_000));
        Viaje cerrado = viaje(T - 3_600_000);
        cerrado.setFin(new Date(T - 1_000));
        when(storage.getObject(eq(Viaje.class), any())).thenReturn(cerrado);
        recientes.add(pos(T, 43.05, false, 0));
        recientes.add(pos(T - 180_000, 43.05, false, 0));
        viajes().revisar(T);
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
    }

    @Test
    public void oneFastSampleOfAManoeuvreIsNotADeparture() throws Exception {
        // The real Ducati on 13/9: 16, 19 and 23 km/h at the edge of its parking
        // within 11 s, and parked again, off. Found in the second review.
        ViajesFamilia viajes = viajes();
        viajes.alPosicion(pos(T - 60_000, 43.0, true, 0));
        viajes.alPosicion(pos(T, 43.0012, true, 16));
        viajes.alPosicion(pos(T + 9_000, 43.0011, true, 19));
        viajes.alPosicion(pos(T + 11_000, 43.0010, true, 23));
        viajes.alPosicion(pos(T + 15_000, 43.0, false, 0));
        viajes.alPosicion(pos(T + 34_000, 43.0, false, 0));
        verify(storage, never()).addObject(any(Viaje.class), any());
    }

    @Test
    public void ridingOutFiresOnceFifteenSecondsAfterTheFirstSample() throws Exception {
        ViajesFamilia viajes = viajes();
        viajes.alPosicion(pos(T - 60_000, 43.0, true, 0));
        viajes.alPosicion(pos(T, 43.002, true, 30));
        viajes.alPosicion(pos(T + 14_000, 43.004, true, 30));
        verify(storage, never()).addObject(any(Viaje.class), any());
        viajes.alPosicion(pos(T + 15_000, 43.005, true, 30));
        viajes.alPosicion(pos(T + 30_000, 43.01, true, 30));
        viajes.alPosicion(pos(T + 45_000, 43.02, true, 30));
        verify(storage).addObject(any(Viaje.class), any());
    }

    @Test
    public void aSlowerSampleInBetweenStartsTheCountAgain() throws Exception {
        ViajesFamilia viajes = viajes();
        viajes.alPosicion(pos(T - 60_000, 43.0, true, 0));
        viajes.alPosicion(pos(T, 43.002, true, 30));
        viajes.alPosicion(pos(T + 10_000, 43.0025, true, 10));
        viajes.alPosicion(pos(T + 20_000, 43.003, true, 30));
        verify(storage, never()).addObject(any(Viaje.class), any());
        viajes.alPosicion(pos(T + 35_000, 43.005, true, 30));
        verify(storage).addObject(any(Viaje.class), any());
    }

    @Test
    public void goingBackInBetweenIsNotRidingOut() throws Exception {
        ViajesFamilia viajes = viajes();
        viajes.alPosicion(pos(T - 60_000, 43.0, true, 0));
        viajes.alPosicion(pos(T, 43.002, true, 30));
        viajes.alPosicion(pos(T + 10_000, 43.0, true, 20));
        viajes.alPosicion(pos(T + 20_000, 43.002, true, 30));
        verify(storage, never()).addObject(any(Viaje.class), any());
    }

    @Test
    public void aBikeTakenAwayWithTheIgnitionOffIsDisarmed() throws Exception {
        // Found in the second review: armed at home, carried to the workshop in a
        // van, and days later "has left home" when ridden out of the workshop.
        ViajesFamilia viajes = viajes();
        viajes.alPosicion(pos(T - 3_600_000, 43.0, false, 0));
        viajes.alPosicion(pos(T - 3_000_000, 43.03, false, 40));
        viajes.alPosicion(pos(T, 43.03, true, 30));
        viajes.alPosicion(pos(T + 20_000, 43.035, true, 30));
        verify(storage, never()).addObject(any(Viaje.class), any());
    }

    @Test
    public void theFixDriftingWithTheIgnitionOffKeepsItArmed() throws Exception {
        ViajesFamilia viajes = viajes();
        viajes.alPosicion(pos(T - 3_600_000, 43.0, false, 0));
        // 167 m out, off: the drift of a garage, not a van.
        viajes.alPosicion(pos(T - 3_000_000, 43.0015, false, 1));
        salir(viajes);
        verify(storage).addObject(any(Viaje.class), any());
    }

    @Test
    public void armedFromAPlaceThatIsNoLongerTheRidersIsNotArmed() throws Exception {
        // Another person now rides it: «Casa» was the old rider's, and leaving it
        // is not the new rider leaving.
        ViajesFamilia viajes = viajes();
        viajes.alPosicion(pos(T - 60_000, 43.0, false, 0));
        when(sitios.de(8)).thenReturn(List.of(new Sitio(2, "Trabajo", new GeofenceCircle("CIRCLE (44 -8, 100)"))));
        viajes.alPosicion(pos(T, 43.002, true, 30));
        viajes.alPosicion(pos(T + 20_000, 43.005, true, 30));
        verify(storage, never()).addObject(any(Viaje.class), any());
    }

    @Test
    public void afterARestartTheArmingIsRebuiltFromTheDatabase() throws Exception {
        // Found in the second review: parked at home, restarted overnight, and the
        // morning's departure was lost - the first fix came already outside.
        recientes.add(pos(T - 8 * 3_600_000L, 43.0, false, 0));
        Position latido = pos(T - 3_600_000, 0, false, 0);
        latido.setLongitude(0);
        latido.setValid(false);
        recientes.add(latido);
        ViajesFamilia viajes = viajes();
        viajes.alPosicion(pos(T, 43.002, true, 30));
        viajes.alPosicion(pos(T + 20_000, 43.005, true, 30));
        verify(storage).addObject(any(Viaje.class), any());
    }

    @Test
    public void afterARestartOutsideEverywhereIsNotArmed() throws Exception {
        recientes.add(pos(T - 8 * 3_600_000L, 43.03, false, 0));
        ViajesFamilia viajes = viajes();
        viajes.alPosicion(pos(T, 43.032, true, 30));
        viajes.alPosicion(pos(T + 20_000, 43.035, true, 30));
        verify(storage, never()).addObject(any(Viaje.class), any());
    }

    @Test
    public void theArmingIsLookedForOnlyOncePerBike() throws Exception {
        ViajesFamilia viajes = viajes();
        for (int i = 0; i < 5; i++) {
            viajes.alPosicion(pos(T + i * 20_000L, 43.03, true, 30));
        }
        verify(storage, org.mockito.Mockito.times(1)).getObjectsStream(eq(Position.class), any());
    }

    @Test
    public void aDatabaseFailureRebuildingIsTriedAgainAndNeverThrows() throws Exception {
        recientes.add(pos(T - 8 * 3_600_000L, 43.0, false, 0));
        when(storage.getObjectsStream(eq(Position.class), any()))
                .thenThrow(new StorageException("caída"))
                .thenAnswer(i -> recientes.stream());
        ViajesFamilia viajes = viajes();
        assertDoesNotThrow(() -> viajes.alPosicion(pos(T, 43.002, true, 30)));
        viajes.alPosicion(pos(T + 20_000, 43.005, true, 30));
        viajes.alPosicion(pos(T + 40_000, 43.008, true, 30));
        verify(storage).addObject(any(Viaje.class), any());
    }

}
