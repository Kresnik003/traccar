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
 * A notification that could not be delivered, waiting for another attempt.
 *
 * Only identifiers are stored, never the formatted text. The message is built
 * again on each attempt, so a held notification goes out with the template that
 * is current, not with a frozen copy - and, more importantly, the queue does not
 * become a plain text log of where people were and what their vehicles did.
 */
@StorageName("nx_avisos_pendientes")
public class AvisoPendiente extends BaseModel {

    private long eventId;

    public long getEventId() {
        return eventId;
    }

    public void setEventId(long eventId) {
        this.eventId = eventId;
    }

    private long positionId;

    public long getPositionId() {
        return positionId;
    }

    public void setPositionId(long positionId) {
        this.positionId = positionId;
    }

    private long userId;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    private long notificationId;

    public long getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(long notificationId) {
        this.notificationId = notificationId;
    }

    private String notificator;

    public String getNotificator() {
        return notificator;
    }

    public void setNotificator(String notificator) {
        this.notificator = notificator;
    }

    private int intentos;

    public int getIntentos() {
        return intentos;
    }

    public void setIntentos(int intentos) {
        this.intentos = intentos;
    }

    private Date creado;

    public Date getCreado() {
        return creado;
    }

    public void setCreado(Date creado) {
        this.creado = creado;
    }

    private Date proximo;

    public Date getProximo() {
        return proximo;
    }

    public void setProximo(Date proximo) {
        this.proximo = proximo;
    }

    private String error;

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

}
