package io.lyracommunity.bolt.session;

import io.lyracommunity.bolt.Config;
import io.lyracommunity.bolt.packet.Destination;
import io.lyracommunity.bolt.statistic.BoltStatistics;
import io.lyracommunity.bolt.util.SeqNum;

import java.net.DatagramPacket;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by keen on 07/04/16.
 */
public class SessionState {


    private final static AtomicInteger NEXT_SOCKET_ID = new AtomicInteger(20 + new Random().nextInt(5000));

    /**
     * Remote Bolt entity (address and socket ID).
     */
    protected final Destination destination;
    /**
     * The size of the receive buffer.
     */
    private final int receiveBufferSize = 64 * 32768;
    /**
     * Session cookie created during handshake.
     */
    protected long sessionCookie = 0;
    /**
     * Flow window size (how many data packets are in-flight at a single time).
     */
    protected int flowWindowSize = 1024 * 10;
    /**
     * Initial packet sequence number.
     */
    protected Integer initialSequenceNumber = null;
    /**
     * Cache dgPacket (peer stays the same always).
     */
    private DatagramPacket dgPacket;

    // TODO review whether statstics belongs to this class or outside
    /** Statistics for the session. */
    private final BoltStatistics statistics;

    /** The socket ID of this session. */
    private final int mySocketID;

    /** Whether the session is started and active. */
    private volatile boolean active;

    private volatile SessionStatus status = SessionStatus.START;

    /**
     * Buffer size (i.e. datagram size). This is negotiated during connection setup.
     */
    private int datagramSize = Config.DEFAULT_DATAGRAM_SIZE;


    public SessionState(final Destination destination, final String description) {
        this.destination = destination;
        this.dgPacket = new DatagramPacket(new byte[0], 0, destination.getAddress(), destination.getPort());
        this.mySocketID = NEXT_SOCKET_ID.incrementAndGet();
        this.statistics = new BoltStatistics(description, datagramSize);
    }

    public int getSocketID() {
        return mySocketID;
    }

    public int getFlowWindowSize() {
        return flowWindowSize;
    }

    public Destination getDestination() {
        return destination;
    }

    public int getDestinationSocketID() {
        return destination.getSocketID();
    }

    public void setDestinationSocketID(final int destSocketID) {
        destination.setSocketID(destSocketID);
    }

    public boolean isReady() {
        return status == SessionStatus.READY;
    }

    public boolean isShutdown() {
        return status == SessionStatus.SHUTDOWN || status == SessionStatus.INVALID;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(final SessionStatus status) {
        this.status = status;
    }

    public long getSessionCookie() {
        return sessionCookie;
    }

    public void setSessionCookie(long sessionCookie) {
        this.sessionCookie = sessionCookie;
    }

    public synchronized int getInitialSequenceNumber() {
        if (initialSequenceNumber == null) {
            initialSequenceNumber = SeqNum.randomPacketSeqNum();
        }
        return initialSequenceNumber;
    }

    synchronized void setInitialSequenceNumber(int initialSequenceNumber) {
        this.initialSequenceNumber = initialSequenceNumber;
    }

    public DatagramPacket getDatagram() {
        return dgPacket;
    }

    public int getReceiveBufferSize() {
        return receiveBufferSize;
    }

    public boolean isActive() {
        return active;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    void setDatagramSize(int datagramSize) {
        this.datagramSize = datagramSize;
    }

    int getDatagramSize() {
        return datagramSize;
    }

    public BoltStatistics getStatistics()
    {
        return statistics;
    }

    @Override
    public String toString()
    {
        return "SessionState{" + "mySocketID=" + mySocketID + '}';
    }

}