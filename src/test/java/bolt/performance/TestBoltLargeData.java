package bolt.performance;

import bolt.BoltClient;
import bolt.BoltServer;
import bolt.BoltTestBase;
import bolt.Config;
import bolt.event.ConnectionReadyEvent;
import bolt.receiver.RoutedData;
import bolt.util.PortUtil;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import rx.schedulers.Schedulers;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.text.NumberFormat;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeoutException;



import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class TestBoltLargeData extends BoltTestBase {

    public static final int SERVER_PORT = PortUtil.nextServerPort();
    private final NumberFormat format = NumberFormat.getNumberInstance();
    boolean running = false;
    //how many
    int num_packets = 50;
    //how large is a single packet
//        int size = 20 * 1024;
    int size = 20 * 1024 * 1024;
    int TIMEOUT = Integer.MAX_VALUE;
    int READ_BUFFERSIZE = 1 * 1024 * 1024;
    volatile long total = 0;
    volatile boolean serverRunning = true;
    volatile boolean serverStarted = false;

    private MessageDigest serverMD5;

    @Test
    public void test1() throws Exception {
//        LoggerFactory.getLogger("bolt").setLevel(Level.INFO); TODO CHECK
//		System.setProperty("bolt.receiver.storeStatistics","true");
//		System.setProperty("bolt.sender.storeStatistics","true");
//        System.setProperty(BoltSession.CC_CLASS, SimpleTCP.class.getName());
        TIMEOUT = Integer.MAX_VALUE;
        try {
            doTest(0);
        }
        catch (TimeoutException te) {
            te.printStackTrace();
            fail();
        }
    }

    protected void doTest(final float packetLossPercentage) throws Exception {
        serverMD5 = MessageDigest.getInstance("MD5");
        final Config config = new Config(InetAddress.getByName("localhost"), PortUtil.nextClientPort())
                .setPacketLoss(packetLossPercentage);

        format.setMaximumFractionDigits(2);
        long start = System.currentTimeMillis();

        final long N = num_packets * size;

        final byte[] data = new byte[size];
        new Random(12345).nextBytes(data);
        final MessageDigest clientMD5 = MessageDigest.getInstance("MD5");

        if (!running) runServer();
        while (!serverStarted) Thread.sleep(100);

        System.out.println("Sending <" + num_packets + "> packets of <" + format.format(size / 1024.0 / 1024.0) + "> Mbytes each");
        final Set<Throwable> errors = new HashSet<>();
        final BoltClient client = new BoltClient(config);
        client.connect(InetAddress.getByName("localhost"), SERVER_PORT)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.computation())
                .ofType(ConnectionReadyEvent.class)
                .subscribe(__ -> {
                            try {
                                for (int i = 0; i < num_packets; i++) {
                                    long block = System.currentTimeMillis();
                                    client.sendBlocking(data);
//                                    clientMD5.update(data);
                                    double took = System.currentTimeMillis() - block;
                                    double arrival = client.getStatistics().getPacketArrivalRate();
                                    double snd = client.getStatistics().getSendPeriod();
                                    System.out.println("Sent block <" + i + "> in " + took + " ms, "
                                            + " pktArr: " + arrival
                                            + " snd: " + format.format(snd)
                                            + " rate: " + format.format(size / (1024 * took)) + " MB/sec");
                                }
                                client.flush();
                            }
                            catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        },
                        errors::add);

        for (int i = 0; i < num_packets; i++) {
            clientMD5.update(data);
        }

        while (total < N && errors.isEmpty()) Thread.sleep(10);
        if (!errors.isEmpty()) throw new RuntimeException(errors.iterator().next());

        long end = System.currentTimeMillis();
        final String md5Sent = hexString(clientMD5);
        final String md5Received = hexString(serverMD5);
        System.out.println("Done. Sending " + N / 1024 / 1024 + " Mbytes took " + (end - start) + " ms");
        final double mbytes = N / (end - start) / 1024.0;
        final double mbit = 8 * mbytes;
        System.out.println("Rate: " + format.format(mbytes) + " Mbytes/sec " + format.format(mbit) + " Mbit/sec");
        System.out.println("Server received: " + total);

        //	assertEquals(N,total);
        System.out.println("MD5 hash of data sent: " + md5Sent);
        System.out.println("MD5 hash of data received: " + md5Received);
        System.out.println(client.getStatistics());

        assertEquals(md5Sent, md5Received);

//        //store stat history to csv file
//        client.getStatistics().writeParameterHistory(File.createTempFile("/boltstats-", ".csv"));
    }

    private void runServer() throws Exception {
        final BoltServer server = new BoltServer(new Config(InetAddress.getByName("localhost"), SERVER_PORT));
        server.bind()
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.computation())
                .ofType(RoutedData.class)
                .map(d -> (byte[]) d.getPayload())
                .subscribe(x -> {
                    serverMD5.update(x, 0, x.length);
                    total += x.length;
                });
        serverRunning = serverStarted = true;
    }
}
