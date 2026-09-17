package org.traccar.helper;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResetCodeTest {

    @Test
    public void testCodesAreRandomAndPlausible() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String code = ResetCode.generate();
            assertTrue(ResetCode.plausible(code), code);
            codes.add(code);
        }
        assertEquals(1000, codes.size());
    }

    /** The published SHA-256 of "abc": what is stored is the hash, not the code. */
    @Test
    public void testHash() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ResetCode.hash("abc"));
        String code = ResetCode.generate();
        assertNotEquals(code, ResetCode.hash(code));
    }

    /** A signed login token from an old link is not a reset code. */
    @Test
    public void testLoginTokensAreNotCodes() {
        assertFalse(ResetCode.plausible("x".repeat(190)));
        assertFalse(ResetCode.plausible(null));
        assertFalse(ResetCode.plausible("short"));
        assertFalse(ResetCode.plausible("a".repeat(42) + "="));
    }

}
