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
 * Thrown when a report would hold more positions in memory than the server allows.
 *
 * <p>Carries what the user needs to act: the ceiling, and what to do about it. The API maps
 * it to 400, so the client shows a message rather than losing the connection to a server
 * that died mid-report.
 */
public class ReportTooLargeException extends IllegalArgumentException {

    private final int ceiling;

    public ReportTooLargeException(int ceiling) {
        super("Report too large: it needs more than " + ceiling
                + " positions. Narrow the date range or select fewer devices.");
        this.ceiling = ceiling;
    }

    public int getCeiling() {
        return ceiling;
    }
}
