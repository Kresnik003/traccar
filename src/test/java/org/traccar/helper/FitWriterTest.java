package org.traccar.helper;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FitWriterTest {

    private static final long T0 = 1789632000000L;

    private static byte[] file(FitWriter writer) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(out, T0);
        return out.toByteArray();
    }

    /** The published check value of CRC-16/ARC, the algorithm FIT uses. */
    @Test
    public void testCrcIsTheFitOne() {
        byte[] check = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0xBB3D, FitWriter.crc16(check, 0, check.length));
    }

    @Test
    public void testConversions() {
        assertEquals(1 << 30, FitWriter.semicircles(90));
        assertEquals(-(1 << 30), FitWriter.semicircles(-90));
        assertEquals(0, FitWriter.fitTime(FitWriter.FIT_EPOCH * 1000));
    }

    @Test
    public void testHeaderSizeAndCrcs() throws IOException {
        FitWriter writer = new FitWriter(7);
        writer.addRecord(T0, 43.48, -8.23, 10, 0);
        writer.addRecord(T0 + 60_000, 43.49, -8.23, 12, 20);
        byte[] bytes = file(writer);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(14, buf.get(0));
        assertEquals(".FIT", new String(bytes, 8, 4, StandardCharsets.US_ASCII));
        assertEquals(bytes.length - 14 - 2, buf.getInt(4), "data size in the header");
        assertEquals(FitWriter.crc16(bytes, 0, 12), buf.getShort(12) & 0xffff, "header CRC");
        // Run over the whole file, CRC included, CRC-16/ARC comes back to zero.
        assertEquals(0, FitWriter.crc16(bytes, 0, bytes.length), "file CRC");
    }

    /** A tracker without a fix sends 0,0; in an activity that is thousands of kilometres. */
    @Test
    public void testZeroZeroIsSkipped() throws IOException {
        FitWriter writer = new FitWriter(7);
        assertTrue(writer.addRecord(T0, 43.48, -8.23, 10, 0));
        assertFalse(writer.addRecord(T0 + 1000, 0, 0, 0, 0));
        assertTrue(writer.addRecord(T0 + 2000, 43.49, -8.23, 10, 0));
        assertEquals(2, writer.getRecords());
        assertEquals(1112, writer.getDistance(), 5);
        file(writer);
    }

    @Test
    public void testEmptyPeriodIsStillAFile() throws IOException {
        byte[] bytes = file(new FitWriter(7));
        assertEquals(0, FitWriter.crc16(bytes, 0, bytes.length));
        assertTrue(bytes.length > 16);
    }

    /** Writing twice must not append the closing messages twice. */
    @Test
    public void testWritingTwiceGivesTheSameFile() throws IOException {
        FitWriter writer = new FitWriter(7);
        writer.addRecord(T0, 43.48, -8.23, 10, 0);
        writer.addRecord(T0 + 60_000, 43.49, -8.23, 12, 20);
        assertEquals(ByteBuffer.wrap(file(writer)), ByteBuffer.wrap(file(writer)));
    }

}
