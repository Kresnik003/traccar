package org.traccar.telegram;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A button's data comes back from Telegram, that is, from outside: it is signed
 * so nobody can cancel someone else's crash guard by sending "g:7".
 */
public class FirmaBotonTest {

    private final FirmaBoton firma = new FirmaBoton("secreto");

    @Test
    public void whatIsSignedChecks() {
        assertEquals(Optional.of(new FirmaBoton.Dato("g", 7)), firma.comprobar(firma.firmar("g", 7)));
    }

    @Test
    public void tamperedDataDoesNotPass() {
        String dato = firma.firmar("g", 7);
        assertTrue(firma.comprobar(dato.replace(":7:", ":8:")).isEmpty());
        assertTrue(firma.comprobar(dato.replace("g:", "v:")).isEmpty());
        assertTrue(new FirmaBoton("otro").comprobar(dato).isEmpty());
    }

    @Test
    public void garbageDoesNotPass() {
        assertTrue(firma.comprobar("basura").isEmpty());
        assertTrue(firma.comprobar("g:x:abc").isEmpty());
        assertTrue(firma.comprobar(null).isEmpty());
    }

    @Test
    public void malformedDataDoesNotPass() {
        String dato = firma.firmar("g", 7);
        String mac = dato.substring(dato.lastIndexOf(':') + 1);
        assertTrue(firma.comprobar("g:7").isEmpty());
        assertTrue(firma.comprobar(dato + ":extra").isEmpty());
        assertTrue(firma.comprobar("g:siete:" + mac).isEmpty());
        assertTrue(firma.comprobar("g:12345678901234567890:" + mac).isEmpty());
        assertTrue(firma.comprobar("").isEmpty());
    }

    @Test
    public void onlyPlainDigitsAreAnId() {
        // The ids are rows of the database. Even signed by the server itself, a
        // negative one is not a valid button: the format is checked before
        // anything else.
        assertTrue(firma.comprobar(firma.firmar("g", -5)).isEmpty());
    }

    @Test
    public void itFitsInCallbackData() {
        assertTrue(firma.firmar("g", Long.MAX_VALUE).length() <= 64);
    }

}
