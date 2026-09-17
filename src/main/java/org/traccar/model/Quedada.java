/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.model;

import org.traccar.storage.StorageName;

import java.util.Date;

/**
 * A group ride: over an imported route, on a known circuit, or free.
 *
 * Who watches and what moves are kept apart, in nx_quedada_usuario and
 * nx_quedada_device. In a large event somebody hands out hundreds of trackers
 * that belong to nobody in particular, and the people watching the map carry
 * none, so a single table would need a fake user per tracker.
 *
 * Ids are longs with zero for absent, following the storage convention for
 * properties ending in Id; see Ruta for why a boxed Long does not work here.
 */
@StorageName("nx_quedadas")
public class Quedada extends BaseModel {

    private String nombre;

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    /** carrera, motoclub or rider: who sees whom. */
    private String modalidad;

    public String getModalidad() {
        return modalidad;
    }

    public void setModalidad(String modalidad) {
        this.modalidad = modalidad;
    }

    /** ruta, circuito or libre. */
    private String tipo;

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    private long rutaId;

    public long getRutaId() {
        return rutaId;
    }

    public void setRutaId(long rutaId) {
        this.rutaId = rutaId;
    }

    /** The stable key of a circuit in circuitos.js, never its display name. */
    private String circuito;

    public String getCircuito() {
        return circuito;
    }

    public void setCircuito(String circuito) {
        this.circuito = circuito;
    }

    private Date inicio;

    public Date getInicio() {
        return inicio;
    }

    public void setInicio(Date inicio) {
        this.inicio = inicio;
    }

    private Date fin;

    public Date getFin() {
        return fin;
    }

    public void setFin(Date fin) {
        this.fin = fin;
    }

    /** prevista, en_marcha or terminada. */
    private String estado;

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    private long creadorId;

    public long getCreadorId() {
        return creadorId;
    }

    public void setCreadorId(long creadorId) {
        this.creadorId = creadorId;
    }

    /** What produced the times: circuit or route, finish line, gate radius, timer version. */
    private String entorno;

    public String getEntorno() {
        return entorno;
    }

    public void setEntorno(String entorno) {
        this.entorno = entorno;
    }

    private String entornohash;

    public String getEntornohash() {
        return entornohash;
    }

    public void setEntornohash(String entornohash) {
        this.entornohash = entornohash;
    }

}
