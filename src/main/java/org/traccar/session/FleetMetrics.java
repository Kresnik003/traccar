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
import java.util.function.Supplier;
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
    private volatile long lastStoredAt;
    private final AtomicLong connectionsRejected = new AtomicLong();
    private final Map<String, AtomicLong> unknownDevices = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> protocolsRejected = new ConcurrentHashMap<>();
    private volatile LongSupplier devicesConnected = () -> 0;

    /** The database pool as the metrics see it (improvement 22). */
    public record Pool(int activas, int libres, int total, int maximo, int esperando) {
    }

    private volatile Supplier<Pool> pool = () -> null;

    public void setPool(Supplier<Pool> pool) {
        this.pool = pool;
    }

    public void positionStored() {
        positionsStored.incrementAndGet();
        lastStoredAt = System.currentTimeMillis();
    }

    /** Milliseconds since the epoch of the last stored position, or zero if none yet. */
    public long getLastStoredAt() {
        return lastStoredAt;
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
        unknownDevices.computeIfAbsent(label(protocol), k -> new AtomicLong()).incrementAndGet();
    }

    /** Counts a known device turned away for reporting on a protocol that is not its own. */
    public void protocolRejected(String protocol) {
        protocolsRejected.computeIfAbsent(label(protocol), k -> new AtomicLong()).incrementAndGet();
    }

    private static String label(String protocol) {
        return protocol != null && PROTOCOL.matcher(protocol).matches() ? protocol : "other";
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
        byProtocol(out, "nordes_unknown_device_total", unknownDevices);

        metric(out, "nordes_protocol_rejected_total", "counter",
                "Known devices turned away for reporting on another protocol, by protocol.");
        byProtocol(out, "nordes_protocol_rejected_total", protocolsRejected);

        metric(out, "nordes_connections_rejected_total", "counter",
                "Connections closed by server.connectionLimit.");
        out.append("nordes_connections_rejected_total ").append(connectionsRejected.get()).append('\n');

        metric(out, "nordes_devices_connected", "gauge",
                "Devices with an open session.");
        out.append("nordes_devices_connected ").append(devicesConnected.getAsLong()).append('\n');

        // WHETHER THE DATABASE IS THE BOTTLENECK, BEFORE IT FAILS. Threads
        // waiting for a connection is the number that moves first: the pool is
        // full and requests queue up, long before a timeout reaches the log.
        Pool estado = pool.get();
        if (estado != null) {
            gauge(out, "nordes_db_pool_active", "Database connections in use.", estado.activas());
            gauge(out, "nordes_db_pool_idle", "Database connections open and free.", estado.libres());
            gauge(out, "nordes_db_pool_total", "Database connections open.", estado.total());
            gauge(out, "nordes_db_pool_max", "Most connections the pool will open.", estado.maximo());
            gauge(out, "nordes_db_pool_pending", "Threads waiting for a database connection.",
                    estado.esperando());
        }
        return out.toString();
    }

    private static void byProtocol(StringBuilder out, String name, Map<String, AtomicLong> counters) {
        counters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> out.append(name).append("{protocol=\"")
                        .append(entry.getKey()).append("\"} ")
                        .append(entry.getValue().get()).append('\n'));
    }

    private static void gauge(StringBuilder out, String name, String help, long value) {
        metric(out, name, "gauge", help);
        out.append(name).append(' ').append(value).append('\n');
    }

    private static void metric(StringBuilder out, String name, String type, String help) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

}
