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
 * <p>Measured on two heaps, with positions carrying 424 bytes of attributes:
 *
 * <pre>
 *   891 MB:  50000 -&gt; spreadsheet in 26 s     100000 -&gt; process killed after 168 s
 *   240 MB:  12000 -&gt; spreadsheet in 12 s      16000 -&gt; process killed
 * </pre>
 *
 * <p>Which is why there is no fixed default: 50000 is fine on the first server and fatal on
 * the second, and 240 MB is what a small cloud instance gives itself. The ceiling is derived
 * from the heap the JVM actually got instead.
 *
 * <p>A report that would cross the ceiling now stops and explains itself instead of dying.
 */
public class ReportBudget {

    private final int ceiling;
    private int used;

    /**
     * Bytes of heap to reserve per position held while building a spreadsheet.
     *
     * <p>Not a guess. Taking the point where each server died: 891 MB died at 100000 positions
     * (8.9 kB each) and 240 MB died at 16000 (15.0 kB each). The larger of the two, rounded up
     * and doubled, is what is reserved here, so the derived ceiling lands below what was
     * measured to survive on both.
     */
    private static final long BYTES_POR_POSICION = 24576;

    /** Below this a report would be useless, so a tiny heap gets this instead of nothing. */
    private static final int TECHO_MINIMO = 1000;

    public ReportBudget(int ceiling) {
        this.ceiling = ceiling;
    }

    /**
     * The ceiling to use when the configuration does not set one: derived from the heap this
     * JVM actually got.
     *
     * <p>A fixed default cannot be right for both: 50000 positions build a report on a 891 MB
     * heap and kill a 240 MB one, and Traccar runs with {@code -XX:+ExitOnOutOfMemoryError}, so
     * getting it wrong does not fail the report, it takes the server down.
     */
    public static int techoPorDefecto() {
        long porMemoria = Runtime.getRuntime().maxMemory() / BYTES_POR_POSICION;
        return (int) Math.max(TECHO_MINIMO, Math.min(Integer.MAX_VALUE, porMemoria));
    }

    /**
     * A budget from the configured ceiling, or from the heap when nothing is configured.
     *
     * @param configurado the value of report.maxTotalPositions, 0 when unset
     */
    public static ReportBudget desdeConfiguracion(int configurado) {
        return new ReportBudget(configurado > 0 ? configurado : techoPorDefecto());
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
