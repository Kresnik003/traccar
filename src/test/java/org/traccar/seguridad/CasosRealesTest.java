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

    @Test
    public void everyDepartureOfTheRealBikeIsARealDeparture() throws Exception {
        List<Position> posiciones = cargar("moto-real.json");
        double[] casa = aparcamiento(posiciones);
        Sitio sitio = new Sitio(1, "Casa", new GeofenceCircle(
                String.format(java.util.Locale.ROOT, "CIRCLE (%.6f %.6f, 100)", casa[0], casa[1])));

        Storage storage = mock(Storage.class);
        ClienteTelegram cliente = mock(ClienteTelegram.class);
        Destinatarios destinatarios = mock(Destinatarios.class);
        SitiosPropios sitios = mock(SitiosPropios.class);
        when(sitios.de(8)).thenReturn(List.of(sitio));
        when(destinatarios.contactos(8)).thenReturn(List.of(new Destinatarios.Destinatario("901", null, 1L)));
        when(destinatarios.persona(8)).thenReturn("Juan");
        when(storage.getObject(eq(Device.class), any())).thenReturn(new Device());
        when(cliente.enviar(anyString(), anyString(), anyList())).thenReturn(new RespuestaTelegram(200, null));
        int[] actual = {0};
        List<Integer> salidas = new ArrayList<>();
        when(storage.addObject(any(Viaje.class), any())).thenAnswer(i -> {
            salidas.add(actual[0]);
            return (long) salidas.size();
        });
        ViajesFamilia viajes = new ViajesFamilia(true, storage, cliente, mock(EnlacesDirectos.class),
                destinatarios, sitios, new FirmaBoton("s"), Runnable::run);

        for (int i = 0; i < posiciones.size(); i++) {
            actual[0] = i;
            viajes.alPosicion(posiciones.get(i), null);
        }

        // Not vacuous: the replica does leave home, and that must be seen.
        assertFalse(salidas.isEmpty(), "the real bike left home and no departure fired");
        for (int indice : salidas) {
            long inicio = posiciones.get(indice).getDeviceTime().getTime();
            boolean seAleja = posiciones.stream()
                    .filter(Fijacion::tiene)
                    .filter(p -> p.getDeviceTime().getTime() >= inicio
                            && p.getDeviceTime().getTime() <= inicio + 180_000)
                    .anyMatch(p -> DistanceCalculator.distance(
                            casa[0], casa[1], p.getLatitude(), p.getLongitude()) > 250);
            assertTrue(seAleja, "a departure at position " + indice + " was not the bike leaving");
        }
    }

    @Test
    public void theRealParkingNeverLooksLikeADeparture() throws Exception {
        List<Position> posiciones = cargar("moto-real.json");
        double[] casa = aparcamiento(posiciones);
        Sitio sitio = new Sitio(1, "Casa", new GeofenceCircle(
                String.format(java.util.Locale.ROOT, "CIRCLE (%.6f %.6f, 100)", casa[0], casa[1])));
        // Every still or slow position with a fix, as if the bike were armed at home.
        for (Position p : posiciones) {
            if (Fijacion.tiene(p) && p.getSpeed() < org.traccar.helper.UnitsConverter.knotsFromKph(15)) {
                assertFalse(ReglasViaje.esSalida(java.util.Optional.of(sitio),
                        ReglasViaje.dentro(List.of(sitio), p.getLatitude(), p.getLongitude()),
                        p.getBoolean(Position.KEY_IGNITION), p.getSpeed()),
                        "a slow position " + p.getDeviceTime() + " fired a departure");
            }
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
