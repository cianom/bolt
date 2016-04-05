package io.lyracommunity.bolt.session;

import io.lyracommunity.bolt.BoltEndPoint;
import io.lyracommunity.bolt.packet.BoltPacket;
import io.lyracommunity.bolt.packet.ConnectionHandshake;
import io.lyracommunity.bolt.packet.Destination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Subscriber;
import rx.schedulers.Schedulers;

import java.io.IOException;
import java.net.SocketException;

import static io.lyracommunity.bolt.session.BoltSession.SessionState.*;

/**
 * Client side of a client-server Bolt connection.
 * Once established, the session provides a valid {@link SessionSocket}.
 */
public class ClientSession extends BoltSession {

    private static final Logger LOG = LoggerFactory.getLogger(ClientSession.class);


    public ClientSession(BoltEndPoint endPoint, Destination dest) throws SocketException {
        super(endPoint, "ClientSession localPort=" + endPoint.getLocalPort(), dest);
        LOG.info("Created " + toString());
    }

    /**
     * Send connection handshake until a reply from server is received.
     *
     * @throws InterruptedException
     * @throws IOException
     */
    public Observable<?> connect() throws InterruptedException, IOException {
        return Observable.create(subscriber -> {
            int n = 0;
            while (getState() != READY && !subscriber.isUnsubscribed()) {
                try {
                    if (getState() == INVALID) {
                        throw new IOException("Can't connect!");
                    }
                    if (getState().seqNo() <= HANDSHAKING.seqNo()) {
                        setState(HANDSHAKING);
                        sendInitialHandShake();
                    }
                    else if (getState() == HANDSHAKING2) {
                        sendSecondHandshake();
                    }

                    if (getState() == INVALID) throw new IOException("Can't connect!");
                    if (n++ > 40) throw new IOException("Could not connect to server within the timeout.");

                    Thread.sleep(500);
                }
                catch (InterruptedException ex) {
                    // Do nothing.
                }
                catch (IOException ex) {
                    subscriber.onError(ex);
                }
            }
            cc.init();
            LOG.info("Connected, " + n + " handshake packets sent");
            subscriber.onCompleted();
        }).subscribeOn(Schedulers.io());
    }

    @Override
    public boolean receiveHandshake(final Subscriber<? super Object> subscriber, final ConnectionHandshake handshake,
                                    final Destination peer) {
        boolean readyToStart = false;
        if (getState() == HANDSHAKING) {
            LOG.info("Received initial handshake response from " + peer + "\n" + handshake);
            if (handshake.getConnectionType() == ConnectionHandshake.CONNECTION_SERVER_ACK) {
                try {
                    // TODO validate parameters sent by peer
                    int peerSocketID = handshake.getSocketID();
                    sessionCookie = handshake.getCookie();
                    destination.setSocketID(peerSocketID);
                    setState(HANDSHAKING2);
//                    sendSecondHandshake();
                }
                catch (Exception ex) {
                    LOG.warn("Error creating socket", ex);
                    setState(INVALID);
                    subscriber.onError(ex);
                }
            }
            else {
                final Exception ex = new IllegalStateException("Unexpected type of handshake packet received");
                LOG.error("Bad connection type received", ex);
                setState(INVALID);
                subscriber.onError(ex);
            }
        }
        else if (getState() == HANDSHAKING2) {
            try {
                LOG.info("Received confirmation handshake response from " + peer + "\n" + handshake);
                // TODO validate parameters sent by peer
                setState(READY);
                readyToStart = true;
            }
            catch (Exception ex) {
                LOG.error("Error creating socket", ex);
                setState(INVALID);
                subscriber.onError(ex);
            }
        }
        return readyToStart;
    }

    @Override
    public void received(BoltPacket packet, Destination peer, Subscriber subscriber) {
        if (getState() == READY) {
            socket.setActive(true);
            try {
                // Send all packets to both sender and receiver
                socket.getSender().receive(packet);
                socket.getReceiver().receive(packet);
            }
            catch (Exception ex) {
                // Session is invalid.
                LOG.error("Error in " + toString(), ex);
                subscriber.onError(ex);
                setState(INVALID);
            }
        }
    }

    /**
     * Initial handshake for connect.
     */
    protected void sendInitialHandShake() throws IOException {
        final ConnectionHandshake handshake = ConnectionHandshake.ofClientInitial(getDatagramSize(), getInitialSequenceNumber(),
                flowWindowSize, mySocketID, endPoint.getLocalAddress());
        LOG.info("Sending {}", handshake);
        endPoint.doSend(handshake, this);
    }

    /**
     * Second handshake for connect.
     */
    protected void sendSecondHandshake() throws IOException {
        final ConnectionHandshake ch = ConnectionHandshake.ofClientSecond(getDatagramSize(), getInitialSequenceNumber(),
                flowWindowSize, mySocketID, getDestination().getSocketID(), sessionCookie, endPoint.getLocalAddress());
        LOG.info("Sending confirmation {}", ch);
        endPoint.doSend(ch, this);
    }


}