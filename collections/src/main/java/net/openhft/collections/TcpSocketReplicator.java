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

import net.openhft.lang.io.ByteBufferBytes;
import net.openhft.lang.io.NativeBytes;
import net.openhft.lang.thread.NamedThreadFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;

import static java.nio.channels.SelectionKey.*;
import static java.util.concurrent.Executors.newSingleThreadExecutor;

/**
 * Used with a {@see net.openhft.collections.ReplicatedSharedHashMap} to send data between the maps using a
 * socket connection
 *
 * {@see net.openhft.collections.OutSocketReplicator}
 *
 * @author Rob Austin.
 */
class TcpSocketReplicator implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(TcpSocketReplicator.class.getName());
    private static final int BUFFER_SIZE = 0x100000; // 1Mb

    private final ExecutorService executorService;
    private final CopyOnWriteArraySet<Closeable> closeables = new CopyOnWriteArraySet<Closeable>();
    private final Connector connector = new Connector();
    private final Selector selector;


    TcpSocketReplicator(@NotNull final ReplicatedSharedHashMap map,
                        final int serializedEntrySize,
                        @NotNull final ReplicatedSharedHashMap.EntryExternalizable externalizable,
                        @NotNull final TcpReplication tcpReplication) throws IOException {


        this.selector = Selector.open();

        this.executorService = newSingleThreadExecutor(
                new NamedThreadFactory("TcpSocketReplicator-" + map.identifier(), true));

        this.executorService.execute(new Runnable() {
                                         @Override
                                         public void run() {
                                             try {
                                                 process(map,
                                                         serializedEntrySize,
                                                         externalizable,
                                                         tcpReplication);

                                             } catch (IOException e) {
                                                 if (selector.isOpen())
                                                     LOG.error("", e);
                                             }
                                         }
                                     }
        );
    }


    private void process(@NotNull final ReplicatedSharedHashMap map,
                         final int serializedEntrySize,
                         @NotNull final ReplicatedSharedHashMap.EntryExternalizable externalizable,
                         @NotNull final TcpReplication tcpReplication) throws IOException {

        final byte identifier = map.identifier();
        final short packetSize = tcpReplication.packetSize();

        try {

            final Queue<SelectableChannel> pendingRegistrations
                    = connector.asyncConnect(identifier, tcpReplication);

            for (; ; ) {

                register(pendingRegistrations);

                if (!selector.isOpen())
                    return;

                final int nSelectedKeys = selector.select(100);


                if (nSelectedKeys == 0) {
                    continue;    // nothing to do
                }

                // its less resource intensive to set this less frequently and use an approximation
                long approxTime = System.currentTimeMillis();

                final long heartBeatInterval = tcpReplication.heartBeatInterval();

                // we add a 10% safety margin, due time fluctuations on the network
                final long approxTimeOutTime = approxTime - (long) (heartBeatInterval * 1.10);

                final Set<SelectionKey> selectedKeys = selector.selectedKeys();

                for (final SelectionKey key : selectedKeys) {
                    try {
                        if (!key.isValid())
                            continue;

                        if (key.isAcceptable())
                            doAccept(key, serializedEntrySize, externalizable, packetSize,
                                    map.identifier(), heartBeatInterval);

                        if (key.isConnectable())
                            onConnect(packetSize, serializedEntrySize, externalizable, key,
                                    map.identifier(), pendingRegistrations, heartBeatInterval);

                        if (key.isReadable())
                            onRead(map, key, approxTime);

                        if (key.isWritable())
                            onWrite(key, approxTime);

                        checkHeartbeat(key, approxTimeOutTime, identifier, pendingRegistrations);

                    } catch (ClosedSelectorException e) {
                        quietClose(key, e);
                    } catch (ClosedChannelException e) {
                        quietClose(key, e);
                    } catch (ConnectException e) {
                        quietClose(key, e);
                    } catch (Exception e) {
                        LOG.info("", e);
                        close(key);
                    }

                }
                selectedKeys.clear();

            }

        } catch (ClosedSelectorException e) {
            if (LOG.isDebugEnabled())
                LOG.debug("", e);
        } catch (ClosedChannelException e) {
            if (LOG.isDebugEnabled())
                LOG.debug("", e);
        } catch (ConnectException e) {
            if (LOG.isDebugEnabled())
                LOG.debug("", e);
        } catch (Exception e) {
            LOG.error("", e);
        } finally {
            if (selector != null)
                try {
                    selector.close();
                } catch (IOException e) {
                    if (LOG.isDebugEnabled())
                        LOG.debug("", e);
                }
            close();
        }
    }

    /**
     * closes by only logs the exception at debug
     *
     * @param key
     * @param e
     */
    private void quietClose(SelectionKey key, Exception e) {
        if (LOG.isDebugEnabled())
            LOG.debug("", e);
        close(key);
    }

    private void close(SelectionKey key) {
        try {
            key.channel().provider().openSocketChannel().close();
            key.channel().close();
        } catch (IOException ex) {
            // do nothing
        }
    }

    private void checkHeartbeat(SelectionKey key,
                                final long timeOutTime,
                                final byte identifier,
                                final Queue<SelectableChannel> pendingRegistrations) throws ConnectException {

        final Attached attached = (Attached) key.attachment();

        if (attached == null)
            return;

        final boolean isServerConnection = key.channel() instanceof ServerSocketChannel;
        if (isServerConnection)
            return;


        final SocketChannel channel = (SocketChannel) key.channel();

        final long lastHeartBeat = attached.entryReader.lastHeartBeatReceived;
        if (timeOutTime > lastHeartBeat) {
            connector.asyncReconnect(identifier, channel.socket(), pendingRegistrations);
            throw new ConnectException("LostConnection : missed heartbeat from identifier=" + attached
                    .remoteIdentifier + " (attempting an automatic reconnection)");
        }
    }


    private class Connector {

        private final Map<InetSocketAddress, Integer> connectionAttempt = new HashMap<InetSocketAddress, Integer>();

        /**
         * used to connect both client and server sockets
         *
         * @param identifier
         * @param socket              a queue containing the SocketChannel as they become connected
         * @param pendingRegistration
         * @return
         */
        private void asyncReconnect(
                final byte identifier,
                final Socket socket,
                final Queue<SelectableChannel> pendingRegistration) {

            final InetSocketAddress inetSocketAddress = new InetSocketAddress(socket.getInetAddress()
                    .getHostName(), socket.getPort());

            final Integer lastAttempts = connectionAttempt.get(connectionAttempt);
            final Integer attempts = lastAttempts == null ? 1 : lastAttempts + 1;
            connectionAttempt.put(inetSocketAddress, attempts);

            int reconnectionInterval = (attempts * attempts) * 100;

            // limit the reconnectionInterval to 20 seconds
            if (reconnectionInterval > 20 * 1000)
                reconnectionInterval = 20 * 1000;

            asyncConnect0(identifier, null, Collections.singleton(inetSocketAddress), reconnectionInterval,
                    pendingRegistration);
        }


        /**
         * used to connect both client and server sockets
         *
         * @param identifier
         * @param tcpReplication
         * @return
         */
        private Queue<SelectableChannel> asyncConnect(
                final byte identifier,
                TcpReplication tcpReplication) {

            final Queue<SelectableChannel> destination = new ConcurrentLinkedQueue<SelectableChannel>();
            return asyncConnect0(identifier, tcpReplication.serverInetSocketAddress(),
                    tcpReplication.endpoints(),
                    0, destination);

        }

        void setSuccessfullyConnected(final Socket socket) {
            final InetSocketAddress inetSocketAddress = new InetSocketAddress(socket.getInetAddress()
                    .getHostName(), socket.getPort());

            connectionAttempt.remove(inetSocketAddress);
        }


        /**
         * used to connect both client and server sockets
         *
         * @param identifier
         * @param clientEndpoints      a queue containing the SocketChannel as they become connected
         * @param reconnectionInterval
         * @param destination
         * @return
         */
        private Queue<SelectableChannel> asyncConnect0(
                final byte identifier,
                final @Nullable SocketAddress serverEndpoint,
                final @NotNull Set<? extends SocketAddress> clientEndpoints,
                final long reconnectionInterval,
                Queue<SelectableChannel> destination) {

            final Queue<SelectableChannel> result = destination == null ? new
                    ConcurrentLinkedQueue<SelectableChannel>() : destination;

            abstract class AbstractConnector implements Runnable {

                private final SocketAddress address;

                abstract SelectableChannel connect(final SocketAddress address, final byte identifier)
                        throws IOException, InterruptedException;

                AbstractConnector(@NotNull final SocketAddress address) {
                    this.address = address;
                }

                public void run() {
                    try {

                        final SelectableChannel socketChannel = connect(this.address, identifier);
                        closeables.add(socketChannel);
                        result.add(socketChannel);
                    } catch (InterruptedException e) {
                        // do nothing
                    } catch (IOException e) {
                        LOG.error("", e);
                    }

                }

            }

            class ServerConnector extends AbstractConnector {

                ServerConnector(@NotNull final SocketAddress address) {
                    super(address);
                }

                SelectableChannel connect(@NotNull final SocketAddress address, final byte identifier) throws
                        IOException, InterruptedException {
                    Thread.sleep(reconnectionInterval);
                    ServerSocketChannel serverChannel = ServerSocketChannel.open();
                    final ServerSocket serverSocket = serverChannel.socket();
                    closeables.add(serverSocket);
                    closeables.add(serverChannel);
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(address);
                    serverChannel.socket().setReceiveBufferSize(BUFFER_SIZE);
                    serverChannel.configureBlocking(false);
                    return serverChannel;
                }

            }

            class ClientConnector extends AbstractConnector {

                ClientConnector(SocketAddress address) {
                    super(address);
                }

                /**
                 * blocks until connected
                 *
                 * @param endpoint   the endpoint to connect
                 * @param identifier used for logging only
                 * @throws IOException if we are not successful at connection
                 */
                SelectableChannel connect(final SocketAddress endpoint,
                                          final byte identifier)
                        throws IOException, InterruptedException {

                    Thread.sleep(reconnectionInterval);
                    boolean success = false;

                    for (; ; ) {

                        final SocketChannel socketChannel = SocketChannel.open();

                        try {

                            socketChannel.configureBlocking(false);
                            socketChannel.socket().setKeepAlive(true);
                            socketChannel.socket().setReuseAddress(false);
                            socketChannel.socket().setSoLinger(false, 0);
                            socketChannel.socket().setSoTimeout(100);
                            socketChannel.socket().setTcpNoDelay(true);
                            socketChannel.socket().setReceiveBufferSize(BUFFER_SIZE);
                            closeables.add(socketChannel.socket());
                            socketChannel.connect(endpoint);
                            closeables.add(socketChannel);

                            if (LOG.isDebugEnabled())
                                LOG.debug("successfully connected to {}, local-id={}", endpoint, identifier);

                            success = true;
                            return socketChannel;

                        } catch (IOException e) {
                            throw e;
                        } finally {
                            if (!success)
                                try {

                                    try {
                                        socketChannel.socket().close();
                                    } catch (Exception e) {
                                        LOG.error("", e);
                                    }

                                    socketChannel.close();
                                } catch (IOException e) {
                                    LOG.error("", e);
                                }
                        }

                    }

                }
            }

            if (serverEndpoint != null) {
                final Thread thread = new Thread(new ServerConnector(serverEndpoint));
                thread.setName("server-tcp-connector-" + serverEndpoint);
                thread.setDaemon(true);
                thread.start();
            }

            for (final SocketAddress endpoint : clientEndpoints) {
                final Thread thread = new Thread(new ClientConnector(endpoint));
                thread.setName("client-tcp-connector-" + endpoint);
                thread.setDaemon(true);
                thread.start();
            }

            return result;
        }
    }


    private void onConnect(short packetSize,
                           int serializedEntrySize,
                           @NotNull final ReplicatedSharedHashMap.EntryExternalizable externalizable,
                           @NotNull final SelectionKey key,
                           final byte identifier, Queue<SelectableChannel> pendingRegistrations, final long heartBeatIntervalMilliseconds) throws IOException, InterruptedException {
        final SocketChannel channel = (SocketChannel) key.channel();
        if (channel == null)
            return;
        try {
            if (!channel.finishConnect()) {
                return;
            }
        } catch (ConnectException e) {
            connector.asyncReconnect(identifier, channel.socket(), pendingRegistrations);
            throw e;
        }

        connector.setSuccessfullyConnected(channel.socket());

        channel.configureBlocking(false);
        channel.socket().setKeepAlive(true);
        channel.socket().setSoTimeout(100);
        channel.socket().setSoLinger(false, 0);

        final Attached attached = new Attached();

        attached.entryReader = new TcpSocketChannelEntryReader(serializedEntrySize,
                externalizable, packetSize);

        attached.entryWriter = new TcpSocketChannelEntryWriter(serializedEntrySize,
                externalizable, packetSize, heartBeatIntervalMilliseconds);

        channel.register(selector, OP_WRITE | OP_READ, attached);

        // register it with the selector and store the ModificationIterator for this key
        attached.entryWriter.identifierToBuffer(identifier);
    }


    @Override
    public void close() throws IOException {

        for (Closeable closeable : this.closeables) {

            try {
                closeable.close();
            } catch (IOException e) {
                LOG.error("", e);
            }

        }

        closeables.clear();
        executorService.shutdownNow();
        selector.close();
    }


    private void doAccept(SelectionKey key,
                          final int serializedEntrySize,
                          final ReplicatedSharedHashMap.EntryExternalizable externalizable,
                          final short packetSize,
                          final int localIdentifier, final long heartBeatPeriod) throws IOException {
        final ServerSocketChannel server = (ServerSocketChannel) key.channel();
        final SocketChannel channel = server.accept();
        channel.configureBlocking(false);
        channel.socket().setReuseAddress(true);
        channel.socket().setKeepAlive(true);
        channel.socket().setSoTimeout(100);
        channel.socket().setSoLinger(false, 0);
        final Attached attached = new Attached();
        channel.register(selector, OP_WRITE | OP_READ, attached);

        attached.entryReader = new TcpSocketChannelEntryReader(serializedEntrySize,
                externalizable, packetSize);

        attached.entryWriter = new TcpSocketChannelEntryWriter(serializedEntrySize,
                externalizable, packetSize, heartBeatPeriod);

        attached.entryWriter.identifierToBuffer(localIdentifier);
    }

    /**
     * used to exchange identifiers and timestamps between the server and client
     *
     * @param map
     * @param attached
     * @param approxTime
     * @throws java.io.IOException
     * @throws InterruptedException
     */
    private void doHandShaking(final ReplicatedSharedHashMap map,
                               final Attached attached,
                               final long approxTime) throws IOException, InterruptedException {

        if (attached.remoteIdentifier == Byte.MIN_VALUE) {

            final byte remoteIdentifier = attached.entryReader.identifierFromBuffer();

            if (remoteIdentifier != Byte.MIN_VALUE) {
                attached.remoteIdentifier = remoteIdentifier;

                if (LOG.isDebugEnabled()) {
                    LOG.debug("server-connection id={}, remoteIdentifier={}",
                            map.identifier(), remoteIdentifier);
                }

                if (remoteIdentifier == map.identifier())
                    throw new IllegalStateException("Non unique identifiers id=" + map.identifier());

                attached.remoteModificationIterator = map.acquireModificationIterator(remoteIdentifier);
                attached.entryWriter.timestampToBuffer(map.lastModificationTime(remoteIdentifier));
            }
        }

        if (attached.remoteIdentifier != Byte.MIN_VALUE &&
                attached.remoteTimestamp == Long.MIN_VALUE) {

            attached.remoteTimestamp = attached.entryReader.timeStampFromBuffer();

            if (attached.remoteTimestamp != Long.MIN_VALUE) {
                attached.remoteModificationIterator.dirtyEntries(attached.remoteTimestamp);
                attached.setHandShakingComplete();
                attached.entryReader.entriesFromBuffer(approxTime);
            }
        }
    }

    private void onWrite(final SelectionKey key, final long approxTime) throws InterruptedException, IOException {
        final SocketChannel socketChannel = (SocketChannel) key.channel();
        final Attached attached = (Attached) key.attachment();

        if (attached.remoteModificationIterator != null)
            attached.entryWriter.entriesToBuffer(
                    attached.remoteModificationIterator);


        attached.entryWriter.writeBufferToSocket(socketChannel, attached.isHandShakingComplete(), approxTime);
    }

    private void onRead(final ReplicatedSharedHashMap map,
                        final SelectionKey key, final long approxTime) throws IOException, InterruptedException {

        final SocketChannel socketChannel = (SocketChannel) key.channel();
        final Attached attached = (Attached) key.attachment();

        if (attached.entryReader.readSocketToBuffer(socketChannel) <= 0)
            return;

        if (attached.isHandShakingComplete())
            attached.entryReader.entriesFromBuffer(approxTime);
        else
            doHandShaking(map, attached, approxTime);

    }

    /**
     * Registers the SocketChannel with the selector
     *
     * @param selectableChannels the SelectableChannel to register
     * @throws ClosedChannelException
     */
    private void register(@NotNull final Queue<SelectableChannel> selectableChannels) throws ClosedChannelException {
        for (SelectableChannel sc = selectableChannels.poll(); sc != null; sc = selectableChannels.poll()) {
            if (sc instanceof ServerSocketChannel)
                sc.register(selector, OP_ACCEPT);
            else
                sc.register(selector, OP_CONNECT);
        }
    }

    static class Attached {

        public TcpSocketChannelEntryReader entryReader;
        public ReplicatedSharedHashMap.ModificationIterator remoteModificationIterator;
        public long remoteTimestamp = Long.MIN_VALUE;
        private boolean handShakingComplete;
        public byte remoteIdentifier = Byte.MIN_VALUE;
        public TcpSocketChannelEntryWriter entryWriter;


        boolean isHandShakingComplete() {
            return handShakingComplete;
        }

        void setHandShakingComplete() {
            handShakingComplete = true;
        }

    }

    /**
     * @author Rob Austin.
     */
    private static class TcpSocketChannelEntryWriter {

        private static final Logger LOG = LoggerFactory.getLogger(TcpSocketChannelEntryWriter.class);

        private final ByteBuffer out;
        private final ByteBufferBytes in;
        private final EntryCallback entryCallback;
        private final int serializedEntrySize;
        private long lastSentTime;
        private long heartBeatPeriod;

        /**
         * @param serializedEntrySize the size of the entry
         * @param externalizable      supports reading and writing serialize entries
         * @param packetSize          the max TCP/IP packet size
         * @param heartBeatPeriod
         */
        TcpSocketChannelEntryWriter(final int serializedEntrySize,
                                    @NotNull final ReplicatedSharedHashMap.EntryExternalizable externalizable,
                                    int packetSize,
                                    long heartBeatPeriod) {
            this.serializedEntrySize = serializedEntrySize;
            this.heartBeatPeriod = heartBeatPeriod;
            out = ByteBuffer.allocateDirect(packetSize + serializedEntrySize);
            in = new ByteBufferBytes(out);
            entryCallback = new EntryCallback(externalizable, in);
        }


        /**
         * writes the timestamp into the buffer
         *
         * @param localIdentifier the current nodes identifier
         */
        void identifierToBuffer(final int localIdentifier) {
            in.writeByte(localIdentifier);
        }

        /**
         * sends the identity and timestamp of this node to a remote node
         *
         * @param timeStampOfLastMessage the last timestamp we received a message from that node
         */
        void timestampToBuffer(final long timeStampOfLastMessage) {
            in.writeLong(timeStampOfLastMessage);
        }

        /**
         * writes all the entries that have changed, to the buffer which will later be written to TCP/IP
         *
         * @param modificationIterator Holds a record of which entries have modification.
         */
        void entriesToBuffer(@NotNull final ReplicatedSharedHashMap.ModificationIterator modificationIterator) throws InterruptedException {

            final long start = in.position();

            for (; ; ) {

                final boolean wasDataRead = modificationIterator.nextEntry(entryCallback);

                // if there was no data written to the buffer and we have not written any more data to
                // the buffer, then give up
                if (!wasDataRead && in.position() == start)
                    return;

                // if we have space in the buffer to write more data and we just wrote data into the
                // buffer then let try and write some more, else if we failed to just write data
                // {@code wasDataRead} then we will send what we have
                if (in.remaining() > serializedEntrySize && wasDataRead)
                    continue;

                // we've filled up one writer lets give another channel a chance to send data
                return;
            }

        }

        /**
         * writes the contents of the buffer to the socket
         *
         * @param socketChannel         the socket to publish the buffer to
         * @param isHandshakingComplete
         * @param approxTime
         * @throws IOException
         */
        public void writeBufferToSocket(SocketChannel socketChannel, boolean isHandshakingComplete, final long approxTime) throws IOException {
            // if we still have some unwritten writer from last time
            if (in.position() > 0) {
                lastSentTime = approxTime;
                out.limit((int) in.position());

                final int len = socketChannel.write(out);

                if (LOG.isDebugEnabled())
                    LOG.debug("bytes-written=" + len);

                if (out.remaining() == 0) {
                    out.clear();
                    in.clear();
                } else {
                    out.compact();
                    in.position(out.position());
                    in.limit(in.capacity());
                    out.clear();
                }
                return;
            }

            final long currentTimeMillis = approxTime;
            if (isHandshakingComplete && lastSentTime + heartBeatPeriod < currentTimeMillis) {
                lastSentTime = approxTime;
                writeHeartBeatToBuffer();
            }

        }

        private void writeHeartBeatToBuffer() {
            in.writeUnsignedShort(0);
        }
    }


    /**
     * {@inheritDoc}
     */
    static class EntryCallback extends VanillaSharedReplicatedHashMap.EntryCallback {

        private final ReplicatedSharedHashMap.EntryExternalizable externalizable;
        private final ByteBufferBytes in;

        EntryCallback(@NotNull final ReplicatedSharedHashMap.EntryExternalizable externalizable, @NotNull final ByteBufferBytes in) {
            this.externalizable = externalizable;
            this.in = in;
        }

        @Override
        public boolean onEntry(final NativeBytes entry) {

            in.skip(2);
            final long start = (int) in.position();
            externalizable.writeExternalEntry(entry, in);

            if (in.position() - start == 0) {
                in.position(in.position() - 2);
                return false;
            }

            // write the length of the entry, just before the start, so when we read it back
            // we read the length of the entry first and hence know how many preceding writer to read
            final int entrySize = (int) (in.position() - start);
            in.writeUnsignedShort(start - 2L, entrySize);

            return true;
        }
    }


    /**
     * Reads map entries from a socket, this could be a client or server socket
     *
     * @author Rob Austin.
     */
    private static class TcpSocketChannelEntryReader {

        public static final int SIZE_OF_UNSIGNED_SHORT = 2;
        private final ReplicatedSharedHashMap.EntryExternalizable externalizable;
        private final int serializedEntrySize;
        private final ByteBuffer in;
        private final ByteBufferBytes out;

        // we use Integer.MIN_VALUE as N/A
        private int sizeOfNextEntry = Integer.MIN_VALUE;
        private long lastHeartBeatReceived = System.currentTimeMillis();

        /**
         * @param serializedEntrySize the maximum size of an entry include the meta data
         * @param externalizable      supports reading and writing serialize entries
         * @param packetSize          the estimated size of a tcp/ip packet
         */
        TcpSocketChannelEntryReader(final int serializedEntrySize,
                                    @NotNull final ReplicatedSharedHashMap.EntryExternalizable externalizable,
                                    final short packetSize) {
            this.serializedEntrySize = serializedEntrySize;
            in = ByteBuffer.allocate(packetSize + serializedEntrySize);
            this.externalizable = externalizable;
            out = new ByteBufferBytes(in);
            out.limit(0);
            in.clear();
        }

        /**
         * reads from the socket and writes the contents to the buffer
         *
         * @param socketChannel the  socketChannel to read from
         * @return the number of bytes read
         * @throws IOException
         */
        int readSocketToBuffer(@NotNull final SocketChannel socketChannel) throws IOException {
            compactBuffer();
            final int len = socketChannel.read(in);
            out.limit(in.position());
            return len;
        }

        /**
         * reads entries from the socket till it is empty
         *
         * @param approxTime
         * @throws InterruptedException
         */
        void entriesFromBuffer(final long approxTime) throws InterruptedException {

            for (; ; ) {

                out.limit(in.position());

                // its set to MIN_VALUE when it should be read again
                if (sizeOfNextEntry == Integer.MIN_VALUE) {
                    if (out.remaining() < SIZE_OF_UNSIGNED_SHORT) {
                        return;
                    }

                    sizeOfNextEntry = out.readUnsignedShort();
                }

                // this is the heartbeat
                if (sizeOfNextEntry == 0) {
                    lastHeartBeatReceived = approxTime;
                    continue;
                }

                if (out.remaining() < sizeOfNextEntry) {
                    return;
                }

                final long nextEntryPos = out.position() + sizeOfNextEntry;
                final long limit = out.limit();
                out.limit(nextEntryPos);
                externalizable.readExternalEntry(out);

                out.limit(limit);
                // skip onto the next entry
                out.position(nextEntryPos);

                // to allow the sizeOfNextEntry to be read the next time around
                sizeOfNextEntry = Integer.MIN_VALUE;
            }

        }

        /**
         * compacts the buffer and updates the {@code in} and  {@code out} accordingly
         */
        private void compactBuffer() {

            // the serializedEntrySize used here may not be the maximum size of the entry in its serialized form
            // however, its only use as an indication that the buffer is becoming full and should be compacted
            // the buffer can be compacted at any time
            if (in.position() == 0 || in.remaining() > serializedEntrySize)
                return;

            in.limit(in.position());
            in.position((int) out.position());

            in.compact();
            out.position(0);
        }

        /**
         * @return the identifier or -1 if unsuccessful
         */
        byte identifierFromBuffer() {
            return (out.remaining() >= 1) ? out.readByte() : Byte.MIN_VALUE;
        }

        /**
         * @return the timestamp or -1 if unsuccessful
         */
        long timeStampFromBuffer() {
            return (out.remaining() >= 8) ? out.readLong() : Long.MIN_VALUE;
        }
    }

}

