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
package org.traccar.telegram;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The data behind a Telegram button comes back from outside, so it is signed:
 * nobody can cancel somebody else's crash guard by crafting "g:7". HMAC-SHA256
 * with a secret only the server has, cut to 16 hex so the whole thing fits the
 * 64 bytes Telegram allows.
 */
public class FirmaBoton {

    public record Dato(String accion, long id) {
    }

    private final String secreto;

    public FirmaBoton(String secreto) {
        this.secreto = secreto;
    }

    public String firmar(String accion, long id) {
        return accion + ":" + id + ":" + mac(accion + ":" + id);
    }

    public Optional<Dato> comprobar(String dato) {
        String[] partes = dato == null ? new String[0] : dato.split(":");
        if (partes.length != 3 || !partes[1].matches("\\d{1,19}")) {
            return Optional.empty();
        }
        boolean igual = MessageDigest.isEqual(
                mac(partes[0] + ":" + partes[1]).getBytes(StandardCharsets.US_ASCII),
                partes[2].getBytes(StandardCharsets.US_ASCII));
        try {
            return igual ? Optional.of(new Dato(partes[0], Long.parseLong(partes[1]))) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String mac(String texto) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(texto.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

}
