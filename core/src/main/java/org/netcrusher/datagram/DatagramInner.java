package org.netcrusher.datagram;

import org.netcrusher.core.buffer.BufferOptions;
import org.netcrusher.core.meter.RateMeterImpl;
import org.netcrusher.core.meter.RateMeters;
import org.netcrusher.core.nio.NioUtils;
import org.netcrusher.core.nio.SelectionKeyControl;
import org.netcrusher.core.reactor.NioReactor;
import org.netcrusher.core.state.BitState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class DatagramInner {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatagramInner.class);

    private static final int DEFAULT_OUTER_CAPACITY = 32;

    private final DatagramCrusher crusher;

    private final NioReactor reactor;

    private final DatagramCrusherSocketOptions socketOptions;

    private final DatagramFilters filters;

    private final InetSocketAddress bindAddress;

    private final InetSocketAddress connectAddress;

    private final DatagramChannel channel;

    private final SelectionKeyControl selectionKeyControl;

    private final ByteBuffer bb;

    private final Map<InetSocketAddress, DatagramOuter> outers;

    private final DatagramQueue incoming;

    private final RateMeterImpl sentByteMeter;

    private final RateMeterImpl readByteMeter;

    private final RateMeterImpl sentPacketMeter;

    private final RateMeterImpl readPacketMeter;

    private final BufferOptions bufferOptions;

    private final State state;

    DatagramInner(
            DatagramCrusher crusher,
            NioReactor reactor,
            DatagramCrusherSocketOptions socketOptions,
            DatagramFilters filters,
            InetSocketAddress bindAddress,
            InetSocketAddress connectAddress,
            BufferOptions bufferOptions) throws IOException
    {
        this.crusher = crusher;
        this.reactor = reactor;
        this.filters = filters;
        this.socketOptions = socketOptions;
        this.bindAddress = bindAddress;
        this.connectAddress = connectAddress;
        this.outers = new ConcurrentHashMap<>(DEFAULT_OUTER_CAPACITY);
        this.incoming = new DatagramQueue(bufferOptions);
        this.bufferOptions = bufferOptions;

        this.sentByteMeter = new RateMeterImpl();
        this.readByteMeter = new RateMeterImpl();
        this.sentPacketMeter = new RateMeterImpl();
        this.readPacketMeter = new RateMeterImpl();

        this.channel = DatagramChannel.open(socketOptions.getProtocolFamily());
        socketOptions.setupSocketChannel(this.channel);
        this.channel.bind(bindAddress);
        this.channel.configureBlocking(false);
        bufferOptions.checkDatagramSocket(channel.socket());

        this.bb = NioUtils.allocaleByteBuffer(channel.socket().getReceiveBufferSize(), bufferOptions.isDirect());

        SelectionKey selectionKey = reactor.getSelector().register(channel, 0, this::callback);
        this.selectionKeyControl = new SelectionKeyControl(selectionKey);

        this.state = new State(State.FROZEN);

        LOGGER.debug("Inner on <{}> is started", bindAddress);
    }

    void close() throws IOException {
        if (state.lockIfNot(State.CLOSED)) {
            try {
                if (state.is(State.OPEN)) {
                    freeze();
                }

                if (!incoming.isEmpty()) {
                    LOGGER.warn("On closing inner has {} incoming datagrams", incoming.size());
                }

                NioUtils.close(channel);

                Iterator<DatagramOuter> outerIterator = outers.values().iterator();
                while (outerIterator.hasNext()) {
                    DatagramOuter outer = outerIterator.next();
                    outerIterator.remove();

                    outer.close();
                    crusher.notifyOuterDeleted(outer);
                }

                reactor.getSelector().wakeup();

                state.set(State.CLOSED);

                LOGGER.debug("Inner on <{}> is closed", bindAddress);
            } finally {
                state.unlock();
            }
        }
    }

    private void closeAll() throws IOException {
        this.close();

        reactor.getScheduler().execute(() -> {
            crusher.close();
            return true;
        });
    }

    void unfreeze() throws IOException {
        if (state.lockIf(State.FROZEN)) {
            try {
                reactor.getSelector().execute(() -> {
                    if (incoming.isEmpty()) {
                        selectionKeyControl.setReadsOnly();
                    } else {
                        selectionKeyControl.setAll();
                    }

                    for (DatagramOuter outer : outers.values()) {
                        if (outer.isFrozen()) {
                            outer.unfreeze();
                        }
                    }

                    return true;
                });

                state.set(State.OPEN);
            } finally {
                state.unlock();
            }
        } else {
            throw new IllegalStateException("Inner is not frozen");
        }
    }

    void freeze() throws IOException {
        if (state.lockIf(State.OPEN)) {
            try {
                reactor.getSelector().execute(() -> {
                    if (selectionKeyControl.isValid()) {
                        selectionKeyControl.setNone();
                    }

                    for (DatagramOuter outer : outers.values()) {
                        if (!outer.isFrozen()) {
                            outer.freeze();
                        }
                    }

                    return true;
                });

                state.set(State.FROZEN);
            } finally {
                state.unlock();
            }
        } else {
            throw new IllegalStateException("Inner is not open on freeze");
        }
    }

    boolean isFrozen() {
        return state.isAnyOf(State.FROZEN | State.CLOSED);
    }

    private void callback(SelectionKey selectionKey) throws IOException {
        if (selectionKey.isWritable()) {
            try {
                handleWritableEvent(false);
            } catch (ClosedChannelException e) {
                LOGGER.debug("Channel is closed on write");
                closeAll();
            } catch (Exception e) {
                LOGGER.error("Exception in inner on write", e);
                closeAll();
            }
        }

        if (selectionKey.isReadable()) {
            try {
                handleReadableEvent();
            } catch (ClosedChannelException e) {
                LOGGER.debug("Channel is closed on read");
                closeAll();
            } catch (Exception e) {
                LOGGER.error("Exception in inner on read", e);
                closeAll();
            }
        }
    }

    void handleWritableEvent(boolean forced) throws IOException {
        int count = 0;
        while (true) {
            final DatagramQueue.BufferEntry entry = incoming.request();
            if (entry == null) {
                break;
            }

            final boolean emptyDatagram = !entry.getBuffer().hasRemaining();
            if (emptyDatagram && (count > 0 || forced)) {
                // due to NIO API problem we can't differ between two cases:
                // - empty datagram is sent (send() returns 0)
                // - no free space in socket buffer (send() returns 0)
                // so we want an empty datagram to be sent first on OP_WRITE
                incoming.retry(entry);
                break;
            }

            final int sent;
            try {
                sent = channel.send(entry.getBuffer(), entry.getAddress());
            } catch (SocketException e) {
                DatagramUtils.rethrowSocketException(e);
                incoming.retry(entry);
                break;
            }

            if (emptyDatagram || sent > 0) {
                if (entry.getBuffer().hasRemaining()) {
                    LOGGER.warn("Datagram is split");
                    incoming.retry(entry);
                } else {
                    incoming.release(entry);
                }

                sentByteMeter.update(sent);
                sentPacketMeter.increment();

                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("Send {} bytes to client <{}>", sent, entry.getAddress());
                }

                count++;
            } else {
                break;
            }
        }

        if (incoming.isEmpty()) {
            selectionKeyControl.disableWrites();
        }
    }

    private void handleReadableEvent() throws IOException {
        while (true) {
            final InetSocketAddress address = (InetSocketAddress) channel.receive(bb);
            if (address == null) {
                break;
            }

            bb.flip();
            final int read = bb.remaining();

            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Received {} bytes from inner <{}>", read, address);
            }

            readByteMeter.update(read);
            readPacketMeter.increment();

            DatagramOuter outer = requestOuter(address);

            outer.enqueue(bb);

            // try to immediately sent the datagram
            if (outer.hasIncoming() && outer.isWritable()) {
                outer.handleWritableEvent(true);
            }

            // if data still remains we raise the OP_WRITE flag
            if (outer.hasIncoming() && outer.isWritable()) {
                outer.enableWrites();
            }

            bb.clear();
        }
    }

    private DatagramOuter requestOuter(InetSocketAddress address) throws IOException {
        DatagramOuter outer = outers.get(address);

        if (outer == null) {
            outer = new DatagramOuter(this,
                reactor, socketOptions, filters, bufferOptions, address, connectAddress
            );
            outer.unfreeze();

            outers.put(address, outer);

            crusher.notifyOuterCreated(outer);
        }

        return outer;
    }

    void enqueue(InetSocketAddress address, ByteBuffer bbToCopy, long delayNs) {
        incoming.add(address, bbToCopy, delayNs);
    }

    boolean closeOuter(InetSocketAddress clientAddress) {
        DatagramOuter outer = outers.remove(clientAddress);
        if (outer != null) {
            outer.close();

            crusher.notifyOuterDeleted(outer);

            return true;
        } else {
            return false;
        }
    }

    int closeIdleOuters(long maxIdleDurationMs) {
        int countBefore = outers.size();
        if (countBefore > 0) {
            Iterator<DatagramOuter> outerIterator = outers.values().iterator();

            while (outerIterator.hasNext()) {
                DatagramOuter outer = outerIterator.next();

                if (outer.getIdleDurationMs() > maxIdleDurationMs) {
                    outerIterator.remove();

                    outer.close();
                    crusher.notifyOuterDeleted(outer);
                }
            }

            int countAfter = outers.size();

            return countBefore - countAfter;
        } else {
            return 0;
        }
    }

    boolean hasIncoming() {
        return !incoming.isEmpty();
    }

    void enableWrites() {
        selectionKeyControl.enableWrites();
    }

    boolean isWritable() {
        return state.isWritable();
    }

    DatagramOuter getOuter(InetSocketAddress clientAddress) {
        return outers.get(clientAddress);
    }

    Collection<DatagramOuter> getOuters() {
        return outers.values();
    }

    RateMeters getByteMeters() {
        return new RateMeters(readByteMeter, sentByteMeter);
    }

    RateMeters getPacketMeters() {
        return new RateMeters(readPacketMeter, sentPacketMeter);
    }

    private static final class State extends BitState {

        private static final int OPEN = bit(0);

        private static final int FROZEN = bit(1);

        private static final int CLOSED = bit(2);

        private State(int state) {
            super(state);
        }

        private boolean isWritable() {
            return is(OPEN);
        }
    }

}
