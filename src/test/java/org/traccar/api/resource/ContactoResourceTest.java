package org.traccar.api.resource;

import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.api.BaseResource;
import org.traccar.api.security.PermissionsService;
import org.traccar.api.security.UserPrincipal;
import org.traccar.model.Contacto;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.telegram.ClienteTelegram;
import org.traccar.telegram.CodigosAlta;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

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

}
