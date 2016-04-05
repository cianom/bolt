package io.lyracommunity.bolt;

import io.lyracommunity.bolt.event.ConnectionReady;
import io.lyracommunity.bolt.event.PeerDisconnected;
import io.lyracommunity.bolt.packet.*;
import io.lyracommunity.bolt.session.BoltSession;
import io.lyracommunity.bolt.session.ServerSession;
import io.lyracommunity.bolt.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.schedulers.Schedulers;

import java.io.IOException;
import java.net.*;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The UDPEndpoint takes care of sending and receiving UDP network packets,
 * dispatching them to the correct {@link BoltSession}
 */
public class BoltEndPoint {

    private static final Logger LOG = LoggerFactory.getLogger(BoltEndPoint.class);

    private final DatagramPacket dp = new DatagramPacket(new byte[Config.DEFAULT_DATAGRAM_SIZE], Config.DEFAULT_DATAGRAM_SIZE);
    private final int port;
    private final DatagramSocket dgSocket;
    private final Config config;

    /**
     * Active sessions keyed by socket ID.
     */
    private final Map<Long, BoltSession> sessions = new ConcurrentHashMap<>();

    private final Map<Destination, BoltSession> sessionsBeingConnected = new ConcurrentHashMap<>();

    private final Map<Long, Subscription> sessionSubscriptions = new ConcurrentHashMap<>();

    /**
     * Bind to the given address and port
     *
     * @param localAddress the local address to bind.
     * @param localPort    the port to bind to. If the port is zero, the system will pick an ephemeral port.
     * @throws SocketException      if for example if the port is already bound to.
     * @throws UnknownHostException
     */
    public BoltEndPoint(final InetAddress localAddress, final int localPort) throws SocketException, UnknownHostException, IOException {
        this(new Config(localAddress, localPort));
    }

    /**
     * Bind to the given address and port.
     *
     * @param config config containing the address information to bind.
     * @throws SocketException
     * @throws UnknownHostException
     */
    public BoltEndPoint(final Config config) throws UnknownHostException, SocketException, IOException {
        this.config = config;
        this.dgSocket = new DatagramSocket(config.getLocalPort(), config.getLocalAddress());
        // If the port is zero, the system will pick an ephemeral port.
        this.port = (config.getLocalPort() > 0) ? config.getLocalPort() : dgSocket.getLocalPort();
        configureSocket();
    }

    protected void configureSocket() throws SocketException, IOException {
        // set a time out to avoid blocking in doReceive()
        dgSocket.setSoTimeout(100_000);
        // buffer size
        dgSocket.setReceiveBufferSize(128 * 1024);
        dgSocket.setReuseAddress(false);
    }

    /**
     * Start the endpoint.
     */
    public Observable<?> start() {
        return Observable.<Object>create(this::doReceive).subscribeOn(Schedulers.io());
    }

    void stop(final Subscriber<? super Object> subscriber) {
        sessionsBeingConnected.clear();
        final Set<Long> destIDs = Stream.concat(sessions.keySet().stream(), sessionSubscriptions.keySet().stream())
                .collect(Collectors.toSet());
        destIDs.forEach(destID -> endSession(subscriber, destID, "Endpoint is closing."));
        sessions.clear();
        sessionSubscriptions.clear();
        dgSocket.close();
    }

    private void endSession(final Subscriber<? super Object> subscriber, final long destinationID, final String reason) {
        final BoltSession session = sessions.remove(destinationID);
        final Subscription sessionSub = sessionSubscriptions.remove(destinationID);
        if (session != null) session.close();
        if (sessionSub != null) sessionSub.unsubscribe();
        if (subscriber != null) subscriber.onNext(new PeerDisconnected(destinationID, reason));
    }

    /**
     * @return the port which this client is bound to
     */
    public int getLocalPort() {
        return this.dgSocket.getLocalPort();
    }

    /**
     * @return Gets the local address to which the socket is bound
     */
    public InetAddress getLocalAddress() {
        return this.dgSocket.getLocalAddress();
    }

    DatagramSocket getSocket() {
        return dgSocket;
    }

    public void addSession(final Long destinationID, final BoltSession session) {
        LOG.info("Storing session [{}]", destinationID);
        sessions.put(destinationID, session);
    }

    public BoltSession getSession(Long destinationID) {
        return sessions.get(destinationID);
    }

    public Collection<BoltSession> getSessions() {
        return sessions.values();
    }

    /**
     * Single receive, run in the receiverThread, see {@link #start()}.
     * <ul>
     * <li>Receives UDP packets from the network.
     * <li>Converts them to Bolt packets.
     * <li>dispatches the Bolt packets according to their destination ID.
     * </ul>
     */
    protected void doReceive(final Subscriber<? super Object> subscriber) {
        Thread.currentThread().setName("Bolt-Endpoint" + Util.THREAD_INDEX.incrementAndGet());
        LOG.info("BoltEndpoint started.");
        while (!subscriber.isUnsubscribed()) {
            try {
                // Will block until a packet is received or timeout has expired.
                dgSocket.receive(dp);

                final Destination peer = new Destination(dp.getAddress(), dp.getPort());
                final int l = dp.getLength();
                final BoltPacket packet = PacketFactory.createPacket(dp.getData(), l);

                if (LOG.isDebugEnabled()) LOG.debug("Received packet {}", packet.getPacketSeqNumber());

                final long destID = packet.getDestinationID();
                final BoltSession session = sessions.get(destID);

                final boolean isConnectionHandshake = PacketType.CONNECTION_HANDSHAKE == packet.getPacketType();
                if (isConnectionHandshake) {
                    final BoltSession result = connectionHandshake(subscriber, (ConnectionHandshake) packet, peer, session);
                    if (result.isReady()) subscriber.onNext(new ConnectionReady(result));
                }
                else if (session != null) {
                    if (PacketType.SHUTDOWN == packet.getPacketType()) {
                        endSession(subscriber, packet.getDestinationID(), "Shutdown received");
                    }
                    // Dispatch to existing session.
                    else {
                        session.received(packet, peer, subscriber);
                    }
                }
                else {
                    LOG.warn("Unknown session [{}] requested from [{}] - Packet Type [{}]", destID, peer, packet.getClass().getName());
                }
            }
            catch (InterruptedException | AsynchronousCloseException ex) {
                LOG.info("Endpoint interrupted.");
            }
            catch (SocketTimeoutException ex) {
                LOG.debug("Endpoint socket timeout");
            }
            catch (SocketException ex) {
                LOG.warn("SocketException: {}", ex.getMessage());
                if (dgSocket.isClosed()) subscriber.onError(ex);
            }
            catch (ClosedChannelException ex) {
                LOG.warn("Channel was closed but receive was attempted");
            }
            catch (Exception ex) {
                LOG.error("Unexpected endpoint error", ex);
            }
        }
        stop(subscriber);
    }

    /**
     * Called when a "connection handshake" packet was received and no
     * matching session yet exists.
     *
     * @param packet
     * @param peer
     * @throws IOException
     * @throws InterruptedException
     */
    protected synchronized BoltSession connectionHandshake(final Subscriber<? super Object> subscriber,
                                                           final ConnectionHandshake packet, final Destination peer,
                                                           final BoltSession existingSession) throws IOException, InterruptedException {
        final long destID = packet.getDestinationID();
        BoltSession session = existingSession;
        if (session == null) {
            final Destination p = new Destination(peer.getAddress(), peer.getPort());
            session = sessionsBeingConnected.get(peer);
            // New session
            if (session == null) {
                session = new ServerSession(peer, this);
                sessionsBeingConnected.put(p, session);
                sessions.put(session.getSocketID(), session);
            }
            // Confirmation handshake
            else if (session.getSocketID() == destID) {
                sessionsBeingConnected.remove(p);
                addSession(destID, session);
            }
            else if (destID > 0) {  // Ignore dest == 0, as it must be a duplicate client initial handshake packet.
                throw new IOException("Destination ID sent by client does not match");
            }
            Integer peerSocketID = packet.getSocketID();
            peer.setSocketID(peerSocketID);
        }
        final boolean readyToStart = session.receiveHandshake(subscriber, packet, peer);
        if (readyToStart) {
            final Subscription sessionSubscription = session.start().subscribe(subscriber::onNext,
                    ex -> endSession(subscriber, destID, ex.getMessage()),
                    () -> endSession(subscriber, destID, "Session ended successfully"));
            sessionSubscriptions.put(destID, sessionSubscription);
        }
        return session;
    }

    public boolean isOpen() {
        return !dgSocket.isClosed();
    }

    public void doSend(final BoltPacket packet, final BoltSession session) throws IOException {
        final byte[] data = packet.getEncoded();
        DatagramPacket dgp = session.getDatagram();
        dgp.setData(data);
        dgSocket.send(dgp);
    }

    public String toString() {
        return "BoltEndpoint port=" + port;
    }

    public void sendRaw(DatagramPacket p) throws IOException {
        dgSocket.send(p);
    }

    public Config getConfig() {
        return config;
    }

}
