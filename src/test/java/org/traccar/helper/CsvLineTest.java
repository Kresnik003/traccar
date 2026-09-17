package org.traccar.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CsvLineTest {

    @Test
    public void testPlain() {
        assertEquals("a,1,2.5\r\n", CsvLine.of("a", 1, 2.5));
        assertEquals(",x\r\n", CsvLine.of(null, "x"));
    }

    /** An address with a comma would otherwise shift every column after it. */
    @Test
    public void testCommaAndQuotes() {
        assertEquals("\"Calle Mayor, 3\"", CsvLine.field("Calle Mayor, 3"));
        assertEquals("\"dijo \"\"hola\"\"\"", CsvLine.field("dijo \"hola\""));
        assertEquals("\"dos\nlineas\"", CsvLine.field("dos\nlineas"));
    }

    /** A spreadsheet runs a leading = as a formula. */
    @Test
    public void testFormulaIsNeutralised() {
        assertEquals("'=1+1", CsvLine.field("=1+1"));
        // With quotes inside, the neutralised text is also quoted and its quotes doubled.
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", CsvLine.field("=HYPERLINK(\"x\")"));
        assertEquals("'@sum", CsvLine.field("@sum"));
        assertEquals("'+cmd", CsvLine.field("+cmd"));
    }

    /** A negative coordinate is a number, not a formula. */
    @Test
    public void testNegativeNumbersStayNumbers() {
        assertEquals("-8.2", CsvLine.field(-8.2));
        assertEquals("-3.70", CsvLine.field("-3.70"));
    }

}
