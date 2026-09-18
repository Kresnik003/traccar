package org.traccar.geocoder;

import org.junit.jupiter.api.Test;
import org.traccar.database.StatisticsManager;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GuardedGeocoderTest {

    /** Records what actually reached the real geocoder, which is the whole point of the guard. */
    private static final class Recording implements Geocoder {

        private final List<String> asked = new ArrayList<>();
        private StatisticsManager statisticsManager;

        @Override
        public String getAddress(double latitude, double longitude, ReverseGeocoderCallback callback) {
            asked.add(latitude + "," + longitude);
            if (callback != null) {
                callback.onSuccess("somewhere");
            }
            return "somewhere";
        }

        @Override
        public void setStatisticsManager(StatisticsManager statisticsManager) {
            this.statisticsManager = statisticsManager;
        }
    }

    @Test
    public void nullIslandIsNeverAsked() {
        var real = new Recording();
        var geocoder = new GuardedGeocoder(real);

        assertNull(geocoder.getAddress(0, 0, null));
        assertTrue(real.asked.isEmpty());
    }

    @Test
    public void aRealPlaceIsAsked() {
        var real = new Recording();
        var geocoder = new GuardedGeocoder(real);

        assertEquals("somewhere", geocoder.getAddress(43.4895, -8.1969, null));
        assertEquals(List.of("43.4895,-8.1969"), real.asked);
    }

    /** A zero on one axis only is a real place: the equator and the Greenwich meridian exist. */
    @Test
    public void onlyBothZerosAreSkipped() {
        var real = new Recording();
        var geocoder = new GuardedGeocoder(real);

        geocoder.getAddress(0, -8.1969, null);
        geocoder.getAddress(43.4895, 0, null);
        assertEquals(2, real.asked.size());
    }

    @Test
    public void nonsenseCoordinatesAreNotAsked() {
        var real = new Recording();
        var geocoder = new GuardedGeocoder(real);

        assertNull(geocoder.getAddress(Double.NaN, 3.0, null));
        assertNull(geocoder.getAddress(3.0, Double.POSITIVE_INFINITY, null));
        assertTrue(real.asked.isEmpty());
    }

    /**
     * THE ONE THAT MATTERS FOR THE PIPELINE. GeocoderHandler only lets the position move on from
     * inside the callback, so a skipped lookup that answers nobody wedges that position for good.
     */
    @Test
    public void aSkippedLookupStillAnswersItsCallback() {
        var real = new Recording();
        var geocoder = new GuardedGeocoder(real);
        var answers = new ArrayList<String>();
        var failures = new ArrayList<Throwable>();

        geocoder.getAddress(0, 0, new Geocoder.ReverseGeocoderCallback() {
            @Override
            public void onSuccess(String address) {
                answers.add(address);
            }

            @Override
            public void onFailure(Throwable e) {
                failures.add(e);
            }
        });

        assertEquals(1, answers.size());
        assertNull(answers.get(0));
        // Not a failure: that would log a stack trace for every heartbeat without a fix.
        assertTrue(failures.isEmpty());
    }

    /** A real place with a callback goes through untouched, callback included. */
    @Test
    public void aRealPlaceWithACallbackIsPassedOn() {
        var real = new Recording();
        var geocoder = new GuardedGeocoder(real);
        var answers = new ArrayList<String>();

        geocoder.getAddress(43.4895, -8.1969, new Geocoder.ReverseGeocoderCallback() {
            @Override
            public void onSuccess(String address) {
                answers.add(address);
            }

            @Override
            public void onFailure(Throwable e) {
            }
        });

        assertEquals(List.of("somewhere"), answers);
        assertEquals(1, real.asked.size());
    }

    @Test
    public void statisticsReachTheRealGeocoder() {
        var real = new Recording();
        var statisticsManager = new StatisticsManager(null, null, null, null);
        new GuardedGeocoder(real).setStatisticsManager(statisticsManager);

        assertSame(statisticsManager, real.statisticsManager);
    }

    @Test
    public void isPlaceIsTheRuleWrittenDown() {
        assertFalse(GuardedGeocoder.isPlace(0, 0));
        assertTrue(GuardedGeocoder.isPlace(43.4895, -8.1969));
        assertFalse(GuardedGeocoder.isPlace(Double.NaN, Double.NaN));
    }

}
