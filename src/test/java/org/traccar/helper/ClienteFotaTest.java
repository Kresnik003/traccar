package org.traccar.helper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClienteFotaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String EQUIPO = """
            {"imei":352093081234567,"model":"FTC880","current_firmware":"3.0.7",
             "current_configuration":null,"activity_status":"Online","seen_at":"2026-09-27 21:43:30",
             "task_queue":"Pending","iccid":"8934000000000000000","imsi":"214000000000000",
             "gsm_number":"600000000",
             "next_task":{"type":"RxConfiguration","status_name":"Pending","created_at":"2026-09-04 16:31:05"}}
            """;

    private static String tareas(int cuantas) {
        StringBuilder s = new StringBuilder("{\"data\":[");
        for (int i = 1; i <= cuantas; i++) {
            s.append(i > 1 ? "," : "").append("{\"type\":\"TxConfiguration\",\"status_name\":\"Cancelled\",")
                    .append("\"created_at\":\"2026-0").append(i).append("-01 10:00:00\",\"file\":{\"name\":\"c")
                    .append(i).append(".cfg\"}}");
        }
        return s.append("]}").toString();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testResumenSinDatosDeLaTarjetaYConLasTareasMasNuevasPrimero() throws Exception {
        var datos = ClienteFota.resumen(JSON.readTree(EQUIPO), JSON.readTree(tareas(7)));
        assertEquals("FTC880", datos.get("modelo"));
        assertEquals("3.0.7", datos.get("firmware"));
        assertNull(datos.get("configuracion"));
        assertEquals("Pending", datos.get("cola"));
        assertEquals("RxConfiguration", ((Map<String, Object>) datos.get("siguiente")).get("tipo"));
        var lista = (List<Map<String, Object>>) datos.get("tareas");
        assertEquals(5, lista.size());
        assertEquals("2026-07-01 10:00:00", lista.get(0).get("creada"));
        assertEquals("c7.cfg", lista.get(0).get("fichero"));
        String todo = JSON.writeValueAsString(datos);
        assertFalse(todo.contains("8934000000000000000") || todo.contains("214000000000000")
                || todo.contains("600000000"), "ni ICCID, ni IMSI, ni teléfono");
    }

    @Test
    public void testSoloGetConTokenCacheYNoEsta() throws Exception {
        List<String> pedidas = new ArrayList<>();
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", intercambio -> {
            pedidas.add(intercambio.getRequestMethod() + " " + intercambio.getRequestURI()
                    + " " + intercambio.getRequestHeaders().getFirst("Authorization"));
            String ruta = intercambio.getRequestURI().getPath();
            String cuerpo;
            int codigo = 200;
            if (ruta.equals("/devices/352093081234567")) {
                cuerpo = EQUIPO;
            } else if (ruta.equals("/tasks")) {
                cuerpo = tareas(2);
            } else {
                codigo = 404;
                cuerpo = "{}";
            }
            byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
            intercambio.sendResponseHeaders(codigo, bytes.length);
            intercambio.getResponseBody().write(bytes);
            intercambio.close();
        });
        servidor.start();
        try {
            Config config = new Config();
            config.setString(Keys.FOTA_URL, "http://127.0.0.1:" + servidor.getAddress().getPort() + "/");
            config.setString(Keys.FOTA_TOKEN, "1|secreto");
            ClienteFota cliente = new ClienteFota(config);
            assertTrue(cliente.activo());

            assertEquals("FTC880", cliente.estado("352093081234567").get("modelo"));
            assertEquals("FTC880", cliente.estado("352093081234567").get("modelo"));
            assertEquals(2, pedidas.size(), "la segunda vez sale de la caché: " + pedidas);
            assertTrue(pedidas.get(1).startsWith("GET /tasks?device_imei=352093081234567"), pedidas.get(1));
            assertTrue(pedidas.stream().allMatch(p -> p.startsWith("GET ") && p.endsWith("Bearer 1|secreto")));

            assertNull(cliente.estado("352093089999999"), "no está en la cuenta");
            assertNull(cliente.estado("../devices"), "lo que no es un IMEI ni se pregunta");
            assertEquals(3, pedidas.size());
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    public void testSinTokenNoEstaActivo() {
        assertFalse(new ClienteFota(new Config()).activo());
    }

}
