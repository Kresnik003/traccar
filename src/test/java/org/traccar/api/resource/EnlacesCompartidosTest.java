package org.traccar.api.resource;

import org.junit.jupiter.api.Test;
import org.traccar.model.User;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EnlacesCompartidosTest {

    private static User usuario(String email, boolean temporal) {
        User user = new User();
        user.setEmail(email);
        user.setTemporary(temporal);
        return user;
    }

    @Test
    public void testAlargar() {
        Date hora = new Date(3_600_000L);
        Date tresDias = new Date(3L * 24 * 3_600_000L);
        assertTrue(EnlacesCompartidos.alargar(hora, tresDias), "el enlace de 3 días alarga al de 1 hora");
        assertFalse(EnlacesCompartidos.alargar(tresDias, hora), "uno más corto no acorta");
        assertTrue(EnlacesCompartidos.alargar(null, hora));
        assertFalse(EnlacesCompartidos.alargar(hora, null));
    }

    @Test
    public void testEsSuyo() {
        User yo = usuario("ana@club.es", false);
        assertTrue(EnlacesCompartidos.esSuyo(yo, usuario("ana@club.es:356307042441021", true)));
        assertFalse(EnlacesCompartidos.esSuyo(yo, usuario("ana@club.es.otro:35630", true)),
                "un correo que empieza igual no es el mismo dueño");
        assertFalse(EnlacesCompartidos.esSuyo(yo, usuario("luis@club.es:356307042441021", true)));
        assertFalse(EnlacesCompartidos.esSuyo(yo, usuario("ana@club.es:x", false)), "no temporal: no es un enlace");
    }

}
