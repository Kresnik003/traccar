/*
 * Copyright 2019 - 2026 Anton Tananaev (anton@traccar.org)
 * Copyright 2021 Rafael Miquelino (rafaelmiquelino@gmail.com)
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
package org.traccar.notificators;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.client.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.WebHelper;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notification.NotificationFormatter;
import org.traccar.notification.NotificationMessage;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

@Singleton
public class NotificatorTelegram extends Notificator {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificatorTelegram.class);

    private final Client client;

    private final String urlSendText;
    private final String urlSendLocation;
    private final String chatId;
    private final boolean sendLocation;

    public static class TextMessage {
        @JsonProperty("chat_id")
        private String chatId;
        @JsonProperty("text")
        private String text;
    }

    public static class LocationMessage {
        @JsonProperty("chat_id")
        private String chatId;
        @JsonProperty("latitude")
        private double latitude;
        @JsonProperty("longitude")
        private double longitude;
        @JsonProperty("horizontal_accuracy")
        private double accuracy;
        @JsonProperty("bearing")
        private int bearing;
    }

    @Inject
    public NotificatorTelegram(Config config, NotificationFormatter notificationFormatter,
            Client client, Provider<ClientBuilder> clientBuilderProvider) {
        super(notificationFormatter);

        String baseUrl = config.getString(Keys.NOTIFICATOR_TELEGRAM_BASE_URL);
        if (baseUrl == null) {
            baseUrl = "https://api.telegram.org/bot";
        }

        urlSendText = String.format(
                "%s%s/sendMessage", baseUrl, config.getString(Keys.NOTIFICATOR_TELEGRAM_KEY));
        urlSendLocation = String.format(
                "%s%s/sendLocation", baseUrl, config.getString(Keys.NOTIFICATOR_TELEGRAM_KEY));
        chatId = config.getString(Keys.NOTIFICATOR_TELEGRAM_CHAT_ID);
        sendLocation = config.getBoolean(Keys.NOTIFICATOR_TELEGRAM_SEND_LOCATION);

        String proxyUrl = config.getString(Keys.NOTIFICATOR_TELEGRAM_PROXY_URL);
        if (proxyUrl != null) {
            ClientBuilder clientBuilder = clientBuilderProvider.get()
                    .property(ClientProperties.PROXY_URI, proxyUrl);
            String userInfo = URI.create(proxyUrl).getUserInfo();
            if (userInfo != null) {
                String[] credentials = userInfo.split(":", 2);
                clientBuilder.property(ClientProperties.PROXY_USERNAME, credentials[0]);
                if (credentials.length > 1) {
                    clientBuilder.property(ClientProperties.PROXY_PASSWORD, credentials[1]);
                }
            }
            this.client = clientBuilder.build();
        } else {
            this.client = client;
        }
    }

    private LocationMessage createLocationMessage(String messageChatId, Position position) {
        LocationMessage locationMessage = new LocationMessage();
        locationMessage.chatId = messageChatId;
        locationMessage.latitude = position.getLatitude();
        locationMessage.longitude = position.getLongitude();
        locationMessage.bearing = (int) Math.ceil(position.getCourse());
        locationMessage.accuracy = position.getAccuracy();
        return locationMessage;
    }

    @Override
    public CompletableFuture<Void> sendAsync(
            User user, NotificationMessage shortMessage, Event event, Position position) {

        TextMessage message = new TextMessage();
        message.chatId = user.getString("telegramChatId");
        if (message.chatId == null) {
            message.chatId = chatId;
        }
        message.text = shortMessage.digest();

        boolean sendUserLocation = user.hasAttribute(Keys.NOTIFICATOR_TELEGRAM_SEND_LOCATION.getKey())
                ? user.getBoolean(Keys.NOTIFICATOR_TELEGRAM_SEND_LOCATION.getKey())
                : sendLocation;

        return WebHelper.post(client.target(urlSendText).request(), Entity.json(message),
                        NotificatorTelegram::checkAnswer)
                .thenCompose(v -> {
                    if (sendUserLocation && position != null) {
                        // THE ALERT IS THE TEXT, and it has already been delivered. A
                        // location refused after it must not fail the whole send: the
                        // retry queue would then send the text a second time.
                        return WebHelper.post(client.target(urlSendLocation).request(),
                                Entity.json(createLocationMessage(message.chatId, position)),
                                NotificatorTelegram::checkAnswer)
                                .exceptionally(throwable -> {
                                    LOGGER.warn("Telegram location failed after the text was delivered", throwable);
                                    return null;
                                });
                    }
                    return CompletableFuture.completedFuture(null);
                });
    }

    /**
     * Telegram answers every request, and a refusal is still an answer: 429 when
     * the bot sends too fast, 5xx when the API is having a bad time, 403 when the
     * member has blocked the bot. The HTTP client only fails the call when there
     * is no answer at all, so until this check a refused alert was reported as
     * delivered: never queued for a retry, and a queued one that was refused
     * again was deleted from the queue as sent.
     */
    static void checkAnswer(Response response) {
        int status = response.getStatus();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Telegram answered HTTP " + status);
        }
    }

}
