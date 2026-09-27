package org.traccar.geocoder;

import org.junit.jupiter.api.Test;
import org.traccar.database.StatisticsManager;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CacheDireccionesTest {

    private static final class Memoria implements CacheDirecciones.Almacen {
        private final Map<String, String> filas = new HashMap<>();
        private boolean roto;

        @Override
        public String leer(String clave) throws SQLException {
            if (roto) {
                throw new SQLException("caída");
            }
            return filas.get(clave);
        }

        @Override
        public void guardar(String clave, String direccion) throws SQLException {
            if (roto) {
                throw new SQLException("caída");
            }
            filas.putIfAbsent(clave, direccion);
        }
    }

    private static final class Contador implements Geocoder {
        private final AtomicInteger preguntas = new AtomicInteger();
        private final String respuesta;

        Contador(String respuesta) {
            this.respuesta = respuesta;
        }

        @Override
        public String getAddress(double latitude, double longitude, ReverseGeocoderCallback callback) {
            preguntas.incrementAndGet();
            if (callback != null) {
                callback.onSuccess(respuesta);
            }
            return respuesta;
        }

        @Override
        public void setStatisticsManager(StatisticsManager statisticsManager) {
        }
    }

    @Test
    public void testClave() {
        assertEquals("43.4833,-8.2019", CacheDirecciones.clave(43.48332, -8.20194));
        assertEquals(CacheDirecciones.clave(43.48332, -8.20194), CacheDirecciones.clave(43.48334, -8.20191),
                "dos posiciones a unos metros son el mismo sitio");
    }

    @Test
    public void testSoloSePreguntaUnaVez() {
        Memoria memoria = new Memoria();
        Contador nominatim = new Contador("Rúa Real, Ferrol");
        CacheDirecciones cache = new CacheDirecciones(memoria, nominatim);
        assertEquals("Rúa Real, Ferrol", cache.getAddress(43.48332, -8.20194, null));
        assertEquals("Rúa Real, Ferrol", cache.getAddress(43.48334, -8.20191, null));
        assertEquals(1, nominatim.preguntas.get(), "la segunda sale de la tabla");
        // Y sobrevive a un «reinicio»: otra caché sobre la misma tabla no pregunta.
        Contador otro = new Contador("no debería preguntarse");
        assertEquals("Rúa Real, Ferrol", new CacheDirecciones(memoria, otro).getAddress(43.48332, -8.20194, null));
        assertEquals(0, otro.preguntas.get());
    }

    @Test
    public void testPorLaViaAsincrona() {
        Memoria memoria = new Memoria();
        Contador nominatim = new Contador("Porto de Ferrol");
        CacheDirecciones cache = new CacheDirecciones(memoria, nominatim);
        AtomicReference<String> recibida = new AtomicReference<>();
        cache.getAddress(43.47, -8.25, new ReverseGeocoder());
        cache.getAddress(43.47, -8.25, new ReverseGeocoder() {
            @Override
            public void onSuccess(String address) {
                recibida.set(address);
            }
        });
        assertEquals("Porto de Ferrol", recibida.get());
        assertEquals(1, nominatim.preguntas.get(), "lo que llega por el callback también se guarda");
    }

    @Test
    public void testUnaTablaCaidaNoParaNada() {
        Memoria memoria = new Memoria();
        memoria.roto = true;
        Contador nominatim = new Contador("Narón");
        assertEquals("Narón", new CacheDirecciones(memoria, nominatim).getAddress(43.5, -8.2, null));
    }

    @Test
    public void testSinDireccionNoSeGuarda() {
        Memoria memoria = new Memoria();
        CacheDirecciones cache = new CacheDirecciones(memoria, new Contador(null));
        assertNull(cache.getAddress(0, 0, null));
        assertEquals(0, memoria.filas.size());
    }

    private static class ReverseGeocoder implements Geocoder.ReverseGeocoderCallback {
        @Override
        public void onSuccess(String address) {
        }

        @Override
        public void onFailure(Throwable e) {
        }
    }

}
