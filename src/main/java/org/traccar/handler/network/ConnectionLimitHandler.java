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
package org.traccar.handler.network;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Closes connections from an address that opens too many of them in a minute.
 *
 * A tracker opens a connection and keeps it. An address opening dozens per
 * minute is either broken or scanning, and in both cases every one of those
 * connections costs a channel, a pipeline and several log lines before the
 * server can tell there is no device behind it.
 *
 * The window is a fixed minute rather than a sliding one. It is cheaper and it
 * cannot leak memory, at the cost of letting a burst that straddles the
 * boundary through at up to twice the limit. That is an acceptable trade for a
 * guard whose job is to stop sustained abuse, not to be exact.
 */
@ChannelHandler.Sharable
public class ConnectionLimitHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionLimitHandler.class);

    private static final long WINDOW = 60000;

    /**
     * Upper bound on the number of addresses tracked at once.
     *
     * Without it the map is itself an attack: one entry per source address,
     * and a spoofed scan across a subnet would grow it without limit. When the
     * bound is reached the oldest windows are dropped, which at worst forgives
     * an address that was already being counted.
     */
    private static final int MAX_TRACKED = 10000;

    private final int limit;
    private final LongSupplier clock;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    private static final class Counter {
        private final AtomicInteger count = new AtomicInteger();
        private volatile long window;
        private volatile boolean reported;
    }

    public ConnectionLimitHandler(int limit) {
        this(limit, System::currentTimeMillis);
    }

    ConnectionLimitHandler(int limit, LongSupplier clock) {
        this.limit = limit;
        this.clock = clock;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        SocketAddress remoteAddress = ctx.channel().remoteAddress();
        if (remoteAddress instanceof InetSocketAddress address && exceeded(address.getHostString())) {
            ctx.close();
        } else {
            super.channelActive(ctx);
        }
    }

    private boolean exceeded(String address) {
        /*
         * Zero means no limit, and it is checked here and not only where the
         * handler is added: a guard that quietly closes every connection when
         * misconfigured is worse than no guard at all.
         */
        if (limit <= 0) {
            return false;
        }
        long now = clock.getAsLong();
        long window = now / WINDOW;
        if (counters.size() > MAX_TRACKED) {
            counters.values().removeIf(counter -> counter.window != window);
        }
        Counter counter = counters.computeIfAbsent(address, key -> new Counter());
        synchronized (counter) {
            if (counter.window != window) {
                counter.window = window;
                counter.count.set(0);
                counter.reported = false;
            }
        }
        if (counter.count.incrementAndGet() <= limit) {
            return false;
        }
        /*
         * Reported once per address per window. Logging every rejection would
         * turn the guard into the amplifier: a scan of a thousand connections
         * writes a thousand lines, which is the cost the guard exists to avoid.
         */
        if (!counter.reported) {
            counter.reported = true;
            LOGGER.warn("Connection limit reached for {} ({} per minute)", address, limit);
        }
        return true;
    }

}
