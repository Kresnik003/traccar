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
package org.traccar.api.resource;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.session.ConnectionManager;
import org.traccar.session.FleetMetrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.sql.DataSource;

/**
 * Fleet counters for Prometheus.
 *
 * Off unless web.metricsToken is set, and then only for a request that
 * presents that token with HTTP basic authentication, as the password of the
 * user "nordes-metrics". A session is not accepted: a scraper should not hold a
 * user account, and a user should not be able to read server-wide activity.
 *
 * WHY BASIC AND NOT A BEARER TOKEN. SecurityRequestFilter reads every
 * Authorization header before any resource runs. A bearer value is taken as a
 * Traccar API token, and one it cannot decode fails with a 400 and a stack
 * trace. Any other scheme is logged as an authentication error with its stack
 * trace, once per scrape. A basic credential for a user that does not exist is
 * looked up, not found, and passed on quietly to a PermitAll resource - and it
 * is also what Prometheus 2.x can send, which cannot add custom headers.
 *
 * Without a token, or with the wrong one, the answer is 404. The endpoint is
 * not advertised to anyone who cannot use it.
 */
@Path("metrics")
public class MetricsResource {

    @Inject
    private Config config;

    @Inject
    private FleetMetrics fleetMetrics;

    @Inject
    private ConnectionManager connectionManager;

    @Inject
    private DataSource dataSource;

    private static final String USER = "nordes-metrics";

    @PermitAll
    @GET
    @Produces("text/plain; version=0.0.4")
    public Response get(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization) {
        String token = config.getString(Keys.WEB_METRICS_TOKEN);
        if (token == null || token.isEmpty() || !valid(authorization, token)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        fleetMetrics.setDevicesConnected(connectionManager::getDeviceSessionCount);
        fleetMetrics.setPool(() -> estadoPool(dataSource));
        return Response.ok(fleetMetrics.render()).build();
    }

    static FleetMetrics.Pool estadoPool(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            return new FleetMetrics.Pool(
                    pool.getActiveConnections(), pool.getIdleConnections(), pool.getTotalConnections(),
                    hikari.getMaximumPoolSize(), pool.getThreadsAwaitingConnection());
        }
        return null;
    }

    private static boolean valid(String authorization, String token) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int colon = decoded.indexOf(':');
        return colon > 0
                && decoded.substring(0, colon).equals(USER)
                && MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8),
                        decoded.substring(colon + 1).getBytes(StandardCharsets.UTF_8));
    }

}
