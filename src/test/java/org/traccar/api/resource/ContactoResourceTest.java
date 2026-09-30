package org.traccar.api.resource;

import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.traccar.api.BaseResource;
import org.traccar.api.security.PermissionsService;
import org.traccar.api.security.UserPrincipal;
import org.traccar.model.Contacto;
import org.traccar.model.Device;
import org.traccar.model.User;
import org.traccar.helper.LogAction;
import org.traccar.seguridad.Destinatarios;
import org.traccar.seguridad.SitiosPropios;
import org.traccar.storage.Storage;
import org.traccar.telegram.ClienteTelegram;
import org.traccar.telegram.CodigosAlta;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The emergency contacts of a bike, as the web sees them.
 */
public class ContactoResourceTest {

    private ContactoResource resource;
    private Storage storage;
    private PermissionsService permissionsService;
    private ClienteTelegram clienteTelegram;
    private Destinatarios destinatarios;
    private SitiosPropios sitiosPropios;
    private LogAction actionLogger;

    private static void poner(Class<?> clase, Object objeto, String campo, Object valor) throws Exception {
        Field field = clase.getDeclaredField(campo);
        field.setAccessible(true);
        field.set(objeto, valor);
    }

    @BeforeEach
    public void preparar() throws Exception {
        resource = new ContactoResource();
        storage = mock(Storage.class);
        permissionsService = mock(PermissionsService.class);
        clienteTelegram = mock(ClienteTelegram.class);
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getUserPrincipal()).thenReturn(new UserPrincipal(2, null));
        poner(BaseResource.class, resource, "storage", storage);
        poner(BaseResource.class, resource, "permissionsService", permissionsService);
        poner(BaseResource.class, resource, "securityContext", securityContext);
        poner(ContactoResource.class, resource, "clienteTelegram", clienteTelegram);
        when(clienteTelegram.nombreDelBot()).thenReturn("NordesBot");
        User juan = new User();
        juan.setId(2);
        juan.setName("Juan");
        when(permissionsService.getUser(2)).thenReturn(juan);
        destinatarios = mock(Destinatarios.class);
        sitiosPropios = mock(SitiosPropios.class);
        actionLogger = mock(LogAction.class);
        poner(ContactoResource.class, resource, "destinatarios", destinatarios);
        poner(ContactoResource.class, resource, "sitiosPropios", sitiosPropios);
        poner(ContactoResource.class, resource, "actionLogger", actionLogger);
        when(destinatarios.puedeLlevarla(anyLong(), anyLong())).thenReturn(true);
        when(storage.getObjects(eq(Contacto.class), any())).thenReturn(new ArrayList<>());
    }

    @Test
    public void theSixthContactIsRefused() throws Exception {
        when(storage.getObjects(eq(Contacto.class), any()))
                .thenReturn(Collections.nCopies(ContactoResource.MAXIMO, new Contacto()));
        assertThrows(IllegalArgumentException.class,
                () -> resource.add(new ContactoResource.Alta(8, "Ana")));
        verify(storage, never()).addObject(any(), any());
    }

    @Test
    public void theBikeMustBeTheUsers() throws Exception {
        doThrow(new SecurityException("no")).when(permissionsService)
                .checkPermission(eq(Device.class), anyLong(), eq(8L));
        assertThrows(SecurityException.class, () -> resource.add(new ContactoResource.Alta(8, "Ana")));
        assertThrows(SecurityException.class, () -> resource.get(8));
        verify(storage, never()).addObject(any(), any());
    }

    @Test
    public void anEmptyNameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> resource.add(new ContactoResource.Alta(8, "  ")));
    }

    @Test
    public void theLinkCarriesTheCodeAndOnlyItsHashIsStored() throws Exception {
        Map<String, Object> alta = resource.add(new ContactoResource.Alta(8, " Ana "));
        String enlace = (String) alta.get("enlace");
        assertTrue(enlace.startsWith("https://t.me/NordesBot?start="), enlace);
        String codigo = enlace.substring(enlace.indexOf('=') + 1);
        ArgumentCaptor<Contacto> captor = ArgumentCaptor.forClass(Contacto.class);
        verify(storage).addObject(captor.capture(), any());
        Contacto guardado = captor.getValue();
        assertEquals(CodigosAlta.huella(codigo), guardado.getCodigo());
        assertEquals("Ana", guardado.getNombre());
        assertEquals(8, guardado.getDeviceId());
        assertTrue(guardado.getCaduca().getTime() - guardado.getCreado().getTime() == CodigosAlta.DURACION);
    }

    @Test
    public void withoutTelegramThereIsNoLink() {
        when(clienteTelegram.nombreDelBot()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> resource.add(new ContactoResource.Alta(8, "Ana")));
    }

    @Test
    public void theListNeverShowsTheChatOrTheCode() throws Exception {
        Contacto contacto = new Contacto();
        contacto.setId(3);
        contacto.setNombre("Ana");
        contacto.setChatId("901");
        contacto.setCodigo("huella");
        contacto.setConfirmado(new Date());
        when(storage.getObjects(eq(Contacto.class), any())).thenReturn(List.of(contacto));
        Map<String, Object> visto = resource.get(8).get(0);
        assertFalse(visto.containsKey("chatId"));
        assertFalse(visto.containsKey("codigo"));
        assertFalse(visto.containsValue("901"));
        assertEquals("Ana", visto.get("nombre"));
    }

    @Test
    public void aDeviceReadonlyUserCanNeitherAddNorRemove() throws Exception {
        // Found in review (29/9): they could remove the rider's contacts, and with
        // them the warnings, or add strangers who would get the live links.
        doThrow(new SecurityException("Write access denied")).when(permissionsService)
                .checkEdit(anyLong(), eq(Device.class), eq(false), eq(false));
        assertThrows(SecurityException.class, () -> resource.add(new ContactoResource.Alta(8, "Ana")));
        Contacto contacto = new Contacto();
        contacto.setId(3);
        contacto.setDeviceId(8);
        when(storage.getObject(eq(Contacto.class), any())).thenReturn(contacto);
        assertThrows(SecurityException.class, () -> resource.remove(3));
        verify(storage, never()).addObject(any(), any());
        verify(storage, never()).removeObject(any(), any());
    }

    @Test
    public void aFollowMeLinkDoesNotShowTheContacts() throws Exception {
        // Found in review: whoever held a "follow me" link could list the names.
        User temporal = new User();
        temporal.setId(2);
        temporal.setTemporary(true);
        when(permissionsService.getUser(2)).thenReturn(temporal);
        WebApplicationException error = assertThrows(WebApplicationException.class, () -> resource.get(8));
        assertEquals(403, error.getResponse().getStatus());
    }

    @Test
    public void iRideItMakesMeTheRider() throws Exception {
        resource.serMotorista(8);
        verify(destinatarios).elegir(8, 2);
        verify(sitiosPropios).olvidar(8);
        // It leaves a trace in the audit, like any other change.
        ArgumentCaptor<Device> editado = ArgumentCaptor.forClass(Device.class);
        verify(actionLogger).edit(any(), eq(2L), editado.capture());
        assertEquals(8, editado.getValue().getId());
        // And it no longer touches the device: the web's stale copies wiped it (30/9).
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void aMemberWithReadOnlyDevicesCanStillSayIRideIt() throws Exception {
        // Found in review (30/9): the member whose bike an administrator set up
        // read-only could never say so, and the question went to the administrator.
        doThrow(new SecurityException("Write access denied")).when(permissionsService)
                .checkEdit(anyLong(), eq(Device.class), eq(false), eq(false));
        resource.serMotorista(8);
        verify(destinatarios).elegir(8, 2);
    }

    @Test
    public void whoDoesNotSeeTheBikeOrHoldsAFollowMeLinkCannotRideIt() throws Exception {
        doThrow(new SecurityException("no")).when(permissionsService)
                .checkPermission(eq(Device.class), anyLong(), eq(9L));
        assertThrows(SecurityException.class, () -> resource.serMotorista(9));

        // An administrator sees every bike but is not linked to this one.
        when(destinatarios.puedeLlevarla(8, 2)).thenReturn(false);
        WebApplicationException ajeno = assertThrows(WebApplicationException.class, () -> resource.serMotorista(8));
        assertEquals(403, ajeno.getResponse().getStatus());

        User temporal = new User();
        temporal.setId(2);
        temporal.setTemporary(true);
        when(permissionsService.getUser(2)).thenReturn(temporal);
        WebApplicationException enlace = assertThrows(WebApplicationException.class, () -> resource.serMotorista(8));
        assertEquals(403, enlace.getResponse().getStatus());
        verify(destinatarios, never()).elegir(anyLong(), anyLong());
    }

    @Test
    public void theWebIsToldWhetherTheRiderHasTelegram() throws Exception {
        User esther = new User();
        esther.setId(6);
        esther.setName("Esther");
        when(destinatarios.motorista(8)).thenReturn(Optional.of(esther));
        assertEquals(false, resource.motorista(8).get("tieneTelegram"));
        esther.set("telegramChatId", "  ");
        assertEquals(false, resource.motorista(8).get("tieneTelegram"));
        esther.set("telegramChatId", "906");
        Map<String, Object> visto = resource.motorista(8);
        assertEquals(true, visto.get("tieneTelegram"));
        assertEquals(false, visto.get("soyYo"));
        assertFalse(visto.containsValue("906"));
        when(destinatarios.motorista(8)).thenReturn(Optional.empty());
        assertEquals(false, resource.motorista(8).get("tieneTelegram"));
        assertEquals(null, resource.motorista(8).get("nombre"));
    }

    @Test
    public void theRiderIsShownWithoutTheirChat() throws Exception {
        User juan = new User();
        juan.setId(2);
        juan.setName("Juan");
        juan.set("telegramChatId", "900");
        when(destinatarios.motorista(8)).thenReturn(Optional.of(juan));
        Map<String, Object> visto = resource.motorista(8);
        assertEquals("Juan", visto.get("nombre"));
        assertEquals(true, visto.get("soyYo"));
        assertFalse(visto.containsValue("900"));
    }

    @Test
    public void withoutARiderTheWebIsTold() throws Exception {
        when(destinatarios.motorista(8)).thenReturn(Optional.empty());
        Map<String, Object> visto = resource.motorista(8);
        assertEquals(null, visto.get("nombre"));
        assertEquals(false, visto.get("soyYo"));
    }

    private User temporal() {
        User temporal = new User();
        temporal.setId(2);
        temporal.setTemporary(true);
        return temporal;
    }

    private Contacto contactoDe(long deviceId) throws Exception {
        Contacto contacto = new Contacto();
        contacto.setId(3);
        contacto.setDeviceId(deviceId);
        when(storage.getObject(eq(Contacto.class), any())).thenReturn(contacto);
        return contacto;
    }

    @Test
    public void aFollowMeLinkCanNeitherAddNorRemoveNorRide() throws Exception {
        // Aunque el resto de permisos le dejaran pasar: el enlace de «sígueme» es
        // de quien lo recibe, no de quien lleva la moto.
        when(permissionsService.getUser(2)).thenReturn(temporal());
        contactoDe(8);
        for (var llamada : List.<Executable>of(
                () -> resource.add(new ContactoResource.Alta(8, "Ana")),
                () -> resource.remove(3),
                () -> resource.serMotorista(8),
                () -> resource.motorista(8))) {
            WebApplicationException error = assertThrows(WebApplicationException.class, llamada);
            assertEquals(403, error.getResponse().getStatus());
        }
        verify(storage, never()).addObject(any(), any());
        verify(storage, never()).removeObject(any(), any());
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void aNamelessContactIsABadRequestNotACrash() {
        assertThrows(IllegalArgumentException.class, () -> resource.add(new ContactoResource.Alta(8, null)));
    }

    @Test
    public void aNameCanBeUpToSixtyFourCharacters() throws Exception {
        resource.add(new ContactoResource.Alta(8, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> resource.add(new ContactoResource.Alta(8, "a".repeat(65))));
        verify(storage).addObject(any(Contacto.class), any());
    }

    @Test
    public void theWebGetsTheIdItNeedsToRemoveTheContact() throws Exception {
        when(storage.addObject(any(Contacto.class), any())).thenReturn(41L);
        assertEquals(41L, resource.add(new ContactoResource.Alta(8, "Ana")).get("id"));
    }

    @Test
    public void removingAContactThatIsGoneIsNotFound() throws Exception {
        when(storage.getObject(eq(Contacto.class), any())).thenReturn(null);
        WebApplicationException error = assertThrows(WebApplicationException.class, () -> resource.remove(3));
        assertEquals(404, error.getResponse().getStatus());
        verify(storage, never()).removeObject(any(), any());
    }

    @Test
    public void removingAContactDeletesItAndAnswersNoContent() throws Exception {
        contactoDe(8);
        assertEquals(204, resource.remove(3).getStatus());
        verify(permissionsService).checkPermission(Device.class, 2, 8);
        verify(storage).removeObject(eq(Contacto.class), any());
    }

    @Test
    public void onlyWhoCanSeeTheBikeKnowsWhoRidesIt() throws Exception {
        doThrow(new SecurityException("no")).when(permissionsService)
                .checkPermission(eq(Device.class), anyLong(), eq(8L));
        assertThrows(SecurityException.class, () -> resource.motorista(8));
        verify(destinatarios, never()).motorista(anyLong());
    }

    @Test
    public void whenSomeoneElseRidesItIsNotMe() throws Exception {
        User esther = new User();
        esther.setId(5);
        esther.setName("Esther");
        when(destinatarios.motorista(8)).thenReturn(Optional.of(esther));
        Map<String, Object> visto = resource.motorista(8);
        assertEquals("Esther", visto.get("nombre"));
        assertEquals(false, visto.get("soyYo"));
    }

    @Test
    public void ridingABikeThatIsGoneIsRefused() throws Exception {
        // Nobody sees a bike that no longer exists, so nobody may ride it.
        when(destinatarios.puedeLlevarla(8, 2)).thenReturn(false);
        WebApplicationException error = assertThrows(WebApplicationException.class, () -> resource.serMotorista(8));
        assertEquals(403, error.getResponse().getStatus());
        verify(destinatarios, never()).elegir(anyLong(), anyLong());
        verify(actionLogger, never()).edit(any(), anyLong(), any());
    }

    @Test
    public void ridingItAnswersNoContent() throws Exception {
        assertEquals(204, resource.serMotorista(8).getStatus());
    }

    @Test
    public void theExpiryIsShownOnlyUntilTheContactConfirms() {
        Contacto pendiente = new Contacto();
        Date caduca = new Date(1_790_000_000_000L);
        pendiente.setCaduca(caduca);
        assertEquals(caduca, ContactoResource.publico(pendiente).get("caduca"));
        Contacto confirmado = new Contacto();
        confirmado.setCaduca(caduca);
        confirmado.setConfirmado(new Date(1_789_990_000_000L));
        assertEquals(null, ContactoResource.publico(confirmado).get("caduca"));
    }

}
