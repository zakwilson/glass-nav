package dev.glass.protocol.transport;

import dev.glass.protocol.Packet;
import dev.glass.protocol.TurnKind;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TcpTransportTest {

    @Test void serverAndClientRoundTripManyPackets() throws Exception {
        int port = findFreePort();
        TcpTransport server = new TcpTransport(TcpTransport.Role.SERVER, "127.0.0.1", port);
        TcpTransport client = new TcpTransport(TcpTransport.Role.CLIENT, "127.0.0.1", port);

        BlockingQueue<Packet> received = new ArrayBlockingQueue<>(200);
        CountDownLatch serverConnected = new CountDownLatch(1);
        CountDownLatch clientConnected = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        server.setListener(new Transport.Listener() {
            @Override public void onConnected() { serverConnected.countDown(); }
            @Override public void onPacket(Packet p) { received.offer(p); }
            @Override public void onDisconnected(Throwable cause) {
                if (cause != null && !"socket closed".equalsIgnoreCase(cause.getMessage())) {
                    failure.compareAndSet(null, cause);
                }
            }
        });
        client.setListener(new Transport.Listener() {
            @Override public void onConnected() { clientConnected.countDown(); }
            @Override public void onPacket(Packet p) {}
            @Override public void onDisconnected(Throwable cause) {}
        });

        try {
            server.start();
            client.start();
            assertThat(serverConnected.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(clientConnected.await(3, TimeUnit.SECONDS)).isTrue();

            Random rnd = new Random(1234);
            int count = 100;
            for (int i = 0; i < count; i++) {
                Packet p = (i % 4 == 0)
                    ? new Packet.TurnBundle(7L, i, TurnKind.TR, i * 10, "Turn " + i,
                        randomBytes(1024 + rnd.nextInt(4096), rnd.nextLong()))
                    : new Packet.Progress(7L, i, 100 - i, (short) (i * 100 - 5000), i % 60, 0, 0);
                client.send(p);
            }

            for (int i = 0; i < count; i++) {
                Packet p = received.poll(3, TimeUnit.SECONDS);
                assertThat(p).as("packet #" + i).isNotNull();
                if (i % 4 == 0) {
                    assertThat(p).isInstanceOf(Packet.TurnBundle.class);
                    assertThat(((Packet.TurnBundle) p).turnIndex).isEqualTo(i);
                } else {
                    assertThat(p).isInstanceOf(Packet.Progress.class);
                    assertThat(((Packet.Progress) p).turnIndex).isEqualTo(i);
                }
            }
        } finally {
            client.stop();
            server.stop();
        }

        Throwable t = failure.get();
        if (t != null) throw new AssertionError("transport failure", t);
    }

    @Test void sendBeforeStartFails() {
        TcpTransport t = new TcpTransport(TcpTransport.Role.CLIENT, "127.0.0.1", 1);
        try {
            t.send(new Packet.Ping(0));
            throw new AssertionError("expected IOException");
        } catch (Exception e) {
            assertThat(e).hasMessageContaining("not connected");
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }
}
