package org.traccar.api;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.api.signature.TokenManager;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.ObjectOperation;
import org.traccar.model.Permission;
import org.traccar.model.Server;
import org.traccar.model.User;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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

    private static final long HORA = 3_600_000L;

    /** Un almacén con la Ducati (8, «359») y el dueño Ana, para crear enlaces de verdad. */
    private static final class Banco {
        private final Storage storage = mock(Storage.class);
        private final CacheManager cacheManager = mock(CacheManager.class);
        private final TokenManager tokenManager = mock(TokenManager.class);
        private final Config config = new Config();
        private final Server server = new Server();
        private final User dueno = usuario(2, false, false);

        Banco() throws Exception {
            when(cacheManager.getServer()).thenReturn(server);
            when(tokenManager.generateToken(anyLong(), any())).thenReturn("t");
            Device device = new Device();
            device.setId(8);
            device.setName("Ducati");
            device.setUniqueId("359");
            when(storage.getObject(eq(Device.class), any())).thenReturn(device);
            when(storage.getObject(eq(User.class), any())).thenReturn(null);
            when(storage.addObject(any(User.class), any())).thenReturn(77L);
            when(storage.getObjects(eq(User.class), any())).thenReturn(List.of(dueno));
            dueno.setEmail("ana@x.es");
            config.setString(Keys.WEB_URL, "https://n.es");
        }

        EnlacesDirectos enlaces() {
            return new EnlacesDirectos(storage, tokenManager, config, cacheManager);
        }

        /** El usuario compartido que se ha creado. */
        User creado() throws Exception {
            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(storage).addObject(captor.capture(), any());
            return captor.getValue();
        }
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

    @Test
    public void aSharedUserCanLookButNeverTouch() throws Exception {
        Banco banco = new Banco();
        banco.enlaces().enlaceDeEquipo(8, CADUCA, "golpe", 3);
        User share = banco.creado();
        // Quien abre el enlace de un golpe o de un viaje no puede editar la moto,
        // ni mandarle órdenes, ni sacar informes (así lo deja la configuración de serie).
        assertTrue(share.getTemporary());
        assertTrue(share.getReadonly());
        assertTrue(share.getLimitCommands());
        assertTrue(share.getDisableReports());
        assertEquals(CADUCA, share.getExpirationTime());
        ArgumentCaptor<Permission> permiso = ArgumentCaptor.forClass(Permission.class);
        verify(banco.storage).addPermission(permiso.capture());
        assertEquals(User.class, permiso.getValue().getOwnerClass());
        assertEquals(77, permiso.getValue().getOwnerId());
        assertEquals(Device.class, permiso.getValue().getPropertyClass());
        assertEquals(8, permiso.getValue().getPropertyId());
        verify(banco.tokenManager).generateToken(77, CADUCA);
    }

    @Test
    public void commandsAndReportsNeedBothTheServerAndTheOwner() throws Exception {
        Banco permitido = new Banco();
        permitido.config.setString(Keys.WEB_SHARE_DEVICE_COMMANDS, "true");
        permitido.config.setString(Keys.WEB_SHARE_DEVICE_REPORTS, "true");
        permitido.enlaces().enlaceDeEquipo(8, CADUCA);
        assertFalse(permitido.creado().getLimitCommands());
        assertFalse(permitido.creado().getDisableReports());

        Banco limitado = new Banco();
        limitado.config.setString(Keys.WEB_SHARE_DEVICE_COMMANDS, "true");
        limitado.config.setString(Keys.WEB_SHARE_DEVICE_REPORTS, "true");
        limitado.dueno.setLimitCommands(true);
        limitado.dueno.setDisableReports(true);
        limitado.enlaces().enlaceDeEquipo(8, CADUCA);
        assertTrue(limitado.creado().getLimitCommands());
        assertTrue(limitado.creado().getDisableReports());
    }

    @Test
    public void sharingDisabledOnTheServerMeansNoLinkAtAll() throws Exception {
        Banco banco = new Banco();
        banco.server.set(Keys.DEVICE_SHARE_DISABLE.getKey(), true);
        assertThrows(SecurityException.class,
                () -> banco.enlaces().compartir(banco.dueno, Device.class, 8, CADUCA));
        assertTrue(banco.enlaces().enlaceDeEquipo(8, CADUCA, "golpe", 3).isEmpty());
        verify(banco.storage, never()).addObject(any(), any());
    }

    @Test
    public void withoutServerSettingsSharingStillWorks() throws Exception {
        Banco banco = new Banco();
        when(banco.cacheManager.getServer()).thenReturn(null);
        assertEquals(77, banco.enlaces().compartir(banco.dueno, Device.class, 8, CADUCA).shareUserId());
    }

    @Test
    public void aTemporaryUserCannotShare() throws Exception {
        Banco banco = new Banco();
        User temporal = usuario(10, true, false);
        temporal.setEmail("ana@x.es:359");
        assertThrows(SecurityException.class,
                () -> banco.enlaces().compartir(temporal, Device.class, 8, CADUCA));
        verify(banco.storage, never()).addObject(any(), any());
    }

    @Test
    public void theLinkNeverOutlivesItsOwner() throws Exception {
        Banco banco = new Banco();
        Date antes = new Date(CADUCA.getTime() - HORA);
        banco.dueno.setExpirationTime(antes);
        banco.enlaces().enlaceDeEquipo(8, CADUCA);
        assertEquals(antes, banco.creado().getExpirationTime());
        verify(banco.tokenManager).generateToken(77, antes);
    }

    @Test
    public void anOwnerWhoExpiresLaterDoesNotShortenTheLink() throws Exception {
        Banco banco = new Banco();
        banco.dueno.setExpirationTime(new Date(CADUCA.getTime() + 24 * HORA));
        banco.enlaces().enlaceDeEquipo(8, CADUCA);
        assertEquals(CADUCA, banco.creado().getExpirationTime());
    }

    @Test
    public void anOwnersOwnLinkHasNoSuffixNorLabel() throws Exception {
        Banco banco = new Banco();
        EnlacesDirectos.Token token = banco.enlaces().compartir(banco.dueno, Device.class, 8, CADUCA);
        assertEquals(new EnlacesDirectos.Token("t", 77), token);
        assertEquals("ana@x.es:359", banco.creado().getEmail());
        assertEquals("Ducati", banco.creado().getName());
    }

    @Test
    public void aGroupLinkIsNamedAfterTheGroup() throws Exception {
        Banco banco = new Banco();
        Group grupo = new Group();
        grupo.setId(9);
        grupo.setName("Club");
        when(banco.storage.getObject(eq(Group.class), any())).thenReturn(grupo);
        banco.enlaces().compartir(banco.dueno, Group.class, 9, CADUCA);
        assertEquals("ana@x.es:group:9", banco.creado().getEmail());
        assertEquals("Club", banco.creado().getName());
    }

    @Test
    public void anExistingSharedUserIsReusedAndOnlyStretched() throws Exception {
        Banco banco = new Banco();
        User existente = usuario(77, true, false);
        existente.setExpirationTime(new Date(CADUCA.getTime() - HORA));
        when(banco.storage.getObject(eq(User.class), any())).thenReturn(existente);
        EnlacesDirectos.Token token = banco.enlaces().compartir(banco.dueno, Device.class, 8, CADUCA);
        assertEquals(77, token.shareUserId());
        verify(banco.storage, never()).addObject(any(), any());
        verify(banco.storage, never()).addPermission(any());
        assertEquals(CADUCA, existente.getExpirationTime());
        verify(banco.storage).updateObject(eq(existente), any(Request.class));
    }

    @Test
    public void aLongerExistingLinkIsNotShortened() throws Exception {
        Banco banco = new Banco();
        Date despues = new Date(CADUCA.getTime() + 24 * HORA);
        User existente = usuario(77, true, false);
        existente.setExpirationTime(despues);
        when(banco.storage.getObject(eq(User.class), any())).thenReturn(existente);
        banco.enlaces().compartir(banco.dueno, Device.class, 8, CADUCA);
        assertEquals(despues, existente.getExpirationTime());
        verify(banco.storage, never()).updateObject(any(), any());
    }

    @Test
    public void cuttingAUserThatIsGoneDoesNothing() throws Exception {
        Storage storage = mock(Storage.class);
        CacheManager cacheManager = mock(CacheManager.class);
        when(storage.getObject(eq(User.class), any())).thenReturn(null);
        new EnlacesDirectos(storage, mock(TokenManager.class), new Config(), cacheManager).cortar(77);
        verify(storage, never()).removeObject(any(), any());
        verify(cacheManager, never()).invalidateObject(anyBoolean(), any(), anyLong(), any());
    }

}
