package org.traccar.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FleetMetricsTest {

    @Test
    public void testCountersAppear() {
        FleetMetrics metrics = new FleetMetrics();
        metrics.positionStored();
        metrics.positionStored();
        metrics.connectionRejected();
        metrics.setDevicesConnected(() -> 3);
        String text = metrics.render();
        assertTrue(text.contains("\nnordes_positions_stored_total 2\n"), text);
        assertTrue(text.contains("\nnordes_connections_rejected_total 1\n"), text);
        assertTrue(text.contains("\nnordes_devices_connected 3\n"), text);
    }

    @Test
    public void testUnknownDevicesByProtocol() {
        FleetMetrics metrics = new FleetMetrics();
        metrics.unknownDevice("teltonika");
        metrics.unknownDevice("teltonika");
        metrics.unknownDevice("osmand");
        String text = metrics.render();
        assertTrue(text.contains("nordes_unknown_device_total{protocol=\"teltonika\"} 2\n"), text);
        assertTrue(text.contains("nordes_unknown_device_total{protocol=\"osmand\"} 1\n"), text);
    }

    /**
     * A label is copied verbatim into the output: a quote or a newline in it
     * would break every scrape, not just this line.
     */
    @Test
    public void testLabelCannotBreakTheFormat() {
        FleetMetrics metrics = new FleetMetrics();
        metrics.unknownDevice("bad\"}\nnordes_fake 1");
        metrics.unknownDevice(null);
        String text = metrics.render();
        assertTrue(text.contains("nordes_unknown_device_total{protocol=\"other\"} 2\n"), text);
        assertFalse(text.contains("nordes_fake"), text);
    }

    @Test
    public void testEveryMetricIsDeclared() {
        String text = new FleetMetrics().render();
        for (String name : new String[] {
                "nordes_positions_stored_total", "nordes_unknown_device_total",
                "nordes_connections_rejected_total", "nordes_devices_connected"}) {
            assertTrue(text.contains("# TYPE " + name + " "), name);
        }
    }

}
