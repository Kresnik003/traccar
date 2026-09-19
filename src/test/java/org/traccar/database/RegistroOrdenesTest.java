package org.traccar.database;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.model.Orden;
import org.traccar.storage.Storage;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which command an answer belongs to.
 *
 * Found in review on 2026-09-19: an unanswered command waited forever, so once
 * the device ignored one, every later answer was stamped one command early; and
 * two commands sent in the same second swapped their answers.
 */
public class RegistroOrdenesTest {

    private static final long NOW = 1_790_000_000_000L;
    private static final long MINUTE = 60_000L;
    private static final long WAIT = RegistroOrdenes.ESPERA_RESPUESTA;

    private static Orden orden(long id, long minutesAgo, boolean queued, boolean answered) {
        Orden orden = new Orden();
        orden.setId(id);
        orden.setDeviceId(1);
        orden.setEnviado(new Date(NOW - minutesAgo * MINUTE));
        orden.setEncolada(queued);
        if (answered) {
            orden.setEntregado(new Date(NOW - minutesAgo * MINUTE + 5000));
        }
        return orden;
    }

    @Test
    public void theCommandJustSentGetsTheAnswer() {
        Orden sent = orden(1, 0, false, false);
        assertSame(sent, RegistroOrdenes.elegir(List.of(sent), NOW, WAIT));
    }

    @Test
    public void anIgnoredCommandDoesNotTakeTheNextAnswer() {
        Orden ignored = orden(1, 60 * 24 * 30, false, false);
        Orden sent = orden(2, 0, false, false);
        // Newest first, as the database returns them.
        assertSame(sent, RegistroOrdenes.elegir(List.of(sent, ignored), NOW, WAIT));
    }

    @Test
    public void theSameSecondIsToldApartById() {
        Orden first = orden(1, 0, false, false);
        Orden second = orden(2, 0, false, false);
        second.setEnviado(new Date(first.getEnviado().getTime()));
        assertSame(first, RegistroOrdenes.elegir(List.of(second, first), NOW, WAIT));
    }

    @Test
    public void aCommandSkippedByTheDeviceIsNotWaiting() {
        Orden skipped = orden(1, 3, false, false);
        Orden answered = orden(2, 2, false, true);
        Orden waiting = orden(3, 1, false, false);
        assertSame(waiting, RegistroOrdenes.elegir(List.of(waiting, answered, skipped), NOW, WAIT));
    }

    @Test
    public void aQueuedCommandWaitsForTheDevice() {
        // Queued two days ago, device offline since: it may be leaving right now.
        Orden queued = orden(1, 60 * 48, true, false);
        assertSame(queued, RegistroOrdenes.elegir(List.of(queued), NOW, WAIT));
    }

    @Test
    public void aQueuedCommandLeftWhenALaterOneWentLive() {
        Orden queued = orden(1, 60 * 48, true, false);
        Orden live = orden(2, 60, false, false);
        // Both left an hour ago at the latest: neither can be answered now.
        assertNull(RegistroOrdenes.elegir(List.of(live, queued), NOW, WAIT));
        // And with the live one fresh, the queued one went first.
        Orden fresh = orden(3, 1, false, false);
        assertSame(queued, RegistroOrdenes.elegir(List.of(fresh, queued), NOW, WAIT));
    }

    @Test
    public void nothingWaitingIsNull() {
        assertNull(RegistroOrdenes.elegir(List.of(orden(1, 1, false, true)), NOW, WAIT));
        assertNull(RegistroOrdenes.elegir(List.of(), NOW, WAIT));
    }

    @Test
    public void theAnswerIsStampedOnTheRightRow() throws Exception {
        Storage storage = mock(Storage.class);
        // contestar() reads the clock, so these two are placed against it.
        Orden ignored = orden(1, 0, false, false);
        ignored.setEnviado(new Date(System.currentTimeMillis() - 24 * 60 * MINUTE));
        Orden sent = orden(2, 0, false, false);
        sent.setEnviado(new Date());
        when(storage.getObjects(eq(Orden.class), any())).thenReturn(List.of(sent, ignored));

        assertTrue(new RegistroOrdenes(storage).contestar(1, "Param ID:800 Value:1"));

        verify(storage).updateObject(eq(sent), any());
        verify(storage, never()).updateObject(eq(ignored), any());
        assertEquals("Param ID:800 Value:1", sent.getRespuesta());
        assertNull(ignored.getEntregado());
    }

    @Test
    public void anAnswerWithNothingWaitingChangesNothing() throws Exception {
        Storage storage = mock(Storage.class);
        Orden ignored = orden(1, 0, false, false);
        ignored.setEnviado(new Date(System.currentTimeMillis() - 24 * 60 * MINUTE));
        when(storage.getObjects(eq(Orden.class), any())).thenReturn(List.of(ignored));
        assertFalse(new RegistroOrdenes(storage).contestar(1, "x"));
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void aLongDescriptionIsCutToTheColumn() throws Exception {
        Storage storage = mock(Storage.class);
        new RegistroOrdenes(storage).anotar(1, 7, "custom", "x".repeat(4000), false);
        ArgumentCaptor<Orden> captor = ArgumentCaptor.forClass(Orden.class);
        verify(storage).addObject(captor.capture(), any());
        assertEquals(512, captor.getValue().getDescripcion().length());
    }

}
