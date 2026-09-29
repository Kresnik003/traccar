package org.traccar.seguridad;

import org.junit.jupiter.api.Test;
import org.traccar.api.EnlacesDirectos;
import org.traccar.geofence.GeofenceCircle;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Contacto;
import org.traccar.model.Device;
import org.traccar.model.Guardia;
import org.traccar.model.Position;
import org.traccar.model.QuedadaEquipo;
import org.traccar.model.User;
import org.traccar.model.Viaje;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;
import org.traccar.telegram.ClienteTelegram;
import org.traccar.telegram.FirmaBoton;
import org.traccar.telegram.RespuestaTelegram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What must never happen, over thousands of generated cases with a fixed seed
 * (so a failure can be reproduced): a crash with a slide taken for riding on, a
 * bike with a wandering fix "leaving", the rider warned of their own crash, and
 * "I'm fine" and "the contacts were warned" both winning.
 *
 * Added on 2026-09-29, after the independent review: the example tests only
 * covered the cases I had thought of.
 */
public class PropiedadesSeguridadTest {

    private static final long T = 1_790_000_000_000L;
    private static final long SEMILLA = 20260929L;

    private static Position posicion(long ms, boolean contacto, double kmh, double lat, double lon, boolean fix) {
        Position position = new Position();
        position.setDeviceId(8);
        position.setDeviceTime(new Date(ms));
        position.setFixTime(new Date(fix ? ms : ms - 3_600_000));
        position.set(Position.KEY_IGNITION, contacto);
        position.setSpeed(UnitsConverter.knotsFromKph(kmh));
        position.setLatitude(lat);
        position.setLongitude(lon);
        position.setValid(fix);
        return position;
    }

    /** Anything at all during the crash itself: fast, slow, without a fix, at 0,0, trace records. */
    private static Position caos(Random r, long ms) {
        boolean cero = r.nextInt(5) == 0;
        Position p = posicion(ms, r.nextBoolean(), r.nextDouble() * 150,
                cero ? 0 : 43 + r.nextGaussian() * 0.01, cero ? 0 : -8 + r.nextGaussian() * 0.01, r.nextBoolean());
        if (r.nextInt(3) == 0) {
            p.set(ReglasGolpe.TRAZA, "traza");
        }
        return p;
    }

    /** After the crash, something that is NOT riding on: still, slow, ignition off, no fix or trace. */
    private static Position noEsMarcha(Random r, long ms) {
        return switch (r.nextInt(4)) {
            case 0 -> posicion(ms, r.nextBoolean(), r.nextDouble() * 9.9, 43.5, -8, true);
            case 1 -> posicion(ms, false, 10 + r.nextDouble() * 100, 43.5, -8, true);
            case 2 -> posicion(ms, true, 10 + r.nextDouble() * 100, 0, 0, false);
            default -> {
                Position p = posicion(ms, true, 10 + r.nextDouble() * 100, 43.5, -8, true);
                p.set(ReglasGolpe.TRAZA, "traza");
                yield p;
            }
        };
    }

    @Test
    public void aCrashIsNeverTakenForRidingOnWithoutSustainedRiding() {
        Random r = new Random(SEMILLA);
        for (int caso = 0; caso < 5_000; caso++) {
            List<Position> despues = new ArrayList<>();
            for (int i = r.nextInt(40); i > 0; i--) {
                despues.add(caos(r, T + r.nextInt((int) ReglasGolpe.ASENTAMIENTO)));
            }
            for (int i = r.nextInt(20); i > 0; i--) {
                despues.add(noEsMarcha(r, T + ReglasGolpe.ASENTAMIENTO + r.nextInt(90_000)));
            }
            // At most one riding position, or two too close together.
            if (r.nextBoolean()) {
                long cuando = T + ReglasGolpe.ASENTAMIENTO + r.nextInt(90_000);
                despues.add(posicion(cuando, true, 20 + r.nextDouble() * 100, 43.6, -8, true));
                if (r.nextBoolean()) {
                    despues.add(posicion(cuando + r.nextInt((int) ReglasGolpe.SEPARACION),
                            true, 20 + r.nextDouble() * 100, 43.6, -8, true));
                }
            }
            Collections.shuffle(despues, r);
            assertFalse(ReglasGolpe.seHaMovido(new Date(T), despues), "case " + caso);
        }
    }

    @Test
    public void sustainedRidingIsAlwaysSeenWhateverTheNoise() {
        Random r = new Random(SEMILLA + 1);
        for (int caso = 0; caso < 5_000; caso++) {
            List<Position> despues = new ArrayList<>();
            for (int i = r.nextInt(40); i > 0; i--) {
                despues.add(caos(r, T + r.nextInt(120_000)));
            }
            long primera = T + ReglasGolpe.ASENTAMIENTO + r.nextInt(60_000);
            despues.add(posicion(primera, true, 11 + r.nextDouble() * 100, 43.6, -8, true));
            despues.add(posicion(primera + ReglasGolpe.SEPARACION + r.nextInt(60_000),
                    true, 11 + r.nextDouble() * 100, 43.7, -8, true));
            Collections.shuffle(despues, r);
            assertTrue(ReglasGolpe.seHaMovido(new Date(T), despues), "case " + caso);
        }
    }

    private record Montaje(ViajesFamilia viajes, AtomicInteger salidas) {
    }

    private static Montaje viajes(Sitio casa) throws Exception {
        Storage storage = mock(Storage.class);
        ClienteTelegram cliente = mock(ClienteTelegram.class);
        Destinatarios destinatarios = mock(Destinatarios.class);
        SitiosPropios sitios = mock(SitiosPropios.class);
        when(sitios.de(8)).thenReturn(List.of(casa));
        when(destinatarios.contactos(8)).thenReturn(List.of(new Destinatarios.Destinatario("901", null, 1L)));
        when(destinatarios.persona(8)).thenReturn("Juan");
        when(storage.getObject(eq(Device.class), any())).thenReturn(new Device());
        when(cliente.enviar(anyString(), anyString(), anyList())).thenReturn(new RespuestaTelegram(200, null));
        AtomicInteger salidas = new AtomicInteger();
        when(storage.addObject(any(Viaje.class), any())).thenAnswer(i -> (long) salidas.incrementAndGet());
        return new Montaje(new ViajesFamilia(true, storage, cliente, mock(EnlacesDirectos.class),
                destinatarios, sitios, new FirmaBoton("s"), Runnable::run), salidas);
    }

    @Test
    public void aParkedBikeWithAWanderingFixNeverLeaves() throws Exception {
        Random r = new Random(SEMILLA + 2);
        Sitio casa = new Sitio(1, "Casa", new GeofenceCircle("CIRCLE (40 -3, 100)"));
        Montaje montaje = viajes(casa);
        for (int caso = 0; caso < 500; caso++) {
            // Armed at home, then the fix wanders up to ~500 m at walking pace or
            // less, with the ignition on or off, and heartbeats at 0,0 in between.
            montaje.viajes().alPosicion(posicion(T, true, 0, 40, -3, true), null);
            for (int i = 0; i < 60; i++) {
                boolean latido = r.nextInt(8) == 0;
                double lat = latido ? 0 : 40 + (r.nextDouble() - 0.5) * 0.009;
                double lon = latido ? 0 : -3 + (r.nextDouble() - 0.5) * 0.012;
                montaje.viajes().alPosicion(posicion(T + i * 5_000L, r.nextBoolean(),
                        latido ? 50 : r.nextDouble() * 14.9, lat, lon, !latido), null);
            }
        }
        assertEquals(0, montaje.salidas().get());
    }

    @Test
    public void ridingOutOfAPlaceLeavesExactlyOnce() throws Exception {
        Random r = new Random(SEMILLA + 3);
        Sitio casa = new Sitio(1, "Casa", new GeofenceCircle("CIRCLE (40 -3, 100)"));
        for (int caso = 0; caso < 300; caso++) {
            Montaje montaje = viajes(casa);
            montaje.viajes().alPosicion(posicion(T, true, 0, 40, -3, true), null);
            // A slow way out first, sometimes.
            for (int i = r.nextInt(4); i > 0; i--) {
                montaje.viajes().alPosicion(posicion(T + i * 1_000L, true, r.nextDouble() * 14, 40.0012, -3, true), null);
            }
            for (int i = 0; i < 10; i++) {
                montaje.viajes().alPosicion(posicion(T + 10_000 + i * 4_000L, true, 15 + r.nextDouble() * 100,
                        40.002 + i * 0.001, -3, true), null);
            }
            assertEquals(1, montaje.salidas().get(), "case " + caso);
        }
    }

    @Test
    public void theRiderIsNeverAmongThePeopleWarned() throws Exception {
        Random r = new Random(SEMILLA + 4);
        String[] chats = {"900", "901", "902", "903", "904", "905"};
        for (int caso = 0; caso < 2_000; caso++) {
            Storage storage = mock(Storage.class);
            List<User> todos = new ArrayList<>();
            List<User> directos = new ArrayList<>();
            for (int i = 0; i < 1 + r.nextInt(4); i++) {
                User user = new User();
                user.setId(10 + i);
                user.setName("U" + i);
                user.setTemporary(r.nextInt(6) == 0);
                user.setDisabled(r.nextInt(8) == 0);
                user.set("telegramChatId", chats[r.nextInt(chats.length)]);
                todos.add(user);
                if (r.nextBoolean()) {
                    directos.add(user);
                }
            }
            Device device = new Device();
            device.setId(8);
            int eleccion = r.nextInt(3);
            if (eleccion == 1) {
                device.set(Destinatarios.MOTORISTA, todos.get(r.nextInt(todos.size())).getId());
            } else if (eleccion == 2) {
                device.set(Destinatarios.MOTORISTA, 999L);
            }
            List<Contacto> contactos = new ArrayList<>();
            for (int i = 0; i < r.nextInt(5); i++) {
                Contacto contacto = new Contacto();
                contacto.setId(i + 1);
                contacto.setChatId(chats[r.nextInt(chats.length)]);
                contacto.setConfirmado(new Date());
                contactos.add(contacto);
            }
            when(storage.getObject(eq(Device.class), any())).thenReturn(device);
            when(storage.getObjects(eq(User.class), argThat((Request q) -> q != null
                    && q.getCondition() instanceof Condition.Permission p && p.getIncludeGroups()))).thenReturn(todos);
            when(storage.getObjects(eq(User.class), argThat((Request q) -> q != null
                    && q.getCondition() instanceof Condition.Permission p && !p.getIncludeGroups()))).thenReturn(directos);
            when(storage.getObjects(eq(Contacto.class), any())).thenReturn(contactos);
            when(storage.getObjects(eq(QuedadaEquipo.class), any())).thenReturn(List.of());

            Destinatarios destinatarios = new Destinatarios(storage);
            Optional<User> motorista = destinatarios.motorista(8);
            Optional<String> chat = destinatarios.chatDelMotorista(8);
            List<String> avisados = destinatarios.de(8).stream().map(Destinatarios.Destinatario::chatId).toList();

            String etiqueta = "case " + caso;
            chat.ifPresent(c -> assertFalse(avisados.contains(c), etiqueta + ": the rider is warned"));
            assertEquals(avisados.size(), avisados.stream().distinct().count(), "case " + caso + ": twice");
            motorista.ifPresent(m -> assertTrue(!m.getTemporary() && !m.getDisabled(), etiqueta));
            if (eleccion == 0) {
                long reales = directos.stream().filter(u -> !u.getTemporary() && !u.getDisabled()).count();
                assertEquals(reales == 1, motorista.isPresent(), "case " + caso + ": several, none chosen");
            }
            if (eleccion == 2) {
                assertTrue(motorista.isEmpty(), "case " + caso + ": a chosen rider who is not there");
            }
        }
    }

    private static Guardia copia(Guardia origen) {
        Guardia guardia = new Guardia();
        guardia.setId(origen.getId());
        guardia.setDeviceId(origen.getDeviceId());
        guardia.setPositionId(origen.getPositionId());
        guardia.setGolpe(origen.getGolpe());
        guardia.setLimite(origen.getLimite());
        guardia.setLatitud(origen.getLatitud());
        guardia.setLongitud(origen.getLongitud());
        guardia.setEstado(origen.getEstado());
        guardia.setEnlace(origen.getEnlace());
        return guardia;
    }

    @Test
    public void theButtonAndTheCountdownNeverBothWin() throws Exception {
        for (int caso = 0; caso < 300; caso++) {
            Map<Long, Guardia> base = Collections.synchronizedMap(new HashMap<>());
            Guardia guardia = new Guardia();
            guardia.setId(7);
            guardia.setDeviceId(8);
            guardia.setGolpe(new Date(T));
            guardia.setLimite(new Date(T + ReglasGolpe.ESPERA));
            guardia.setEstado(Guardia.ESPERANDO);
            base.put(7L, guardia);

            Storage storage = mock(Storage.class);
            when(storage.getObject(eq(Guardia.class), any())).thenAnswer(i -> copia(base.get(7L)));
            when(storage.getObjects(eq(Guardia.class), any())).thenAnswer(
                    i -> Guardia.ESPERANDO.equals(base.get(7L).getEstado()) ? List.of(copia(base.get(7L))) : List.of());
            when(storage.getObjectsStream(eq(Position.class), any())).thenAnswer(i -> Stream.empty());
            org.mockito.Mockito.doAnswer(i -> {
                base.put(7L, copia(i.getArgument(0)));
                return null;
            }).when(storage).updateObject(any(Guardia.class), any());
            when(storage.getObject(eq(Device.class), any())).thenReturn(new Device());
            ClienteTelegram cliente = mock(ClienteTelegram.class);
            AtomicInteger avisos = new AtomicInteger();
            when(cliente.enviar(eq("901"), anyString(), anyList())).thenAnswer(i -> {
                avisos.incrementAndGet();
                return new RespuestaTelegram(200, null);
            });
            Destinatarios destinatarios = mock(Destinatarios.class);
            when(destinatarios.chatDelMotorista(8)).thenReturn(Optional.of("900"));
            when(destinatarios.de(8)).thenReturn(List.of(new Destinatarios.Destinatario("901", null, 1L)));
            when(destinatarios.persona(8)).thenReturn("Juan");
            EnlacesDirectos enlaces = mock(EnlacesDirectos.class);
            when(enlaces.enlaceDeEquipo(any(Long.class), any(), any(), any(Long.class))).thenReturn(Optional.empty());
            GuardianGolpes guardian = new GuardianGolpes(
                    true, storage, cliente, enlaces, destinatarios, new FirmaBoton("s"), Runnable::run);

            CountDownLatch salida = new CountDownLatch(1);
            AtomicReference<String> respuesta = new AtomicReference<>();
            Thread boton = new Thread(() -> {
                await(salida);
                respuesta.set(guardian.pulsado(7, "900"));
            });
            Thread cuenta = new Thread(() -> {
                await(salida);
                guardian.revisar(T + ReglasGolpe.ESPERA + 1_000);
            });
            boton.start();
            cuenta.start();
            salida.countDown();
            boton.join();
            cuenta.join();

            String estado = base.get(7L).getEstado();
            if (respuesta.get().startsWith("Perfecto")) {
                assertEquals(0, avisos.get(), "case " + caso + ": cancelled and warned");
                assertEquals(Guardia.CANCELADA_BOTON, estado, "case " + caso);
            } else {
                assertEquals(1, avisos.get(), "case " + caso + ": neither cancelled nor warned");
                assertEquals(Guardia.AVISADA, estado, "case " + caso);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
