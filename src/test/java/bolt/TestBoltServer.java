package bolt;

import bolt.event.ConnectionReadyEvent;
import bolt.receiver.RoutedData;
import bolt.util.TestUtil;
import org.junit.Test;
import rx.Observable;
import rx.observables.ConnectableObservable;
import rx.schedulers.Schedulers;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;

public class TestBoltServer extends BoltTestBase {

    private static AtomicInteger SERVER_PORT = new AtomicInteger(65310);
    private static AtomicInteger CLIENT_PORT = new AtomicInteger(12335);
    boolean running = false;
    int num_packets = 32;
    long total = 0;
    volatile boolean serverRunning = true;
    volatile String md5_received = null;

    @Test(expected = Exception.class)
    public void testErrorTooManyChunks() throws Throwable {
        Logger.getLogger("bolt").setLevel(Level.INFO);
        num_packets = 10000;
        doTest(0);
    }

    @Test
    public void testWithoutLoss() throws Throwable {
        Logger.getLogger("bolt").setLevel(Level.INFO);
        num_packets = 1000;
        doTest(0);
    }

    // Set an artificial loss rate.
    @Test
    public void testWithLoss() throws Throwable {
        num_packets = 100;
        //set log level
        Logger.getLogger("bolt").setLevel(Level.INFO);
        doTest(0.33334f);
    }

    // Send even more data.
    @Test
    public void testLargeDataSet() throws Throwable {
        num_packets = 100;
        //set log level
        Logger.getLogger("bolt").setLevel(Level.INFO);
        doTest(0);
    }

    protected void doTest(final float packetLossPercentage) throws Throwable {
        final Config clientConfig = new Config(InetAddress.getByName("localhost"), CLIENT_PORT.incrementAndGet());
        final Config serverConfig = new Config(InetAddress.getByName("localhost"), SERVER_PORT.incrementAndGet())
                .setPacketLoss(packetLossPercentage);
        if (!running) runServer(serverConfig);
        final BoltClient client = new BoltClient(clientConfig);

        Observable<?> in = client.connect(InetAddress.getByName("localhost"), SERVER_PORT.get()).subscribeOn(Schedulers.io());
        ConnectableObservable<?> cin = in.publish();

        int N = num_packets * 32768;
        byte[] data = new byte[N];
        new Random().nextBytes(data);

        while (!serverRunning) Thread.sleep(100);

        final String md5_sent = computeMD5(data);
        final long start = System.currentTimeMillis();
        System.out.println("Sending data block of <" + N / 1024 + "> Kbytes.");

        final Set<Throwable> errors = new HashSet<>();

        cin.ofType(ConnectionReadyEvent.class)
                .take(1)
                .timeout(5, TimeUnit.SECONDS)
                .observeOn(Schedulers.computation())
                .subscribe(__ -> client.sendBlocking(data),
                        errors::add);

        cin.observeOn(Schedulers.computation()).subscribe();
        cin.connect();

        while (total < N && errors.isEmpty()) Thread.sleep(100);

        if (!errors.isEmpty()) throw errors.iterator().next();

        long end = System.currentTimeMillis();
        System.out.println("Shutdown client.");
        System.out.println("Done. Sending " + N / 1024 + " Kbytes took " + (end - start) + " ms");
        System.out.println("Rate " + N / (end - start) + " Kbytes/sec");
        System.out.println("Server received: " + total);

        assertEquals(N, total);
        System.out.println("MD5 hash of data sent: " + md5_sent);
        System.out.println("MD5 hash of data received: " + md5_received);
        System.out.println(client.getStatistics());

        assertEquals(md5_sent, md5_received);
    }

    private void runServer(Config config) throws Exception {
        final MessageDigest md5 = MessageDigest.getInstance("MD5");
        final BoltServer server = new BoltServer(config);

        server.bind()
                .subscribeOn(Schedulers.io())
                .onBackpressureBuffer()
                .observeOn(Schedulers.computation())
                .ofType(RoutedData.class)
                .map(rd -> (byte[]) rd.getPayload())
                .subscribe(x -> {
                            md5.update(x, 0, x.length);
                            total += x.length;
                            md5_received = TestUtil.hexString(md5);
                        },
                        ex -> {
                            System.out.println(ex.toString());
                            serverRunning = false;
                        },
                        () -> {
                            serverRunning = false;
                            md5_received = TestUtil.hexString(md5);
                        });
    }

}
