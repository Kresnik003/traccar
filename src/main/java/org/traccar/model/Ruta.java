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
 * A route of a user's own, imported from a GPX file.
 *
 * Column names are in Spanish because the table is, and the storage layer maps
 * a getter straight onto a column of the same name. Renaming either side would
 * mean a mapping layer that exists only to translate, and the first time it is
 * out of step the query fails at runtime with a column that is not there.
 *
 * A route has exactly one owner and no permission table. Sharing a route copies
 * it, so the recipient gets a row of their own: that is what keeps the author
 * from changing, after the fact and in silence, the terrain of every meet that
 * points at it.
 */
@StorageName("nx_rutas")
public class Ruta extends BaseModel {

    private String nombre;

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    private String descripcion;

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    private long creadorId;

    public long getCreadorId() {
        return creadorId;
    }

    public void setCreadorId(long creadorId) {
        this.creadorId = creadorId;
    }

    private String trazado;

    public String getTrazado() {
        return trazado;
    }

    public void setTrazado(String trazado) {
        this.trazado = trazado;
    }

    private double distancia;

    public double getDistancia() {
        return distancia;
    }

    public void setDistancia(double distancia) {
        this.distancia = distancia;
    }

    private double centrolat;

    public double getCentrolat() {
        return centrolat;
    }

    public void setCentrolat(double centrolat) {
        this.centrolat = centrolat;
    }

    private double centrolon;

    public double getCentrolon() {
        return centrolon;
    }

    public void setCentrolon(double centrolon) {
        this.centrolon = centrolon;
    }

    private double alcance;

    public double getAlcance() {
        return alcance;
    }

    public void setAlcance(double alcance) {
        this.alcance = alcance;
    }

    private Date subida;

    public Date getSubida() {
        return subida;
    }

    public void setSubida(Date subida) {
        this.subida = subida;
    }

    /*
     * ZERO MEANS NO ORIGIN, and it is a long rather than a Long on purpose.
     *
     * The storage layer writes a boxed null as the four letters n-u-l-l, and
     * MySQL says so: "Incorrect integer value: 'null' for column origenid".
     * Traccar's own convention is a primitive with zero standing for absent,
     * turned into SQL NULL when the property name ends in Id - which is why
     * this is origenId while the column is origenid, matched without regard to
     * case by the database.
     *
     * Going the other way a SQL NULL reads back as zero, so the round trip is
     * symmetric and neither end has to remember which side it is on.
     */
    private long origenId;

    public long getOrigenId() {
        return origenId;
    }

    public void setOrigenId(long origenId) {
        this.origenId = origenId;
    }

    private String origenautor;

    public String getOrigenautor() {
        return origenautor;
    }

    public void setOrigenautor(String origenautor) {
        this.origenautor = origenautor;
    }

}
