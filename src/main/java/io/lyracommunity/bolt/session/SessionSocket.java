package io.lyracommunity.bolt.session;

import io.lyracommunity.bolt.BoltEndPoint;
import io.lyracommunity.bolt.receiver.BoltReceiver;
import io.lyracommunity.bolt.sender.BoltSender;
import io.lyracommunity.bolt.packet.DataPacket;
import io.lyracommunity.bolt.util.ReceiveBuffer;
import rx.Observable;
import rx.schedulers.Schedulers;

import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

/**
 * SessionSocket is analogous to a normal java.net.Socket, it provides input and
 * output streams for the application.
 * TODO consider if this class is even necessary anymore with In/Out streams.
 */
public class SessionSocket
{

    // Endpoint
    private final BoltSession session;
    private volatile boolean active;

    // Processing received data
    private final BoltReceiver  receiver;
    private final BoltSender    sender;
    private final ReceiveBuffer receiveBuffer;

    /**
     * @param endpoint
     * @param session
     * @throws SocketException, UnknownHostException
     */
    SessionSocket(final BoltEndPoint endpoint, final BoltSession session) throws SocketException, UnknownHostException {
        this.session = session;
        this.receiver = new BoltReceiver(session, endpoint, endpoint.getConfig());
        this.sender = new BoltSender(session, endpoint);

        final int capacity = 2 * session.getFlowWindowSize();

        this.receiveBuffer = new ReceiveBuffer(capacity);
    }

    public Observable<?> start() {
        return Observable.merge(
                receiver.start().subscribeOn(Schedulers.io()),
                sender.doStart().subscribeOn(Schedulers.io()))
                .doOnSubscribe(() -> setActive(true));
    }

    public BoltReceiver getReceiver() {
        return receiver;
    }

    public BoltSender getSender() {
        return sender;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * New application data.
     *
     * @param packet
     */
    public ReceiveBuffer.OfferResult haveNewData(final DataPacket packet) throws IOException {
        return receiveBuffer.offer(packet);
    }

    public final BoltSession getSession() {
        return session;
    }

    public void doWrite(final DataPacket dataPacket) throws IOException {
        try {
            sender.sendPacket(dataPacket, 10, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            throw new IOException(ie);
        }
        if (dataPacket.getDataLength() > 0) setActive(true);
    }

    protected void doWriteBlocking(final DataPacket dataPacket) throws IOException, InterruptedException {
        doWrite(dataPacket);
        flush();
    }

    void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Will block until the outstanding packets have really been sent out
     * and acknowledged.
     */
    public void flush() throws InterruptedException, IllegalStateException {
        if (!isActive()) return;
        // TODO change to reliability seq number. Also, logic needs careful looking over.
        final int seqNo = sender.getCurrentSequenceNumber();
        final int relSeqNo = sender.getCurrentReliabilitySequenceNumber();
        if (seqNo < 0) throw new IllegalStateException();
        while (isActive() && !sender.isSentOut(seqNo)) {
            Thread.sleep(5);
        }
        if (seqNo > -1) {
            // Wait until data has been sent out and acknowledged.
            while (isActive() && !sender.haveAcknowledgementFor(relSeqNo)) {
                sender.waitForAck(seqNo);
            }
        }
        // TODO need to check if we can pause the sender...
//        sender.pause();
    }

    /**
     * Close the connection.
     *
     * @throws IOException
     */
    public void close() throws IOException {
        setActive(false);
    }

    public ReceiveBuffer getReceiveBuffer() {
        return receiveBuffer;
    }


}