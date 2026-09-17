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
package org.traccar.reports.common;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReportBudgetTest {

    @Test
    public void spendingUnderTheCeilingIsAllowed() {
        var budget = new ReportBudget(100);
        budget.spend(60);
        budget.spend(40);
        assertEquals(100, budget.getUsed());
    }

    @Test
    public void spendingExactlyTheCeilingIsAllowed() {
        var budget = new ReportBudget(10);
        budget.spend(10);
        assertEquals(10, budget.getUsed());
    }

    @Test
    public void goingOverTheCeilingIsRefused() {
        var budget = new ReportBudget(10);
        budget.spend(9);
        var e = assertThrows(ReportTooLargeException.class, () -> budget.spend(2));
        assertEquals(10, e.getCeiling());
    }

    @Test
    public void theMessageSaysWhatToDoAboutIt() {
        var e = assertThrows(ReportTooLargeException.class, () -> new ReportBudget(50000).spend(50001));
        assertTrue(e.getMessage().contains("50000"), e.getMessage());
        assertTrue(e.getMessage().contains("fewer devices"), e.getMessage());
    }

    @Test
    public void severalDevicesAddUpEvenWhenEachOneFits() {
        // The bug this exists for: REPORT_MAX_POSITIONS caps each device, so four devices
        // just under their own cap still multiply into an out-of-memory.
        var budget = new ReportBudget(100);
        budget.spend(50);
        assertThrows(ReportTooLargeException.class, () -> budget.spend(51));
    }

    @Test
    public void remainingStopsAStreamOnePastTheCeiling() {
        // How the provider uses it: limit() the read so that at most ceiling + 1 positions
        // are ever held, which is enough to tell "exactly the budget" from "over it".
        var budget = new ReportBudget(10);
        try (Stream<Integer> source = IntStream.rangeClosed(1, 1000).boxed()) {
            var read = source.limit(budget.remaining()).toList();
            assertEquals(11, read.size());
            assertThrows(ReportTooLargeException.class, () -> budget.spend(read.size()));
        }
    }

    @Test
    public void remainingNeverGoesNegative() {
        var budget = new ReportBudget(5);
        assertThrows(ReportTooLargeException.class, () -> budget.spend(99));
        assertTrue(budget.remaining() >= 1, "remaining was " + budget.remaining());
    }

    @Test
    public void theDefaultCeilingComesFromTheHeap() {
        // A fixed default cannot be right for both servers this was measured on: 50000
        // positions build the report on a 891 MB heap and kill a 240 MB one. So the default
        // is derived, and has to land inside what the heap can actually take.
        int techo = ReportBudget.techoPorDefecto();
        long heap = Runtime.getRuntime().maxMemory();
        assertTrue(techo >= 1000, "even a tiny heap should allow some report: " + techo);
        // Two positions per 48 kB is the reserve; anything far above that is not derived.
        assertTrue(techo <= heap / 12288, "ceiling too generous for " + heap + " bytes: " + techo);
        assertTrue(techo >= heap / 49152, "ceiling too mean for " + heap + " bytes: " + techo);
    }

    @Test
    public void theMeasuredHeapsWouldGetASurvivableCeiling() {
        // The two points that were measured, checked against the rule the code applies:
        //   891 MB survived 50000 and died at 100000
        //   240 MB survived 12000 and died at 16000
        // The derived ceiling must sit under the surviving figure on both.
        assertTrue(891L * 1024 * 1024 / 24576 < 50000, "891 MB would get more than it survives");
        assertTrue(240L * 1024 * 1024 / 24576 < 12000, "240 MB would get more than it survives");
    }

    @Test
    public void aConfiguredCeilingWins() {
        assertEquals(777, ReportBudget.desdeConfiguracion(777).getCeiling());
    }

    @Test
    public void anUnsetCeilingFallsBackToTheHeap() {
        // 0 is what the config returns when nobody set the key.
        assertEquals(ReportBudget.techoPorDefecto(), ReportBudget.desdeConfiguracion(0).getCeiling());
        assertEquals(ReportBudget.techoPorDefecto(), ReportBudget.desdeConfiguracion(-5).getCeiling());
    }
}
