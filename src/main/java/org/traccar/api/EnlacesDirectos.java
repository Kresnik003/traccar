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
package org.traccar.api;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.resource.EnlacesCompartidos;
import org.traccar.api.security.PermissionsService;
import org.traccar.api.signature.TokenManager;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.WebHelper;
import org.traccar.model.BaseModel;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.ObjectOperation;
import org.traccar.model.Permission;
import org.traccar.model.User;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Date;
import java.util.Locale;
import java.util.Optional;

/**
 * Follow-me links. The body of ShareResource.share, moved here so the server
 * can hand out a link itself: after an unanswered crash, or when a rider leaves
 * on a trip, nobody is at the screen to press "share".
 */
@Singleton
public class EnlacesDirectos {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnlacesDirectos.class);

    /** A token and the temporary user behind it, which is what has to be deleted to cut the link. */
    public record Token(String token, long shareUserId) {
    }

    public record Enlace(String url, long shareUserId) {
    }

    private final Storage storage;
    private final PermissionsService permissionsService;
    private final TokenManager tokenManager;
    private final Config config;
    private final CacheManager cacheManager;

    @Inject
    public EnlacesDirectos(
            Storage storage, PermissionsService permissionsService, TokenManager tokenManager, Config config,
            CacheManager cacheManager) {
        this.storage = storage;
        this.permissionsService = permissionsService;
        this.tokenManager = tokenManager;
        this.config = config;
        this.cacheManager = cacheManager;
    }

    public Token compartir(User user, Class<? extends BaseModel> clazz, long id, Date expiration)
            throws StorageException, GeneralSecurityException, IOException {
        return compartir(user, clazz, id, expiration, null, null);
    }

    /**
     * With a {@code sufijo}, the link gets a shared user of its own instead of the
     * one every link of the device reuses: a trip or a crash can then be cut
     * without cutting the links the owner handed out, and a 24-hour crash link
     * does not stretch a one-hour link. {@code etiqueta} goes after the name, so
     * the owner's list of links says what each one is.
     */
    public Token compartir(
            User user, Class<? extends BaseModel> clazz, long id, Date expiration, String sufijo, String etiqueta)
            throws StorageException, GeneralSecurityException, IOException {

        if (permissionsService.getServer().getBoolean(Keys.DEVICE_SHARE_DISABLE.getKey())) {
            throw new SecurityException("Sharing is disabled");
        }
        if (user.getTemporary()) {
            throw new SecurityException("Temporary user");
        }
        if (user.getExpirationTime() != null && user.getExpirationTime().before(expiration)) {
            expiration = user.getExpirationTime();
        }

        BaseModel object = storage.getObject(clazz, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("id", id),
                        new Condition.Permission(User.class, user.getId(), clazz))));

        String shareEmail;
        if (clazz == Device.class) {
            shareEmail = user.getEmail() + ":" + ((Device) object).getUniqueId();
            if (sufijo != null) {
                shareEmail += ":" + sufijo;
            }
        } else {
            shareEmail = user.getEmail() + ":" + clazz.getSimpleName().toLowerCase(Locale.ROOT) + ":" + object.getId();
        }

        User share = storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("email", shareEmail)));

        if (share == null) {
            share = new User();
            share.setName((clazz == Device.class ? ((Device) object).getName() : ((Group) object).getName())
                    + (etiqueta != null ? " · " + etiqueta : ""));
            share.setEmail(shareEmail);
            share.setExpirationTime(expiration);
            share.setTemporary(true);
            share.setReadonly(true);
            share.setLimitCommands(user.getLimitCommands() || !config.getBoolean(Keys.WEB_SHARE_DEVICE_COMMANDS));
            share.setDisableReports(user.getDisableReports() || !config.getBoolean(Keys.WEB_SHARE_DEVICE_REPORTS));

            share.setId(storage.addObject(share, new Request(new Columns.Exclude("id"))));

            storage.addPermission(new Permission(User.class, share.getId(), clazz, id));
        } else if (EnlacesCompartidos.alargar(share.getExpirationTime(), expiration)) {
            // The shared user is reused for every link of this device, but its
            // expiration was only set by the first one: sharing «1 hour» and then
            // «3 days» made the 3-day link die after an hour with the user.
            share.setExpirationTime(expiration);
            storage.updateObject(share, new Request(
                    new Columns.Include("expirationTime"),
                    new Condition.Equals("id", share.getId())));
        }

        return new Token(tokenManager.generateToken(share.getId(), expiration), share.getId());
    }

    /**
     * A link to follow a bike, on behalf of the first real user that can see it
     * (not a temporary share user, not disabled). Empty, and logged, when there
     * is no such user or sharing is refused: the message still goes out, without
     * the link.
     */
    public Optional<Enlace> enlaceDeEquipo(long deviceId, Date caduca) {
        return enlaceDeEquipo(deviceId, caduca, null, 0);
    }

    /** A link with a shared user of its own, named «etiqueta-referencia»: see {@link #compartir}. */
    public Optional<Enlace> enlaceDeEquipo(long deviceId, Date caduca, String etiqueta, long referencia) {
        String sufijo = etiqueta != null ? etiqueta + "-" + referencia : null;
        try {
            for (User user : storage.getObjects(User.class, new Request(
                    new Columns.All(), new Condition.Permission(User.class, Device.class, deviceId)))) {
                if (!user.getTemporary() && !user.getDisabled()) {
                    Token token = compartir(user, Device.class, deviceId, caduca, sufijo, etiqueta);
                    return Optional.of(new Enlace(
                            WebHelper.retrieveWebUrl(config) + "/?token=" + token.token(), token.shareUserId()));
                }
            }
            LOGGER.warn("No user can share device {}", deviceId);
        } catch (StorageException | GeneralSecurityException | IOException | SecurityException e) {
            LOGGER.warn("Could not create a follow link for device {}", deviceId, e);
        }
        return Optional.empty();
    }

    /** Cuts a link made with its own shared user. A real user is never deleted here. */
    public void cortar(long shareUserId) {
        try {
            User share = storage.getObject(User.class, new Request(
                    new Columns.All(), new Condition.Equals("id", shareUserId)));
            if (share == null || !share.getTemporary()) {
                return;
            }
            storage.removeObject(User.class, new Request(new Condition.Equals("id", shareUserId)));
            cacheManager.invalidateObject(true, User.class, shareUserId, ObjectOperation.DELETE);
        } catch (Exception e) {
            LOGGER.warn("Could not cut follow link {}", shareUserId, e);
        }
    }

}
