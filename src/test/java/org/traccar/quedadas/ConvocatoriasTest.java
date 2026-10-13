package org.traccar.quedadas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.model.Quedada;
import org.traccar.storage.DatabaseStorage;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;
import org.traccar.telegram.ClienteTelegram;
import org.traccar.telegram.RespuestaTelegram;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The call to ride (improvement 116) and "I need to stop" (112), against a real
 * database: what matters here is which rows a query finds, and a mock would
 * only find what the test expects.
 *
 * The bench: ride 1 «Ruta del Eume», organized by Juan (2). Esther (6) rides
 * the Ducati (8) and Adrián (5) the FMT100 (7); Pedro (9) is in another club
 * and sees only his own bike (20). Juan and Esther have Telegram.
 */
public class ConvocatoriasTest {

    private static final String CODIGO = "abcdefghijklmnopqrstuv";

    private JdbcDataSource dataSource;
    private Storage storage;
    private ClienteTelegram cliente;
    private Convocatorias convocatorias;
    private final List<Long> esperas = new ArrayList<>();
    private final AtomicLong ahora = new AtomicLong(1_000_000);

    /** Who sees which tracker, as Traccar would decide it. */
    private final Map<Long, Set<Long>> ve = Map.of(
            2L, Set.of(8L, 7L), 6L, Set.of(8L), 5L, Set.of(7L), 9L, Set.of(20L));
    private final Convocatorias.Permiso permiso = (userId, deviceId) -> {
        if (!ve.getOrDefault(userId, Set.of()).contains(deviceId)) {
            throw new SecurityException("no");
        }
    };

    private void sql(String... sentencias) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String sentencia : sentencias) {
                statement.execute(sentencia);
            }
        }
    }

    private long contar(String consulta) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(consulta)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @BeforeEach
    public void preparar() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:convocatorias;DB_CLOSE_DELAY=-1");
        sql("DROP ALL OBJECTS",
                "CREATE TABLE tc_users (id INT PRIMARY KEY, name VARCHAR(128), attributes VARCHAR(4000),"
                        + " administrator BOOLEAN DEFAULT FALSE, disabled BOOLEAN DEFAULT FALSE,"
                        + " temporary BOOLEAN DEFAULT FALSE)",
                "CREATE TABLE tc_devices (id INT PRIMARY KEY, name VARCHAR(128), positionid INT,"
                        + " attributes VARCHAR(4000))",
                "CREATE TABLE tc_positions (id INT PRIMARY KEY, deviceid INT, latitude DOUBLE, longitude DOUBLE,"
                        + " valid BOOLEAN, attributes VARCHAR(4000))",
                "CREATE TABLE nx_rutas (id INT AUTO_INCREMENT PRIMARY KEY, nombre VARCHAR(128),"
                        + " descripcion VARCHAR(512), creadorid INT, trazado CLOB, distancia DOUBLE,"
                        + " centrolat DOUBLE, centrolon DOUBLE, alcance DOUBLE)",
                "CREATE TABLE nx_quedadas (id INT AUTO_INCREMENT PRIMARY KEY, nombre VARCHAR(128),"
                        + " modalidad VARCHAR(16), tipo VARCHAR(16), rutaid INT, circuito VARCHAR(64),"
                        + " inicio TIMESTAMP, fin TIMESTAMP, estado VARCHAR(16), creadorid INT, entorno CLOB,"
                        + " entornohash VARCHAR(16), briefing VARCHAR(2000), salidanombre VARCHAR(128),"
                        + " salidalat DOUBLE, salidalon DOUBLE, convocatoria VARCHAR(32) UNIQUE)",
                "CREATE TABLE nx_quedada_device (quedadaid INT, deviceid INT, dorsal VARCHAR(8))",
                "CREATE TABLE nx_quedada_usuario (quedadaid INT, userid INT)",
                "INSERT INTO tc_users (id, name, attributes) VALUES"
                        + " (2, 'Juan', '{\"telegramChatId\":\"900\"}'),"
                        + " (6, 'Esther', '{\"telegramChatId\":\"906\"}'),"
                        + " (5, 'Adrián', '{}'),"
                        + " (9, 'Pedro', '{\"telegramChatId\":\"909\"}')",
                "INSERT INTO tc_devices (id, name, positionid, attributes) VALUES (8, 'Ducati', 100, '{}'),"
                        + " (7, 'FMT100', 0, '{}'), (20, 'Moto de Pedro', 0, '{}')",
                "INSERT INTO tc_positions (id, deviceid, latitude, longitude, valid, attributes) VALUES"
                        + " (100, 8, 43.4877, -8.2237, TRUE, '{}')",
                "INSERT INTO nx_rutas (id, nombre, creadorid, trazado, distancia) VALUES"
                        + " (3, 'Fragas do Eume', 2, '[[43.4,-8.1],[43.5,-8.0]]', 87000)",
                "INSERT INTO nx_quedadas (id, nombre, modalidad, tipo, rutaid, inicio, estado, creadorid,"
                        + " briefing, salidanombre, salidalat, salidalon, convocatoria) VALUES"
                        + " (1, 'Ruta del Eume', 'motoclub', 'ruta', 3, TIMESTAMP '2026-10-04 09:30:00',"
                        + " 'prevista', 2, 'Café en Pontedeume', 'Gasolinera de Xuvia', 43.49, -8.22, '" + CODIGO + "')");
        storage = new DatabaseStorage(new Config(), dataSource, new ObjectMapper());
        cliente = mock(ClienteTelegram.class);
        when(cliente.enviar(anyString(), anyString(), anyList())).thenReturn(new RespuestaTelegram(200, "ok"));
        // Sends run inline so the test sees them; retries record the wait instead of sleeping.
        convocatorias = new Convocatorias(storage, cliente, Runnable::run, esperas::add, ahora::get);
    }

    private Quedada quedada() throws StorageException {
        return storage.getObject(Quedada.class, new Request(new Columns.All(), new Condition.Equals("id", 1)));
    }

    // ---- The public link ----------------------------------------------------

    @Test
    public void thePublicLinkShowsWhatWasPublishedAndNobody() throws Exception {
        sql("INSERT INTO nx_quedada_device (quedadaid, deviceid) VALUES (1, 8)",
                "INSERT INTO nx_quedada_usuario (quedadaid, userid) VALUES (1, 6)");
        Map<String, Object> publica = convocatorias.publica(CODIGO).orElseThrow();
        assertEquals("Ruta del Eume", publica.get("nombre"));
        assertEquals("Café en Pontedeume", publica.get("briefing"));
        assertEquals(Map.of("nombre", "Gasolinera de Xuvia", "lat", 43.49, "lon", -8.22), publica.get("salida"));
        assertEquals(87000.0, ((Map<?, ?>) publica.get("ruta")).get("distancia"));
        assertEquals(1, publica.get("apuntados"));
        // Nothing that says who: no ids, no names, no trackers, no organizer.
        String todo = publica.toString();
        for (String prohibido : List.of("Juan", "Esther", "Ducati", "creador", "deviceId", "userId", CODIGO)) {
            assertFalse(todo.contains(prohibido), prohibido + " in " + todo);
        }
    }

    @Test
    public void aWrongRenewedOrFinishedCodeOpensNothing() throws Exception {
        assertTrue(convocatorias.publica("abcdefghijklmnopqrstuw").isEmpty(), "other code");
        assertTrue(convocatorias.publica("abc").isEmpty(), "wrong format");
        assertTrue(convocatorias.publica("abcdefghijklmnopqrst%'").isEmpty(), "crafted");
        assertTrue(convocatorias.publica(null).isEmpty(), "none");
        sql("UPDATE nx_quedadas SET estado = 'terminada'");
        assertTrue(convocatorias.publica(CODIGO).isEmpty(), "finished");
    }

    @Test
    public void aNewCodeIs22UrlSafeCharactersAndNeverTheSame() {
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String codigo = convocatorias.nuevoCodigo();
            assertTrue(Convocatorias.FORMATO.matcher(codigo).matches(), codigo);
            assertTrue(vistos.add(codigo), "repeated " + codigo);
        }
    }

    // ---- Signing up ---------------------------------------------------------

    @Test
    public void signingUpPutsTheOwnBikeInTheRideAndLetsTheRiderWatchIt() throws Exception {
        Convocatorias.Apuntado apuntado = convocatorias.apuntar(6, CODIGO, 8, permiso).orElseThrow();
        assertTrue(apuntado.nuevo());
        assertEquals(1, contar("SELECT COUNT(*) FROM nx_quedada_device WHERE quedadaid = 1 AND deviceid = 8"));
        assertEquals(1, contar("SELECT COUNT(*) FROM nx_quedada_usuario WHERE quedadaid = 1 AND userid = 6"));
    }

    @Test
    public void signingUpTwiceIsOnce() throws Exception {
        convocatorias.apuntar(6, CODIGO, 8, permiso);
        Convocatorias.Apuntado otra = convocatorias.apuntar(6, CODIGO, 8, permiso).orElseThrow();
        assertFalse(otra.nuevo());
        assertEquals(1, contar("SELECT COUNT(*) FROM nx_quedada_device WHERE deviceid = 8"));
        assertEquals(1, contar("SELECT COUNT(*) FROM nx_quedada_usuario WHERE userid = 6"));
    }

    @Test
    public void nobodySignsUpSomebodyElsesBike() throws Exception {
        // Pedro, from another club, with the link: not with Esther's Ducati.
        assertThrows(SecurityException.class, () -> convocatorias.apuntar(9, CODIGO, 8, permiso));
        assertEquals(0, contar("SELECT COUNT(*) FROM nx_quedada_device"));
        assertEquals(0, contar("SELECT COUNT(*) FROM nx_quedada_usuario"));
    }

    @Test
    public void theOrganizerIsNotAddedAsAWatcherOfTheirOwnRide() throws Exception {
        convocatorias.apuntar(2, CODIGO, 7, permiso);
        assertEquals(1, contar("SELECT COUNT(*) FROM nx_quedada_device WHERE deviceid = 7"));
        assertEquals(0, contar("SELECT COUNT(*) FROM nx_quedada_usuario"));
    }

    @Test
    public void aFinishedOrWrongCodeSignsNobodyUp() throws Exception {
        assertTrue(convocatorias.apuntar(6, "abcdefghijklmnopqrstuw", 8, permiso).isEmpty());
        sql("UPDATE nx_quedadas SET estado = 'terminada'");
        assertTrue(convocatorias.apuntar(6, CODIGO, 8, permiso).isEmpty());
        assertEquals(0, contar("SELECT COUNT(*) FROM nx_quedada_device"));
    }

    // ---- I need to stop -----------------------------------------------------

    private void enMarchaConEstherYAdrian() throws Exception {
        sql("UPDATE nx_quedadas SET estado = 'en_marcha'",
                "INSERT INTO nx_quedada_device (quedadaid, deviceid) VALUES (1, 8), (1, 7)",
                "INSERT INTO nx_quedada_usuario (quedadaid, userid) VALUES (1, 6), (1, 5)");
    }

    @Test
    public void aStopReachesTheOthersAndNotTheOneWhoAsks() throws Exception {
        enMarchaConEstherYAdrian();
        int avisados = convocatorias.parada(6, quedada(), permiso);
        // Juan (organizer) has Telegram; Adrián has none; Esther is who asks.
        assertEquals(1, avisados);
        verify(cliente).enviar(eq("900"), any(), anyList());
        verify(cliente, never()).enviar(eq("906"), any(), anyList());
        verify(cliente).enviar(eq("900"),
                org.mockito.ArgumentMatchers.contains("Esther necesita parar (Ducati)"), anyList());
        verify(cliente).enviar(eq("900"),
                org.mockito.ArgumentMatchers.contains("maps?q=43.487700,-8.223700"), anyList());
    }

    @Test
    public void onlyARiderOfTheRunningRideCanAskItToStop() throws Exception {
        // Not started yet.
        sql("INSERT INTO nx_quedada_device (quedadaid, deviceid) VALUES (1, 8)",
                "INSERT INTO nx_quedada_usuario (quedadaid, userid) VALUES (1, 6)");
        assertThrows(SecurityException.class, () -> convocatorias.parada(6, quedada(), permiso));
        assertFalse(convocatorias.puedePedirParada(6, quedada(), permiso));
        sql("UPDATE nx_quedadas SET estado = 'en_marcha'");
        assertTrue(convocatorias.puedePedirParada(6, quedada(), permiso));
        // Pedro rides nothing in this ride, even if he could see it.
        assertThrows(SecurityException.class, () -> convocatorias.parada(9, quedada(), permiso));
        assertFalse(convocatorias.puedePedirParada(9, quedada(), permiso));
        sql("UPDATE nx_quedadas SET estado = 'terminada'");
        assertThrows(SecurityException.class, () -> convocatorias.parada(6, quedada(), permiso));
        verify(cliente, never()).enviar(anyString(), anyString(), anyList());
    }

    @Test
    public void aSecondStopWithinTwoMinutesIsRefused() throws Exception {
        enMarchaConEstherYAdrian();
        convocatorias.parada(6, quedada(), permiso);
        ahora.addAndGet(Convocatorias.ENTRE_PARADAS - 1);
        assertThrows(Convocatorias.DemasiadoPronto.class, () -> convocatorias.parada(6, quedada(), permiso));
        ahora.addAndGet(1);
        convocatorias.parada(6, quedada(), permiso);
        verify(cliente, times(2)).enviar(eq("900"), any(), anyList());
        // Another rider is not held back by Esther's stop.
        convocatorias.parada(5, quedada(), permiso);
    }

    @Test
    public void aBusyTelegramIsRetriedAndABlockedChatIsNot() throws Exception {
        enMarchaConEstherYAdrian();
        when(cliente.enviar(eq("900"), anyString(), anyList()))
                .thenReturn(new RespuestaTelegram(429, "Too Many Requests"))
                .thenReturn(new RespuestaTelegram(502, "Bad Gateway"))
                .thenReturn(new RespuestaTelegram(200, "ok"));
        convocatorias.parada(6, quedada(), permiso);
        verify(cliente, times(3)).enviar(eq("900"), anyString(), anyList());
        assertEquals(List.of(5_000L, 15_000L), esperas);

        esperas.clear();
        when(cliente.enviar(eq("900"), anyString(), anyList())).thenReturn(new RespuestaTelegram(403, "blocked"));
        ahora.addAndGet(Convocatorias.ENTRE_PARADAS);
        convocatorias.parada(6, quedada(), permiso);
        assertEquals(List.of(), esperas, "a blocked chat is not insisted on");
    }

    @Test
    public void retriesGiveUpAfterThree() throws Exception {
        enMarchaConEstherYAdrian();
        when(cliente.enviar(eq("900"), anyString(), anyList())).thenReturn(new RespuestaTelegram(502, "down"));
        convocatorias.parada(6, quedada(), permiso);
        verify(cliente, times(1 + Convocatorias.ESPERAS_REINTENTO.length)).enviar(eq("900"), anyString(), anyList());
    }

    // ---- What the organizer writes -------------------------------------------

    @Test
    public void theBriefingAndTheMeetingPointAreChecked() {
        Quedada q = new Quedada();
        q.setBriefing("x".repeat(Convocatorias.MAXIMO_BRIEFING + 1));
        assertThrows(IllegalArgumentException.class, () -> Convocatorias.validar(q));
        q.setBriefing("x".repeat(Convocatorias.MAXIMO_BRIEFING));
        Convocatorias.validar(q);
        q.setSalidalat(91);
        assertThrows(IllegalArgumentException.class, () -> Convocatorias.validar(q));
        q.setSalidalat(Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> Convocatorias.validar(q));
        q.setSalidalat(43.49);
        q.setSalidalon(-181);
        assertThrows(IllegalArgumentException.class, () -> Convocatorias.validar(q));
        q.setSalidalon(-8.22);
        q.setSalidanombre("y".repeat(Convocatorias.MAXIMO_SALIDA + 1));
        assertThrows(IllegalArgumentException.class, () -> Convocatorias.validar(q));
    }

}
