package bolt;

import bolt.packets.*;
import bolt.sender.FlowWindow;
import bolt.sender.SenderLossList;
import bolt.statistic.BoltStatistics;
import bolt.statistic.MeanThroughput;
import bolt.statistic.MeanValue;
import bolt.util.SequenceNumber;
import bolt.util.Util;
import rx.Observable;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Sender half of a Bolt entity.
 * <p>
 * The sender sends (and retransmits) application data according to the
 * flow control and congestion control.
 *
 * @see BoltReceiver
 */
public class BoltSender {

    private static final Logger LOG = Logger.getLogger(BoltClient.class.getName());

    private final BoltEndPoint endpoint;

    private final BoltSession session;

    private final BoltStatistics statistics;

    /**
     * Stores the seq numbers of lost packets fed back by the receiver through NAK packets
     */
    private final SenderLossList senderLossList;

    /**
     * Stores the sent data packets and their sequence numbers
     */
    private final Map<Integer, byte[]> sendBuffer;

    private final FlowWindow flowWindow;

    /**
     * Protects against races when reading/writing to the sendBuffer
     */
    private final Object sendLock = new Object();

    /**
     * Number of unacknowledged data packets
     */
    private final AtomicInteger unacknowledged = new AtomicInteger(0);

    /**
     * Used by the sender to wait for an ACK
     */
    private final ReentrantLock ackLock = new ReentrantLock();
    private final Condition ackCondition = ackLock.newCondition();

    private final boolean storeStatistics;
    private final int chunkSize;
    private final DataPacket retransmit = new DataPacket();

    /**
     * For generating data packet sequence numbers
     */
    private volatile int currentSequenceNumber = 0;

    //the largest data packet sequence number that has actually been sent out
    private volatile int largestSentSequenceNumber = -1;
    //last acknowledge number, initialised to the initial sequence number
    private volatile int lastAckSequenceNumber;
    private volatile boolean started = false;
    private volatile boolean stopped = false;
    private volatile boolean paused = false;

    /**
     * Used to signal that the sender should start to send
     */
    private volatile CountDownLatch startLatch = new CountDownLatch(1);
    private MeanValue dgSendTime;
    private MeanValue dgSendInterval;
    private MeanThroughput throughput;

    public BoltSender(final BoltSession session, final BoltEndPoint endpoint) {
        if (!session.isReady()) throw new IllegalStateException("BoltSession is not ready.");
        this.endpoint = endpoint;
        this.session = session;
        this.statistics = session.getStatistics();
        this.senderLossList = new SenderLossList();
        this.sendBuffer = new ConcurrentHashMap<>(session.getFlowWindowSize(), 0.75f, 2);
        this.chunkSize = session.getDatagramSize() - 24;//need space for the header;
        this.flowWindow = new FlowWindow(session.getFlowWindowSize(), chunkSize);
        this.lastAckSequenceNumber = session.getInitialSequenceNumber();
        this.currentSequenceNumber = session.getInitialSequenceNumber() - 1;
        this.storeStatistics = Boolean.getBoolean("bolt.sender.storeStatistics");
        initMetrics();
    }

    private void initMetrics() {
        if (!storeStatistics) return;
        dgSendTime = new MeanValue("SENDER: Datagram send time");
        statistics.addMetric(dgSendTime);
        dgSendInterval = new MeanValue("SENDER: Datagram send interval");
        statistics.addMetric(dgSendInterval);
        throughput = new MeanThroughput("SENDER: Throughput", session.getDatagramSize());
        statistics.addMetric(throughput);
    }

    /**
     * Start the sender thread.
     */
    public void start() {
        LOG.info("STARTING SENDER for " + session);
        startLatch.countDown();
        started = true;
    }

    /**
     * Starts the sender algorithm
     */
    public Observable<?> doStart() {
        return Observable.create(subscriber -> {
            try {
                String s = (session instanceof ServerSession) ? "ServerSession" : "ClientSession";
                Thread.currentThread().setName("Bolt-Sender-" + s + Util.THREAD_INDEX.incrementAndGet());

                Supplier<Boolean> stopped = () -> this.stopped || subscriber.isUnsubscribed();
                while (!stopped.get()) {
                    // Wait until explicitly (re)started.
//                    startLatch.await();
                    paused = false;
                    senderAlgorithm(stopped);
                }
            }
            catch (IOException | InterruptedException ex) {
                LOG.log(Level.SEVERE, "", ex);
                subscriber.onError(ex);
            }
            LOG.info("STOPPING SENDER for " + session);
            subscriber.onCompleted();
            stop();
        });
    }

    /**
     * sends the given data packet, storing the relevant information
     */
    private void send(final DataPacket p) throws IOException {
        synchronized (sendLock) {
            if (storeStatistics) {
                dgSendInterval.end();
                dgSendTime.begin();
            }
            endpoint.doSend(p);
            if (storeStatistics) {
                dgSendTime.end();
                dgSendInterval.begin();
                throughput.end();
                throughput.begin();
            }
            if (p.isReliable()) {
                // Store data for potential retransmit.
                int l = p.getLength();
                byte[] data = new byte[l];
                System.arraycopy(p.getData(), 0, data, 0, l);
                sendBuffer.put(p.getPacketSequenceNumber(), data);
                unacknowledged.incrementAndGet();
                largestSentSequenceNumber = p.getPacketSequenceNumber();
            }
        }
        statistics.incNumberOfSentDataPackets();
    }

    /**
     * Writes a data packet, waiting at most for the specified time.
     * If this is not possible due to a full send queue.
     *
     * @param timeout
     * @param units
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    protected void sendPacket(final DataPacket src, int timeout, TimeUnit units) throws IOException, InterruptedException {
        if (!started) start();
        src.setSession(session);
        src.setDestinationID(session.getDestination().getSocketID());
        src.setPacketSequenceNumber(src.isReliable() ? getNextSequenceNumber() : 0);

        boolean complete = false;
        while (!complete) {
            complete = flowWindow.tryProduce(src);
            if (!complete) Thread.sleep(1);
        }
    }

    /**
     * Receive a packet from server from the peer.
     */
    protected void receive(final BoltPacket p) throws IOException {
        if (p instanceof Acknowledgement) {
            Acknowledgement acknowledgement = (Acknowledgement) p;
            onAcknowledge(acknowledgement);
        }
        else if (p instanceof NegativeAcknowledgement) {
            NegativeAcknowledgement nak = (NegativeAcknowledgement) p;
            onNAKPacketReceived(nak);
        }
        else if (p instanceof KeepAlive) {
            session.getSocket().getReceiver().resetEXPCount();
        }
    }

    /**
     * On ACK packet received:
     * <ol>
     * <li> Update the largest acknowledged sequence number.
     * <li> Send back an ACK2 with the same ACK sequence number in this ACK.
     * <li> Update RTT and RTTVar.
     * <li> Update both ACK and NAK period to 4 * RTT + RTTVar + SYN.
     * <li> Update flow window size.
     * <li> If this is a Light ACK, stop.
     * <li> Update packet arrival rate: A = (A * 7 + a) / 8, where a is the value carried in the ACK.
     * <li> Update estimated link capacity: B = (B * 7 + b) / 8, where b is the value carried in the ACK.
     * <li> Update sender's buffer (by releasing the buffer that has been acknowledged).
     * <li> Update sender's loss list (by removing all those that has been acknowledged).
     * </ol>
     *
     * @param acknowledgement the received ACK packet.
     * @throws IOException if sending of ACK2 fails.
     */
    protected void onAcknowledge(final Acknowledgement acknowledgement) throws IOException {
        ackLock.lock();
        ackCondition.signal();
        ackLock.unlock();

        CongestionControl cc = session.getCongestionControl();
        long rtt = acknowledgement.getRoundTripTime();
        if (rtt > 0) {
            long rttVar = acknowledgement.getRoundTripTimeVar();
            cc.setRTT(rtt, rttVar);
            statistics.setRTT(rtt, rttVar);
        }
        long rate = acknowledgement.getPacketReceiveRate();
        if (rate > 0) {
            long linkCapacity = acknowledgement.getEstimatedLinkCapacity();
            cc.updatePacketArrivalRate(rate, linkCapacity);
            statistics.setPacketArrivalRate(cc.getPacketArrivalRate(), cc.getEstimatedLinkCapacity());
        }

        int ackNumber = acknowledgement.getAckNumber();
        cc.onACK(ackNumber);
        statistics.setCongestionWindowSize((long) cc.getCongestionWindowSize());
        // Need to remove all sequence numbers up the ACK number from the sendBuffer.
        boolean removed;
        for (int s = lastAckSequenceNumber; s < ackNumber; s++) {
            synchronized (sendLock) {
                removed = sendBuffer.remove(s) != null;
                senderLossList.remove(s);
            }
            if (removed) {
                unacknowledged.decrementAndGet();
            }
        }
        lastAckSequenceNumber = Math.max(lastAckSequenceNumber, ackNumber);
        // Send ACK2 packet to the receiver.
        sendAck2(ackNumber);
        statistics.incNumberOfACKReceived();
        if (storeStatistics) statistics.storeParameters();
    }

    /**
     * On NAK packet received:
     * <ol>
     * <li> Add all sequence numbers carried in the NAK into the sender's loss list.
     * <li> Update the SND period by rate control (see section 3.6).
     * <li> Reset the EXP time variable.
     * </ol>
     *
     * @param nak NAK packet received.
     */
    protected void onNAKPacketReceived(NegativeAcknowledgement nak) {
        for (Integer i : nak.getDecodedLossInfo()) {
            senderLossList.insert(i);
        }
        session.getCongestionControl().onLoss(nak.getDecodedLossInfo());
        session.getSocket().getReceiver().resetEXPTimer();
        statistics.incNumberOfNAKReceived();

        if (LOG.isLoggable(Level.FINER)) {
            LOG.finer("NAK for " + nak.getDecodedLossInfo().size() + " packets lost, "
                    + "set send period to " + session.getCongestionControl().getSendInterval());
        }
    }

    /**
     * Send single keep alive packet -> TODO: move to socket!
     */
    protected void sendKeepAlive() throws Exception {
        KeepAlive keepAlive = new KeepAlive();
        keepAlive.setSession(session);
        endpoint.doSend(keepAlive);
    }

    protected void sendAck2(long ackSequenceNumber) throws IOException {
        Acknowledgment2 ackOfAckPkt = new Acknowledgment2();
        ackOfAckPkt.setAckSequenceNumber(ackSequenceNumber);
        ackOfAckPkt.setSession(session);
        ackOfAckPkt.setDestinationID(session.getDestination().getSocketID());
        endpoint.doSend(ackOfAckPkt);
    }

    /**
     * Data Sending Algorithm:
     * <ol>
     * <li> If the sender's loss list is not empty, retransmit the first
     * packet in the list and remove it from the list. Go to 5).
     * <li> In messaging mode, if the packets has been the loss list for a
     * time more than the application specified TTL (time-to-live), send
     * a message drop request and remove all related packets from the
     * loss list. Go to 1).
     * <li> Wait until there is application data to be sent.
     * <li>
     * a. If the number of unacknowledged packets exceeds the flow/congestion
     * window size, wait until an ACK comes. Go back to step 1). <br/>
     * b. Pack a new data packet and send it out.
     * </ol>
     * <li> If the sequence number of the current packet is 16n, where n is an
     * integer, go to 2).
     * <li> Wait (SND - t) time, where SND is the inter-packet interval updated by
     * congestion control and t is the total time used by step 1 to step 5. Go to 1).
     * </ol>
     *
     * @throws InterruptedException if the thread is interrupted wating for an ACK.
     * @throws IOException          on failure to send the DataPacket.
     */
    public void senderAlgorithm(final Supplier<Boolean> stopped) throws InterruptedException, IOException {
        while (!paused) {
            long iterationStart = Util.getCurrentTime();
            // If the sender's loss list is not empty
            Integer entry = senderLossList.getFirstEntry();
            if (entry != null) {
                handleRetransmit(entry);
            }
            else {
                // If the number of unacknowledged data packets does not exceed the congestion
                // and the flow window sizes, pack a new packet.
                int unAcknowledged = unacknowledged.get();

                if (unAcknowledged < session.getCongestionControl().getCongestionWindowSize()
                        && unAcknowledged < session.getFlowWindowSize()) {
                    // Check for application data
                    final DataPacket dp = flowWindow.consumeData();
                    if (dp != null) {
                        send(dp);
                    }
                    else {
                        statistics.incNumberOfMissingDataEvents();
                    }
                }
                else {
                    // Congestion window full, wait for an ack
                    if (unAcknowledged >= session.getCongestionControl().getCongestionWindowSize()) {
                        statistics.incNumberOfCCWindowExceededEvents();
                    }
                    waitForAck();
                }
            }

            // Wait
            if (largestSentSequenceNumber % 16 != 0) {
                long snd = (long) session.getCongestionControl().getSendInterval();
                long passed = Util.getCurrentTime() - iterationStart;
                int x = 0;
                while (snd - passed > 0) {
                    // Can't wait with microsecond precision :(
                    if (x == 0) {
                        statistics.incNumberOfCCSlowDownEvents();
                        x++;
                    }
                    passed = Util.getCurrentTime() - iterationStart;
                    if (stopped.get()) return;
                }
            }
        }
    }

    /**
     * Re-transmit an entry from the sender loss list.
     *
     * @param seqNumber
     */
    protected void handleRetransmit(Integer seqNumber) {
        try {
            // Retransmit the packet and remove it from the list.
            byte[] data = sendBuffer.get(seqNumber);
            if (data != null) {
                retransmit.setPacketSequenceNumber(seqNumber);
                retransmit.setSession(session);
                retransmit.setDestinationID(session.getDestination().getSocketID());
                retransmit.setData(data);
                endpoint.doSend(retransmit);
                statistics.incNumberOfRetransmittedDataPackets();
            }
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "", e);
        }
    }

    /**
     * for processing EXP event (see spec. p 13)
     */
    protected void putUnacknowledgedPacketsIntoLossList() {
        synchronized (sendLock) {
            for (final Integer l : sendBuffer.keySet()) {
                senderLossList.insert(l);
            }
        }
    }

    /**
     * The next sequence number for data packets.
     * The initial sequence number is "0".
     */
    public int getNextSequenceNumber() {
        currentSequenceNumber = SequenceNumber.increment(currentSequenceNumber);
        return currentSequenceNumber;
    }

    public int getCurrentSequenceNumber() {
        return currentSequenceNumber;
    }

    boolean haveAcknowledgementFor(int sequenceNumber) {
        return SequenceNumber.compare(sequenceNumber, lastAckSequenceNumber) <= 0;
    }

    boolean isSentOut(int sequenceNumber) {
        return SequenceNumber.compare(largestSentSequenceNumber, sequenceNumber) >= 0;
    }

    boolean haveLostPackets() {
        return !senderLossList.isEmpty();
    }

    /**
     * wait until the given sequence number has been acknowledged
     *
     * @throws InterruptedException
     */
    public void waitForAck(int sequenceNumber) throws InterruptedException {
        while (!session.isShutdown() && !haveAcknowledgementFor(sequenceNumber)) {
            ackLock.lock();
            try {
                ackCondition.await(100, TimeUnit.MICROSECONDS);
            }
            finally {
                ackLock.unlock();
            }
        }
    }

    public void waitForAck(int sequenceNumber, int timeout) throws InterruptedException {
        while (!session.isShutdown() && !haveAcknowledgementFor(sequenceNumber)) {
            ackLock.lock();
            try {
                ackCondition.await(timeout, TimeUnit.MILLISECONDS);
            }
            finally {
                ackLock.unlock();
            }
        }
    }

    /**
     * Wait for the next acknowledge.
     *
     * @throws InterruptedException if the thread is interrupted.
     */
    public void waitForAck() throws InterruptedException {
        ackLock.lock();
        try {
            ackCondition.await(200, TimeUnit.MICROSECONDS);
        }
        finally {
            ackLock.unlock();
        }
    }


    public void stop() {
        stopped = true;
    }

    public void pause() {
        startLatch = new CountDownLatch(1);
        paused = true;
    }

}
