package org.traccar.seguridad;

import org.junit.jupiter.api.Test;
import org.traccar.model.Contacto;
import org.traccar.model.Device;
import org.traccar.model.Quedada;
import org.traccar.model.QuedadaEquipo;
import org.traccar.model.QuedadaUsuario;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who rides a bike, and who is warned when its rider crashes and does not answer.
 *
 * Found in review on 2026-09-29: "the owner" was anybody who could see the bike.
 * With two people on it, the other one got "a crash in your bike, are you fine?"
 * and, by pressing it, cancelled the warning to the rider's contacts.
 */
public class DestinatariosTest {

    private static User usuario(long id, String chat) {
        User user = new User();
        user.setId(id);
        user.setName("U" + id);
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

    private static Request directos() {
        return argThat(r -> r != null && r.getCondition() instanceof Condition.Permission p && !p.getIncludeGroups());
    }

    private static Request todos() {
        return argThat(r -> r != null && r.getCondition() instanceof Condition.Permission p && p.getIncludeGroups());
    }

    /** Juan (2) and Esther (6) see the bike; Juan directly, Esther through a group. */
    private Storage storage(Long motorista, List<User> directos) throws Exception {
        Storage storage = mock(Storage.class);
        Device device = new Device();
        device.setId(8);
        if (motorista != null) {
            device.set("motorista", motorista);
        }
        when(storage.getObject(eq(Device.class), any())).thenReturn(device);
        User temporal = usuario(50, "950");
        temporal.setTemporary(true);
        when(storage.getObjects(eq(User.class), todos())).thenReturn(
                List.of(usuario(2, "900"), usuario(6, "906"), temporal));
        when(storage.getObjects(eq(User.class), directos())).thenReturn(directos);
        when(storage.getObjects(eq(Contacto.class), any())).thenReturn(List.of(
                contacto(1, "901", true, false),
                contacto(2, "902", false, false),     // pending
                contacto(3, "903", true, true),       // blocked the bot
                contacto(4, "906", true, false)));    // Esther, also a contact
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
        when(storage.getObjects(eq(QuedadaUsuario.class), any())).thenReturn(List.of(companero));
        // The ride's participant, then its creator (the rider).
        when(storage.getObject(eq(User.class), any())).thenReturn(usuario(5, "905"), usuario(2, "900"));
        return storage;
    }

    @Test
    public void theChosenRiderRules() throws Exception {
        Destinatarios destinatarios = new Destinatarios(storage(2L, List.of(usuario(2, "900"), usuario(6, "906"))));
        assertEquals(Optional.of("900"), destinatarios.chatDelMotorista(8));
        assertEquals("U2", destinatarios.persona(8));
    }

    @Test
    public void withoutAChoiceTheOnlyDirectUserIsTheRider() throws Exception {
        Destinatarios destinatarios = new Destinatarios(storage(null, List.of(usuario(2, "900"))));
        assertEquals(Optional.of("900"), destinatarios.chatDelMotorista(8));
    }

    @Test
    public void withoutAChoiceAndSeveralDirectUsersThereIsNoRider() throws Exception {
        Destinatarios destinatarios = new Destinatarios(storage(null, List.of(usuario(2, "900"), usuario(6, "906"))));
        assertTrue(destinatarios.motorista(8).isEmpty());
        assertTrue(destinatarios.chatDelMotorista(8).isEmpty());
    }

    @Test
    public void aChosenRiderWhoNoLongerSeesTheBikeDoesNotCount() throws Exception {
        Destinatarios destinatarios = new Destinatarios(storage(77L, List.of(usuario(2, "900"), usuario(6, "906"))));
        assertTrue(destinatarios.motorista(8).isEmpty());
    }

    @Test
    public void onlyConfirmedAndNotRejectedContacts() throws Exception {
        assertEquals(List.of("901", "906"), new Destinatarios(storage(2L, List.of(usuario(2, "900"))))
                .contactos(8).stream().map(Destinatarios.Destinatario::chatId).toList());
    }

    @Test
    public void anotherUserOfTheBikeWhoIsAContactIsWarned() throws Exception {
        // Esther sees the bike and is Juan's contact: she is warned, not asked.
        var todos = new Destinatarios(storage(2L, List.of(usuario(2, "900"), usuario(6, "906")))).de(8);
        assertEquals(List.of("901", "906", "905"), todos.stream().map(Destinatarios.Destinatario::chatId).toList());
        assertEquals("Ruta del Eume", todos.get(2).quedada());
    }

}
