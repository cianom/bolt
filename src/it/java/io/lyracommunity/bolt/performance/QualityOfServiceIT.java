package io.lyracommunity.bolt.performance;

import io.lyracommunity.bolt.helper.Infra;
import io.lyracommunity.bolt.helper.TestObjects;
import org.junit.Test;

import java.text.MessageFormat;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by keen on 08/04/16.
 */
public class QualityOfServiceIT {


    /**
     * Set an artificial latency and packet loss and ensure all sent packets take longer
     * than the expected minimum time to deliver.
     */
    @Test
    public void test_HighLatencyAndHighPacketLoss_TimeTakeToDeliveryReliable() throws Throwable {
//        while (true) {
            doTest(500, 0.2f, 20, TestObjects.reliableUnordered(100), 1000);
//            System.out.println("\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n");
//            Thread.sleep(1000);
//        }
    }

    /**
     * Set an artificial latency and packet loss and ensure all sent packets take longer
     * than the expected minimum time to deliver.
     */
    @Test
    public void test_VeryHighLatencyAndHighPacketLoss_TimeTakeToDeliveryReliable() throws Throwable {
        doTest(1000, 0.2f, 5, TestObjects.reliableUnordered(100), 2000);
    }

    /**
     * Set an artificial latency and packet loss and ensure all sent packets take longer
     * than the expected minimum time to deliver.
     */
    @Test
    public void test_LowLatencyAndExtremePacketLoss_TimeTakeToDeliveryReliable() throws Throwable {
        doTest(50, 0.5f, 10, TestObjects.reliableUnordered(100), 100);
    }

    /**
     * Set an artificial latency and packet loss and ensure all sent packets take longer
     * than the expected minimum time to deliver.
     */
    @Test
    public void test_LANLatencyAndMediumPacketLoss_TimeTakeToDeliveryReliable() throws Throwable {
        doTest(5, 0.1f, 10, TestObjects.reliableUnordered(100), 10);
    }


    private void doTest(final int latencyInMillis, final float packetLoss, final int numPackets, final Object toSend,
                        final int minimumExpectedTotalTime) throws Throwable {

        Infra.Builder builder = Infra.Builder.withServerAndClients(1)
                .preconfigureServer(s -> {
                    s.config().setSimulatedLatency(latencyInMillis);
                    s.config().setPacketLoss(packetLoss);
                })
                .onReadyClient((tc, evt) -> {
                    System.out.println("Connected, begin send.");
                    for (int i = 0; i < numPackets; i++) tc.client.send(toSend);
                })
                .setWaitCondition(inf -> inf.server().receivedOf(toSend.getClass()) < numPackets);

        try (Infra i = builder.build()) {
            final long millisTaken = i.start().awaitCompletion(2, TimeUnit.MINUTES);

            assertEquals(numPackets, i.server().receivedOf(toSend.getClass()));
            assertTrue(MessageFormat.format("Took shorter [{0}] than min expected [{1}]", millisTaken, minimumExpectedTotalTime),
                    minimumExpectedTotalTime <= millisTaken);
        }
    }

}
