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

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.model.Contacto;
import org.traccar.model.Device;
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

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who hears about a bike: its rider (the real users who can see it) and the
 * people to warn (its confirmed emergency contacts and whoever is in a ride in
 * progress with it). Each chat once, and never the rider's own chat among the
 * people to warn.
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

    private List<User> usuariosReales(long deviceId) throws StorageException {
        return storage.getObjects(User.class, new Request(
                new Columns.All(), new Condition.Permission(User.class, Device.class, deviceId))).stream()
                .filter(user -> !user.getTemporary() && !user.getDisabled())
                .toList();
    }

    public List<String> chatsDelDueno(long deviceId) throws StorageException {
        return usuariosReales(deviceId).stream()
                .map(user -> user.getString("telegramChatId"))
                .filter(chat -> chat != null && !chat.isBlank())
                .distinct()
                .toList();
    }

    /** The rider's name for the messages. */
    public String persona(long deviceId) throws StorageException {
        return usuariosReales(deviceId).stream()
                .map(User::getName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse("tu contacto");
    }

    public List<Destinatario> contactos(long deviceId) throws StorageException {
        return storage.getObjects(Contacto.class, new Request(
                new Columns.All(), new Condition.Equals("deviceid", deviceId), new Order("creado"))).stream()
                .filter(c -> c.getChatId() != null && c.getConfirmado() != null && c.getRechazado() == null)
                .map(c -> new Destinatario(c.getChatId(), null, c.getId()))
                .toList();
    }

    public List<Destinatario> de(long deviceId) throws StorageException {
        List<String> delDueno = chatsDelDueno(deviceId);
        Map<String, Destinatario> porChat = new LinkedHashMap<>();
        for (Destinatario contacto : contactos(deviceId)) {
            if (!delDueno.contains(contacto.chatId())) {
                porChat.putIfAbsent(contacto.chatId(), contacto);
            }
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
                if (chat != null && !chat.isBlank() && !delDueno.contains(chat)) {
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
