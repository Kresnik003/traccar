package org.traccar.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.model.Contacto;
import org.traccar.model.Device;
import org.traccar.model.EstadoTelegram;
import org.traccar.seguridad.Destinatarios;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * The bot's inbox: contacts sign up and leave, buttons answer.
 */
public class BuzonTelegramTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FirmaBoton firma = new FirmaBoton("s");
    private Storage storage;
    private ClienteTelegram cliente;
    private Destinatarios destinatarios;
    private ReceptorBoton guardian;

    private BuzonTelegram buzon() {
        return new BuzonTelegram(storage, cliente, destinatarios, firma, List.of(guardian));
    }

    private long procesar(String json) throws Exception {
        return buzon().procesar(mapper.readTree(json), 0);
    }

    @BeforeEach
    public void preparar() throws Exception {
        storage = mock(Storage.class);
        cliente = mock(ClienteTelegram.class);
        destinatarios = mock(Destinatarios.class);
        guardian = mock(ReceptorBoton.class);
        when(guardian.accion()).thenReturn("g");
        when(guardian.pulsado(anyLong(), anyString())).thenReturn("Perfecto");
        when(cliente.enviar(anyString(), anyString(), anyList())).thenReturn(new RespuestaTelegram(200, null));
        when(destinatarios.persona(8)).thenReturn("Juan");
        Device device = new Device();
        device.setName("Ducati");
        when(storage.getObject(eq(Device.class), any())).thenReturn(device);
    }

    private Contacto pendiente(String codigo) {
        Contacto contacto = new Contacto();
        contacto.setId(3);
        contacto.setDeviceId(8);
        contacto.setNombre("Ana");
        contacto.setCodigo(CodigosAlta.huella(codigo));
        contacto.setCaduca(new Date(System.currentTimeMillis() + 60_000));
        return contacto;
    }

    @Test
    public void aValidSignUpConfirmsAndWelcomes() throws Exception {
        when(storage.getObject(eq(Contacto.class), any())).thenReturn(pendiente("C0d"));
        procesar("[{\"update_id\":1,\"message\":{\"chat\":{\"id\":901},\"text\":\"/start C0d\"}}]");
        ArgumentCaptor<Contacto> captor = ArgumentCaptor.forClass(Contacto.class);
        verify(storage).updateObject(captor.capture(), any());
        assertEquals("901", captor.getValue().getChatId());
        assertNotNull(captor.getValue().getConfirmado());
        assertNull(captor.getValue().getCodigo());
        verify(cliente).enviar(eq("901"), contains("contacto de emergencia de Juan (Ducati)"), anyList());
        verify(cliente).enviar(eq("901"), contains("/baja"), anyList());
    }

    @Test
    public void aBikeThatIsGoneIsCalledJustMoto() throws Exception {
        when(storage.getObject(eq(Contacto.class), any())).thenReturn(pendiente("C0d"));
        when(storage.getObject(eq(Device.class), any())).thenReturn(null);
        procesar("[{\"update_id\":1,\"message\":{\"chat\":{\"id\":901},\"text\":\"/start C0d\"}}]");
        verify(cliente).enviar(eq("901"), contains("contacto de emergencia de Juan (moto)"), anyList());
    }

    @Test
    public void signingUpAgainAfterBlockingTheBotGetsTheWarningsBack() throws Exception {
        // A 403 (the bot was blocked) marks the contact as refused and the
        // warnings skip it. A new sign-up is a new "yes": the mark goes, and
        // the column is written, or the old refusal would stay in the table.
        Contacto contacto = pendiente("C0d");
        contacto.setRechazado(new Date());
        when(storage.getObject(eq(Contacto.class), any())).thenReturn(contacto);
        procesar("[{\"update_id\":1,\"message\":{\"chat\":{\"id\":901},\"text\":\"/start C0d\"}}]");
        ArgumentCaptor<Contacto> captor = ArgumentCaptor.forClass(Contacto.class);
        ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
        verify(storage).updateObject(captor.capture(), request.capture());
        assertNull(captor.getValue().getRechazado());
        assertTrue(request.getValue().getColumns().getColumns(Contacto.class, "set").contains("rechazado"));
    }

    @Test
    public void aUsedOrExpiredCodeIsExplained() throws Exception {
        Contacto usado = pendiente("C0d");
        usado.setConfirmado(new Date());
        when(storage.getObject(eq(Contacto.class), any())).thenReturn(usado);
        procesar("[{\"update_id\":1,\"message\":{\"chat\":{\"id\":902},\"text\":\"/start C0d\"}}]");
        verify(storage, never()).updateObject(any(), any());
        verify(cliente).enviar(eq("902"), contains("ha caducado o ya se usó"), anyList());
    }

    @Test
    public void anUnknownCodeIsExplainedToo() throws Exception {
        when(storage.getObject(eq(Contacto.class), any())).thenReturn(null);
        procesar("[{\"update_id\":1,\"message\":{\"chat\":{\"id\":902},\"text\":\"/start nada\"}}]");
        verify(cliente).enviar(eq("902"), contains("ha caducado o ya se usó"), anyList());
    }

    @Test
    public void leavingRemovesTheChatFromEveryBike() throws Exception {
        Contacto uno = pendiente("a");
        uno.setChatId("901");
        uno.setConfirmado(new Date());
        Contacto otro = pendiente("b");
        otro.setId(4);
        otro.setChatId("901");
        otro.setConfirmado(new Date());
        when(storage.getObjects(eq(Contacto.class), any())).thenReturn(List.of(uno, otro));
        procesar("[{\"update_id\":1,\"message\":{\"chat\":{\"id\":901},\"text\":\"/baja\"}}]");
        ArgumentCaptor<Contacto> captor = ArgumentCaptor.forClass(Contacto.class);
        verify(storage, times(2)).updateObject(captor.capture(), any());
        captor.getAllValues().forEach(c -> {
            assertNull(c.getChatId());
            assertNull(c.getConfirmado());
        });
        verify(cliente).enviar(eq("901"), contains("Ya no recibirás avisos"), anyList());
    }

    @Test
    public void aSignedButtonReachesItsOwner() throws Exception {
        procesar("[{\"update_id\":1,\"callback_query\":{\"id\":\"cb\",\"data\":\"" + firma.firmar("g", 7)
                + "\",\"message\":{\"chat\":{\"id\":900}}}}]");
        verify(guardian).pulsado(7, "900");
        verify(cliente).responderBoton("cb", "Perfecto");
    }

    @Test
    public void aSignedButtonOfAnActionNobodyHandlesIsAnsweredAnyway() throws Exception {
        // Telegram keeps a spinner on a button until it gets an answer.
        procesar("[{\"update_id\":1,\"callback_query\":{\"id\":\"cb\",\"data\":\"" + firma.firmar("x", 7)
                + "\",\"message\":{\"chat\":{\"id\":900}}}}]");
        verify(guardian, never()).pulsado(anyLong(), anyString());
        verify(cliente).responderBoton("cb", "Este botón ya no sirve.");
    }

    @Test
    public void aForgedButtonReachesNobody() throws Exception {
        procesar("[{\"update_id\":1,\"callback_query\":{\"id\":\"cb\",\"data\":\"g:7:0000000000000000\","
                + "\"message\":{\"chat\":{\"id\":900}}}}]");
        verify(guardian, never()).pulsado(anyLong(), anyString());
        verify(cliente).responderBoton(eq("cb"), anyString());
    }

    @Test
    public void itReturnsTheNextOffset() throws Exception {
        assertEquals(23, procesar("[{\"update_id\":20},{\"update_id\":22},{\"update_id\":21}]"));
        assertEquals(0, procesar("[]"));
    }

    @Test
    public void oneBrokenUpdateDoesNotStopTheRest() throws Exception {
        when(storage.getObject(eq(Contacto.class), any())).thenThrow(new RuntimeException("caída"));
        assertEquals(3, procesar("[{\"update_id\":1,\"message\":{\"chat\":{\"id\":1},\"text\":\"/start x\"}},"
                + "{\"update_id\":2,\"callback_query\":{\"id\":\"cb\",\"data\":\"" + firma.firmar("g", 7)
                + "\",\"message\":{\"chat\":{\"id\":900}}}}]"));
        verify(guardian).pulsado(7, "900");
    }

    @Test
    public void onlyTheVeryFirstStartSkipsTheBacklog() throws Exception {
        EstadoTelegram estado = new EstadoTelegram();
        estado.setUltimo(0);
        when(storage.getObject(eq(EstadoTelegram.class), any())).thenReturn(estado);
        assertEquals(0, buzon().inicio());
        verify(cliente, never()).leer(anyLong(), anyInt());
    }

    @Test
    public void aRestartGoesOnFromTheSavedOffset() throws Exception {
        EstadoTelegram estado = new EstadoTelegram();
        estado.setUltimo(57);
        when(storage.getObject(eq(EstadoTelegram.class), any())).thenReturn(estado);
        assertEquals(57, buzon().inicio());
        verify(cliente, never()).leer(anyLong(), anyInt());
        verify(storage, never()).addObject(any(), any());
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void whenTheSavedOffsetCannotBeReadItDoesNotSkipAnything() throws Exception {
        // Unknown is not "first start": skipping could lose a sign-up.
        when(storage.getObject(eq(EstadoTelegram.class), any())).thenThrow(new StorageException("caída"));
        assertEquals(0, buzon().inicio());
        verify(cliente, never()).leer(anyLong(), anyInt());
    }

    @Test
    public void theFirstStartSkipsWhatWasWrittenBefore() throws Exception {
        when(storage.getObject(eq(EstadoTelegram.class), any())).thenReturn(null);
        when(cliente.leer(-1, 0)).thenReturn(
                new ClienteTelegram.Lectura(200, mapper.readTree("[{\"update_id\":41}]"), null));
        assertEquals(42, buzon().inicio());
        // The one row of nx_telegram, found later by its id.
        ArgumentCaptor<EstadoTelegram> captor = ArgumentCaptor.forClass(EstadoTelegram.class);
        verify(storage).addObject(captor.capture(), any());
        assertEquals(EstadoTelegram.FILA, captor.getValue().getId());
        assertEquals(42, captor.getValue().getUltimo());
    }

    @Test
    public void aFirstStartWithoutTelegramSavesNothingAndIsStillTheFirst() throws Exception {
        when(storage.getObject(eq(EstadoTelegram.class), any())).thenReturn(null);
        when(cliente.leer(-1, 0)).thenReturn(new ClienteTelegram.Lectura(
                409, mapper.createArrayNode(), "Conflict: terminated by other getUpdates request"));
        assertEquals(0, buzon().inicio());
        verify(storage, never()).addObject(any(), any());
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void afterFailuresItWaitsLongerUpToAMinute() {
        assertEquals(1_000, BuzonTelegram.esperaTrasFallos(1));
        assertEquals(2_000, BuzonTelegram.esperaTrasFallos(2));
        assertEquals(32_000, BuzonTelegram.esperaTrasFallos(6));
        assertEquals(60_000, BuzonTelegram.esperaTrasFallos(7));
        assertEquals(60_000, BuzonTelegram.esperaTrasFallos(40));
    }

}
