package org.traccar.api;

import org.junit.jupiter.api.Test;
import org.traccar.api.security.PermissionsService;
import org.traccar.api.signature.TokenManager;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.model.User;
import org.traccar.storage.Storage;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A follow-me link created by the server itself: after an unanswered crash and
 * when a rider leaves on a trip, nobody is at the screen to press "share".
 */
public class EnlacesDirectosTest {

    private static final Date CADUCA = new Date(1_790_000_000_000L);

    private static User usuario(long id, boolean temporal, boolean deshabilitado) {
        User user = new User();
        user.setId(id);
        user.setTemporary(temporal);
        user.setDisabled(deshabilitado);
        return user;
    }

    private EnlacesDirectos enlaces(Storage storage) {
        Config config = new Config();
        config.setString(Keys.WEB_URL, "https://n.es/");
        return spy(new EnlacesDirectos(storage, mock(PermissionsService.class), mock(TokenManager.class), config));
    }

    @Test
    public void theOwnerIsARealUser() throws Exception {
        Storage storage = mock(Storage.class);
        User temporal = usuario(10, true, false);
        User apagado = usuario(11, false, true);
        User dueno = usuario(2, false, false);
        when(storage.getObjects(eq(User.class), any())).thenReturn(List.of(temporal, apagado, dueno));
        EnlacesDirectos enlaces = enlaces(storage);
        doReturn(new EnlacesDirectos.Token("t0k", 55)).when(enlaces)
                .compartir(any(User.class), eq(Device.class), eq(8L), eq(CADUCA));
        enlaces.enlaceDeEquipo(8, CADUCA);
        verify(enlaces).compartir(eq(dueno), eq(Device.class), eq(8L), eq(CADUCA));
    }

    @Test
    public void theUrlCarriesTheTokenAndTheSharedUser() throws Exception {
        Storage storage = mock(Storage.class);
        when(storage.getObjects(eq(User.class), any())).thenReturn(List.of(usuario(2, false, false)));
        EnlacesDirectos enlaces = enlaces(storage);
        doReturn(new EnlacesDirectos.Token("t0k", 55)).when(enlaces)
                .compartir(any(User.class), eq(Device.class), eq(8L), eq(CADUCA));
        assertEquals(Optional.of(new EnlacesDirectos.Enlace("https://n.es/?token=t0k", 55)),
                enlaces.enlaceDeEquipo(8, CADUCA));
    }

    @Test
    public void withoutAnOwnerThereIsNoLink() throws Exception {
        Storage storage = mock(Storage.class);
        when(storage.getObjects(eq(User.class), any())).thenReturn(List.of(usuario(10, true, false)));
        EnlacesDirectos enlaces = enlaces(storage);
        assertTrue(enlaces.enlaceDeEquipo(8, CADUCA).isEmpty());
        verify(enlaces, never()).compartir(any(), any(), eq(8L), any());
    }

    @Test
    public void aRefusalToShareIsNoLinkNotAnError() throws Exception {
        Storage storage = mock(Storage.class);
        when(storage.getObjects(eq(User.class), any())).thenReturn(List.of(usuario(2, false, false)));
        EnlacesDirectos enlaces = enlaces(storage);
        doThrow(new SecurityException("Sharing is disabled")).when(enlaces)
                .compartir(any(User.class), eq(Device.class), eq(8L), eq(CADUCA));
        assertTrue(enlaces.enlaceDeEquipo(8, CADUCA).isEmpty());
    }

}
