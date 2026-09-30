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
package org.traccar.seguridad;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.EnlacesDirectos;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Device;
import org.traccar.model.Guardia;
import org.traccar.model.Position;
import org.traccar.telegram.BuzonTelegram;
import org.traccar.telegram.ClienteTelegram;
import org.traccar.telegram.FirmaBoton;
import org.traccar.telegram.RespuestaTelegram;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A warning going out survives a restart (found in review on 2026-09-30). The
 * guard used to be marked AVISADA before sending, and the retries lived only in
 * memory: Telegram answering 502 and Traccar restarting within the 12.5 minutes
 * of retries meant a crash warning nobody got, and no alert could see it.
 *
 * Each restart here is a new guardian over the same storage, which keeps what it
 * is given and hands back copies, as the database does.
 */
public class GuardianGolpesReinicioTest {

    private static final long T = 1_790_000_000_000L;
    private static final long LIMITE = T + ReglasGolpe.ESPERA;

    private AlmacenMemoria almacen;
    private Destinatarios destinatarios;
    private EnlacesDirectos enlaces;
    /** Messages Telegram accepted, by chat. */
    private Map<String, AtomicInteger> recibidos;
    /** What Telegram answers each chat, one call after another. */
    private Function<String, Integer> telegram;

    @BeforeEach
    public void preparar() throws Exception {
        almacen = new AlmacenMemoria();
        Device moto = new Device();
        moto.setId(8);
        moto.setName("Ducati");
        almacen.poner(moto);
        Position golpe = new Position();
        golpe.setId(100);
        golpe.setDeviceId(8);
        golpe.setDeviceTime(new Date(T));
        golpe.setFixTime(new Date(T));
        golpe.setLatitude(43.47);
        golpe.setLongitude(-8.2);
        golpe.setValid(true);
        golpe.setSpeed(UnitsConverter.knotsFromKph(0));
        almacen.poner(golpe);
        destinatarios = mock(Destinatarios.class);
        when(destinatarios.chatDelMotorista(8)).thenReturn(Optional.of("900"));
        when(destinatarios.persona(8)).thenReturn("Juan");
        when(destinatarios.de(8)).thenReturn(List.of(
                new Destinatarios.Destinatario("901", null, 1L),
                new Destinatarios.Destinatario("902", null, 2L),
                new Destinatarios.Destinatario("905", "Ruta del Eume", null)));
        enlaces = mock(EnlacesDirectos.class);
        when(enlaces.enlaceDeEquipo(anyLong(), any(), anyString(), anyLong())).thenReturn(
                Optional.of(new EnlacesDirectos.Enlace("https://n.es/?token=t", 60)));
        recibidos = new ConcurrentHashMap<>();
        telegram = chat -> 200;
    }

    private GuardianGolpes arrancar() {
        ClienteTelegram cliente = mock(ClienteTelegram.class);
        when(cliente.enviar(anyString(), anyString(), anyList())).thenAnswer(i -> {
            String chat = i.getArgument(0);
            int estado = telegram.apply(chat);
            if (estado >= 200 && estado < 300) {
                recibidos.computeIfAbsent(chat, c -> new AtomicInteger()).incrementAndGet();
            }
            return new RespuestaTelegram(estado, null);
        });
        return new GuardianGolpes(true, almacen, cliente, enlaces, destinatarios, new FirmaBoton("s"), Runnable::run);
    }

    private Guardia guardia(String estado, String resueltos) {
        Guardia guardia = new Guardia();
        guardia.setDeviceId(8);
        guardia.setPositionId(100);
        guardia.setGolpe(new Date(T));
        guardia.setLimite(new Date(LIMITE));
        guardia.setEstado(estado);
        guardia.setResueltos(resueltos);
        guardia.setEnlace(Guardia.AVISANDO.equals(estado) ? "https://n.es/?token=t" : null);
        return almacen.poner(guardia);
    }

    private int recibidos(String chat) {
        return recibidos.getOrDefault(chat, new AtomicInteger()).get();
    }

    @Test
    public void aRestartDuringTheRetriesSendsWhatWasMissingAndNothingTwice() throws Exception {
        Guardia guardia = guardia(Guardia.ESPERANDO, null);
        telegram = chat -> chat.equals("905") ? 502 : 200;
        arrancar().revisar(LIMITE + 1_000);
        Guardia tras = almacen.leer(Guardia.class, guardia.getId());
        assertEquals(Guardia.AVISANDO, tras.getEstado(), "not warned while one is missing");
        assertEquals(",901,902,", tras.getResueltos());

        // Traccar restarts 40 s later; Telegram is back.
        telegram = chat -> 200;
        GuardianGolpes otro = arrancar();
        otro.revisar(LIMITE + 41_000);
        otro.revisar(LIMITE + 51_000);
        assertEquals(1, recibidos("901"));
        assertEquals(1, recibidos("902"));
        assertEquals(1, recibidos("905"));
        assertEquals(Guardia.AVISADA, almacen.leer(Guardia.class, guardia.getId()).getEstado());
    }

    @Test
    public void aRestartRightAfterMarkingItSendsToEverybody() throws Exception {
        // Marked AVISANDO and nothing sent: the process died in between.
        Guardia guardia = guardia(Guardia.AVISANDO, ",");
        arrancar().revisar(LIMITE + 30_000);
        assertEquals(1, recibidos("901"));
        assertEquals(1, recibidos("902"));
        assertEquals(1, recibidos("905"));
        assertEquals(Guardia.AVISADA, almacen.leer(Guardia.class, guardia.getId()).getEstado());
    }

    @Test
    public void theSameProcessDoesNotSendAgainWhileItsRetriesWait() throws Exception {
        Guardia guardia = guardia(Guardia.ESPERANDO, null);
        telegram = chat -> chat.equals("905") ? 502 : 200;
        GuardianGolpes guardian = arrancar();
        guardian.revisar(LIMITE);
        guardian.revisar(LIMITE + 10_000);
        guardian.revisar(LIMITE + 20_000);
        assertEquals(1, recibidos("901"), "a pass must not start the warning again");
        telegram = chat -> 200;
        guardian.revisar(LIMITE + 30_000);
        assertEquals(1, recibidos("905"));
        assertEquals(1, recibidos("901"));
        assertEquals(Guardia.AVISADA, almacen.leer(Guardia.class, guardia.getId()).getEstado());
    }

    @Test
    public void aRefusedContactIsServedAndNotSentAgainAfterARestart() throws Exception {
        Guardia guardia = guardia(Guardia.ESPERANDO, null);
        telegram = chat -> chat.equals("902") ? 403 : chat.equals("905") ? 502 : 200;
        arrancar().revisar(LIMITE);
        assertEquals(",901,902,", almacen.leer(Guardia.class, guardia.getId()).getResueltos());
        telegram = chat -> 200;
        arrancar().revisar(LIMITE + 60_000);
        assertEquals(0, recibidos("902"));
        assertEquals(1, recibidos("905"));
    }

    @Test
    public void aWarningThatCannotBeNotedIsSentAgainNeverLost() throws Exception {
        // At least once: if writing that it went out fails, a restart sends it again.
        Guardia guardia = guardia(Guardia.AVISANDO, ",");
        telegram = chat -> {
            almacen.escriturasCaidas(true);
            return 200;
        };
        arrancar().revisar(LIMITE + 1_000);
        almacen.escriturasCaidas(false);
        telegram = chat -> 200;
        arrancar().revisar(LIMITE + 20_000);
        assertTrue(recibidos("901") >= 1 && recibidos("902") >= 1 && recibidos("905") >= 1);
        assertEquals(Guardia.AVISADA, almacen.leer(Guardia.class, guardia.getId()).getEstado());
    }

    @Test
    public void aWarningStuckFifteenMinutesIsOverdue() throws Exception {
        guardia(Guardia.ESPERANDO, null);
        telegram = chat -> 502;
        GuardianGolpes guardian = arrancar();
        guardian.revisar(LIMITE);
        guardian.revisar(LIMITE + GuardianGolpes.ATASCADA);
        assertEquals(OptionalInt.of(0), guardian.vencidas(LIMITE + GuardianGolpes.ATASCADA));
        // Restarted just after: the retries start over, and it is still missing.
        GuardianGolpes otro = arrancar();
        otro.revisar(LIMITE + GuardianGolpes.ATASCADA + 1_000);
        assertEquals(OptionalInt.of(1), otro.vencidas(LIMITE + GuardianGolpes.ATASCADA + 1_000));
    }

    @Test
    public void theButtonDuringTheWarningSaysTheContactsKnow() throws Exception {
        Guardia guardia = guardia(Guardia.AVISANDO, ",901,");
        assertEquals("Tus contactos ya han sido avisados. Diles tú que estás bien.",
                arrancar().pulsado(guardia.getId(), "900"));
        assertEquals(Guardia.AVISANDO, almacen.leer(Guardia.class, guardia.getId()).getEstado());
    }

    @Test
    public void withTheDatabaseDownTheCountIsUnknownAndNothingIsLost() throws Exception {
        Guardia guardia = guardia(Guardia.ESPERANDO, null);
        GuardianGolpes guardian = arrancar();
        guardian.revisar(LIMITE - 10_000);
        almacen.caida(true);
        guardian.revisar(LIMITE + 5_000);
        guardian.revisar(LIMITE + 55_000);
        assertEquals(OptionalInt.of(0), guardian.vencidas(LIMITE - 10_000 + GuardianGolpes.FRESCURA));
        assertTrue(guardian.vencidas(LIMITE + 55_000).isEmpty(), "unknown, not zero");
        almacen.caida(false);
        guardian.revisar(LIMITE + 65_000);
        assertEquals(1, recibidos("901"));
        assertEquals(Guardia.AVISADA, almacen.leer(Guardia.class, guardia.getId()).getEstado());
    }

    @Test
    public void withTheDatabaseHungTheMetricsPageStillAnswers() throws Exception {
        // Found in review on 2026-09-30: the page counted the overdue guards in the
        // database on every scrape. With MySQL hung it waited 30 s for a connection,
        // Prometheus gave up at 10 s and the whole scrape was lost - with it the
        // alerts meant for "server alive, database down".
        guardia(Guardia.ESPERANDO, null);
        GuardianGolpes guardian = arrancar();
        BuzonTelegram buzon = mock(BuzonTelegram.class);
        long[] reloj = {LIMITE - 30_000};
        VigiaSeguridad vigia = new VigiaSeguridad(guardian, buzon, () -> reloj[0]);
        guardian.revisar(reloj[0]);
        CountDownLatch cuelgue = new CountDownLatch(1);
        almacen.colgada(cuelgue);
        try {
            CompletableFuture<String> pagina = CompletableFuture.supplyAsync(vigia::render);
            String texto = pagina.get(2, TimeUnit.SECONDS);
            assertTrue(texto.contains("nordes_seguridad_activa 1\n"), texto);
            assertTrue(texto.contains("nordes_guardias_vencidas 0\n"), texto);
            // A minute later, with the countdown stuck on the database, the count is not published.
            reloj[0] += GuardianGolpes.FRESCURA + 1;
            texto = CompletableFuture.supplyAsync(vigia::render)
                    .get(2, TimeUnit.SECONDS);
            assertTrue(!texto.contains("nordes_guardias_vencidas"), texto);
            assertTrue(texto.contains("nordes_guardias_revision_segundos 60\n"), texto);
        } finally {
            cuelgue.countDown();
        }
    }

    /**
     * The invariant, over thousands of runs with a fixed seed: Telegram failing at
     * random, Traccar restarting at random passes. Nobody ever gets it twice;
     * whoever never got it was given up after every retry and counted (the alert
     * AvisoDeGolpePerdido); and in the end the guard is warned.
     */
    @Test
    public void whateverFailsAndRestartsEveryoneEndsUpWithTheWarningExactlyOnce() throws Exception {
        Random r = new Random(20260930L);
        int conPerdidos = 0;
        int reiniciosAMitad = 0;
        for (int caso = 0; caso < 2_000; caso++) {
            preparar();
            Guardia guardia = guardia(Guardia.ESPERANDO, null);
            Map<String, Double> fallo = new HashMap<>();
            for (String chat : List.of("901", "902", "905")) {
                fallo.put(chat, r.nextDouble());
            }
            telegram = chat -> r.nextDouble() < fallo.get(chat) ? (r.nextBoolean() ? 502 : 429) : 200;
            List<GuardianGolpes> procesos = new ArrayList<>();
            GuardianGolpes guardian = arrancar();
            procesos.add(guardian);
            long ahora = LIMITE - 20_000;
            for (int paso = 0; paso < 30; paso++) {
                if (r.nextInt(6) == 0) {
                    if (Guardia.AVISANDO.equals(almacen.leer(Guardia.class, guardia.getId()).getEstado())) {
                        reiniciosAMitad++;
                    }
                    guardian = arrancar();
                    procesos.add(guardian);
                }
                guardian.revisar(ahora);
                ahora += 10_000 + r.nextInt(60_000);
            }
            telegram = chat -> 200;
            for (int paso = 0; paso < 80; paso++) {
                guardian.revisar(ahora);
                ahora += 10_000;
            }
            String etiqueta = "case " + caso;
            int sinAviso = 0;
            for (String chat : List.of("901", "902", "905")) {
                assertTrue(recibidos(chat) <= 1, etiqueta + ": " + chat + " got it twice");
                sinAviso += 1 - recibidos(chat);
            }
            long perdidos = procesos.stream().mapToLong(GuardianGolpes::avisosPerdidos).sum();
            assertEquals(sinAviso, perdidos, etiqueta + ": a recipient without the warning and nothing counted");
            if (perdidos > 0) {
                conPerdidos++;
            }
            assertEquals(Guardia.AVISADA, almacen.leer(Guardia.class, guardia.getId()).getEstado(), etiqueta);
        }
        // Not a vacuous green: the runs reach what the property is about.
        assertTrue(reiniciosAMitad > 200, "restarts in the middle of a warning: " + reiniciosAMitad);
        assertTrue(conPerdidos > 20 && conPerdidos < 1_900, "runs with a message given up: " + conPerdidos);
    }

}
