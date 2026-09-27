package org.traccar.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PoliticaContrasenasTest {

    @Test
    public void testCortaNoVale() {
        assertThrows(IllegalArgumentException.class, () -> new User().setPassword("pruebas"));
    }

    @Test
    public void testRepetidaNoVale() {
        assertThrows(IllegalArgumentException.class, () -> PoliticaContrasenas.comprobar("aaaaaaaaaa"));
        assertThrows(IllegalArgumentException.class, () -> PoliticaContrasenas.comprobar("abababab"));
    }

    @Test
    public void testOchoCaracteresValen() {
        var user = new User();
        assertDoesNotThrow(() -> user.setPassword("ferrol26"));
        assertNotNull(user.getHashedPassword());
    }

    @Test
    public void testCuentaCaracteresNoBytes() {
        // Siete caracteres, pero nueve unidades de Java: cada emoji ocupa dos.
        assertThrows(IllegalArgumentException.class, () -> PoliticaContrasenas.comprobar("ñandú😀😀"));
        assertDoesNotThrow(() -> PoliticaContrasenas.comprobar("ñandú😀ab"));
    }

    @Test
    public void testVaciaNoCambiaNada() {
        var user = new User();
        assertDoesNotThrow(() -> user.setPassword(""));
        assertDoesNotThrow(() -> user.setPassword(null));
    }

}
