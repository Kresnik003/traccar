package org.traccar.api.resource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.seguridad.VigiaSeguridad;
import org.traccar.session.ConnectionManager;
import org.traccar.session.FleetMetrics;

import jakarta.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The safety chain goes out with the rest of the metrics (2026-09-29), and only
 * to Prometheus: counting the overdue guards asks the database, and an
 * anonymous request must not be able to make it do so.
 */
public class MetricsResourceSeguridadTest {

    private MetricsResource resource;
    private VigiaSeguridad vigia;

    private static void poner(Object objeto, String campo, Object valor) throws Exception {
        Field field = MetricsResource.class.getDeclaredField(campo);
        field.setAccessible(true);
        field.set(objeto, valor);
    }

    private static String basica(String usuario, String clave) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (usuario + ":" + clave).getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    public void preparar() throws Exception {
        resource = new MetricsResource();
        Config config = new Config();
        config.setString(Keys.WEB_METRICS_TOKEN, "secreto");
        vigia = mock(VigiaSeguridad.class);
        when(vigia.render()).thenReturn("nordes_seguridad_activa 1\n");
        poner(resource, "config", config);
        poner(resource, "fleetMetrics", new FleetMetrics());
        poner(resource, "connectionManager", mock(ConnectionManager.class));
        poner(resource, "vigiaSeguridad", vigia);
    }

    @Test
    public void prometheusGetsTheSafetyChainWithTheRest() {
        Response respuesta = resource.get(basica("nordes-metrics", "secreto"));
        assertEquals(200, respuesta.getStatus());
        String texto = (String) respuesta.getEntity();
        assertTrue(texto.contains("nordes_positions_stored_total "), texto);
        assertTrue(texto.endsWith("nordes_seguridad_activa 1\n"), texto);
    }

    @Test
    public void withoutTheTokenNothingIsComputed() {
        assertEquals(404, resource.get(null).getStatus());
        assertEquals(404, resource.get(basica("nordes-metrics", "otro")).getStatus());
        verify(vigia, never()).render();
    }

}
