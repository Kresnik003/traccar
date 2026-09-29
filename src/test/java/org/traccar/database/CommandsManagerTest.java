package org.traccar.database;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.ServerManager;
import org.traccar.broadcast.BroadcastService;
import org.traccar.command.CommandSenderManager;
import org.traccar.model.Command;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.QueuedCommand;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The queuedCommandSent event says which command it was.
 *
 * Asked for on 2026-09-29: the inbox showed "queued command sent" and nothing
 * else, because the event only kept the id of a row deleted on delivery.
 */
public class CommandsManagerTest {

    private static QueuedCommand queued(long id, String type, String data) {
        QueuedCommand command = new QueuedCommand();
        command.setId(id);
        command.setDeviceId(8);
        command.setType(type);
        if (data != null) {
            command.set(Command.KEY_DATA, data);
        }
        return command;
    }

    @Test
    public void aCustomCommandIsItsText() {
        assertEquals("setparam 113:1",
                CommandsManager.textoDeOrden(queued(1, Command.TYPE_CUSTOM, "setparam 113:1")));
    }

    @Test
    public void anyOtherCommandIsItsType() {
        assertEquals(Command.TYPE_ENGINE_STOP,
                CommandsManager.textoDeOrden(queued(1, Command.TYPE_ENGINE_STOP, null)));
    }

    @Test
    public void aCustomCommandWithoutTextIsStillSomething() {
        assertEquals(Command.TYPE_CUSTOM,
                CommandsManager.textoDeOrden(queued(1, Command.TYPE_CUSTOM, " ")));
    }

    @Test
    public void aLongTextIsCut() {
        String largo = "setparam " + "1".repeat(2000);
        assertEquals(CommandsManager.TEXTO_MAXIMO,
                CommandsManager.textoDeOrden(queued(1, Command.TYPE_CUSTOM, largo)).length());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void theEventCarriesTheCommand() throws Exception {
        Storage storage = mock(Storage.class);
        NotificationManager notificationManager = mock(NotificationManager.class);
        when(storage.getObjects(eq(QueuedCommand.class), any()))
                .thenReturn(List.of(queued(36, Command.TYPE_CUSTOM, "setparam 113:1")));
        CommandsManager manager = new CommandsManager(
                storage, mock(ServerManager.class), null, mock(ConnectionManager.class),
                mock(BroadcastService.class), notificationManager, mock(CacheManager.class),
                mock(CommandSenderManager.class));

        manager.readQueuedCommands(8);

        ArgumentCaptor<Map<Event, Position>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationManager).updateEvents(captor.capture());
        Event event = captor.getValue().keySet().iterator().next();
        assertEquals(Event.TYPE_QUEUED_COMMAND_SENT, event.getType());
        assertEquals(36L, event.getLong("id"));
        assertEquals("setparam 113:1", event.getString(CommandsManager.KEY_COMMAND));
    }

}
