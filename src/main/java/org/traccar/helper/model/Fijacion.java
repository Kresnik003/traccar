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
package org.traccar.helper.model;

import org.traccar.model.Position;

import java.util.Deque;
import java.util.Iterator;

/**
 * Whether a position says where the device is.
 *
 * One definition for every handler that must not reason from a position
 * without a fix. The first guard (0029) asked only for the valid flag, and
 * that let two kinds through:
 *
 *   - 0,0 marked valid. It is the Gulf of Guinea, not a fix.
 *   - A position located by GeolocationHandler from cell towers or Wi-Fi. It
 *     arrives with valid set and "approximate", and is off by hundreds of
 *     metres to kilometres: enough to leave a garage geofence and to open a
 *     trip on a bike that has not moved.
 */
public final class Fijacion {

    private Fijacion() {
    }

    public static boolean tiene(Position position) {
        return position.getValid()
                && !(position.getLatitude() == 0 && position.getLongitude() == 0)
                && !position.getBoolean(Position.KEY_APPROXIMATE);
    }

    /** The newest position with a fix, or null if there is none. */
    public static Position ultima(Deque<Position> positions) {
        for (Iterator<Position> iterator = positions.descendingIterator(); iterator.hasNext();) {
            Position position = iterator.next();
            if (tiene(position)) {
                return position;
            }
        }
        return null;
    }

}
