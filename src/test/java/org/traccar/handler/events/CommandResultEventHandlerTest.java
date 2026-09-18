package org.traccar.handler.events;

import org.junit.jupiter.api.Test;
import org.traccar.BaseTest;
import org.traccar.database.RegistroOrdenes;
import org.traccar.model.Event;
import org.traccar.model.Position;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class CommandResultEventHandlerTest extends BaseTest {

    @Test
    public void testCommandResultEventHandler() throws Exception {

        // The handler now also stamps the answer on the command that was
        // waiting for it. That needs storage, which this test does not have,
        // so a stub records the call instead: what is checked here is that the
        // event still comes out, and that the answer reached the register.
        var anotadas = new ArrayList<String>();
        RegistroOrdenes registro = new RegistroOrdenes(null) {
            @Override
            public boolean contestar(long deviceId, String respuesta) {
                anotadas.add(respuesta);
                return true;
            }
        };
        CommandResultEventHandler commandResultEventHandler = new CommandResultEventHandler(registro);

        Position position = new Position();
        position.set(Position.KEY_RESULT, "Test Result");
        List<Event> events = new ArrayList<>();
        commandResultEventHandler.analyzePosition(position, events::add);
        assertFalse(events.isEmpty());
        Event event = events.iterator().next();
        assertEquals(Event.TYPE_COMMAND_RESULT, event.getType());
        assertEquals(List.of("Test Result"), anotadas);
    }

}
