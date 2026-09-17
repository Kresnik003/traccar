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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes a track as a FIT activity, the format Garmin devices and Strava read.
 *
 * GPX already exists in Traccar, but Strava and Garmin Connect treat a GPX as a
 * bare line; a FIT file arrives as an activity, with its sport, distance and
 * times. Only the messages an activity needs are written: file_id, one record
 * per point, and one lap, one session and one activity closing it.
 *
 * Speed and altitude go in the enhanced fields. The plain speed field is a
 * 16-bit number of millimetres per second and tops out at 236 km/h, which a
 * motorcycle on a track goes past.
 *
 * The header carries the size of the data, so the records are built in memory
 * before anything is written. At about thirty bytes a point, the report limit
 * on positions keeps that small.
 */
public class FitWriter {

    /** Seconds between the Unix epoch and the FIT epoch, 1989-12-31T00:00:00Z. */
    static final long FIT_EPOCH = 631065600L;

    static final int SPORT_MOTORCYCLING = 22;

    private static final int FILE_ID = 0;
    private static final int SESSION = 18;
    private static final int LAP = 19;
    private static final int RECORD = 20;
    private static final int ACTIVITY = 34;

    private static final int ENUM = 0x00;
    private static final int UINT16 = 0x84;
    private static final int SINT32 = 0x85;
    private static final int UINT32 = 0x86;
    private static final int UINT32Z = 0x8C;

    private static final int EVENT_SESSION = 8;
    private static final int EVENT_LAP = 9;
    private static final int EVENT_ACTIVITY = 26;
    private static final int EVENT_TYPE_STOP = 1;

    private static final double EARTH_RADIUS = 6371008.8;

    private ByteArrayOutputStream target = new ByteArrayOutputStream();
    private final long serial;

    private int records;
    private long first = -1;
    private long last = -1;
    private double distance;
    private double lastLatitude;
    private double lastLongitude;
    private boolean recordDefined;
    private boolean closed;

    public FitWriter(long serial) {
        this.serial = serial;
    }

    static int crc16(int crc, int value) {
        crc ^= value & 0xff;
        for (int i = 0; i < 8; i++) {
            crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
        }
        return crc & 0xffff;
    }

    static int crc16(byte[] bytes, int offset, int length) {
        int crc = 0;
        for (int i = offset; i < offset + length; i++) {
            crc = crc16(crc, bytes[i]);
        }
        return crc;
    }

    static int semicircles(double degrees) {
        return (int) Math.round(degrees * (Math.pow(2, 31) / 180));
    }

    static long fitTime(long millis) {
        return millis / 1000 - FIT_EPOCH;
    }

    private void u8(int value) {
        target.write(value & 0xff);
    }

    private void u16(int value) {
        u8(value);
        u8(value >>> 8);
    }

    private void u32(long value) {
        u16((int) (value & 0xffff));
        u16((int) (value >>> 16 & 0xffff));
    }

    /** A definition message: local type, global message, then (field, size, base type) triples. */
    private void define(int local, int global, int... fields) {
        u8(0x40 | local);
        u8(0);
        u8(0);
        u16(global);
        u8(fields.length / 3);
        for (int field : fields) {
            u8(field);
        }
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /**
     * Adds one point. A point at exactly 0,0 is skipped: it is what a tracker
     * without a fix sends, and in an activity it becomes a detour to the Gulf of
     * Guinea and back that adds thousands of kilometres.
     *
     * @return whether the point was written
     */
    public boolean addRecord(long timeMillis, double latitude, double longitude, double altitude, double speedKnots) {
        if (latitude == 0 && longitude == 0) {
            return false;
        }
        if (!recordDefined) {
            define(1, RECORD,
                    253, 4, UINT32,
                    0, 4, SINT32,
                    1, 4, SINT32,
                    5, 4, UINT32,
                    73, 4, UINT32,
                    78, 4, UINT32);
            recordDefined = true;
        }
        if (records > 0) {
            distance += haversine(lastLatitude, lastLongitude, latitude, longitude);
        } else {
            first = timeMillis;
        }
        last = timeMillis;
        lastLatitude = latitude;
        lastLongitude = longitude;

        u8(1);
        u32(fitTime(timeMillis));
        u32(semicircles(latitude));
        u32(semicircles(longitude));
        u32(Math.round(distance * 100));
        u32(Math.round(Math.max(0, speedKnots * 1852 / 3600) * 1000));
        u32(Math.round(Math.max(0, altitude + 500) * 5));
        records++;
        return true;
    }

    public int getRecords() {
        return records;
    }

    /** Metres along the track, as written in the last record. */
    public double getDistance() {
        return distance;
    }

    private void closing() {
        long end = fitTime(last);
        long start = fitTime(first);
        long elapsed = (last - first);

        define(2, LAP,
                253, 4, UINT32,
                2, 4, UINT32,
                7, 4, UINT32,
                8, 4, UINT32,
                9, 4, UINT32,
                0, 1, ENUM,
                1, 1, ENUM);
        u8(2);
        u32(end);
        u32(start);
        u32(elapsed);
        u32(elapsed);
        u32(Math.round(distance * 100));
        u8(EVENT_LAP);
        u8(EVENT_TYPE_STOP);

        define(3, SESSION,
                253, 4, UINT32,
                2, 4, UINT32,
                7, 4, UINT32,
                8, 4, UINT32,
                9, 4, UINT32,
                5, 1, ENUM,
                6, 1, ENUM,
                25, 2, UINT16,
                26, 2, UINT16,
                0, 1, ENUM,
                1, 1, ENUM);
        u8(3);
        u32(end);
        u32(start);
        u32(elapsed);
        u32(elapsed);
        u32(Math.round(distance * 100));
        u8(SPORT_MOTORCYCLING);
        u8(0);
        u16(0);
        u16(1);
        u8(EVENT_SESSION);
        u8(EVENT_TYPE_STOP);

        define(4, ACTIVITY,
                253, 4, UINT32,
                0, 4, UINT32,
                1, 2, UINT16,
                2, 1, ENUM,
                3, 1, ENUM,
                4, 1, ENUM);
        u8(4);
        u32(end);
        u32(elapsed);
        u16(1);
        u8(0);
        u8(EVENT_ACTIVITY);
        u8(EVENT_TYPE_STOP);
    }

    /**
     * Writes the whole file. With no points it is still a valid FIT file, with
     * only its file_id: an empty period is an answer, not an error.
     */
    public void write(OutputStream output, long createdMillis) throws IOException {
        if (records > 0 && !closed) {
            closing();
            closed = true;
        }
        // file_id has to come first, and it is written last: the points were
        // already being encoded before anyone knew the file was finished.
        ByteArrayOutputStream points = target;
        target = new ByteArrayOutputStream();
        define(0, FILE_ID,
                0, 1, ENUM,
                1, 2, UINT16,
                2, 2, UINT16,
                3, 4, UINT32Z,
                4, 4, UINT32);
        u8(0);
        u8(4);
        u16(255);
        u16(0);
        u32(serial > 0 ? serial : 1);
        u32(fitTime(createdMillis));
        points.writeTo(target);
        byte[] body = target.toByteArray();
        target = points;

        byte[] header = new byte[14];
        header[0] = 14;
        header[1] = 0x20;
        header[2] = (byte) (2132 & 0xff);
        header[3] = (byte) (2132 >>> 8);
        header[4] = (byte) body.length;
        header[5] = (byte) (body.length >>> 8);
        header[6] = (byte) (body.length >>> 16);
        header[7] = (byte) (body.length >>> 24);
        System.arraycopy(".FIT".getBytes(StandardCharsets.US_ASCII), 0, header, 8, 4);
        int headerCrc = crc16(header, 0, 12);
        header[12] = (byte) headerCrc;
        header[13] = (byte) (headerCrc >>> 8);

        int crc = 0;
        for (byte b : header) {
            crc = crc16(crc, b);
        }
        for (byte b : body) {
            crc = crc16(crc, b);
        }
        output.write(header);
        output.write(body);
        output.write(crc & 0xff);
        output.write(crc >>> 8 & 0xff);
    }

}
