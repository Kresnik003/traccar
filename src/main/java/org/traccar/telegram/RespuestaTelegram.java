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
 * What Telegram answered. A 403 means the user blocked the bot or deleted the
 * chat: insisting is useless, unlike a 429 or a network failure (status 0).
 */
public record RespuestaTelegram(int estado, String descripcion) {

    public boolean ok() {
        return estado >= 200 && estado < 300;
    }

    public boolean rechazado() {
        return estado == 403;
    }

}
