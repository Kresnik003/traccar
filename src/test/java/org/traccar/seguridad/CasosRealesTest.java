package org.traccar.seguridad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.traccar.api.EnlacesDirectos;
import org.traccar.geofence.GeofenceCircle;
import org.traccar.helper.DistanceCalculator;
import org.traccar.helper.model.Fijacion;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.model.Viaje;
import org.traccar.storage.Storage;
import org.traccar.telegram.ClienteTelegram;
import org.traccar.telegram.FirmaBoton;
import org.traccar.telegram.RespuestaTelegram;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The safety rules against data I did not make up: the positions of the bench
 * replica of the Ducati (its trips, its parking with the GPS drifting, its 0,0
 * heartbeats) and a crash trace in the FTC880's real frame format. Extracted
 * with web/scripts/extraer-casos-seguridad.mjs, coordinates moved elsewhere.
 *
 * Added on 2026-09-29 after the independent review found three critical faults
 * with every test green: the tests confirmed my own assumptions.
 */
public class CasosRealesTest {

    private static List<Position> cargar(String fichero) throws Exception {
        List<Position> lista = new ArrayList<>();
        try (InputStream entrada = CasosRealesTest.class.getResourceAsStream("/nordes/seguridad/" + fichero)) {
            for (JsonNode nodo : new ObjectMapper().readTree(entrada)) {
                Position position = new Position();
                position.setDeviceId(8);
                position.setDeviceTime(Date.from(java.time.OffsetDateTime.parse(
                        nodo.path("deviceTime").asText()).toInstant()));
                position.setFixTime(Date.from(java.time.OffsetDateTime.parse(
                        nodo.path("fixTime").asText()).toInstant()));
                position.setValid(nodo.path("valid").asBoolean());
                position.setLatitude(nodo.path("latitude").asDouble());
                position.setLongitude(nodo.path("longitude").asDouble());
                position.setSpeed(nodo.path("speed").asDouble());
                position.set(Position.KEY_IGNITION, nodo.path("ignition").asBoolean());
                if (nodo.path("traza").asBoolean()) {
                    position.set(ReglasGolpe.TRAZA, "traza");
                }
                lista.add(position);
            }
        }
        return lista;
    }

    /** Where the replica parks: the median of its still positions with a fix. */
    private static double[] aparcamiento(List<Position> posiciones) {
        List<Position> quietas = posiciones.stream()
                .filter(Fijacion::tiene).filter(p -> p.getSpeed() < 1.6).toList();
        double[] lat = quietas.stream().mapToDouble(Position::getLatitude).sorted().toArray();
        double[] lon = quietas.stream().mapToDouble(Position::getLongitude).sorted().toArray();
        return new double[] {lat[lat.length / 2], lon[lon.length / 2]};
    }

    private static final long[] RADIOS = {30, 40, 50, 60, 70, 100};

    private static long hora(String cuando) {
        return java.time.OffsetDateTime.parse("2026-09-13T" + cuando + "Z").toInstant().toEpochMilli();
    }

    /**
     * The real day through the trips, armed at home from the start, with a round
     * «Casa» of the given radius where the replica sleeps. The fix times of the
     * positions that opened a trip.
     */
    private static List<Long> salidas(List<Position> posiciones, double[] casa, long radio) throws Exception {
        Sitio sitio = new Sitio(1, "Casa", new GeofenceCircle(String.format(
                java.util.Locale.ROOT, "CIRCLE (%.6f %.6f, %d)", casa[0], casa[1], radio)));
        Storage storage = mock(Storage.class);
        ClienteTelegram cliente = mock(ClienteTelegram.class);
        Destinatarios destinatarios = mock(Destinatarios.class);
        SitiosPropios sitios = mock(SitiosPropios.class);
        when(sitios.de(8)).thenReturn(List.of(sitio));
        when(destinatarios.contactos(8)).thenReturn(List.of(new Destinatarios.Destinatario("901", null, 1L)));
        when(destinatarios.persona(8)).thenReturn("Juan");
        when(storage.getObject(eq(Device.class), any())).thenReturn(new Device());
        when(cliente.enviar(anyString(), anyString(), anyList())).thenReturn(new RespuestaTelegram(200, null));
        long[] actual = {0};
        List<Long> salidas = new ArrayList<>();
        when(storage.addObject(any(Viaje.class), any())).thenAnswer(i -> {
            salidas.add(actual[0]);
            return (long) salidas.size();
        });
        ViajesFamilia viajes = new ViajesFamilia(true, storage, cliente, mock(EnlacesDirectos.class),
                destinatarios, sitios, new FirmaBoton("s"), Runnable::run);
        Position enCasa = new Position();
        enCasa.setDeviceId(8);
        enCasa.setDeviceTime(new Date(posiciones.get(0).getFixTime().getTime() - 60_000));
        enCasa.setFixTime(enCasa.getDeviceTime());
        enCasa.setValid(true);
        enCasa.setLatitude(casa[0]);
        enCasa.setLongitude(casa[1]);
        viajes.alPosicion(enCasa);
        for (Position position : posiciones) {
            actual[0] = position.getFixTime().getTime();
            viajes.alPosicion(position);
        }
        return salidas;
    }

    @Test
    public void everyDepartureOfTheRealBikeIsARealDeparture() throws Exception {
        List<Position> posiciones = cargar("moto-real.json");
        double[] casa = aparcamiento(posiciones);
        for (long radio : RADIOS) {
            List<Long> salidas = salidas(posiciones, casa, radio);
            // Not vacuous: the replica does leave home, and that must be seen.
            assertFalse(salidas.isEmpty(), "radius " + radio + ": the real bike left home and nothing fired");
            for (long inicio : salidas) {
                boolean seAleja = posiciones.stream()
                        .filter(Fijacion::tiene)
                        .filter(p -> p.getFixTime().getTime() >= inicio
                                && p.getFixTime().getTime() <= inicio + 180_000)
                        .anyMatch(p -> DistanceCalculator.distance(
                                casa[0], casa[1], p.getLatitude(), p.getLongitude()) > 250);
                assertTrue(seAleja, "radius " + radio + ": a departure at " + new Date(inicio)
                        + " was not the bike leaving");
            }
        }
    }

    @Test
    public void theRealManoeuvreIsNotADepartureAndTheRealOneFiresOnce() throws Exception {
        // Found in the second review: at 20:13:15 the replica did 16 km/h 74 m
        // from where it sleeps, then 19 and 23 km/h, and at 20:13:49 it was parked
        // again, off. One sample was enough to tell the family it had left, and
        // the arming it spent was the one the real departure at 20:19:46 needed.
        List<Position> posiciones = cargar("moto-real.json");
        double[] casa = aparcamiento(posiciones);
        for (long radio : RADIOS) {
            List<Long> salidas = salidas(posiciones, casa, radio);
            assertTrue(salidas.stream().noneMatch(t -> t >= hora("20:12:00") && t < hora("20:19:00")),
                    "radius " + radio + ": the manoeuvre fired a departure");
            assertEquals(1, salidas.stream().filter(t -> t >= hora("20:19:00") && t <= hora("20:23:00")).count(),
                    "radius " + radio + ": the real departure of 20:19 must fire once, fired at " + salidas);
        }
    }

    @Test
    public void theRealParkingNeverLooksLikeADeparture() throws Exception {
        // Every sample of the real parking - within 100 m of where it sleeps,
        // whatever its speed or ignition: the drift, the manoeuvre at 16-23 km/h,
        // the first metres of each ride - and never a departure, armed from the start.
        List<Position> posiciones = cargar("moto-real.json");
        double[] casa = aparcamiento(posiciones);
        List<Position> aparcamiento = posiciones.stream()
                .filter(p -> !Fijacion.tiene(p) || DistanceCalculator.distance(
                        casa[0], casa[1], p.getLatitude(), p.getLongitude()) <= 100)
                .toList();
        // Not vacuous: the parking has fast samples, above the riding threshold.
        assertTrue(aparcamiento.stream().filter(Fijacion::tiene)
                .filter(p -> p.getSpeed() >= org.traccar.helper.UnitsConverter.knotsFromKph(15)).count() >= 5);
        for (long radio : RADIOS) {
            assertEquals(List.of(), salidas(aparcamiento, casa, radio), "radius " + radio);
        }
    }

    @Test
    public void aRealCrashTraceIsNotRidingOn() throws Exception {
        List<Position> posiciones = cargar("traza-choque-7.json");
        Date golpe = posiciones.stream().filter(p -> p.hasAttribute(ReglasGolpe.TRAZA))
                .findFirst().orElseThrow().getDeviceTime();
        List<Position> despues = posiciones.stream()
                .filter(p -> p.getDeviceTime().after(golpe)).toList();
        assertFalse(ReglasGolpe.seHaMovido(golpe, despues));
    }

}
