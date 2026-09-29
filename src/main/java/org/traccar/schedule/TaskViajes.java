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
package org.traccar.schedule;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.seguridad.ViajesFamilia;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Closes the trips of improvement 115: the arrivals at one of the rider's places
 * and the trips forgotten for twelve hours. Every 30 s: an arrival needs two
 * minutes still, so it is told at most half a minute late.
 */
public class TaskViajes extends SingleScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskViajes.class);

    private static final long PERIODO_SEGUNDOS = 30;

    private final ViajesFamilia viajesFamilia;

    @Inject
    public TaskViajes(ViajesFamilia viajesFamilia) {
        this.viajesFamilia = viajesFamilia;
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleAtFixedRate(this, PERIODO_SEGUNDOS, PERIODO_SEGUNDOS, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        // An exception here would cancel scheduleAtFixedRate for good.
        try {
            viajesFamilia.revisar(System.currentTimeMillis());
        } catch (RuntimeException e) {
            LOGGER.warn("Trip check failed", e);
        }
    }

}
