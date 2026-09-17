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
package org.traccar.helper;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which API requests read location history, with no I/O of its own.
 *
 * Only history counts: positions over a period, reports and exports. The live
 * position of one's own bike is requested constantly and says nothing an
 * access log is for; what reveals where somebody has been is the history.
 */
public final class LocationAccessLog {

    private LocationAccessLog() {
    }

    public record Access(String what, List<Long> deviceIds, List<Long> groupIds, Long exportedUserId,
                         Date from, Date to) {

        /**
         * Whether the endpoint drops the devices the user cannot see and still
         * answers 200. Reports do; positions and exports refuse the request
         * instead, so a success there means every requested device was read.
         */
        public boolean silentlyFiltered() {
            return what.startsWith("informe:");
        }

    }

    private static final Pattern POSITIONS = Pattern.compile("positions(?:/(kml|kmz|csv|gpx|fit))?");
    private static final Pattern REPORTS = Pattern.compile(
            "reports/(route|events|stops|trips|summary|combined|geofences)(?:/(xlsx|mail))?");
    private static final Pattern EXPORT = Pattern.compile("socios/(\\d+)/datos");

    public interface Parameters {
        List<String> get(String name);
    }

    private static List<Long> ids(List<String> values) {
        List<Long> result = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                try {
                    result.add(Long.parseLong(value.trim()));
                } catch (NumberFormatException e) {
                    // not an id, not logged
                }
            }
        }
        return result;
    }

    private static Date date(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return Date.from(OffsetDateTime.parse(values.get(0).trim()).toInstant());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Collapses repeats of the same look.
     *
     * Opening a vehicle's panel in the web asks for five reports at once, and
     * people open it again and again; measured on the test bench, 55 identical
     * rows in one minute. A log where one look becomes a screenful hides the
     * look that matters. The same user reading the same thing of the same device
     * over the same days is logged once per window. The period counts by day,
     * because the web asks "until now" and now moves on every request.
     */
    public static final class Repeats {

        private final long windowMillis;
        private final Map<String, Long> seen;

        public Repeats(long windowMillis, int capacity) {
            this.windowMillis = windowMillis;
            this.seen = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > capacity;
                }
            };
        }

        private static long day(Date date) {
            return date != null ? Math.floorDiv(date.getTime(), 86_400_000L) : -1;
        }

        public synchronized boolean first(long userId, long deviceId, Access access, long now) {
            String key = userId + ":" + deviceId + ":" + access.what()
                    + ":" + day(access.from()) + ":" + day(access.to());
            Long last = seen.get(key);
            if (last != null && now - last < windowMillis) {
                return false;
            }
            seen.put(key, now);
            return true;
        }

    }

    /**
     * What a request read, or null when it did not read location history.
     *
     * @param path request path relative to /api, without a leading slash
     */
    public static Access classify(String method, String path, Parameters parameters) {
        if (!"GET".equals(method) || path == null) {
            return null;
        }
        String clean = path.replaceAll("^/+|/+$", "");
        Matcher export = EXPORT.matcher(clean);
        if (export.matches()) {
            return new Access("exportar", List.of(), List.of(), Long.parseLong(export.group(1)), null, null);
        }
        Matcher positions = POSITIONS.matcher(clean);
        if (positions.matches()) {
            // Without a period it is the latest position, not history.
            Date from = date(parameters.get("from"));
            List<Long> devices = ids(parameters.get("deviceId"));
            if (from == null || devices.isEmpty()) {
                return null;
            }
            String what = positions.group(1) != null ? "posiciones:" + positions.group(1) : "posiciones";
            return new Access(what, devices, List.of(), null, from, date(parameters.get("to")));
        }
        Matcher reports = REPORTS.matcher(clean);
        if (reports.matches()) {
            List<Long> devices = ids(parameters.get("deviceId"));
            List<Long> groups = ids(parameters.get("groupId"));
            if (devices.isEmpty() && groups.isEmpty()) {
                return null;
            }
            String what = "informe:" + reports.group(1) + (reports.group(2) != null ? ":" + reports.group(2) : "");
            return new Access(what, devices, groups, null,
                    date(parameters.get("from")), date(parameters.get("to")));
        }
        return null;
    }

}
