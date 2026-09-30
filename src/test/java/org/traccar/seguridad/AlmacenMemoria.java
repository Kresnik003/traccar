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

import org.traccar.model.BaseModel;
import org.traccar.model.Guardia;
import org.traccar.model.Permission;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * A storage that keeps what it is given and gives back copies, as a database
 * does. The crash guard's tests with mocks handed back the very object the code
 * changed, so a write that never happened looked done; and a restart - a new
 * guardian over the same database - could not be tried at all.
 *
 * It understands the queries the safety block makes: equals, between, and, or,
 * and an order with a limit. Anything else fails the test.
 */
class AlmacenMemoria extends Storage {

    private final Map<Class<?>, Map<Long, Object>> tablas = new ConcurrentHashMap<>();
    private final AtomicLong siguiente = new AtomicLong(1000);
    /** While set, every call fails as a database that went away. */
    private final AtomicBoolean caida = new AtomicBoolean();
    /** While set, only writes fail. */
    private final AtomicBoolean escriturasCaidas = new AtomicBoolean();
    /** While not null, every call hangs on it, as MySQL hung with the connections taken. */
    private volatile CountDownLatch colgada;

    void caida(boolean valor) {
        caida.set(valor);
    }

    void escriturasCaidas(boolean valor) {
        escriturasCaidas.set(valor);
    }

    void colgada(CountDownLatch cuelgue) {
        colgada = cuelgue;
    }

    <T extends BaseModel> T poner(T objeto) {
        if (objeto.getId() == 0) {
            objeto.setId(siguiente.incrementAndGet());
        }
        tablas.computeIfAbsent(objeto.getClass(), c -> new ConcurrentHashMap<>()).put(objeto.getId(), copia(objeto));
        return objeto;
    }

    <T> T leer(Class<T> clazz, long id) {
        Object objeto = tablas.getOrDefault(clazz, Map.of()).get(id);
        return objeto == null ? null : clazz.cast(copia(objeto));
    }

    private void comprobar() throws StorageException {
        CountDownLatch cuelgue = colgada;
        if (cuelgue != null) {
            try {
                cuelgue.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StorageException("interrumpida");
            }
        }
        if (caida.get()) {
            throw new StorageException("la base no contesta");
        }
    }

    @Override
    public <T> List<T> getObjects(Class<T> clazz, Request request) throws StorageException {
        comprobar();
        List<T> filas = new ArrayList<>();
        for (Object objeto : tablas.getOrDefault(clazz, Map.of()).values()) {
            if (request.getCondition() == null || cumple(request.getCondition(), objeto)) {
                filas.add(clazz.cast(copia(objeto)));
            }
        }
        Order order = request.getOrder();
        if (order != null) {
            Comparator<T> orden = Comparator.comparing(o -> (Comparable) valor(o, order.getColumn()));
            filas.sort(order.getDescending() ? orden.reversed() : orden);
            if (order.getLimit() > 0 && filas.size() > order.getLimit()) {
                filas = new ArrayList<>(filas.subList(0, order.getLimit()));
            }
        }
        return filas;
    }

    @Override
    public <T> Stream<T> getObjectsStream(Class<T> clazz, Request request) throws StorageException {
        return getObjects(clazz, request).stream();
    }

    @Override
    public <T> long addObject(T entity, Request request) throws StorageException {
        comprobar();
        if (escriturasCaidas.get()) {
            throw new StorageException("no se pudo escribir");
        }
        BaseModel modelo = (BaseModel) copia(entity);
        modelo.setId(siguiente.incrementAndGet());
        tablas.computeIfAbsent(entity.getClass(), c -> new ConcurrentHashMap<>()).put(modelo.getId(), modelo);
        return modelo.getId();
    }

    @Override
    public <T> void updateObject(T entity, Request request) throws StorageException {
        comprobar();
        if (escriturasCaidas.get()) {
            throw new StorageException("no se pudo escribir");
        }
        long id = ((Number) ((Condition.Equals) request.getCondition()).getValue()).longValue();
        Object guardado = tablas.getOrDefault(entity.getClass(), Map.of()).get(id);
        if (guardado == null) {
            return;
        }
        Columns columnas = request.getColumns();
        for (String columna : columnas.getColumns(entity.getClass(), "set")) {
            copiarCampo(entity, guardado, columna);
        }
    }

    @Override
    public void removeObject(Class<?> clazz, Request request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Permission> getPermissions(
            Class<? extends BaseModel> ownerClass, long ownerId,
            Class<? extends BaseModel> propertyClass, long propertyId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addPermission(Permission permission) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removePermission(Permission permission) {
        throw new UnsupportedOperationException();
    }

    private static boolean cumple(Condition condition, Object objeto) {
        if (condition instanceof Condition.And and) {
            return cumple(and.getFirst(), objeto) && cumple(and.getSecond(), objeto);
        }
        if (condition instanceof Condition.Or or) {
            return cumple(or.getFirst(), objeto) || cumple(or.getSecond(), objeto);
        }
        if (condition instanceof Condition.Equals equals) {
            return igual(valor(objeto, equals.getColumn()), equals.getValue());
        }
        if (condition instanceof Condition.Between between) {
            Date fecha = (Date) valor(objeto, between.getColumn());
            return fecha != null && !fecha.before((Date) between.getFromValue())
                    && !fecha.after((Date) between.getToValue());
        }
        throw new IllegalArgumentException("Unexpected query: " + condition);
    }

    private static boolean igual(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.longValue() == y.longValue();
        }
        return a != null && a.equals(b);
    }

    private static Method getter(Class<?> clazz, String columna) {
        for (Method method : clazz.getMethods()) {
            String nombre = method.getName();
            if (method.getParameterCount() == 0
                    && (nombre.equalsIgnoreCase("get" + columna) || nombre.equalsIgnoreCase("is" + columna))) {
                return method;
            }
        }
        throw new IllegalArgumentException("No column " + columna + " in " + clazz.getSimpleName());
    }

    private static Object valor(Object objeto, String columna) {
        try {
            return getter(objeto.getClass(), columna).invoke(objeto);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void copiarCampo(Object desde, Object hacia, String columna) {
        try {
            Method get = getter(desde.getClass(), columna);
            for (Method set : hacia.getClass().getMethods()) {
                if (set.getName().equalsIgnoreCase("set" + columna) && set.getParameterCount() == 1) {
                    set.invoke(hacia, get.invoke(desde));
                    return;
                }
            }
            throw new IllegalArgumentException("No setter for " + columna);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new object with the same values, as a row read again. */
    private static Object copia(Object origen) {
        if (origen instanceof Guardia g) {
            Guardia guardia = new Guardia();
            guardia.setId(g.getId());
            guardia.setDeviceId(g.getDeviceId());
            guardia.setEventId(g.getEventId());
            guardia.setPositionId(g.getPositionId());
            guardia.setGolpe(g.getGolpe());
            guardia.setLimite(g.getLimite());
            guardia.setLatitud(g.getLatitud());
            guardia.setLongitud(g.getLongitud());
            guardia.setEstado(g.getEstado());
            guardia.setEnlace(g.getEnlace());
            guardia.setResueltos(g.getResueltos());
            return guardia;
        }
        // Positions and devices are only read by the guardian, never changed.
        return origen;
    }

}
