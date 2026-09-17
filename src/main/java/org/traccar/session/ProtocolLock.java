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
package org.traccar.session;

import java.util.Arrays;

/**
 * Which protocols a device may open a session on.
 *
 * Traccar identifies a device by the identifier the device itself declares.
 * Unknown identifiers are already turned away; a known one is accepted on any
 * enabled protocol. That is the hole: a Teltonika on the binary port is hard
 * to imitate, but the same IMEI sent over OsmAnd is one HTTP request that
 * anyone can type, and it writes positions into that bike's history. It has
 * happened here by accident, with a test run using a real bike's identifier.
 *
 * Two ways to close it. A device attribute naming its protocols, which always
 * applies. Or server.protocolLock, which ties every device without that
 * attribute to the protocol of its last stored position. A device with no
 * position yet is accepted on anything, so its first report decides; setting
 * the attribute closes that window too.
 */
public final class ProtocolLock {

    public static final String ATTRIBUTE = "allowedProtocols";

    private ProtocolLock() {
    }

    /**
     * @param protocol the protocol of the connection
     * @param allowed the device attribute, a comma separated list, or null
     * @param lastProtocol the protocol of the device's last position, or null
     * @param lock whether server.protocolLock is on
     */
    public static boolean allowed(String protocol, String allowed, String lastProtocol, boolean lock) {
        if (allowed != null && !allowed.isBlank()) {
            return Arrays.stream(allowed.split(","))
                    .map(String::trim)
                    .anyMatch(name -> name.equalsIgnoreCase(protocol));
        }
        if (lock && lastProtocol != null && !lastProtocol.isBlank()) {
            return lastProtocol.equalsIgnoreCase(protocol);
        }
        return true;
    }

}
