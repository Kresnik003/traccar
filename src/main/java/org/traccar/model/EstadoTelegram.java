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

/**
 * The only row of nx_telegram: the next update the bot's inbox has to read, so a
 * restart does not process a message twice.
 */
@StorageName("nx_telegram")
public class EstadoTelegram extends BaseModel {

    public static final long FILA = 1;

    private long ultimo;

    public long getUltimo() {
        return ultimo;
    }

    public void setUltimo(long ultimo) {
        this.ultimo = ultimo;
    }

}
