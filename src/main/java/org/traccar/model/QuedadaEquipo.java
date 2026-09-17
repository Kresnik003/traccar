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
 * A tracker taking part in a group ride, with the number it carries.
 *
 * The number lives here and not on the device because it belongs to the
 * participation: the same tracker is 47 in one race and 112 in the next.
 */
@StorageName("nx_quedada_device")
public class QuedadaEquipo extends BaseModel {

    private long quedadaId;

    public long getQuedadaId() {
        return quedadaId;
    }

    public void setQuedadaId(long quedadaId) {
        this.quedadaId = quedadaId;
    }

    private long deviceId;

    public long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(long deviceId) {
        this.deviceId = deviceId;
    }

    private String dorsal;

    public String getDorsal() {
        return dorsal;
    }

    public void setDorsal(String dorsal) {
        this.dorsal = dorsal;
    }

}
