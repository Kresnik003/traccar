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
package org.traccar.notification;

/**
 * Marks a delivery failure that no retry can fix: a webhook with no URL, one
 * that points inside, one the destination answers with 404. Such a failure is
 * logged and not queued; queuing it would only repeat the same error, and for
 * an administrator without a URL, on every alarm of every device.
 */
public interface FalloDefinitivo {

    static boolean es(Throwable error) {
        int saltos = 0;
        for (Throwable t = error; t != null && saltos < 16; t = t.getCause(), saltos++) {
            if (t instanceof FalloDefinitivo) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

}
