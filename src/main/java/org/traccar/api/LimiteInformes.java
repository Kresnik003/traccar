/*
 * Copyright 2026 Kresnik003 (juanilloferrol@gmail.com)
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
package org.traccar.api;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import org.traccar.api.security.UserPrincipal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Límite de informes por usuario (mejora 31). Un informe es lo único de la API
 * que puede llevarse la memoria del servidor, y una pantalla con un bucle —o un
 * script ajeno con una sesión— los pide sin parar. Cubo de fichas por usuario:
 * {@link #CAPACIDAD} seguidos y luego uno cada {@link #REPOSICION_MS}. La ficha
 * de un equipo pide cinco a la vez, así que el cubo deja holgura para eso.
 *
 * ADEMÁS, TURNOS: como mucho {@link #SIMULTANEOS} a la vez por usuario. Medido
 * en el banco: 25 informes en paralelo de un solo usuario ocupaban las 10
 * conexiones de la base y dejaban 15 hilos esperando, y en esa cola esperaba
 * también la escritura de posiciones de todas las motos. El resto no se
 * rechaza: espera su turno hasta {@link #ESPERA_TURNO_S} s (no más: nginx corta a
 * los 60), así que la web,
 * que abre un panel con unos diez a la vez, no nota nada salvo que tarda lo
 * que tardaba.
 *
 * Corre después de {@code SecurityRequestFilter}, que es quien dice qué usuario es.
 */
@Priority(Priorities.USER + 100)
public class LimiteInformes implements ContainerRequestFilter, ContainerResponseFilter {

    public static final int SIMULTANEOS = 4;
    public static final int ESPERA_TURNO_S = 45;
    private static final String TURNO = "nordes.turnoInforme";
    private static final Map<Long, Semaphore> TURNOS = new ConcurrentHashMap<>();

    public static final int CAPACIDAD = 30;
    public static final long REPOSICION_MS = 2000;
    private static final int LIMPIAR_POR_ENCIMA_DE = 10_000;

    private static final Map<Long, Cubo> CUBOS = new ConcurrentHashMap<>();

    static final class Cubo {
        private double fichas = CAPACIDAD;
        private long ultima;

        Cubo(long ahora) {
            ultima = ahora;
        }

        /** 0 si hay ficha y la gasta; si no, los segundos hasta la siguiente. */
        synchronized long gastar(long ahora) {
            fichas = Math.min(CAPACIDAD, fichas + (double) (ahora - ultima) / REPOSICION_MS);
            ultima = ahora;
            if (fichas >= 1) {
                fichas -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - fichas) * REPOSICION_MS / 1000));
        }

        synchronized boolean lleno(long ahora) {
            return fichas + (double) (ahora - ultima) / REPOSICION_MS >= CAPACIDAD;
        }
    }

    static long gastar(long usuario, long ahora) {
        if (CUBOS.size() > LIMPIAR_POR_ENCIMA_DE) {
            CUBOS.values().removeIf(c -> c.lleno(ahora));
        }
        return CUBOS.computeIfAbsent(usuario, k -> new Cubo(ahora)).gastar(ahora);
    }

    static void olvidar() {
        CUBOS.clear();
        TURNOS.clear();
    }

    static Semaphore turnos(long usuario) {
        return TURNOS.computeIfAbsent(usuario, k -> new Semaphore(SIMULTANEOS, true));
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!requestContext.getUriInfo().getPath().startsWith("reports")
                || requestContext.getSecurityContext() == null
                || !(requestContext.getSecurityContext().getUserPrincipal() instanceof UserPrincipal principal)) {
            return;
        }
        long espera = gastar(principal.getUserId(), System.currentTimeMillis());
        if (espera > 0) {
            throw new WebApplicationException(Response
                    .status(Response.Status.TOO_MANY_REQUESTS)
                    .header("Retry-After", espera)
                    .entity("Demasiados informes seguidos: espera " + espera + " s")
                    .build());
        }
        Semaphore turnos = turnos(principal.getUserId());
        boolean turno;
        try {
            turno = turnos.tryAcquire(ESPERA_TURNO_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            turno = false;
        }
        if (!turno) {
            throw new WebApplicationException(Response
                    .status(Response.Status.TOO_MANY_REQUESTS)
                    .header("Retry-After", 5)
                    .build());
        }
        requestContext.setProperty(TURNO, turnos);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        // Runs for every response, also the ones made from an exception, so a
        // report that failed gives its turn back too.
        if (requestContext.getProperty(TURNO) instanceof Semaphore turnos) {
            requestContext.removeProperty(TURNO);
            turnos.release();
        }
    }

}
