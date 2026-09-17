/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.helper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * One-time password reset codes.
 *
 * The reset link used to carry a signed token of the same kind the login
 * accepts, valid for seven days and reusable: on the test bench it opened a
 * session, skipped the second factor, and still worked after the password had
 * been changed. A reset code is none of that. It is random, only its hash is
 * stored, it expires soon, it is spent when used, and nothing but the password
 * update accepts it.
 */
public final class ResetCode {

    public static final long VALIDITY_MILLIS = 60 * 60 * 1000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ResetCode() {
    }

    /** 32 random bytes, URL-safe: it goes in a link. */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** What is stored instead of the code. */
    public static String hash(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Whether something could be a code at all, checked before touching the
     * database. A signed login token is much longer and has a different
     * alphabet mix, so an old-style link is turned away here too.
     */
    public static boolean plausible(String code) {
        return code != null && code.matches("[A-Za-z0-9_-]{43}");
    }

}
