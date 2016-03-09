package bolt;

import bolt.packets.DataPacket;
import bolt.packets.PacketUtil;
import bolt.receiver.RoutedData;
import bolt.xcoder.XCoderRepository;
import rx.Observable;
import rx.Subscriber;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Collection;
import java.util.Optional;

/**
 * Created by omahoc9 on 3/3/16.
 */
public class BoltServer implements Server {

    private final XCoderRepository xCoderRepository;

    private volatile BoltEndPoint serverEndpoint;

    public BoltServer(final XCoderRepository xCoderRepository) {
        this.xCoderRepository = xCoderRepository;
    }

    @Override
    public Observable<?> bind(final InetAddress address, final int port) {
        return Observable.create(subscriber -> {
            try {
                this.serverEndpoint = new BoltEndPoint(address, port);
                this.serverEndpoint.start().subscribe(subscriber);

                while (!subscriber.isUnsubscribed()) {
//                    try {
                        pollReceivedData(subscriber);
//                        Thread.sleep(0, 1000);
//                    }
//                    catch (InterruptedException e) {
//                        // Do nothing.
//                    }
                }
            }
            catch (Exception ex) {
                subscriber.onError(ex);
            }
            subscriber.onCompleted();
            shutdown();
        });
//                .share();
    }

    private void pollReceivedData(final Subscriber<? super Object> subscriber) {
        for (BoltSession session : serverEndpoint.getSessions()) {
            if (session.getSocket() != null) {
                final DataPacket packet = session.getSocket().getReceiveBuffer().poll();

                if (packet != null) {
                    final Object decoded = xCoderRepository.decode(packet);
                    if (decoded != null) {
                        subscriber.onNext(new RoutedData(session.getSocketID(), decoded));
                    }
                }
            }
        }
    }

    @Override
    public void send(final Object obj, final long destId) throws IOException {
        final BoltSession session = Optional.ofNullable(serverEndpoint).map(e -> e.getSession(destId)).orElse(null);
        if (session != null) {
            final Collection<DataPacket> data = xCoderRepository.encode(obj);
            for (final DataPacket dp : data) {
                session.getSocket().doWrite(dp);
            }
        }
    }

    private void shutdown() {
        if (this.serverEndpoint != null) {
            this.serverEndpoint.stop();
            this.serverEndpoint = null;
        }
    }

}
