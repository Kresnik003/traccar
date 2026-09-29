package org.traccar.seguridad;

import org.junit.jupiter.api.Test;
import org.traccar.model.Contacto;
import org.traccar.model.Quedada;
import org.traccar.model.QuedadaEquipo;
import org.traccar.model.QuedadaUsuario;
import org.traccar.model.User;
import org.traccar.storage.Storage;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who is warned after an unanswered crash: the confirmed contacts and the
 * people of the rides in progress, once each, and never the rider.
 */
public class DestinatariosTest {

    private static User usuario(long id, String chat, boolean temporal) {
        User user = new User();
        user.setId(id);
        user.setName("U" + id);
        user.setTemporary(temporal);
        if (chat != null) {
            user.set("telegramChatId", chat);
        }
        return user;
    }

    private static Contacto contacto(long id, String chat, boolean confirmado, boolean rechazado) {
        Contacto contacto = new Contacto();
        contacto.setId(id);
        contacto.setChatId(chat);
        contacto.setConfirmado(confirmado ? new Date() : null);
        contacto.setRechazado(rechazado ? new Date() : null);
        return contacto;
    }

    private Storage storage() throws Exception {
        Storage storage = mock(Storage.class);
        // Rider (user 2, chat 900) and a temporary share user.
        when(storage.getObjects(eq(User.class), any())).thenReturn(
                List.of(usuario(2, "900", false), usuario(50, "950", true)));
        when(storage.getObjects(eq(Contacto.class), any())).thenReturn(List.of(
                contacto(1, "901", true, false),
                contacto(2, "902", false, false),     // pending
                contacto(3, "903", true, true),       // blocked the bot
                contacto(4, "904", true, false)));
        QuedadaEquipo equipo = new QuedadaEquipo();
        equipo.setQuedadaId(30);
        equipo.setDeviceId(8);
        when(storage.getObjects(eq(QuedadaEquipo.class), any())).thenReturn(List.of(equipo));
        Quedada quedada = new Quedada();
        quedada.setId(30);
        quedada.setNombre("Ruta del Eume");
        quedada.setEstado("en_marcha");
        quedada.setCreadorId(2);
        when(storage.getObject(eq(Quedada.class), any())).thenReturn(quedada);
        QuedadaUsuario companero = new QuedadaUsuario();
        companero.setQuedadaId(30);
        companero.setUserId(5);
        QuedadaUsuario tambienContacto = new QuedadaUsuario();
        tambienContacto.setQuedadaId(30);
        tambienContacto.setUserId(6);
        when(storage.getObjects(eq(QuedadaUsuario.class), any())).thenReturn(List.of(companero, tambienContacto));
        // Participants 5 and 6 in order, then the ride's creator, who is the rider.
        when(storage.getObject(eq(User.class), any())).thenReturn(
                usuario(5, "905", false), usuario(6, "901", false), usuario(2, "900", false));
        return storage;
    }

    @Test
    public void theRidersChatsAreTheRealUsers() throws Exception {
        assertEquals(List.of("900"), new Destinatarios(storage()).chatsDelDueno(8));
    }

    @Test
    public void onlyConfirmedAndNotRejectedContacts() throws Exception {
        assertEquals(List.of("901", "904"),
                new Destinatarios(storage()).contactos(8).stream().map(Destinatarios.Destinatario::chatId).toList());
    }

    @Test
    public void contactsAndRideMatesOnceEachAndNeverTheRider() throws Exception {
        var todos = new Destinatarios(storage()).de(8);
        assertEquals(List.of("901", "904", "905"), todos.stream().map(Destinatarios.Destinatario::chatId).toList());
        assertEquals("Ruta del Eume", todos.get(2).quedada());
        assertEquals(null, todos.get(0).quedada());
    }

}
