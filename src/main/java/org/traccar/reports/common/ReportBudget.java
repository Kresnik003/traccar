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

/**
 * A running count of the positions a report has already pulled into memory.
 *
 * <p>Spreadsheet reports hold every position of every requested device at once, so a wide
 * enough request exhausts the heap. With {@code -XX:+ExitOnOutOfMemoryError}, which is how
 * Traccar is started, that does not fail the report: it terminates the process and takes
 * live tracking down with it until the service restarts.
 *
 * <p>Measured on a bench server with the default ergonomic heap of 891 MB and positions
 * carrying 424 bytes of attributes: 50000 positions produce the spreadsheet in 26 s, while
 * 100000 killed the process after 168 s. Hence a default ceiling of 50000, configurable for
 * servers with a larger heap.
 *
 * <p>A report that would cross the ceiling now stops and explains itself instead of dying.
 */
public class ReportBudget {

    private final int ceiling;
    private int used;

    public ReportBudget(int ceiling) {
        this.ceiling = ceiling;
    }

    public int getCeiling() {
        return ceiling;
    }

    public int getUsed() {
        return used;
    }

    /**
     * How many more positions may be read. Always at least one, so that a caller reading
     * this many can tell "exactly the budget" from "over the budget".
     */
    public long remaining() {
        return (long) Math.max(0, ceiling - used) + 1;
    }

    /**
     * Records positions that are now held in memory, and refuses the report if that puts it
     * over the ceiling.
     */
    public void spend(int count) {
        used += count;
        if (used > ceiling) {
            throw new ReportTooLargeException(ceiling);
        }
    }
}
