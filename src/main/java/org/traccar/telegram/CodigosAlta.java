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

import org.traccar.model.Contacto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

/**
 * The sign-up code of an emergency contact. It travels in a t.me link that is
 * handed out by WhatsApp or a QR, so it is random, single use and lasts a day,
 * and the database keeps only its SHA-256: whoever reads a backup cannot sign
 * up as somebody's contact.
 */
public final class CodigosAlta {

    public static final long DURACION = 24 * 60 * 60 * 1000L;

    private static final SecureRandom AZAR = new SecureRandom();

    private CodigosAlta() {
    }

    public static String nuevo() {
        byte[] bytes = new byte[18];
        AZAR.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String huella(String codigo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(codigo.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean vale(Contacto contacto, Date ahora) {
        return contacto.getCodigo() != null && contacto.getConfirmado() == null
                && contacto.getCaduca() != null && contacto.getCaduca().after(ahora);
    }

}
