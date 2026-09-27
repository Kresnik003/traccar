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
package org.traccar.handler;

import org.traccar.model.Position;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last supply voltage of each device, for the frames that come without one
 * (improvement 15).
 *
 * The context of an alarm - moving, parked, device disconnected - comes from
 * the fix and, without a fix, from {@code power}. The accident frames of the
 * FTC880 come in a burst with neither: 73 of 1.466 alarms, ALL of them
 * accidents, had no context, and those are the ones that matter most. The
 * previous frame did not help either ({@code lastAttributes} covered 8 of 73):
 * it belongs to the same burst.
 *
 * So the last voltage seen is remembered per device and, if a frame arrives
 * without {@code power}, it gets {@code ultimaTension} - never {@code power}
 * itself: a value that was not in the frame must not pass for one that was.
 * Only if the reading is recent: a voltage from yesterday says nothing about
 * now.
 *
 * It runs before the filter, so that it learns from every frame, including the
 * ones that are dropped afterwards.
 */
public class TensionReciente extends BasePositionHandler {

    public static final String ATRIBUTO = "ultimaTension";

    static final long VIGENCIA = 10 * 60 * 1000L;

    record Lectura(double tension, long momento) {
    }

    private final Map<Long, Lectura> ultimas = new ConcurrentHashMap<>();

    static Double tension(Object valor) {
        return valor instanceof Number numero ? numero.doubleValue() : null;
    }

    /** What to add to this frame, or null. Pure, to be tested without a pipeline. */
    Double completar(long deviceId, Object power, long momento) {
        Double tension = tension(power);
        if (tension != null) {
            ultimas.put(deviceId, new Lectura(tension, momento));
            return null;
        }
        Lectura ultima = ultimas.get(deviceId);
        if (ultima != null && momento - ultima.momento() >= 0 && momento - ultima.momento() <= VIGENCIA) {
            return ultima.tension();
        }
        return null;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        long momento = position.getFixTime() != null
                ? position.getFixTime().getTime() : System.currentTimeMillis();
        Double ultima = completar(position.getDeviceId(), position.getAttributes().get(Position.KEY_POWER), momento);
        if (ultima != null) {
            position.set(ATRIBUTO, ultima);
        }
        callback.processed(false);
    }

}
