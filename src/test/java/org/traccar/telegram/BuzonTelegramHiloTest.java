package org.traccar.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.EstadoTelegram;
import org.traccar.seguridad.Destinatarios;
import org.traccar.seguridad.GuardianGolpes;
import org.traccar.seguridad.ViajesFamilia;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The inbox's own thread. If it dies or gets stuck, "I'm fine" stops working -
 * the family of somebody who is fine gets warned - and nobody can sign up as a
 * contact. Until 2026-09-29 not a line of it ran in a test: PIT found start(),
 * the loop and stop() without coverage.
 */
public class BuzonTelegramHiloTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FirmaBoton firma = new FirmaBoton("s");
    private final Queue<Object> guion = new ConcurrentLinkedQueue<>();
    private final List<Long> desdes = new CopyOnWriteArrayList<>();
    private final List<Long> esperas = new CopyOnWriteArrayList<>();
    private final List<BuzonTelegram> arrancados = new ArrayList<>();
    private volatile Thread hilo;
    private Supplier<ClienteTelegram.Lectura> alVaciar;
    private Storage storage;
    private ClienteTelegram cliente;
    private Destinatarios destinatarios;
    private ReceptorBoton guardian;
    private ReceptorBoton viajes;

    @BeforeEach
    public void preparar() {
        storage = mock(Storage.class);
        destinatarios = mock(Destinatarios.class);
        guardian = mock(ReceptorBoton.class);
        when(guardian.accion()).thenReturn("g");
        when(guardian.pulsado(anyLong(), anyString())).thenReturn("Perfecto");
        cliente = mock(ClienteTelegram.class);
        // Once the script runs out, getUpdates finds nothing new: it comes back
        // soon, or at once if the thread is being interrupted.
        alVaciar = () -> {
            pausa(10);
            return lectura("[]");
        };
        when(cliente.leer(anyLong(), anyInt())).thenAnswer(invocacion -> {
            hilo = Thread.currentThread();
            desdes.add(invocacion.getArgument(0));
            Object siguiente = guion.poll();
            if (siguiente instanceof RuntimeException e) {
                throw e;
            }
            return siguiente != null ? (ClienteTelegram.Lectura) siguiente : alVaciar.get();
        });
    }

    @AfterEach
    public void parar() throws InterruptedException {
        for (BuzonTelegram buzon : arrancados) {
            buzon.stop();
        }
    }

    private static void pausa(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void esperarA(BooleanSupplier condicion) throws InterruptedException {
        long limite = System.currentTimeMillis() + 5_000;
        while (!condicion.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < limite, "the inbox did not get there in 5 s");
            Thread.sleep(5);
        }
    }

    private ClienteTelegram.Lectura lectura(String json) {
        try {
            return new ClienteTelegram.Lectura(200, mapper.readTree(json), null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ClienteTelegram.Lectura fallo(int estado) {
        return new ClienteTelegram.Lectura(estado, mapper.createArrayNode(), "refused");
    }

    private String boton(long updateId, String accion, long id) {
        return "{\"update_id\":" + updateId + ",\"callback_query\":{\"id\":\"cb" + updateId + "\",\"data\":\""
                + firma.firmar(accion, id) + "\",\"message\":{\"chat\":{\"id\":900}}}}";
    }

    private EstadoTelegram guardado(long ultimo) throws StorageException {
        EstadoTelegram estado = new EstadoTelegram();
        estado.setId(EstadoTelegram.FILA);
        estado.setUltimo(ultimo);
        when(storage.getObject(eq(EstadoTelegram.class), any())).thenReturn(estado);
        return estado;
    }

    /** An inbox switched on, with a sleep that only writes down how long it would wait. */
    private BuzonTelegram activo(BuzonTelegram.Dormir dormir) {
        BuzonTelegram buzon = new BuzonTelegram(
                true, storage, cliente, destinatarios, firma, List.of(guardian), dormir);
        arrancados.add(buzon);
        return buzon;
    }

    private BuzonTelegram activo() {
        return activo(esperas::add);
    }

    private BuzonTelegram configurado(String buzon, String secundario) {
        Config config = new Config();
        if (buzon != null) {
            config.setString(Keys.TELEGRAM_BUZON, buzon);
        }
        if (secundario != null) {
            config.setString(Keys.BROADCAST_SECONDARY, secundario);
        }
        config.setString(Keys.NOTIFICATOR_TELEGRAM_KEY, "s");
        GuardianGolpes guardianGolpes = mock(GuardianGolpes.class);
        when(guardianGolpes.accion()).thenReturn("g");
        when(guardianGolpes.pulsado(anyLong(), anyString())).thenReturn("Perfecto");
        ViajesFamilia viajesFamilia = mock(ViajesFamilia.class);
        when(viajesFamilia.accion()).thenReturn("v");
        when(viajesFamilia.pulsado(anyLong(), anyString())).thenReturn("Viaje cerrado");
        guardian = guardianGolpes;
        viajes = viajesFamilia;
        BuzonTelegram resultado = new BuzonTelegram(
                config, storage, cliente, destinatarios, guardianGolpes, viajesFamilia);
        arrancados.add(resultado);
        return resultado;
    }

    @Test
    public void itReadsHandlesAndSavesTheOffsetOnlyWhenItMoves() throws Exception {
        guardado(5);
        guion.add(lectura("[" + boton(5, "g", 7) + "]"));
        BuzonTelegram buzon = activo();
        buzon.start();
        esperarA(() -> desdes.size() >= 4);
        buzon.stop();

        // A long poll of 25 s from the saved offset, then from the next one.
        verify(cliente, times(1)).leer(5, 25);
        assertEquals(List.of(5L, 6L, 6L, 6L), desdes.subList(0, 4));
        verify(guardian).pulsado(7, "900");
        verify(cliente).responderBoton("cb5", "Perfecto");
        // Saved once, when it moved - not after every empty read - and on the
        // row that is already there.
        ArgumentCaptor<EstadoTelegram> estado = ArgumentCaptor.forClass(EstadoTelegram.class);
        ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
        verify(storage, times(1)).updateObject(estado.capture(), request.capture());
        assertEquals(6, estado.getValue().getUltimo());
        assertEquals(List.of("ultimo"), request.getValue().getColumns().getColumns(EstadoTelegram.class, "set"));
        verify(storage, never()).addObject(any(), any());
        assertTrue(esperas.isEmpty(), "it waited without any failure: " + esperas);
    }

    @Test
    public void afterRefusalsItWaitsLongerEachTimeAndThenWorksAgain() throws Exception {
        guardado(5);
        guion.add(fallo(409));
        guion.add(fallo(0));
        guion.add(fallo(401));
        guion.add(lectura("[" + boton(5, "g", 7) + "]"));
        guion.add(fallo(409));
        BuzonTelegram buzon = activo();
        buzon.start();
        esperarA(() -> esperas.size() >= 4);
        verify(guardian, timeout(5_000)).pulsado(7, "900");
        buzon.stop();

        // 1 s, 2 s, 4 s; and once it works again the count starts over.
        assertEquals(List.of(1_000L, 2_000L, 4_000L, 1_000L), esperas.subList(0, 4));
        // A refused read is not "nothing new": the offset does not move with it.
        assertEquals(List.of(5L, 5L, 5L, 5L, 6L), desdes.subList(0, 5));
    }

    @Test
    public void anExceptionInTheLoopDoesNotKillTheInbox() throws Exception {
        guardado(5);
        guion.add(new IllegalStateException("caída"));
        guion.add(lectura("[" + boton(5, "g", 7) + "]"));
        BuzonTelegram buzon = activo();
        buzon.start();
        verify(guardian, timeout(5_000)).pulsado(7, "900");
        buzon.stop();
    }

    @Test
    public void aFailureSavingTheOffsetNeitherKillsTheInboxNorRepeatsAButton() throws Exception {
        guardado(5);
        doThrow(new IllegalStateException("caída")).doNothing().when(storage).updateObject(any(), any());
        guion.add(lectura("[" + boton(5, "g", 7) + "]"));
        guion.add(lectura("[" + boton(6, "g", 8) + "]"));
        BuzonTelegram buzon = activo();
        buzon.start();
        verify(guardian, timeout(5_000)).pulsado(8, "900");
        buzon.stop();

        verify(guardian, times(1)).pulsado(7, "900");
        assertEquals(List.of(5L, 6L, 7L), desdes.subList(0, 3));
    }

    @Test
    public void stopEndsTheThreadInTheMiddleOfALongPoll() throws Exception {
        guardado(5);
        CountDownLatch dentro = new CountDownLatch(1);
        alVaciar = () -> {
            dentro.countDown();
            try {
                // A getUpdates waiting for somebody to write.
                Thread.sleep(20_000);
            } catch (InterruptedException e) {
                // Letting go of the connection takes a moment.
                pausa(300);
                Thread.currentThread().interrupt();
            }
            return fallo(0);
        };
        BuzonTelegram buzon = activo(Thread::sleep);
        buzon.start();
        assertTrue(dentro.await(5, TimeUnit.SECONDS));
        long antes = System.nanoTime();
        buzon.stop();

        assertFalse(hilo.isAlive(), "the inbox thread is still running after stop()");
        assertTrue(System.nanoTime() - antes < TimeUnit.SECONDS.toNanos(4));
    }

    @Test
    public void stopEndsTheLoopAlsoWhenReadsComeBackAtOnce() throws Exception {
        guardado(5);
        BuzonTelegram buzon = activo();
        buzon.start();
        esperarA(() -> desdes.size() >= 3);
        buzon.stop();

        assertFalse(hilo.isAlive(), "the inbox thread is still running after stop()");
    }

    @Test
    public void anInboxThatIsOffNeverReadsAndStopsCleanly() throws Exception {
        BuzonTelegram buzon = new BuzonTelegram(storage, cliente, destinatarios, firma, List.of(guardian));
        buzon.start();
        buzon.stop();

        verify(cliente, after(300).never()).leer(anyLong(), anyInt());
    }

    @Test
    public void switchedOnItReadsAndBothKindsOfButtonReachTheirOwner() throws Exception {
        guardado(5);
        BuzonTelegram buzon = configurado("true", null);
        guion.add(lectura("[" + boton(5, "g", 7) + "," + boton(6, "v", 9) + "]"));
        buzon.start();
        verify(cliente, timeout(5_000)).responderBoton("cb6", "Viaje cerrado");
        buzon.stop();

        verify(guardian).pulsado(7, "900");
        verify(cliente).responderBoton("cb5", "Perfecto");
        verify(viajes).pulsado(9, "900");
        // Daemon: a long poll stuck on a dead socket must not hold Traccar's shutdown.
        assertTrue(hilo.isDaemon());
    }

    @Test
    public void onASecondaryNodeItNeverReads() throws Exception {
        // Two readers of one bot take each other's messages: 409 forever.
        BuzonTelegram buzon = configurado("true", "true");
        buzon.start();
        verify(cliente, after(500).never()).leer(anyLong(), anyInt());
        buzon.stop();
    }

    @Test
    public void itIsOffUnlessSwitchedOn() throws Exception {
        BuzonTelegram buzon = configurado(null, null);
        buzon.start();
        verify(cliente, after(500).never()).leer(anyLong(), anyInt());
        buzon.stop();
    }

}
