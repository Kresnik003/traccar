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
 * What one member of the club thinks of a campsite, motorhome area, dump point
 * or charger.
 *
 * <p>OpenStreetMap carries the facts - showers, toilets, water, whether it
 * charges - but no opinions, by design. Nor can opinions be borrowed from
 * elsewhere: Park4Night publishes no API, and the Google Places terms require
 * their ratings to be shown on a Google map and forbid storing them, while this
 * product has a map of its own. So the ratings worth having here are the club's,
 * which are also the ones that count: anyone can read a stranger's review, but
 * not one from someone they ride with.
 *
 * <p>Column names are in Spanish because the table is, and the storage layer
 * maps a getter straight onto a column of the same name.
 *
 * <p>The site is an OpenStreetMap id ({@code node/123456}), not a foreign key:
 * the site list is regenerated from an OSM extract, and a key would tie the
 * ratings to that regeneration.
 *
 * <p>Privacy: a rating says "I was here", which is location data about a person,
 * and members are deliberately isolated from each other's whereabouts. The row
 * always knows its author - needed so each person can edit or delete their own -
 * but {@link #getPublica()} decides whether the name is ever shown, it defaults
 * to false, and the form says so before saving.
 */
@StorageName("nx_valoraciones")
public class Valoracion extends BaseModel {

    public static final int MIN_ESTRELLAS = 1;
    public static final int MAX_ESTRELLAS = 5;
    public static final int MAX_COMENTARIO = 1000;

    private String sitio;

    public String getSitio() {
        return sitio;
    }

    public void setSitio(String sitio) {
        this.sitio = sitio;
    }

    private long userId;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    private int estrellas;

    public int getEstrellas() {
        return estrellas;
    }

    public void setEstrellas(int estrellas) {
        this.estrellas = estrellas;
    }

    private String comentario;

    public String getComentario() {
        return comentario;
    }

    public void setComentario(String comentario) {
        this.comentario = comentario;
    }

    private boolean publica;

    public boolean getPublica() {
        return publica;
    }

    public void setPublica(boolean publica) {
        this.publica = publica;
    }

    private Date visitado;

    public Date getVisitado() {
        return visitado;
    }

    public void setVisitado(Date visitado) {
        this.visitado = visitado;
    }

    private Date creada;

    public Date getCreada() {
        return creada;
    }

    public void setCreada(Date creada) {
        this.creada = creada;
    }

    private Date modificada;

    public Date getModificada() {
        return modificada;
    }

    public void setModificada(Date modificada) {
        this.modificada = modificada;
    }
}
