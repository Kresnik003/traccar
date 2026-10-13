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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.seguridad.GuardianGolpes;

import jakarta.inject.Inject;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Counts the two minutes of the crash guards (improvement 114) and retries the
 * safety messages that did not go out. Every 10 s: a guard is resolved at most
 * that late, which is nothing next to the two minutes it waits.
 */
public class TaskGuardias extends SingleScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskGuardias.class);

    private static final long PERIODO_SEGUNDOS = 10;

    private final GuardianGolpes guardianGolpes;

    @Inject
    public TaskGuardias(GuardianGolpes guardianGolpes) {
        this.guardianGolpes = guardianGolpes;
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleAtFixedRate(this, PERIODO_SEGUNDOS, PERIODO_SEGUNDOS, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        // An exception here would cancel scheduleAtFixedRate for good.
        try {
            guardianGolpes.revisar(System.currentTimeMillis());
        } catch (RuntimeException e) {
            LOGGER.warn("Crash guard check failed", e);
        }
    }

}
