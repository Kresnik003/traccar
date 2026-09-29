package org.traccar.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * What the bot's inbox understands. Everything else is ignored: the bot is not
 * a chat.
 */
public class ActualizacionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Actualizacion leer(String json) throws Exception {
        return Actualizacion.leer(mapper.readTree(json));
    }

    @Test
    public void aStartWithACodeIsASignUp() throws Exception {
        assertEquals(new Actualizacion.Alta(10, "42", "AbC_123", "Ana"), leer("""
                {"update_id":10,"message":{"chat":{"id":42},"from":{"first_name":"Ana"},"text":"/start AbC_123"}}"""));
    }

    @Test
    public void aStartWithoutACodeIsNothing() throws Exception {
        assertInstanceOf(Actualizacion.Otra.class, leer("""
                {"update_id":11,"message":{"chat":{"id":42},"text":"/start"}}"""));
        assertInstanceOf(Actualizacion.Otra.class, leer("""
                {"update_id":11,"message":{"chat":{"id":42},"text":"/start ../../etc"}}"""));
    }

    @Test
    public void leavingAndButtons() throws Exception {
        assertEquals(new Actualizacion.Baja(12, "42"), leer("""
                {"update_id":12,"message":{"chat":{"id":42},"text":"/baja"}}"""));
        assertEquals(new Actualizacion.Pulsacion(13, "cb1", "42", "g:7:ab"), leer("""
                {"update_id":13,"callback_query":{"id":"cb1","data":"g:7:ab","message":{"chat":{"id":42}}}}"""));
    }

    @Test
    public void everythingElseIsIgnored() throws Exception {
        assertInstanceOf(Actualizacion.Otra.class, leer("""
                {"update_id":14,"message":{"chat":{"id":42},"text":"hola"}}"""));
        assertInstanceOf(Actualizacion.Otra.class, leer("{\"update_id\":15}"));
        assertEquals(15, leer("{\"update_id\":15}").updateId());
    }

}
