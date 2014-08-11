/*
 * Copyright 2014 Higher Frequency Trading
 * <p/>
 * http://www.higherfrequencytrading.com
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.collections;

import net.openhft.lang.io.AbstractBytes;
import net.openhft.lang.io.ByteBufferBytes;
import net.openhft.lang.model.constraints.NotNull;
import net.openhft.lang.model.constraints.Nullable;
import net.openhft.lang.thread.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.Math.round;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static net.openhft.collections.VanillaSharedReplicatedHashMap.MAX_UNSIGNED_SHORT;

/**
 * @author Rob Austin.
 */
abstract class AbstractChannelReplicator implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractChannelReplicator.class);
    static final int BITS_IN_A_BYTE = 8;
    static final int SIZE_OF_SHORT = 2;

    private final ExecutorService executorService;
    final Selector selector;
    final Set<Closeable> closeables = Collections.synchronizedSet(new LinkedHashSet<Closeable>());
    private final Queue<Runnable> pendingRegistrations = new ConcurrentLinkedQueue<Runnable>();
    @Nullable
    private final Throttler throttler;

    AbstractChannelReplicator(String name, AbstractReplicationBuilder<?> replicationBuilder,
                              int maxEntrySizeBytes)
            throws IOException {
        executorService = Executors.newSingleThreadExecutor(
                new NamedThreadFactory(name, true));
        selector = Selector.open();
        closeables.add(selector);

        throttler = replicationBuilder.throttle(DAYS) > 0 ?
                new Throttler(selector,
                        replicationBuilder.throttleBucketInterval(MILLISECONDS),
                        maxEntrySizeBytes, replicationBuilder.throttle(DAYS)) : null;
    }

    void addPendingRegistration(Runnable registration) {
        pendingRegistrations.add(registration);
    }

    void registerPendingRegistrations() throws ClosedChannelException {
        for (Runnable runnable = pendingRegistrations.poll(); runnable != null;
             runnable = pendingRegistrations.poll()) {
            try {
                runnable.run();
            } catch (Exception e) {
                LOG.info("", e);
            }
        }
    }

    abstract void process() throws IOException;

    final void start() {
        executorService.execute(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            process();
                        } catch (ClosedSelectorException e) {
                            if (LOG.isDebugEnabled())
                                LOG.debug("", e);
                        } catch (Exception e) {
                            LOG.error("", e);
                        }
                    }
                }
        );
    }


    @Override
    public void close() {
        synchronized (this.closeables) {
            for (Closeable closeable : this.closeables) {
                try {
                    closeable.close();
                } catch (IOException e) {
                    LOG.error("", e);
                }
            }
            closeables.clear();
        }
        executorService.shutdownNow();
    }

    void closeEarlyAndQuietly(SelectableChannel channel) {
        try {
            if (throttler != null)
                throttler.remove(channel);
            closeables.remove(channel);
            channel.close();
        } catch (IOException ex) {
            // do nothing
        }
    }

    /**
     * forces the TCP and UDP replicators to re-bootstrap This is called whenever a new SHM is added to a
     * custer
     */
    public abstract void forceBootstrap();

    void checkThrottleInterval() throws ClosedChannelException {
        if (throttler != null)
            throttler.checkThrottleInterval();
    }

    void contemplateThrottleWrites(int bytesJustWritten) throws ClosedChannelException {
        if (throttler != null)
            throttler.contemplateThrottleWrites(bytesJustWritten);
    }

    void throttle(SelectableChannel channel) {
        if (throttler != null)
            throttler.add(channel);
    }

    /**
     * throttles 'writes' to ensure the network is not swamped, this is achieved by periodically
     * de-registering the write selector during periods of high volume.
     */
    static class Throttler {

        private final Selector selector;
        private final Set<SelectableChannel> channels = new CopyOnWriteArraySet<SelectableChannel>();
        private final long throttleInterval;
        private final long maxBytesInInterval;

        private long lastTime = System.currentTimeMillis();
        private long bytesWritten;

        Throttler(@NotNull Selector selector,
                  long throttleIntervalInMillis,
                  long serializedEntrySize,
                  long bitsPerDay) {

            this.selector = selector;
            this.throttleInterval = throttleIntervalInMillis;
            double bytesPerMs = ((double) bitsPerDay) / DAYS.toMillis(1) / BITS_IN_A_BYTE;
            this.maxBytesInInterval = round(bytesPerMs * throttleInterval) - serializedEntrySize;
        }

        public void add(SelectableChannel selectableChannel) {
            channels.add(selectableChannel);
        }

        public void remove(SelectableChannel socketChannel) {
            channels.remove(socketChannel);
        }

        /**
         * re register the 'write' on the selector if the throttleInterval has passed
         *
         * @throws java.nio.channels.ClosedChannelException
         */
        public void checkThrottleInterval() throws ClosedChannelException {
            final long time = System.currentTimeMillis();

            if (lastTime + throttleInterval >= time)
                return;

            lastTime = time;
            bytesWritten = 0;


            if (LOG.isDebugEnabled())
                LOG.debug("Restoring OP_WRITE on all channels");

            for (SelectableChannel selectableChannel : channels) {

                final SelectionKey selectionKey = selectableChannel.keyFor(selector);
                if (selectionKey != null)
                    selectionKey.interestOps(selectionKey.interestOps() | OP_WRITE);

            }
        }


        /**
         * checks the number of bytes written in this interval, if this number of bytes exceeds a threshold,
         * the selected will de-register the socket that is being written to, until the interval is finished.
         *
         * @throws ClosedChannelException
         */
        public void contemplateThrottleWrites(int bytesJustWritten)
                throws ClosedChannelException {
            bytesWritten += bytesJustWritten;
            if (bytesWritten > maxBytesInInterval) {
                for (SelectableChannel channel : channels) {
                    final SelectionKey selectionKey = channel.keyFor(selector);
                    if (selectionKey != null)
                        selectionKey.interestOps(selectionKey.interestOps() & ~OP_WRITE);

                    if (LOG.isDebugEnabled())
                        LOG.debug("Throttling UDP writes");
                }
            }
        }
    }

    /**
     * details about the socket connection
     */
    static class Details {

        private final InetSocketAddress address;
        private final byte localIdentifier;

        Details(@NotNull final InetSocketAddress address, final byte localIdentifier) {
            this.address = address;
            this.localIdentifier = localIdentifier;
        }

        public InetSocketAddress address() {
            return address;
        }

        public byte localIdentifier() {
            return localIdentifier;
        }

        @Override
        public String toString() {
            return "Details{" +
                    "address=" + address +
                    ", localIdentifier=" + localIdentifier +
                    '}';
        }
    }

    static class EntryCallback extends Replica.AbstractEntryCallback {

        private final Replica.EntryExternalizable externalizable;
        private final ByteBufferBytes in;

        EntryCallback(@NotNull final Replica.EntryExternalizable externalizable,
                      @NotNull final ByteBufferBytes in) {
            this.externalizable = externalizable;
            this.in = in;
        }

        @Override
        public boolean onEntry(final AbstractBytes entry, final int chronicleId) {
            in.skip(SIZE_OF_SHORT);
            final long start = in.position();
            externalizable.writeExternalEntry(entry, in, chronicleId);

            if (in.position() == start) {
                in.position(in.position() - SIZE_OF_SHORT);
                return false;
            }

            // write the length of the entry, just before the start, so when we read it back
            // we read the length of the entry first and hence know how many preceding writer to read
            final int entrySize = (int) (in.position() - start);
            if (entrySize > MAX_UNSIGNED_SHORT)
                throw new IllegalStateException("entry too large, the entry size=" + entrySize + ", " +
                        "entries are limited to a size of " + MAX_UNSIGNED_SHORT);
            in.writeUnsignedShort(start - SIZE_OF_SHORT, entrySize);

            return true;
        }
    }

    abstract class AbstractConnector {

        private final String name;
        private int connectionAttempts = 0;
        private volatile SelectableChannel socketChannel;

        public AbstractConnector(String name) {
            this.name = name;
        }

        abstract SelectableChannel doConnect() throws IOException, InterruptedException;

        /**
         * connects or reconnects, but first waits a period of time proportional to the {@code
         * connectionAttempts}
         */
        public final void connectLater() {
            try {
                if (socketChannel != null)
                    socketChannel.close();
                socketChannel = null;

            } catch (IOException e1) {
                LOG.error("", e1);
            }

            final long reconnectionInterval = connectionAttempts * 100;
            if (connectionAttempts < 5)
                connectionAttempts++;
            doConnect(reconnectionInterval);

        }

        /**
         * connects or reconnects immediately
         */
        public void connect() {
            doConnect(0);
        }


        /**
         * @param reconnectionInterval the period to wait before connecting
         */
        private void doConnect(final long reconnectionInterval) {

            final Thread thread = new Thread(new Runnable() {

                public void run() {
                    SelectableChannel socketChannel = null;
                    try {
                        if (reconnectionInterval > 0)
                            Thread.sleep(reconnectionInterval);

                        synchronized (closeables) {
                            socketChannel = doConnect();
                            if (socketChannel == null)
                                return;

                            closeables.add(socketChannel);
                            AbstractConnector.this.socketChannel = socketChannel;
                        }

                    } catch (Exception e) {
                        if (socketChannel != null)
                            try {
                                socketChannel.close();
                                AbstractConnector.this.socketChannel = null;
                            } catch (IOException e1) {
                                //
                            }
                        LOG.error("", e);
                    }

                }
            });

            thread.setName(name);
            thread.setDaemon(true);
            thread.start();
        }

        public void setSuccessfullyConnected() {
            connectionAttempts = 0;
        }
    }

    interface ChannelReplicatorBuilder {
    }

}
