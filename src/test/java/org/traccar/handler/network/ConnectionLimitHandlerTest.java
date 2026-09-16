package org.traccar.handler.network;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectionLimitHandlerTest {

    private final AtomicLong clock = new AtomicLong(0);

    private ConnectionLimitHandler handler(int limit) {
        return new ConnectionLimitHandler(limit, clock::get);
    }

    /**
     * A channel that reports the address we want, because the limit is per
     * address and an embedded channel reports none by default.
     */
    private boolean connect(ConnectionLimitHandler handler, String address) {
        EmbeddedChannel channel = new EmbeddedChannel() {
            @Override
            protected SocketAddress remoteAddress0() {
                return new InetSocketAddress(address, 12345);
            }
        };
        channel.pipeline().addLast(handler);
        channel.pipeline().fireChannelActive();
        boolean open = channel.isOpen();
        channel.close();
        return open;
    }

    @Test
    public void testUnderLimitPassesThrough() {
        ConnectionLimitHandler handler = handler(3);
        for (int i = 0; i < 3; i++) {
            assertTrue(connect(handler, "10.0.0.1"), "connection " + i + " was closed");
        }
    }

    @Test
    public void testOverLimitIsClosed() {
        ConnectionLimitHandler handler = handler(3);
        for (int i = 0; i < 3; i++) {
            connect(handler, "10.0.0.1");
        }
        assertFalse(connect(handler, "10.0.0.1"));
    }

    /**
     * The point of counting per address. One noisy source must not close the
     * door on a device that happens to reconnect at the same time.
     */
    @Test
    public void testOtherAddressIsUnaffected() {
        ConnectionLimitHandler handler = handler(2);
        for (int i = 0; i < 10; i++) {
            connect(handler, "10.0.0.1");
        }
        assertTrue(connect(handler, "10.0.0.2"));
    }

    @Test
    public void testWindowResets() {
        ConnectionLimitHandler handler = handler(2);
        connect(handler, "10.0.0.1");
        connect(handler, "10.0.0.1");
        assertFalse(connect(handler, "10.0.0.1"));
        clock.addAndGet(60000);
        assertTrue(connect(handler, "10.0.0.1"), "the window did not reset after a minute");
    }

    /**
     * Zero means no limit. A guard that cannot be turned off is a guard that
     * gets deployed off.
     */
    @Test
    public void testZeroDisables() {
        ConnectionLimitHandler handler = handler(0);
        for (int i = 0; i < 50; i++) {
            assertTrue(connect(handler, "10.0.0.1"), "connection " + i + " was closed with no limit set");
        }
    }

}
