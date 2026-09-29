package org.traccar.api;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.api.signature.TokenManager;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.model.ObjectOperation;
import org.traccar.model.Server;
import org.traccar.model.User;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
        return spy(new EnlacesDirectos(storage, mock(TokenManager.class), config, mock(CacheManager.class)));
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
                .compartir(any(User.class), eq(Device.class), eq(8L), eq(CADUCA), isNull(), isNull());
        enlaces.enlaceDeEquipo(8, CADUCA);
        verify(enlaces).compartir(eq(dueno), eq(Device.class), eq(8L), eq(CADUCA), isNull(), isNull());
    }

    @Test
    public void theUrlCarriesTheTokenAndTheSharedUser() throws Exception {
        Storage storage = mock(Storage.class);
        when(storage.getObjects(eq(User.class), any())).thenReturn(List.of(usuario(2, false, false)));
        EnlacesDirectos enlaces = enlaces(storage);
        doReturn(new EnlacesDirectos.Token("t0k", 55)).when(enlaces)
                .compartir(any(User.class), eq(Device.class), eq(8L), eq(CADUCA), isNull(), isNull());
        assertEquals(Optional.of(new EnlacesDirectos.Enlace("https://n.es/?token=t0k", 55)),
                enlaces.enlaceDeEquipo(8, CADUCA));
    }

    @Test
    public void aTripOrACrashGetsASharedUserOfItsOwn() throws Exception {
        Storage storage = mock(Storage.class);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getServer()).thenReturn(new Server());
        TokenManager tokenManager = mock(TokenManager.class);
        when(tokenManager.generateToken(anyLong(), any())).thenReturn("t");
        Device device = new Device();
        device.setId(8);
        device.setName("Ducati");
        device.setUniqueId("359");
        when(storage.getObject(eq(Device.class), any())).thenReturn(device);
        when(storage.getObject(eq(User.class), any())).thenReturn(null);
        when(storage.addObject(any(User.class), any())).thenReturn(77L);
        User dueno = usuario(2, false, false);
        dueno.setEmail("ana@x.es");
        when(storage.getObjects(eq(User.class), any())).thenReturn(List.of(dueno));
        Config config = new Config();
        config.setString(Keys.WEB_URL, "https://n.es");
        EnlacesDirectos enlaces = new EnlacesDirectos(storage, tokenManager, config, cacheManager);

        var enlace = enlaces.enlaceDeEquipo(8, CADUCA, "viaje", 5);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(storage).addObject(captor.capture(), any());
        assertEquals("ana@x.es:359:viaje-5", captor.getValue().getEmail());
        assertEquals("Ducati · viaje", captor.getValue().getName());
        assertTrue(captor.getValue().getTemporary());
        assertEquals(Optional.of(new EnlacesDirectos.Enlace("https://n.es/?token=t", 77)), enlace);
    }

    @Test
    public void cuttingALinkDeletesOnlyItsSharedUser() throws Exception {
        Storage storage = mock(Storage.class);
        CacheManager cacheManager = mock(CacheManager.class);
        EnlacesDirectos enlaces = new EnlacesDirectos(storage, mock(TokenManager.class), new Config(), cacheManager);
        User share = usuario(77, true, false);
        when(storage.getObject(eq(User.class), any())).thenReturn(share);
        enlaces.cortar(77);
        verify(storage).removeObject(eq(User.class), any());
        verify(cacheManager).invalidateObject(true, User.class, 77, ObjectOperation.DELETE);
    }

    @Test
    public void aRealUserIsNeverCut() throws Exception {
        Storage storage = mock(Storage.class);
        EnlacesDirectos enlaces = new EnlacesDirectos(
                storage, mock(TokenManager.class), new Config(), mock(CacheManager.class));
        when(storage.getObject(eq(User.class), any())).thenReturn(usuario(2, false, false));
        enlaces.cortar(2);
        verify(storage, never()).removeObject(any(), any());
    }

    @Test
    public void withoutAnOwnerThereIsNoLink() throws Exception {
        Storage storage = mock(Storage.class);
        when(storage.getObjects(eq(User.class), any())).thenReturn(List.of(usuario(10, true, false)));
        EnlacesDirectos enlaces = enlaces(storage);
        assertTrue(enlaces.enlaceDeEquipo(8, CADUCA).isEmpty());
        verify(enlaces, never()).compartir(any(), any(), eq(8L), any(), any(), any());
    }

    @Test
    public void aRefusalToShareIsNoLinkNotAnError() throws Exception {
        Storage storage = mock(Storage.class);
        when(storage.getObjects(eq(User.class), any())).thenReturn(List.of(usuario(2, false, false)));
        EnlacesDirectos enlaces = enlaces(storage);
        doThrow(new SecurityException("Sharing is disabled")).when(enlaces)
                .compartir(any(User.class), eq(Device.class), eq(8L), eq(CADUCA), isNull(), isNull());
        assertTrue(enlaces.enlaceDeEquipo(8, CADUCA).isEmpty());
    }

}
