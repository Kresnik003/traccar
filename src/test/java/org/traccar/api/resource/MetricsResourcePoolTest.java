package org.traccar.api.resource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class MetricsResourcePoolTest {

    @Test
    public void testReadsAHikariPool() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:pool-metricas;DB_CLOSE_DELAY=-1");
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        try (HikariDataSource dataSource = new HikariDataSource(config);
                Connection ignored = dataSource.getConnection()) {
            var pool = MetricsResource.estadoPool(dataSource);
            assertEquals(1, pool.activas());
            assertEquals(3, pool.maximo());
            assertEquals(0, pool.esperando());
            assertEquals(pool.total(), pool.activas() + pool.libres());
        }
    }

    @Test
    public void testAnythingElseIsUnknown() {
        assertNull(MetricsResource.estadoPool(null));
    }

}
