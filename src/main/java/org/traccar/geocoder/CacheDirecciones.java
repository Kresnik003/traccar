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
package org.traccar.geocoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.database.StatisticsManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * A geocoding cache that survives a restart (improvement 19).
 *
 * Traccar's cache lives in memory and is keyed by the EXACT coordinates, so it
 * only hits when a position repeats to the last decimal, and it is thrown away
 * on every restart. Nominatim is then asked again for the same places, and the
 * log fills with «429 Too Many Requests».
 *
 * Here the coordinates are rounded to four decimals - about 11 m, in line with
 * the {@code geocoder.reuseDistance} of 10 m measured on a real ride - and the
 * address is kept in {@code nx_direcciones}. Only a miss goes out, and what
 * comes back is stored, also through the asynchronous path the pipeline uses.
 * A cache that fails never stops the geocoding: it just asks outside.
 */
public class CacheDirecciones implements Geocoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheDirecciones.class);

    /** Where the addresses live; an interface so the logic can be tested without a database. */
    public interface Almacen {
        String leer(String clave) throws SQLException;

        void guardar(String clave, String direccion) throws SQLException;
    }

    private final Almacen almacen;
    private final Geocoder delegate;

    public CacheDirecciones(Almacen almacen, Geocoder delegate) {
        this.almacen = almacen;
        this.delegate = delegate;
    }

    public CacheDirecciones(DataSource dataSource, Geocoder delegate) {
        this(new Tabla(dataSource), delegate);
    }

    /** The key: both coordinates rounded to four decimals, about 11 m. */
    static String clave(double latitude, double longitude) {
        return String.format(Locale.ROOT, "%.4f,%.4f", latitude, longitude);
    }

    private String leer(String clave) {
        try {
            return almacen.leer(clave);
        } catch (SQLException e) {
            LOGGER.warn("Address cache read failed", e);
            return null;
        }
    }

    private void guardar(String clave, String direccion) {
        if (direccion == null || direccion.isEmpty()) {
            return;
        }
        try {
            almacen.guardar(clave, direccion);
        } catch (SQLException e) {
            LOGGER.warn("Address cache write failed", e);
        }
    }

    @Override
    public String getAddress(double latitude, double longitude, ReverseGeocoderCallback callback) {
        String clave = clave(latitude, longitude);
        String guardada = leer(clave);
        if (guardada != null) {
            if (callback != null) {
                callback.onSuccess(guardada);
            }
            return guardada;
        }
        if (callback == null) {
            String direccion = delegate.getAddress(latitude, longitude, null);
            guardar(clave, direccion);
            return direccion;
        }
        return delegate.getAddress(latitude, longitude, new ReverseGeocoderCallback() {
            @Override
            public void onSuccess(String address) {
                guardar(clave, address);
                callback.onSuccess(address);
            }

            @Override
            public void onFailure(Throwable e) {
                callback.onFailure(e);
            }
        });
    }

    @Override
    public void setStatisticsManager(StatisticsManager statisticsManager) {
        delegate.setStatisticsManager(statisticsManager);
    }

    static final class Tabla implements Almacen {

        /** The width of nx_direcciones.direccion. */
        private static final int ANCHO = 512;

        private final DataSource dataSource;

        Tabla(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public String leer(String clave) throws SQLException {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT direccion FROM nx_direcciones WHERE clave = ?")) {
                statement.setString(1, clave);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        }

        @Override
        public void guardar(String clave, String direccion) throws SQLException {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO nx_direcciones (clave, direccion, creada) VALUES (?, ?, ?)")) {
                statement.setString(1, clave);
                statement.setString(2, direccion.length() > ANCHO ? direccion.substring(0, ANCHO) : direccion);
                statement.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
                statement.executeUpdate();
            } catch (SQLIntegrityConstraintViolationException e) {
                // Two positions of the same place at once: the first one stays.
            }
        }
    }

}
