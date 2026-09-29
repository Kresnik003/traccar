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
package org.traccar.handler.events;

import jakarta.inject.Inject;
import org.traccar.model.Position;
import org.traccar.seguridad.ViajesFamilia;
import org.traccar.session.cache.CacheManager;

/**
 * Hands every position to the trips of improvement 115. It raises no event of
 * its own: a departure is a Telegram message to the rider's contacts.
 */
public class ViajesHandler extends BasePositionEventHandler {

    private final ViajesFamilia viajesFamilia;

    @Inject
    public ViajesHandler(CacheManager cacheManager, ViajesFamilia viajesFamilia) {
        super(cacheManager);
        this.viajesFamilia = viajesFamilia;
    }

    @Override
    protected void onPosition(Position position, Position lastPosition, Callback callback) {
        viajesFamilia.alPosicion(position, lastPosition);
    }

}
