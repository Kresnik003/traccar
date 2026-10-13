package org.traccar.api.resource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.seguridad.VigiaSeguridad;
import org.traccar.session.ConnectionManager;
import org.traccar.session.FleetMetrics;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Base64;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who can read /api/metrics: only Prometheus, as "nordes-metrics" with the
 * token. Found by PIT on 2026-09-29: no test tried an empty token, a broken
 * header or another user with the right password, and the metrics say how
 * many bikes are connected and whether a crash went unwarned.
 */
public class MetricsResourceAccesoTest {

    private final ConnectionManager connectionManager = mock(ConnectionManager.class);

    private MetricsResource resource(String token, DataSource dataSource) throws Exception {
        MetricsResource resource = new MetricsResource();
        Config config = new Config();
        if (token != null) {
            config.setString(Keys.WEB_METRICS_TOKEN, token);
        }
        VigiaSeguridad vigia = mock(VigiaSeguridad.class);
        when(vigia.render()).thenReturn("");
        for (var campo : new Object[][] {
                {"config", config}, {"fleetMetrics", new FleetMetrics()},
                {"connectionManager", connectionManager}, {"dataSource", dataSource},
                {"vigiaSeguridad", vigia}}) {
            Field field = MetricsResource.class.getDeclaredField((String) campo[0]);
            field.setAccessible(true);
            field.set(resource, campo[1]);
        }
        return resource;
    }

    private static String basica(String credenciales) {
        return "Basic " + Base64.getEncoder().encodeToString(credenciales.getBytes(StandardCharsets.UTF_8));
    }

    private static int estado(MetricsResource resource, String authorization) {
        return resource.get(authorization).getStatus();
    }

    @Test
    public void withoutATokenConfiguredNobodyGetsIn() throws Exception {
        assertEquals(404, estado(resource(null, null), basica("nordes-metrics:")));
        assertEquals(404, estado(resource(null, null), basica("nordes-metrics:null")));
    }

    @Test
    public void anEmptyTokenIsNoTokenNotAnEmptyPassword() throws Exception {
        assertEquals(404, estado(resource("", null), basica("nordes-metrics:")));
    }

    @Test
    public void onlyNordesMetricsWithTheTokenGetsIn() throws Exception {
        MetricsResource resource = resource("secreto", null);
        assertEquals(200, estado(resource, basica("nordes-metrics:secreto")));
        assertEquals(200, estado(resource, "basic " + basica("nordes-metrics:secreto").substring(6)));
        assertEquals(404, estado(resource, basica("admin:secreto")));
        assertEquals(404, estado(resource, basica(":secreto")));
        assertEquals(404, estado(resource, basica("nordes-metrics:secret")));
        assertEquals(404, estado(resource, basica("nordes-metrics:secreto2")));
    }

    @Test
    public void aBrokenHeaderIsNotFoundNotAnError() throws Exception {
        MetricsResource resource = resource("secreto", null);
        assertEquals(404, estado(resource, null));
        assertEquals(404, estado(resource, "Bearer secreto"));
        assertEquals(404, estado(resource, "Basic !!!no-es-base64!!!"));
        assertEquals(404, estado(resource, basica("nordes-metrics")));
        assertEquals(404, estado(resource, "Basic"));
    }

    @Test
    public void theConnectedBikesAndThePoolAreRead() throws Exception {
        when(connectionManager.getDeviceSessionCount()).thenReturn(3);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:pool-metricas-acceso;DB_CLOSE_DELAY=-1");
        config.setMaximumPoolSize(4);
        try (HikariDataSource dataSource = new HikariDataSource(config);
                Connection ignored = dataSource.getConnection()) {
            String texto = (String) resource("secreto", dataSource)
                    .get(basica("nordes-metrics:secreto")).getEntity();
            assertTrue(texto.contains("nordes_devices_connected 3\n"), texto);
            assertTrue(texto.contains("nordes_db_pool_max 4\n"), texto);
        }
    }

    @Test
    public void aPoolNotStartedYetIsUnknownNotAnError() {
        // Sin configuración en el constructor, Hikari no arranca el pool hasta la primera conexión.
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl("jdbc:h2:mem:pool-sin-arrancar;DB_CLOSE_DELAY=-1");
            assertNull(MetricsResource.estadoPool(dataSource));
        }
    }

}
