package org.traccar.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import org.junit.jupiter.api.Test;
import org.traccar.api.resource.MetricsResource;
import org.traccar.api.resource.ServerResource;
import org.traccar.database.StatisticsManager;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A metrics scrape is not a login attempt.
 *
 * Found on 2026-09-29: each scrape, with the nordes-metrics basic credential, was
 * tried as a login, counted as a failure by the account lock and logged with a
 * stack trace, twice a minute.
 */
public class SecurityRequestFilterTest {

    private static final String BASIC = "Basic " + Base64.getEncoder().encodeToString(
            "nordes-metrics:token".getBytes(StandardCharsets.US_ASCII));

    private static void poner(Object objeto, String campo, Object valor) throws Exception {
        Field field = SecurityRequestFilter.class.getDeclaredField(campo);
        field.setAccessible(true);
        field.set(objeto, valor);
    }

    private static LoginService filtrar(Class<?> recurso, String metodo, Class<?>... parametros)
            throws Exception {
        SecurityRequestFilter filtro = new SecurityRequestFilter();
        LoginService loginService = mock(LoginService.class);
        ResourceInfo info = mock(ResourceInfo.class);
        doReturn(recurso).when(info).getResourceClass();
        when(info.getResourceMethod()).thenReturn(recurso.getMethod(metodo, parametros));
        poner(filtro, "loginService", loginService);
        poner(filtro, "resourceInfo", info);
        poner(filtro, "request", mock(HttpServletRequest.class));
        poner(filtro, "statisticsManager", mock(StatisticsManager.class));
        ContainerRequestContext contexto = mock(ContainerRequestContext.class);
        when(contexto.getMethod()).thenReturn("GET");
        when(contexto.getHeaderString("Authorization")).thenReturn(BASIC);
        filtro.filter(contexto);
        return loginService;
    }

    @Test
    public void onlyTheMetricsResourceSkipsTheLogin() {
        assertTrue(SecurityRequestFilter.sinInicioDeSesion(MetricsResource.class));
        assertFalse(SecurityRequestFilter.sinInicioDeSesion(ServerResource.class));
    }

    @Test
    public void aMetricsScrapeIsNotTriedAsALogin() throws Exception {
        LoginService loginService = filtrar(MetricsResource.class, "get", String.class);
        verify(loginService, never()).login(anyString(), anyString());
    }

    @Test
    public void anyOtherResourceStillTriesTheLogin() throws Exception {
        LoginService loginService = filtrar(ServerResource.class, "get");
        verify(loginService).login(anyString(), anyString());
    }

}
