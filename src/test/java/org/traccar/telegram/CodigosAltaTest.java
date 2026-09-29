package org.traccar.telegram;

import org.junit.jupiter.api.Test;
import org.traccar.model.Contacto;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sign-up code of an emergency contact: random, single use, 24 hours, and
 * only its hash is stored.
 */
public class CodigosAltaTest {

    private static final Date AHORA = new Date(1_790_000_000_000L);

    @Test
    public void aNewCodeIsUniqueAndFitsAStartLink() {
        String codigo = CodigosAlta.nuevo();
        assertNotEquals(codigo, CodigosAlta.nuevo());
        // t.me/<bot>?start= takes up to 64 characters of [A-Za-z0-9_-]
        assertTrue(codigo.matches("[A-Za-z0-9_-]{24}"), codigo);
    }

    @Test
    public void theHashIsStoredNotTheCode() {
        assertEquals(64, CodigosAlta.huella("abc").length());
        assertNotEquals("abc", CodigosAlta.huella("abc"));
        assertEquals(CodigosAlta.huella("abc"), CodigosAlta.huella("abc"));
    }

    private static Contacto pendiente(long caducaEnMs) {
        Contacto contacto = new Contacto();
        contacto.setCodigo(CodigosAlta.huella("x"));
        contacto.setCaduca(new Date(AHORA.getTime() + caducaEnMs));
        return contacto;
    }

    @Test
    public void aPendingCodeInTimeIsValid() {
        assertTrue(CodigosAlta.vale(pendiente(1000), AHORA));
    }

    @Test
    public void anExpiredCodeIsNotValid() {
        assertFalse(CodigosAlta.vale(pendiente(-1), AHORA));
    }

    @Test
    public void aCodeWithoutAnExpiryIsNotValid() {
        Contacto contacto = pendiente(1000);
        contacto.setCaduca(null);
        assertFalse(CodigosAlta.vale(contacto, AHORA));
    }

    @Test
    public void aCodeExpiresAtItsVeryMoment() {
        assertFalse(CodigosAlta.vale(pendiente(0), AHORA));
        assertTrue(CodigosAlta.vale(pendiente(1), AHORA));
    }

    @Test
    public void aUsedCodeIsNotValid() {
        Contacto contacto = pendiente(1000);
        contacto.setConfirmado(AHORA);
        assertFalse(CodigosAlta.vale(contacto, AHORA));
        Contacto sinCodigo = pendiente(1000);
        sinCodigo.setCodigo(null);
        assertFalse(CodigosAlta.vale(sinCodigo, AHORA));
    }

}
