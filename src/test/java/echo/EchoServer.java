package echo;

import bolt.BoltServer;
import bolt.Config;
import bolt.receiver.RoutedData;
import rx.Subscription;
import rx.schedulers.Schedulers;

import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EchoServer {

    final ExecutorService pool = Executors.newFixedThreadPool(2);

    final BoltServer server;

    volatile boolean started = false;
    volatile boolean stopped = false;

    public EchoServer(final int port) throws Exception {
        server = new BoltServer(new Config(InetAddress.getByName("localhost"), port));
    }

    public void stop() {
        stopped = true;
    }

    public synchronized Subscription start() {

        Subscription s = server.bind()
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.computation())
                .ofType(RoutedData.class)
                .subscribe(x -> pool.execute(new Request(server, x)));
        started = true;
        return s;
    }

    public static class Request implements Runnable {

        private final BoltServer server;
        private final RoutedData received;

        public Request(BoltServer server, RoutedData received) {
            this.server = server;
            this.received = received;
        }

        public void run() {
            try {
                if (received.getPayload().getClass().equals(byte[].class)) {
                    System.out.println(new String((byte[]) received.getPayload()));
                }
                else {
                    System.out.println(received.getPayload().toString());
                }

                server.send(received.getPayload(), received.getSourceId());
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

}
