package dev.glass.protocol.transport;

import dev.glass.protocol.Packet;
import dev.glass.protocol.TurnKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a synthetic ride against the Glass emulator's NavLiveCardService over TCP.
 * Substitutes for the unbootable API 30 phone emulator: this test plays the phone-app role from
 * the JVM, while the Glass APK runs on the API 19 emulator (clj-android-19).
 *
 * Prerequisites (manual, since this is not a unit test):
 *   1. Boot the Glass emulator and install the glass-app APK.
 *   2. adb -s emulator-5556 forward tcp:8765 tcp:8765
 *   3. Run: ./gradlew :protocol:test --tests "*PairEmulatorE2eTest*" -Dpair.e2e=1
 *
 * Confirms via Glass logcat:
 *   - PacketDispatcher logs ROUTE_START, every TURN_BUNDLE, every PROGRESS, ROUTE_END
 *   - NavLiveCardService.updateRemoteViews is called for each PROGRESS
 */
@EnabledIfSystemProperty(named = "pair.e2e", matches = "1")
class PairEmulatorE2eTest {

    private static final String HOST = System.getProperty("pair.e2e.host", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getProperty("pair.e2e.port", "8765"));

    @Test void connectsAndPushesSyntheticRoute() throws Exception {
        TcpTransport client = new TcpTransport(TcpTransport.Role.CLIENT, HOST, PORT);
        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        client.setListener(new Transport.Listener() {
            @Override public void onConnected() { connected.countDown(); }
            @Override public void onPacket(Packet p) {}
            @Override public void onDisconnected(Throwable cause) {
                if (cause != null) failure.compareAndSet(null, cause);
            }
        });
        client.start();
        try {
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

            long routeId = 0xCAFEBABEL;
            int totalTurns = 5;
            client.send(new Packet.RouteStart(routeId, totalTurns, "Brandenburger Tor"));

            Random rnd = new Random(7);
            int distFromStart = 0;
            for (int i = 0; i < totalTurns; i++) {
                distFromStart += 200;
                byte[] png = new byte[1024 + rnd.nextInt(4096)];
                rnd.nextBytes(png);
                TurnKind kind = TurnKind.values()[(i % (TurnKind.values().length - 1)) + 1];
                client.send(new Packet.TurnBundle(
                    routeId, i, kind, distFromStart,
                    "Synthetic turn #" + i + " (" + kind + ")", png));
            }

            // 1 Hz progress for 6 ticks
            for (int t = 0; t < 6; t++) {
                int turnIdx = Math.min(t / 2, totalTurns - 1);
                int distToTurn = Math.max(0, 100 - t * 15);
                client.send(new Packet.Progress(routeId, turnIdx, distToTurn, (short) 0, 20, 0, 0));
                Thread.sleep(200);
            }

            client.send(new Packet.RouteEnd(routeId, Packet.RouteEnd.Reason.ARRIVED));
            // Give the dispatcher a moment to process before tearing down the socket.
            Thread.sleep(500);
        } finally {
            client.stop();
        }

        Throwable f = failure.get();
        // Clean disconnect by close() will surface as 'socket closed' — ignore that.
        if (f != null && f.getMessage() != null && !f.getMessage().toLowerCase().contains("closed")) {
            throw new AssertionError("transport failure", f);
        }
    }
}
