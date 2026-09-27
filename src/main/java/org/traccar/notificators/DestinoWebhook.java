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

import org.traccar.notification.FalloDefinitivo;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adónde puede llamar un webhook (mejora 25). La URL la escribe el usuario, así
 * que sin esto cualquiera con cuenta haría que el servidor llamara hacia dentro:
 * a su propia API en 127.0.0.1, a la base, a la red de Oracle o a Tailscale.
 *
 * Sólo https —además, un certificado que no casa frena al que resuelve un nombre
 * suyo a una dirección interna entre la comprobación y la llamada— y ninguna de
 * las direcciones de todos los nombres que devuelva el DNS puede ser local,
 * privada, de enlace, CGNAT (Tailscale), multidifusión o sin especificar.
 *
 * DNS REBINDING. Se comprueba el nombre y luego el cliente lo vuelve a resolver
 * al conectar: un nombre del atacante podría contestar una dirección pública a
 * la comprobación y una interna a la conexión. Con https eso no llega a mandar
 * nada: el certificado se valida contra el nombre del atacante y ningún
 * servicio interno lo tiene, así que el TLS falla antes de la petición. Queda,
 * como mucho, saber por el tiempo si un puerto interno contesta; se acepta.
 *
 * La excepción la pone sólo el administrador, en {@code traccar.xml}:
 * {@code notificator.webhook.permitidos} con {@code host:puerto} separados por
 * comas. Esos destinos pueden ser internos y por http: el Home Assistant de
 * casa, que vive en la red local y rara vez tiene certificado.
 */
public final class DestinoWebhook {

    /** A destination that is not allowed; retrying it would fail the same way. */
    public static class NoValido extends IllegalArgumentException implements FalloDefinitivo {
        public NoValido(String message) {
            super(message);
        }
    }

    private DestinoWebhook() {
    }

    public static URI comprobar(String url) {
        return comprobar(url, Set.of());
    }

    public static Set<String> permitidos(String lista) {
        if (lista == null || lista.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(lista.split(","))
                .map(String::trim).filter(p -> !p.isEmpty())
                .map(p -> p.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public static URI comprobar(String url, Set<String> permitidos) {
        if (url == null || url.isBlank()) {
            throw new NoValido("Sin URL de webhook");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new NoValido("URL de webhook mal escrita");
        }
        String esquema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || uri.getUserInfo() != null || !esquema.startsWith("http")) {
            throw new NoValido("URL de webhook mal escrita");
        }
        int puerto = uri.getPort() > 0 ? uri.getPort() : "https".equals(esquema) ? 443 : 80;
        if (permitidos.contains(uri.getHost().toLowerCase(Locale.ROOT) + ":" + puerto)
                && ("http".equals(esquema) || "https".equals(esquema))) {
            return uri;
        }
        if (!"https".equals(esquema)) {
            throw new NoValido("El webhook tiene que ser https");
        }
        InetAddress[] direcciones;
        try {
            direcciones = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new NoValido("El nombre del webhook no existe");
        }
        for (InetAddress direccion : direcciones) {
            if (interna(direccion)) {
                throw new NoValido("El webhook no puede apuntar a una dirección interna");
            }
        }
        return uri;
    }

    static boolean interna(InetAddress direccion) {
        if (direccion.isLoopbackAddress() || direccion.isAnyLocalAddress() || direccion.isLinkLocalAddress()
                || direccion.isSiteLocalAddress() || direccion.isMulticastAddress()) {
            return true;
        }
        byte[] b = direccion.getAddress();
        if (direccion instanceof Inet4Address) {
            int primero = b[0] & 0xff;
            int segundo = b[1] & 0xff;
            // 0.0.0.0/8; 100.64/10 (CGNAT y Tailscale); 192.0.0.0/24; 198.18/15
            // (bancos de pruebas); 240/4 y la difusión.
            return primero == 0
                    || (primero == 100 && segundo >= 64 && segundo < 128)
                    || (primero == 192 && segundo == 0 && (b[2] & 0xff) == 0)
                    || (primero == 198 && (segundo == 18 || segundo == 19))
                    || primero >= 240;
        }
        if (direccion instanceof Inet6Address) {
            int primero = b[0] & 0xff;
            if ((primero & 0xfe) == 0xfc) {
                return true; // fc00::/7, direcciones locales únicas
            }
            boolean mapeada = true;
            for (int i = 0; i < 10; i++) {
                mapeada &= b[i] == 0;
            }
            if (mapeada && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return interna(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }

}
