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
package org.traccar.geocoder;

import org.traccar.database.StatisticsManager;

/**
 * A guard in front of whatever geocoder is configured: null island is not a place.
 *
 * <p>A tracker with no fix reports 0,0, and asking any geocoder for 0,0 asks about a point in the
 * Gulf of Guinea. LocationIQ answers {@code 404 Unable to geocode}, which is the correct answer to
 * a question that should never have been asked. One bike here left 372 such positions before its
 * reporting was fixed, and every stop report covering those days asked for all of them again -
 * 372 requests that can only ever fail, burning a rate limit that the positions people actually
 * care about need.
 *
 * <p>The filter belongs here and not in the callers. There are three of them - the position
 * pipeline, the report builder and the notification formatter - and a guard written in one of
 * them is a guard missing from the other two. A client-side filter is later still: by the time
 * the browser drops a 0,0 stop, the server has already spent the request.
 *
 * <p>A CALLBACK IS ALWAYS ANSWERED. {@code GeocoderHandler} only lets the position continue from
 * inside the callback, so a guard that returns without calling it leaves the position stuck in
 * the pipeline for good. Skipping is reported as success with no address, which is what a failed
 * lookup already does, and not as a failure, which would log a stack trace per heartbeat.
 *
 * <p>THIS GUARD NEVER WAITS. A rate limiter was tried here and taken out: the notification
 * formatter geocodes synchronously from the position pipeline thread
 * ({@code ProcessingHandler} -> {@code NotificationManager}), so anything that sleeps in this
 * object stalls the decoding of every other device's traffic. Spacing requests needs a
 * non-blocking design at the network call, not a sleep in front of the cache.
 */
public class GuardedGeocoder implements Geocoder {

    private final Geocoder delegate;

    public GuardedGeocoder(Geocoder delegate) {
        this.delegate = delegate;
    }

    /**
     * Whether these coordinates name somewhere. Exactly 0,0 is the no-fix report, not the island
     * that is not there; a real position that close to it is in the sea anyway.
     */
    static boolean isPlace(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && (latitude != 0 || longitude != 0);
    }

    @Override
    public String getAddress(double latitude, double longitude, ReverseGeocoderCallback callback) {
        if (!isPlace(latitude, longitude)) {
            if (callback != null) {
                callback.onSuccess(null);
            }
            return null;
        }
        return delegate.getAddress(latitude, longitude, callback);
    }

    @Override
    public void setStatisticsManager(StatisticsManager statisticsManager) {
        delegate.setStatisticsManager(statisticsManager);
    }

}
