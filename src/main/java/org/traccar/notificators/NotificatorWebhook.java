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
package org.traccar.notificators;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notification.FalloDefinitivo;
import org.traccar.notification.NotificationFormatter;
import org.traccar.notification.NotificationMessage;
import org.traccar.session.cache.CacheManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Webhooks salientes (mejora 25): cada aviso, por POST y en JSON, a la URL que
 * el usuario pone en su cuenta ({@code webhook.url}). Así se engancha Home
 * Assistant, n8n o cualquier cosa sin tocar Java otra vez.
 *
 * Con {@code webhook.secreto} el cuerpo va firmado: cabecera
 * {@code X-Nordes-Firma: sha256=<HMAC-SHA256 del cuerpo, en hex>}.
 *
 * Una respuesta que no es 2xx es un fallo, y el aviso entra en la cola de
 * reintentos como cualquier otro canal. No se siguen redirecciones: una 302
 * hacia dentro saltaría la comprobación de {@link DestinoWebhook}.
 */
@Singleton
public class NotificatorWebhook extends Notificator {

    public static final String URL = "webhook.url";
    public static final String SECRETO = "webhook.secreto";
    public static final String CABECERA_FIRMA = "X-Nordes-Firma";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CacheManager cacheManager;
    private final HttpClient client;
    private final Set<String> permitidos;

    @Inject
    public NotificatorWebhook(Config config, NotificationFormatter notificationFormatter, CacheManager cacheManager) {
        super(notificationFormatter);
        this.cacheManager = cacheManager;
        this.permitidos = DestinoWebhook.permitidos(config.getString(Keys.NOTIFICATOR_WEBHOOK_PERMITIDOS));
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    static Map<String, Object> cuerpo(
            NotificationMessage message, Event event, Position position, Device device, long enviado) {
        Map<String, Object> evento = new LinkedHashMap<>();
        evento.put("id", event.getId());
        evento.put("tipo", event.getType());
        evento.put("alarma", event.getString(Position.KEY_ALARM));
        evento.put("gravedad", event.getString("gravedad"));
        evento.put("hora", event.getEventTime() != null ? event.getEventTime().toInstant().toString() : null);
        Map<String, Object> equipo = new LinkedHashMap<>();
        equipo.put("id", event.getDeviceId());
        equipo.put("nombre", device != null ? device.getName() : null);
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("evento", evento);
        cuerpo.put("equipo", equipo);
        if (position != null) {
            Map<String, Object> posicion = new LinkedHashMap<>();
            posicion.put("latitud", position.getLatitude());
            posicion.put("longitud", position.getLongitude());
            posicion.put("velocidadKmh", Math.round(UnitsConverter.kphFromKnots(position.getSpeed()) * 10) / 10.0);
            posicion.put("hora", position.getFixTime() != null ? position.getFixTime().toInstant().toString() : null);
            posicion.put("direccion", position.getAddress());
            cuerpo.put("posicion", posicion);
        }
        Map<String, Object> mensaje = new LinkedHashMap<>();
        mensaje.put("asunto", message.subject());
        mensaje.put("texto", message.digest());
        cuerpo.put("mensaje", mensaje);
        cuerpo.put("enviado", Instant.ofEpochMilli(enviado).toString());
        return cuerpo;
    }

    static String firma(String secreto, byte[] cuerpo) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(cuerpo));
    }

    /** A 4xx other than 408 and 429 will not change on a retry: the URL is wrong or gone. */
    public static class Rechazado extends IllegalStateException implements FalloDefinitivo {
        public Rechazado(int codigo) {
            super("El webhook contestó " + codigo);
        }
    }

    static void comprobarRespuesta(int codigo) {
        if (codigo >= 200 && codigo < 300) {
            return;
        }
        if (codigo >= 400 && codigo < 500 && codigo != 408 && codigo != 429) {
            throw new Rechazado(codigo);
        }
        throw new IllegalStateException("El webhook contestó " + codigo);
    }

    @Override
    public CompletableFuture<Void> sendAsync(User user, NotificationMessage message, Event event, Position position) {
        try {
            URI destino = DestinoWebhook.comprobar(user.getString(URL), permitidos);
            Device device = cacheManager.getObject(Device.class, event.getDeviceId());
            byte[] datos = JSON.writeValueAsBytes(cuerpo(message, event, position, device, System.currentTimeMillis()));
            HttpRequest.Builder peticion = HttpRequest.newBuilder(destino)
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Nordes-webhook/1")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(datos));
            String secreto = user.getString(SECRETO);
            if (secreto != null && !secreto.isEmpty()) {
                peticion.header(CABECERA_FIRMA, firma(secreto, datos));
            }
            return client.sendAsync(peticion.build(), HttpResponse.BodyHandlers.discarding())
                    .thenAccept(respuesta -> comprobarRespuesta(respuesta.statusCode()));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

}
