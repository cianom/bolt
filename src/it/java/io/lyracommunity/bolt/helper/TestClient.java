package io.lyracommunity.bolt.helper;

import io.lyracommunity.bolt.BoltClient;
import io.lyracommunity.bolt.api.Config;
import io.lyracommunity.bolt.api.event.ConnectionReady;
import io.lyracommunity.bolt.api.event.Message;
import rx.Subscription;
import rx.schedulers.Schedulers;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Created by keen on 24/03/16.
 */
public class TestClient implements AutoCloseable {

    public final  BoltClient                              client;
    private final PacketReceiver                          receivedByType;
    private final List<Throwable>                         errors;
    private final BiConsumer<TestClient, Object>          onNext;
    private final BiConsumer<TestClient, ConnectionReady> onReady;
    private final AtomicLong readyTime = new AtomicLong();
    private       Subscription                            subscription;

    public TestClient(BoltClient client, PacketReceiver receivedByType, List<Throwable> errors,
                      BiConsumer<TestClient, Object> onNext, BiConsumer<TestClient, ConnectionReady> onReady) {
        this.client = client;
        this.receivedByType = receivedByType;
        this.errors = errors;
        this.onNext = onNext;
        this.onReady = onReady;
    }

    public static TestClient runClient(final int serverPort, final BiConsumer<TestClient, Object> onNext,
                                       final BiConsumer<TestClient, ConnectionReady> onReady) throws Exception {
        return runClient(serverPort, onNext, onReady, null);
    }

    private static TestClient runClient(final int serverPort, final BiConsumer<TestClient, Object> onNext,
                                        final BiConsumer<TestClient, ConnectionReady> onReady,
                                        final Consumer<BoltClient> init) throws Exception {
        final Config clientConfig = new Config(InetAddress.getByName("localhost"), PortUtil.nextClientPort());
        final BoltClient client = new BoltClient(clientConfig);
        if (init != null) init.accept(client);
        TestObjects.registerAll(client.codecs());

        final PacketReceiver packetReceiver = new PacketReceiver();
        final List<Throwable> errors = new ArrayList<>();

        return new TestClient(client, packetReceiver, errors, onNext, onReady);
    }

    public static List<TestClient> runClients(final int clientCount, final int serverPort,
                                              final BiConsumer<TestClient, Object> onNext,
                                              final BiConsumer<TestClient, ConnectionReady> onReady,
                                              final Consumer<BoltClient> init) throws Exception {

        final List<TestClient> clients = new ArrayList<>(clientCount);
        for (int i = 0; i < clientCount; i++) {
            clients.add(runClient(serverPort, onNext, onReady, init));
        }
        return clients;
    }

    public void start(final int serverPort) throws IOException {
        subscription = client.connect(InetAddress.getByName("localhost"), serverPort)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.computation())
                .subscribe(
                        x -> {
                            receivedByType.receive(x);
                            if (onNext != null) {
                                onNext.accept(this, x);
                                if (Message.class.equals(x.getClass()))
                                    onNext.accept(this, ((Message) x).getPayload());
                            }
                            if (ConnectionReady.class.equals(x.getClass())) {
                                readyTime.set(System.currentTimeMillis());
                                if (onReady != null) onReady.accept(this, (ConnectionReady) x);
                            }
                        },
                        errors::add);
    }

    long getReadyTime() {
        return readyTime.get();
    }

    private TestClient printStatistics() {
        System.out.println(client.getStatistics());
        return this;
    }

    public List<Throwable> getErrors() {
        return errors;
    }

    public int receivedOf(final Class clazz) {
        return receivedByType.getTotalReceived(clazz);
    }

    @Override
    public void close() {
        if (subscription != null && !subscription.isUnsubscribed()) {
            printStatistics();
            subscription.unsubscribe();
        }
    }

}
