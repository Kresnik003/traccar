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
package org.traccar.model;

import org.traccar.storage.StorageName;

import java.util.Date;

/**
 * A trip the rider's contacts follow (improvement 115), from leaving one of the
 * rider's places to arriving at another, or to the "I've arrived" button. It
 * keeps the temporary user behind its link, which is deleted when it closes.
 */
@StorageName("nx_viajes")
public class Viaje extends BaseModel {

    private long deviceId;

    public long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(long deviceId) {
        this.deviceId = deviceId;
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

    private String motivoFin;

    public String getMotivoFin() {
        return motivoFin;
    }

    public void setMotivoFin(String motivoFin) {
        this.motivoFin = motivoFin;
    }

    private String origen;

    public String getOrigen() {
        return origen;
    }

    public void setOrigen(String origen) {
        this.origen = origen;
    }

    private long shareUserId;

    public long getShareUserId() {
        return shareUserId;
    }

    public void setShareUserId(long shareUserId) {
        this.shareUserId = shareUserId;
    }

    private String enlace;

    public String getEnlace() {
        return enlace;
    }

    public void setEnlace(String enlace) {
        this.enlace = enlace;
    }

}
