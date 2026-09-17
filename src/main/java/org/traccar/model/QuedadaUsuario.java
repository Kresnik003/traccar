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

/**
 * Who may watch a group ride.
 *
 * The table has no id column, so this is always read and written with the id
 * excluded from the column list.
 */
@StorageName("nx_quedada_usuario")
public class QuedadaUsuario extends BaseModel {

    private long quedadaId;

    public long getQuedadaId() {
        return quedadaId;
    }

    public void setQuedadaId(long quedadaId) {
        this.quedadaId = quedadaId;
    }

    private long userId;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

}
