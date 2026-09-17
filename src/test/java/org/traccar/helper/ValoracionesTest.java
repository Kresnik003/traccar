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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ValoracionesTest {

    @Test
    public void realOpenStreetMapIdsAreAccepted() {
        assertTrue(Valoraciones.sitioValido("node/123456"));
        assertTrue(Valoraciones.sitioValido("way/987"));
        assertTrue(Valoraciones.sitioValido("relation/55"));
        assertTrue(Valoraciones.sitioValido("  node/1  "), "surrounding spaces should be tolerated");
    }

    @Test
    public void anythingElseIsRefusedRatherThanStored() {
        // These strings arrive in a request body and end up in a query, so the
        // shape is checked before anything is done with them.
        assertFalse(Valoraciones.sitioValido(null));
        assertFalse(Valoraciones.sitioValido(""));
        assertFalse(Valoraciones.sitioValido("   "));
        assertFalse(Valoraciones.sitioValido("node/"));
        assertFalse(Valoraciones.sitioValido("/123"));
        assertFalse(Valoraciones.sitioValido("nodo/123"));
        assertFalse(Valoraciones.sitioValido("node/abc"));
        assertFalse(Valoraciones.sitioValido("node/1 or 1=1"));
        assertFalse(Valoraciones.sitioValido("node/1'"));
        assertFalse(Valoraciones.sitioValido("node/1;drop"));
        assertFalse(Valoraciones.sitioValido("node/12345678901234567890123"));
    }

    @Test
    public void aBadSiteSaysWhatOneLooksLike() {
        var e = assertThrows(IllegalArgumentException.class, () -> Valoraciones.sitioLimpio("camping-pepe"));
        assertTrue(e.getMessage().contains("node/123456"), e.getMessage());
    }

    @Test
    public void aCleanSiteComesBackTrimmed() {
        assertEquals("node/42", Valoraciones.sitioLimpio(" node/42 "));
    }

    @Test
    public void onlyOneToFiveStars() {
        assertTrue(Valoraciones.estrellasValidas(1));
        assertTrue(Valoraciones.estrellasValidas(5));
        assertFalse(Valoraciones.estrellasValidas(0));
        assertFalse(Valoraciones.estrellasValidas(6));
        assertFalse(Valoraciones.estrellasValidas(-3));
        assertFalse(Valoraciones.estrellasValidas(Integer.MAX_VALUE));
    }

    @Test
    public void aCommentMayBeAbsentButNotEndless() {
        assertTrue(Valoraciones.comentarioValido(null));
        assertTrue(Valoraciones.comentarioValido(""));
        assertTrue(Valoraciones.comentarioValido("x".repeat(1000)));
        assertFalse(Valoraciones.comentarioValido("x".repeat(1001)));
    }

    @Test
    public void theAverageIsTheOneShownEverywhere() {
        assertEquals(4.0, Valoraciones.media(4, 1));
        assertEquals(4.5, Valoraciones.media(9, 2));
        assertEquals(3.3, Valoraciones.media(10, 3), "10/3 is 3.33, shown as 3.3");
        assertEquals(3.3, Valoraciones.media(13, 4), "13/4 is 3.25, and must not be 3.2 on one screen and 3.3 on another");
    }

    @Test
    public void aSiteNobodyRatedScoresNothingRatherThanCrashing() {
        assertEquals(0, Valoraciones.media(0, 0));
        assertEquals(0, Valoraciones.media(17, 0), "no divide by zero");
    }
}
