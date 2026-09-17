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

import jakarta.inject.Singleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Counters about the fleet, rendered in the Prometheus text format.
 *
 * Machine monitoring says whether the server is alive; it cannot say whether
 * positions are still arriving, or whether someone is trying identifiers
 * against the protocol port. These counters answer that.
 *
 * Nothing here carries a device identifier. Unknown identifiers are counted by
 * protocol only: an IMEI is personal data, and a label per identifier would
 * also let anyone probing the port grow the series without limit.
 */
@Singleton
public class FleetMetrics {

    private static final Pattern PROTOCOL = Pattern.compile("[a-z0-9]{1,32}");

    private final AtomicLong positionsStored = new AtomicLong();
    private final AtomicLong connectionsRejected = new AtomicLong();
    private final Map<String, AtomicLong> unknownDevices = new ConcurrentHashMap<>();
    private volatile LongSupplier devicesConnected = () -> 0;

    public void positionStored() {
        positionsStored.incrementAndGet();
    }

    public void connectionRejected() {
        connectionsRejected.incrementAndGet();
    }

    /**
     * Counts a frame from an identifier with no device behind it.
     *
     * The protocol name is checked against a strict pattern before it becomes
     * a label. It comes from code, not from the wire, but a label is copied
     * verbatim into the output and a stray quote or newline there would break
     * every scrape.
     */
    public void unknownDevice(String protocol) {
        String key = protocol != null && PROTOCOL.matcher(protocol).matches() ? protocol : "other";
        unknownDevices.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    public void setDevicesConnected(LongSupplier supplier) {
        this.devicesConnected = supplier;
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        metric(out, "nordes_positions_stored_total", "counter",
                "Positions written to the database.");
        out.append("nordes_positions_stored_total ").append(positionsStored.get()).append('\n');

        metric(out, "nordes_unknown_device_total", "counter",
                "Frames from an identifier with no device, by protocol.");
        unknownDevices.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> out.append("nordes_unknown_device_total{protocol=\"")
                        .append(entry.getKey()).append("\"} ")
                        .append(entry.getValue().get()).append('\n'));

        metric(out, "nordes_connections_rejected_total", "counter",
                "Connections closed by server.connectionLimit.");
        out.append("nordes_connections_rejected_total ").append(connectionsRejected.get()).append('\n');

        metric(out, "nordes_devices_connected", "gauge",
                "Devices with an open session.");
        out.append("nordes_devices_connected ").append(devicesConnected.getAsLong()).append('\n');
        return out.toString();
    }

    private static void metric(StringBuilder out, String name, String type, String help) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

}
