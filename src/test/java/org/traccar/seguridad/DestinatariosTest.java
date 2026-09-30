package org.traccar.seguridad;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.model.Contacto;
import org.traccar.model.Motorista;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    private static Request todos() {
        return argThat(r -> r != null && r.getCondition() instanceof Condition.Permission p && p.getIncludeGroups());
    }

    /**
     * Who sees the bike (directly or through a group: the storage answers only the
     * query that includes groups) and who said "I ride it", if anybody.
     */
    private Storage storage(Long motorista, List<User> laVen) throws Exception {
        Storage storage = mock(Storage.class);
        if (motorista != null) {
            Motorista elegido = new Motorista();
            elegido.setDeviceId(8);
            elegido.setUserId(motorista);
            when(storage.getObject(eq(Motorista.class), any())).thenReturn(elegido);
        }
        when(storage.getObjects(eq(User.class), todos())).thenReturn(laVen);
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
    public void withoutAChoiceTheOnlyUserWhoSeesItIsTheRider() throws Exception {
        Destinatarios destinatarios = new Destinatarios(storage(null, List.of(usuario(2, "900"))));
        assertEquals(Optional.of("900"), destinatarios.chatDelMotorista(8));
    }

    @Test
    public void withoutAChoiceAndSeveralUsersThereIsNoRider() throws Exception {
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

    private static List<String> chats(List<Destinatarios.Destinatario> lista) {
        return lista.stream().map(Destinatarios.Destinatario::chatId).toList();
    }

    @Test
    public void aBlankChatIsNoChat() throws Exception {
        Destinatarios destinatarios = new Destinatarios(storage(null, List.of(usuario(2, "  "))));
        assertTrue(destinatarios.motorista(8).isPresent());
        assertTrue(destinatarios.chatDelMotorista(8).isEmpty());
    }

    @Test
    public void aRiderWithoutANameIsCalledYourContact() throws Exception {
        User sinNombre = usuario(2, "900");
        sinNombre.setName("  ");
        assertEquals("tu contacto", new Destinatarios(storage(null, List.of(sinNombre))).persona(8));
        sinNombre.setName(null);
        assertEquals("tu contacto", new Destinatarios(storage(null, List.of(sinNombre))).persona(8));
        assertEquals("tu contacto", new Destinatarios(storage(null, List.of())).persona(8));
    }

    @Test
    public void aConfirmedContactWithoutAChatIsSkippedNotACrash() throws Exception {
        Storage storage = storage(2L, List.of(usuario(2, "900")));
        when(storage.getObjects(eq(Contacto.class), any())).thenReturn(List.of(
                contacto(1, null, true, false), contacto(2, "901", true, false)));
        assertEquals(List.of("901"), chats(new Destinatarios(storage).contactos(8)));
    }

    @Test
    public void aRideThatIsNotUnderwayWarnsNobodyOfIt() throws Exception {
        Storage storage = storage(2L, List.of(usuario(2, "900")));
        when(storage.getObject(eq(Quedada.class), any())).thenReturn(null);
        assertEquals(List.of("901", "906"), chats(new Destinatarios(storage).de(8)));
    }

    @Test
    public void aDeletedOrDisabledCompanionIsSkipped() throws Exception {
        Storage borrado = storage(2L, List.of(usuario(2, "900")));
        when(borrado.getObject(eq(User.class), any())).thenReturn(null, usuario(2, "900"));
        assertEquals(List.of("901", "906"), chats(new Destinatarios(borrado).de(8)));

        Storage apagado = storage(2L, List.of(usuario(2, "900")));
        User deshabilitado = usuario(5, "905");
        deshabilitado.setDisabled(true);
        when(apagado.getObject(eq(User.class), any())).thenReturn(deshabilitado, usuario(2, "900"));
        assertEquals(List.of("901", "906"), chats(new Destinatarios(apagado).de(8)));
    }

    @Test
    public void aCompanionWithoutTelegramIsSkipped() throws Exception {
        Storage sinChat = storage(2L, List.of(usuario(2, "900")));
        when(sinChat.getObject(eq(User.class), any())).thenReturn(usuario(5, null), usuario(2, "900"));
        assertEquals(List.of("901", "906"), chats(new Destinatarios(sinChat).de(8)));

        Storage enBlanco = storage(2L, List.of(usuario(2, "900")));
        when(enBlanco.getObject(eq(User.class), any())).thenReturn(usuario(5, " "), usuario(2, "900"));
        assertEquals(List.of("901", "906"), chats(new Destinatarios(enBlanco).de(8)));
    }

    @Test
    public void aContactWhoBlockedTheBotIsMarkedRejected() throws Exception {
        Storage storage = mock(Storage.class);
        new Destinatarios(storage).rechazar(4);
        ArgumentCaptor<Contacto> contacto = ArgumentCaptor.forClass(Contacto.class);
        ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
        verify(storage).updateObject(contacto.capture(), request.capture());
        assertEquals(4, contacto.getValue().getId());
        assertNotNull(contacto.getValue().getRechazado());
        Condition.Equals donde = (Condition.Equals) request.getValue().getCondition();
        assertEquals("id", donde.getColumn());
        assertEquals(4L, donde.getValue());
    }

    @Test
    public void followMeLinksAndDisabledUsersAreNeverTheRider() throws Exception {
        // Elegido el 50, que es un enlace de «sígueme»: no hay motorista.
        assertTrue(new Destinatarios(storage(50L, List.of())).motorista(8).isEmpty());
        // Sin elegir, Juan y un enlace, o Juan y alguien deshabilitado: el motorista es Juan.
        User enlace = usuario(51, "951");
        enlace.setTemporary(true);
        User apagado = usuario(52, "952");
        apagado.setDisabled(true);
        assertEquals(Optional.of("900"),
                new Destinatarios(storage(null, List.of(usuario(2, "900"), enlace))).chatDelMotorista(8));
        assertEquals(Optional.of("900"),
                new Destinatarios(storage(null, List.of(usuario(2, "900"), apagado))).chatDelMotorista(8));
    }

    @Test
    public void aRiderWhoIsTheirOwnContactIsNotWarnedOfTheirOwnCrash() throws Exception {
        Storage storage = storage(2L, List.of(usuario(2, "900")));
        when(storage.getObjects(eq(Contacto.class), any())).thenReturn(List.of(
                contacto(1, "900", true, false), contacto(2, "901", true, false)));
        assertEquals(List.of("901"), chats(new Destinatarios(storage).contactos(8)));
        assertEquals(List.of("901", "905"), chats(new Destinatarios(storage).de(8)));
    }

    private static User apagado(long id, String chat) {
        User user = usuario(id, chat);
        user.setDisabled(true);
        return user;
    }

    private static User temporal(long id, String chat) {
        User user = usuario(id, chat);
        user.setTemporary(true);
        return user;
    }

    @Test
    public void aChoiceThatNoLongerCountsFallsBackInsteadOfLeavingNoRider() throws Exception {
        // Found in review (30/9): Esther chose "I ride it" and was then unlinked or
        // disabled; Juan was left alone with the bike and nothing was armed.
        assertEquals(Optional.of("900"),
                new Destinatarios(storage(77L, List.of(usuario(2, "900")))).chatDelMotorista(8));
        assertEquals(Optional.of("900"), new Destinatarios(
                storage(6L, List.of(usuario(2, "900"), apagado(6, "906")))).chatDelMotorista(8));
        assertEquals(Optional.of("900"), new Destinatarios(
                storage(6L, List.of(usuario(2, "900"), temporal(6, "906")))).chatDelMotorista(8));
    }

    @Test
    public void whoSeesTheBikeThroughAGroupCounts() throws Exception {
        // Found in review (30/9): Juan sees the Ducati through a group and Esther
        // directly; counting only direct links made Esther the rider of Juan's bike.
        // The storage here answers only the query that includes groups.
        assertEquals(Optional.of("900"),
                new Destinatarios(storage(null, List.of(usuario(2, "900")))).chatDelMotorista(8));
        assertTrue(new Destinatarios(storage(null, List.of(usuario(2, "900"), usuario(6, "906"))))
                .motorista(8).isEmpty());
    }

    @Test
    public void twoThroughAGroupAndOneDirectlyIsNobodyUntilSomeoneChooses() throws Exception {
        List<User> tres = List.of(usuario(2, "900"), usuario(6, "906"), usuario(7, "907"));
        assertTrue(new Destinatarios(storage(null, tres)).motorista(8).isEmpty());
        assertEquals(Optional.of("906"), new Destinatarios(storage(6L, tres)).chatDelMotorista(8));
    }

    @Test
    public void aLinkedAdministratorIsOneMorePerson() throws Exception {
        User admin = usuario(1, "901");
        admin.setAdministrator(true);
        assertTrue(new Destinatarios(storage(null, List.of(admin, usuario(2, "900")))).motorista(8).isEmpty());
        assertEquals(Optional.of("901"), new Destinatarios(storage(null, List.of(admin))).chatDelMotorista(8));
    }

    @Test
    public void thePersonLinkedTwiceIsStillOnePerson() throws Exception {
        // Directly and through a group: the same user comes back twice.
        assertEquals(Optional.of("900"), new Destinatarios(
                storage(null, List.of(usuario(2, "900"), usuario(2, "900")))).chatDelMotorista(8));
    }

    @Test
    public void onlyARealUserWhoSeesTheBikeMayRideIt() throws Exception {
        Destinatarios destinatarios = new Destinatarios(
                storage(null, List.of(usuario(2, "900"), apagado(6, "906"), temporal(50, "950"))));
        assertTrue(destinatarios.puedeLlevarla(8, 2));
        assertFalse(destinatarios.puedeLlevarla(8, 6));
        assertFalse(destinatarios.puedeLlevarla(8, 50));
        assertFalse(destinatarios.puedeLlevarla(8, 77));
    }

    @Test
    public void choosingReplacesTheBikesRow() throws Exception {
        Storage storage = mock(Storage.class);
        new Destinatarios(storage).elegir(8, 2);
        ArgumentCaptor<Request> borrado = ArgumentCaptor.forClass(Request.class);
        verify(storage).removeObject(eq(Motorista.class), borrado.capture());
        Condition.Equals donde = (Condition.Equals) borrado.getValue().getCondition();
        assertEquals("deviceid", donde.getColumn());
        assertEquals(8L, donde.getValue());
        ArgumentCaptor<Motorista> fila = ArgumentCaptor.forClass(Motorista.class);
        verify(storage).addObject(fila.capture(), any());
        assertEquals(8, fila.getValue().getDeviceId());
        assertEquals(2, fila.getValue().getUserId());
        assertNotNull(fila.getValue().getDesde());
    }

}
