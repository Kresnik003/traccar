/*
 * Copyright 2026 Kresnik003 (juanilloferrol@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.seguridad;

import org.traccar.model.Contacto;
import org.traccar.model.Device;
import org.traccar.model.Motorista;
import org.traccar.model.Quedada;
import org.traccar.model.QuedadaEquipo;
import org.traccar.model.QuedadaUsuario;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Who hears about a bike: its RIDER, the one person asked "are you fine?" and
 * the only one who can answer, and the people to warn (the confirmed emergency
 * contacts and whoever is in a ride in progress with the bike). Each chat once,
 * and never the rider's own chat among the people to warn.
 *
 * THE RIDER IS ONE PERSON, NOT "WHOEVER SEES THE BIKE". Found in review on
 * 2026-09-29: with two people on a bike (a couple, a bike an administrator set
 * up), the other one got "a crash in your bike" and, by pressing "I'm fine",
 * cancelled the warning to the rider's contacts.
 *
 * The rider is who said "I ride it" (table nx_motoristas), while that person is
 * still a real user who sees the bike. Otherwise, the only real user who sees
 * it, directly or through a group. With several and no valid choice there is no
 * rider and nothing is armed: never a guess between people. Found in review on
 * 2026-09-30: counting only direct links made the one person linked directly
 * the rider of a bike its owner sees through a group, and a choice that no
 * longer counted (unlinked, disabled) left the bike with no rider at all.
 */
@Singleton
public class Destinatarios {

    /** A chat to warn; {@code quedada} is the ride's name for a ride mate, null for a contact. */
    public record Destinatario(String chatId, String quedada, Long contactoId) {
    }

    private static final String EN_MARCHA = "en_marcha";

    private final Storage storage;

    @Inject
    public Destinatarios(Storage storage) {
        this.storage = storage;
    }

    private static boolean real(User user) {
        return !user.getTemporary() && !user.getDisabled();
    }

    /** Real users who see the bike, directly or through a group (nested too), each once. */
    private List<User> quienesLaVen(long deviceId) throws StorageException {
        Map<Long, User> porId = new LinkedHashMap<>();
        for (User user : storage.getObjects(User.class, new Request(
                new Columns.All(), new Condition.Permission(User.class, Device.class, deviceId)))) {
            if (real(user)) {
                porId.putIfAbsent(user.getId(), user);
            }
        }
        return new ArrayList<>(porId.values());
    }

    public Optional<User> motorista(long deviceId) throws StorageException {
        List<User> laVen = quienesLaVen(deviceId);
        Motorista elegido = storage.getObject(Motorista.class, new Request(
                new Columns.All(), new Condition.Equals("deviceid", deviceId)));
        if (elegido != null) {
            Optional<User> vale = laVen.stream().filter(user -> user.getId() == elegido.getUserId()).findFirst();
            if (vale.isPresent()) {
                return vale;
            }
        }
        return laVen.size() == 1 ? Optional.of(laVen.get(0)) : Optional.empty();
    }

    /** Whether this user may be the bike's rider: a real user who sees it. */
    public boolean puedeLlevarla(long deviceId, long userId) throws StorageException {
        return quienesLaVen(deviceId).stream().anyMatch(user -> user.getId() == userId);
    }

    /** "I ride it": one row per bike, replaced. */
    public void elegir(long deviceId, long userId) throws StorageException {
        Motorista motorista = new Motorista();
        motorista.setDeviceId(deviceId);
        motorista.setUserId(userId);
        motorista.setDesde(new Date());
        storage.removeObject(Motorista.class, new Request(new Condition.Equals("deviceid", deviceId)));
        storage.addObject(motorista, new Request(new Columns.Exclude("id")));
    }

    public Optional<String> chatDelMotorista(long deviceId) throws StorageException {
        return motorista(deviceId)
                .map(user -> user.getString("telegramChatId"))
                .filter(chat -> !chat.isBlank());
    }

    /** The rider's name for the messages. */
    public String persona(long deviceId) throws StorageException {
        return motorista(deviceId)
                .map(User::getName)
                .filter(name -> name != null && !name.isBlank())
                .orElse("tu contacto");
    }

    public List<Destinatario> contactos(long deviceId) throws StorageException {
        String delMotorista = chatDelMotorista(deviceId).orElse(null);
        return storage.getObjects(Contacto.class, new Request(
                new Columns.All(), new Condition.Equals("deviceid", deviceId), new Order("creado"))).stream()
                .filter(c -> c.getChatId() != null && c.getConfirmado() != null && c.getRechazado() == null)
                .filter(c -> !c.getChatId().equals(delMotorista))
                .map(c -> new Destinatario(c.getChatId(), null, c.getId()))
                .toList();
    }

    public List<Destinatario> de(long deviceId) throws StorageException {
        String delMotorista = chatDelMotorista(deviceId).orElse(null);
        Map<String, Destinatario> porChat = new LinkedHashMap<>();
        for (Destinatario contacto : contactos(deviceId)) {
            porChat.putIfAbsent(contacto.chatId(), contacto);
        }
        for (QuedadaEquipo equipo : storage.getObjects(QuedadaEquipo.class, new Request(
                new Columns.Exclude("id"), new Condition.Equals("deviceId", deviceId)))) {
            Quedada quedada = storage.getObject(Quedada.class, new Request(new Columns.All(), new Condition.And(
                    new Condition.Equals("id", equipo.getQuedadaId()), new Condition.Equals("estado", EN_MARCHA))));
            if (quedada == null) {
                continue;
            }
            List<Long> usuarios = new ArrayList<>();
            for (QuedadaUsuario participante : storage.getObjects(QuedadaUsuario.class, new Request(
                    new Columns.Exclude("id"), new Condition.Equals("quedadaId", quedada.getId())))) {
                usuarios.add(participante.getUserId());
            }
            usuarios.add(quedada.getCreadorId());
            for (long userId : usuarios.stream().distinct().toList()) {
                User user = storage.getObject(User.class, new Request(
                        new Columns.All(), new Condition.Equals("id", userId)));
                String chat = user == null || user.getDisabled() ? null : user.getString("telegramChatId");
                if (chat != null && !chat.isBlank() && !chat.equals(delMotorista)) {
                    porChat.putIfAbsent(chat, new Destinatario(chat, quedada.getNombre(), null));
                }
            }
        }
        return new ArrayList<>(porChat.values());
    }

    /** Telegram refused: the contact blocked the bot. It stops being warned until it signs up again. */
    public void rechazar(long contactoId) throws StorageException {
        Contacto contacto = new Contacto();
        contacto.setId(contactoId);
        contacto.setRechazado(new Date());
        storage.updateObject(contacto, new Request(
                new Columns.Include("rechazado"), new Condition.Equals("id", contactoId)));
    }

}
