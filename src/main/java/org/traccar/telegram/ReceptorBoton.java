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
package org.traccar.telegram;

/**
 * Whoever owns the buttons of an action: "g" for the crash guard, "v" for a
 * trip. The inbox checks the signature and hands over the id.
 */
public interface ReceptorBoton {

    String accion();

    /** Handles the press and returns the short text Telegram shows to the person who pressed. */
    String pulsado(long id, String chatId);

}
