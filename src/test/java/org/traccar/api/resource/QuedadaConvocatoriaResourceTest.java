package org.traccar.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.SecurityContext;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.BaseResource;
import org.traccar.api.security.PermissionsService;
import org.traccar.api.security.UserPrincipal;
import org.traccar.config.Config;
import org.traccar.model.Device;
import org.traccar.model.Quedada;
import org.traccar.quedadas.Convocatorias;
import org.traccar.storage.DatabaseStorage;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;
import org.traccar.telegram.ClienteTelegram;
import org.traccar.telegram.RespuestaTelegram;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Date;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The ride resource around the call to ride: who gets the public code back, a
 * plain save that must not kill the link sent to the group, and the answers of
 * «I need to stop». Juan (2) organizes ride 1; Esther (6) watches it and rides
 * the Ducati (8).
 */
public class QuedadaConvocatoriaResourceTest {

    private JdbcDataSource dataSource;
    private Storage storage;
    private SecurityContext securityContext;
    private QuedadaResource resource;
    private ConvocatoriaResource publico;
    private long usuario;

    private static void poner(Class<?> clase, Object objeto, String campo, Object valor) throws Exception {
        Field field = clase.getDeclaredField(campo);
        field.setAccessible(true);
        field.set(objeto, valor);
    }

    private void sql(String... sentencias) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sentencia : sentencias) {
                statement.execute(sentencia);
            }
        }
    }

    private void como(long userId) {
        usuario = userId;
    }

    @BeforeEach
    public void preparar() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:convocatoriarecurso;DB_CLOSE_DELAY=-1");
        sql("DROP ALL OBJECTS",
                "CREATE TABLE tc_users (id INT PRIMARY KEY, name VARCHAR(128), attributes VARCHAR(4000),"
                        + " administrator BOOLEAN DEFAULT FALSE, disabled BOOLEAN DEFAULT FALSE,"
                        + " temporary BOOLEAN DEFAULT FALSE)",
                "CREATE TABLE tc_devices (id INT PRIMARY KEY, name VARCHAR(128), positionid INT,"
                        + " attributes VARCHAR(4000))",
                "CREATE TABLE nx_rutas (id INT AUTO_INCREMENT PRIMARY KEY, nombre VARCHAR(128), creadorid INT,"
                        + " trazado CLOB, distancia DOUBLE)",
                "CREATE TABLE nx_quedadas (id INT AUTO_INCREMENT PRIMARY KEY, nombre VARCHAR(128),"
                        + " modalidad VARCHAR(16), tipo VARCHAR(16), rutaid INT, circuito VARCHAR(64),"
                        + " inicio TIMESTAMP, fin TIMESTAMP, estado VARCHAR(16), creadorid INT, entorno CLOB,"
                        + " entornohash VARCHAR(16), briefing VARCHAR(2000), salidanombre VARCHAR(128),"
                        + " salidalat DOUBLE, salidalon DOUBLE, convocatoria VARCHAR(32) UNIQUE)",
                "CREATE TABLE nx_quedada_device (quedadaid INT, deviceid INT, dorsal VARCHAR(8))",
                "CREATE TABLE nx_quedada_usuario (quedadaid INT, userid INT)",
                "INSERT INTO tc_users (id, name, attributes) VALUES (2, 'Juan', '{\"telegramChatId\":\"900\"}'),"
                        + " (6, 'Esther', '{}')",
                "INSERT INTO tc_devices (id, name, positionid, attributes) VALUES (8, 'Ducati', 0, '{}')",
                "INSERT INTO nx_quedadas (id, nombre, modalidad, tipo, inicio, estado, creadorid, convocatoria)"
                        + " VALUES (1, 'Ruta del Eume', 'motoclub', 'libre', TIMESTAMP '2026-10-04 09:30:00',"
                        + " 'en_marcha', 2, 'abcdefghijklmnopqrstuv')",
                "INSERT INTO nx_quedada_device (quedadaid, deviceid) VALUES (1, 8)",
                "INSERT INTO nx_quedada_usuario (quedadaid, userid) VALUES (1, 6)",
                // The ride above was inserted with its id: new ones start after it.
                "ALTER TABLE nx_quedadas ALTER COLUMN id RESTART WITH 10");
        storage = new DatabaseStorage(new Config(), dataSource, new ObjectMapper());
        PermissionsService permissionsService = mock(PermissionsService.class);
        when(permissionsService.notAdmin(anyLong())).thenReturn(true);
        Map<Long, Set<Long>> ve = Map.of(2L, Set.of(), 6L, Set.of(8L));
        doAnswer(invocacion -> {
            long userId = invocacion.getArgument(1);
            long deviceId = invocacion.getArgument(2);
            if (!ve.getOrDefault(userId, Set.of()).contains(deviceId)) {
                throw new SecurityException("no");
            }
            return null;
        }).when(permissionsService).checkPermission(eq(Device.class), anyLong(), anyLong());
        securityContext = mock(SecurityContext.class);
        when(securityContext.getUserPrincipal()).thenAnswer(i -> new UserPrincipal(usuario, null));
        ClienteTelegram cliente = mock(ClienteTelegram.class);
        when(cliente.enviar(anyString(), anyString(), anyList())).thenReturn(new RespuestaTelegram(200, "ok"));
        Convocatorias convocatorias = new Convocatorias(storage, cliente,
                java.util.concurrent.Executors.newSingleThreadExecutor());
        resource = new QuedadaResource();
        publico = new ConvocatoriaResource();
        for (BaseResource r : new BaseResource[] {resource, publico}) {
            poner(BaseResource.class, r, "storage", storage);
            poner(BaseResource.class, r, "permissionsService", permissionsService);
            poner(BaseResource.class, r, "securityContext", securityContext);
        }
        poner(QuedadaResource.class, resource, "convocatorias", convocatorias);
        poner(ConvocatoriaResource.class, publico, "convocatorias", convocatorias);
        como(2);
    }

    private String guardado() throws Exception {
        return storage.getObject(Quedada.class, new Request(new Columns.All(), new Condition.Equals("id", 1)))
                .getConvocatoria();
    }

    @Test
    public void onlyTheOrganizerGetsThePublicCodeBack() throws Exception {
        assertEquals("abcdefghijklmnopqrstuv", resource.getSingle(1).getConvocatoria());
        como(6);
        assertNull(resource.getSingle(1).getConvocatoria(), "a watcher could re-share the link");
        assertTrue(resource.get().stream().allMatch(q -> q.getConvocatoria() == null));
    }

    @Test
    public void aPlainSaveKeepsTheLinkAndCannotPlantOne() throws Exception {
        Quedada cambio = resource.getSingle(1);
        cambio.setConvocatoria(null);
        cambio.setBriefing("Café en Pontedeume");
        resource.update(1, cambio);
        assertEquals("abcdefghijklmnopqrstuv", guardado(), "a stale copy killed the link");
        cambio.setConvocatoria("zzzzzzzzzzzzzzzzzzzzzz");
        resource.update(1, cambio);
        assertEquals("abcdefghijklmnopqrstuv", guardado(), "the body chose the code");
    }

    @Test
    public void aNewRideNeverComesWithACode() throws Exception {
        Quedada nueva = new Quedada();
        nueva.setNombre("Otra");
        nueva.setModalidad("motoclub");
        nueva.setTipo("libre");
        nueva.setInicio(new Date());
        nueva.setConvocatoria("zzzzzzzzzzzzzzzzzzzzzz");
        Quedada creada = (Quedada) resource.add(nueva).getEntity();
        assertNull(storage.getObject(Quedada.class, new Request(
                new Columns.All(), new Condition.Equals("id", creada.getId()))).getConvocatoria());
    }

    @Test
    public void renewingTheLinkKillsTheOldOne() throws Exception {
        String nuevo = resource.convocar(1).get("codigo");
        assertNotEquals("abcdefghijklmnopqrstuv", nuevo);
        assertThrows(WebApplicationException.class, () -> publico.get("abcdefghijklmnopqrstuv"));
        assertEquals("Ruta del Eume", publico.get(nuevo).get("nombre"));
        resource.desconvocar(1);
        assertThrows(WebApplicationException.class, () -> publico.get(nuevo));
        como(6);
        // A watcher cannot make or remove the link.
        assertEquals(403, assertThrows(WebApplicationException.class, () -> resource.convocar(1))
                .getResponse().getStatus());
    }

    @Test
    public void onlyTheRiderSeesTheStopButtonAndAskingTwiceIs429() throws Exception {
        assertEquals(false, resource.getSingle(1).getCapacidades().get("parada"), "Juan rides nothing");
        assertEquals(403, assertThrows(WebApplicationException.class, () -> resource.parada(1))
                .getResponse().getStatus());
        como(6);
        assertEquals(true, resource.getSingle(1).getCapacidades().get("parada"));
        assertEquals(1, resource.parada(1).get("avisados"));
        assertEquals(429, assertThrows(WebApplicationException.class, () -> resource.parada(1))
                .getResponse().getStatus());
    }

    @Test
    public void aStrangerCannotAskAStopInARideThatIsNotTheirs() throws Exception {
        como(9);
        // Not the organizer, not watching: the ride does not exist for him.
        assertEquals(404, assertThrows(WebApplicationException.class, () -> resource.parada(1))
                .getResponse().getStatus());
    }

}
