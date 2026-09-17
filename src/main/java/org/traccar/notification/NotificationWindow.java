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
package org.traccar.notification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Decides which notifications go out now and which are folded into a summary.
 *
 * The first notification for a key always goes out at once. A crash alarm is
 * the reason this server exists; grouping must never delay the first one.
 * Repeats of the same key inside the window are held, and when the window
 * closes they leave as one summary. The key carries the device, so a second
 * bike never waits behind the first.
 *
 * No clock and no I/O here: time comes in as a number, which is what makes the
 * window testable.
 *
 * @param <T> whatever the caller needs to send the summary with
 */
public class NotificationWindow<T> {

    public record Summary<T>(String key, int count, long first, long last, T latest) {
    }

    private static final class Open<T> {
        private final long opened;
        private int held;
        private long first;
        private long last;
        private T latest;

        private Open(long opened) {
            this.opened = opened;
        }
    }

    private final long windowMillis;
    private final Map<String, Open<T>> open = new HashMap<>();
    private final List<Open<T>> expired = new ArrayList<>();
    private final List<String> expiredKeys = new ArrayList<>();

    public NotificationWindow(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /**
     * @return true if this one has to be sent now, false if it was held
     */
    public synchronized boolean offer(String key, long now, T payload) {
        Open<T> window = open.get(key);
        if (window == null || now - window.opened >= windowMillis) {
            if (window != null && window.held > 0) {
                // A window that expired with repeats inside and was not flushed
                // yet keeps them: they leave with the next flush, not lost.
                return keepAndReopen(key, window, now);
            }
            open.put(key, new Open<>(now));
            return true;
        }
        if (window.held == 0) {
            window.first = now;
        }
        window.held++;
        window.last = now;
        window.latest = payload;
        return false;
    }

    private boolean keepAndReopen(String key, Open<T> window, long now) {
        expired.add(window);
        expiredKeys.add(key);
        open.put(key, new Open<>(now));
        return true;
    }

    /** The summaries of every window that has closed, removed from the window. */
    public synchronized List<Summary<T>> flush(long now) {
        List<Summary<T>> result = new ArrayList<>();
        for (int i = 0; i < expired.size(); i++) {
            Open<T> w = expired.get(i);
            result.add(new Summary<>(expiredKeys.get(i), w.held, w.first, w.last, w.latest));
        }
        expired.clear();
        expiredKeys.clear();
        for (Iterator<Map.Entry<String, Open<T>>> it = open.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Open<T>> entry = it.next();
            Open<T> w = entry.getValue();
            if (now - w.opened >= windowMillis) {
                if (w.held > 0) {
                    result.add(new Summary<>(entry.getKey(), w.held, w.first, w.last, w.latest));
                }
                it.remove();
            }
        }
        return result;
    }

    /** What is still open, for a clean shutdown and for tests. */
    public synchronized int openWindows() {
        return open.size();
    }

}
