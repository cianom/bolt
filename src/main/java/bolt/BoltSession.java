package bolt;

import bolt.packets.Destination;
import bolt.statistic.BoltStatistics;
import bolt.util.SequenceNumber;

import java.net.DatagramPacket;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class BoltSession {

    //state constants
    public static final int start = 0;
    public static final int handshaking = 1;
    public static final int ready = 50;
    public static final int keepalive = 80;
    public static final int shutdown = 90;
    public static final int invalid = 99;
    public static final int DEFAULT_DATAGRAM_SIZE = BoltEndPoint.DATAGRAM_SIZE;
    /**
     * key for a system property defining the CC class to be used
     *
     * @see CongestionControl
     */
    public static final String CC_CLASS = "bolt.congestioncontrol.class";
    private static final Logger logger = Logger.getLogger(BoltSession.class.getName());
    private final static AtomicLong nextSocketID = new AtomicLong(20 + new Random().nextInt(5000));
    protected final BoltStatistics statistics;
    protected final CongestionControl cc;
    /**
     * remote Bolt entity (address and socket ID)
     */
    protected final Destination destination;
    protected final long mySocketID;
    protected int mode;
    protected volatile boolean active;
    protected volatile BoltPacket lastPacket;
    protected volatile BoltSocket socket;
    protected int receiveBufferSize = 64 * 32768;
    //session cookie created during handshake
    protected long sessionCookie = 0;
    /**
     * flow window size, i.e. how many data packets are
     * in-flight at a single time
     */
    protected int flowWindowSize = 1024 * 10;
    /**
     * local port
     */
    protected int localPort;
    /**
     * Buffer size (i.e. datagram size)
     * This is negotiated during connection setup
     */
    protected int datagramSize = DEFAULT_DATAGRAM_SIZE;

    protected Long initialSequenceNumber = null;
    private volatile int state = start;
    //cache dgPacket (peer stays the same always)
    private DatagramPacket dgPacket;

    public BoltSession(String description, Destination destination) {
        statistics = new BoltStatistics(description);
        mySocketID = nextSocketID.incrementAndGet();
        this.destination = destination;
        this.dgPacket = new DatagramPacket(new byte[0], 0, destination.getAddress(), destination.getPort());
        String clazzP = System.getProperty(CC_CLASS, BoltCongestionControl.class.getName());
        Object ccObject;
        try {
            Class<?> clazz = Class.forName(clazzP);
            ccObject = clazz.getDeclaredConstructor(BoltSession.class).newInstance(this);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Can't setup congestion control class <" + clazzP + ">, using default.", e);
            ccObject = new BoltCongestionControl(this);
        }
        cc = (CongestionControl) ccObject;
        logger.info("Using " + cc.getClass().getName());
    }


    public abstract void received(BoltPacket packet, Destination peer);


    public BoltSocket getSocket() {
        return socket;
    }

    public void setSocket(BoltSocket socket) {
        this.socket = socket;
    }

    public CongestionControl getCongestionControl() {
        return cc;
    }

    public int getState() {
        return state;
    }

    public void setState(int state) {
        logger.info(toString() + " connection state CHANGED to <" + state + ">");
        this.state = state;
    }

    public void setMode(int mode) {
        this.mode = mode;
    }

    public boolean isReady() {
        return state == ready;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isShutdown() {
        return state == shutdown || state == invalid;
    }

    public Destination getDestination() {
        return destination;
    }

    public int getDatagramSize() {
        return datagramSize;
    }

    public void setDatagramSize(int datagramSize) {
        this.datagramSize = datagramSize;
    }

    public int getReceiveBufferSize() {
        return receiveBufferSize;
    }

    public void setReceiveBufferSize(int bufferSize) {
        this.receiveBufferSize = bufferSize;
    }

    public int getFlowWindowSize() {
        return flowWindowSize;
    }

    public void setFlowWindowSize(int flowWindowSize) {
        this.flowWindowSize = flowWindowSize;
    }

    public BoltStatistics getStatistics() {
        return statistics;
    }

    public long getSocketID() {
        return mySocketID;
    }


    public synchronized long getInitialSequenceNumber() {
        if (initialSequenceNumber == null) {
            initialSequenceNumber = SequenceNumber.random();
        }
        return initialSequenceNumber;
    }

    public synchronized void setInitialSequenceNumber(long initialSequenceNumber) {
        this.initialSequenceNumber = initialSequenceNumber;
    }

    public DatagramPacket getDatagram() {
        return dgPacket;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(super.toString());
        sb.append(" [");
        sb.append("socketID=").append(this.mySocketID);
        sb.append(" ]");
        return sb.toString();
    }

}