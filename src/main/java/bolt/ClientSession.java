package bolt;

import bolt.packets.ConnectionHandshake;
import bolt.packets.Destination;
import bolt.packets.Shutdown;

import java.io.IOException;
import java.net.SocketException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client side of a client-server Bolt connection.
 * Once established, the session provides a valid {@link BoltSocket}.
 */
public class ClientSession extends BoltSession {

    private static final Logger logger = Logger.getLogger(ClientSession.class.getName());

    private BoltEndPoint endPoint;

    //TODO CIAN: REMOVED
//    long initialSequenceNo = SequenceNumber.random();

    public ClientSession(BoltEndPoint endPoint, Destination dest) throws SocketException {
        super("ClientSession localPort=" + endPoint.getLocalPort(), dest);
        this.endPoint = endPoint;
        logger.info("Created " + toString());
    }

    /**
     * send connection handshake until a reply from server is received
     *
     * @throws InterruptedException
     * @throws IOException
     */

    public void connect() throws InterruptedException, IOException {
        int n = 0;
        while (getState() != ready) {
            if (getState() == invalid) throw new IOException("Can't connect!");
            if (getState() <= handshaking) {
                setState(handshaking);
                sendInitialHandShake();
            } else if (getState() == handshaking + 1) {
                sendSecondHandshake();
            }

            if (getState() == invalid) throw new IOException("Can't connect!");
            if (n++ > 10) throw new IOException("Could not connect to server within the timeout.");

            Thread.sleep(500);
        }
        Thread.sleep(1000);
        cc.init();
        logger.info("Connected, " + n + " handshake packets sent");
    }

    @Override
    public void received(BoltPacket packet, Destination peer) {

        lastPacket = packet;

        if (packet.isConnectionHandshake()) {
            ConnectionHandshake hs = (ConnectionHandshake) packet;
            handleConnectionHandshake(hs, peer);
            return;
        }

        if (getState() == ready) {

            if (packet instanceof Shutdown) {
                setState(shutdown);
                active = false;
                logger.info("Connection shutdown initiated by the other side.");
                return;
            }
            active = true;
            try {
                if (packet.forSender()) {
                    socket.getSender().receive(lastPacket);
                } else {
                    socket.getReceiver().receive(lastPacket);
                }
            } catch (Exception ex) {
                //session is invalid
                logger.log(Level.SEVERE, "Error in " + toString(), ex);
                setState(invalid);
            }
            return;
        }
    }

    protected void handleConnectionHandshake(ConnectionHandshake hs, Destination peer) {

        if (getState() == handshaking) {
            logger.info("Received initial handshake response from " + peer + "\n" + hs);
            if (hs.getConnectionType() == ConnectionHandshake.CONNECTION_SERVER_ACK) {
                try {
                    //TODO validate parameters sent by peer
                    long peerSocketID = hs.getSocketID();
                    sessionCookie = hs.getCookie();
                    destination.setSocketID(peerSocketID);
                    setState(handshaking + 1);
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "Error creating socket", ex);
                    setState(invalid);
                }
                return;
            } else {
                logger.info("Unexpected type of handshake packet received");
                setState(invalid);
            }
        } else if (getState() == handshaking + 1) {
            try {
                logger.info("Received confirmation handshake response from " + peer + "\n" + hs);
                //TODO validate parameters sent by peer
                setState(ready);
                socket = new BoltSocket(endPoint, this);
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Error creating socket", ex);
                setState(invalid);
            }
        }
    }

    //initial handshake for connect
    protected void sendInitialHandShake() throws IOException {
        ConnectionHandshake handshake = new ConnectionHandshake();
        handshake.setConnectionType(ConnectionHandshake.CONNECTION_TYPE_REGULAR);
        handshake.setSocketType(ConnectionHandshake.SOCKET_TYPE_DGRAM);
//        long initialSequenceNo = SequenceNumber.random();
//        setInitialSequenceNumber(initialSequenceNo);
//        handshake.setInitialSeqNo(initialSequenceNo);
        //TODO CIAN: REMOVED

        handshake.setInitialSeqNo(getInitialSequenceNumber());

        handshake.setPacketSize(getDatagramSize());
        handshake.setSocketID(mySocketID);
        handshake.setMaxFlowWndSize(flowWindowSize);
        handshake.setSession(this);
        handshake.setAddress(endPoint.getLocalAddress());
        logger.info("Sending " + handshake);
        endPoint.doSend(handshake);
    }

    //2nd handshake for connect
    protected void sendSecondHandshake() throws IOException {
        ConnectionHandshake handshake = new ConnectionHandshake();
        handshake.setConnectionType(ConnectionHandshake.CONNECTION_TYPE_REGULAR);
        handshake.setSocketType(ConnectionHandshake.SOCKET_TYPE_DGRAM);

        //TODO CIAN: REMOVED
//        handshake.setInitialSeqNo(initialSequenceNo);
        handshake.setInitialSeqNo(getInitialSequenceNumber());


        handshake.setPacketSize(getDatagramSize());
        handshake.setSocketID(mySocketID);
        handshake.setMaxFlowWndSize(flowWindowSize);
        handshake.setSession(this);
        handshake.setCookie(sessionCookie);
        handshake.setAddress(endPoint.getLocalAddress());
        handshake.setDestinationID(getDestination().getSocketID());
        logger.info("Sending confirmation " + handshake);
        endPoint.doSend(handshake);
    }


    public BoltPacket getLastPkt() {
        return lastPacket;
    }


}