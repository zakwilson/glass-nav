package dev.glass.protocol.transport;

import dev.glass.protocol.Packet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class KeepaliveTest {

    @Test void activeSideSendsPingsPeriodically() throws Exception {
        List<Packet> sent = new ArrayList<>();
        Keepalive k = new Keepalive(
                /* sendPings = */ true,
                /* pingIntervalMs = */ 50L,
                /* timeoutMs = */ 5_000L,
                p -> { synchronized (sent) { sent.add(p); } },
                () -> { throw new AssertionError("watchdog must not fire"); });
        k.start();
        try {
            Thread.sleep(250);
        } finally {
            k.stop();
        }
        synchronized (sent) {
            assertThat(sent).isNotEmpty();
            for (Packet p : sent) assertThat(p).isInstanceOf(Packet.Ping.class);
        }
    }

    @Test void passiveSideRepliesPongOnPing() {
        List<Packet> sent = new ArrayList<>();
        Keepalive k = new Keepalive(
                /* sendPings = */ false,
                /* pingIntervalMs = */ 10_000L,
                /* timeoutMs = */ 10_000L,
                sent::add,
                () -> {});
        boolean filtered = k.handleInbound(new Packet.Ping(42L));
        assertThat(filtered).isTrue();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).isInstanceOf(Packet.Pong.class);
        assertThat(((Packet.Pong) sent.get(0)).echoTimestampMs).isEqualTo(42L);
    }

    @Test void pongIsFilteredButNoReply() {
        List<Packet> sent = new ArrayList<>();
        Keepalive k = new Keepalive(true, 10_000L, 10_000L, sent::add, () -> {});
        assertThat(k.handleInbound(new Packet.Pong(7L))).isTrue();
        assertThat(sent).isEmpty();
    }

    @Test void nonHeartbeatPacketsAreNotFiltered() {
        Keepalive k = new Keepalive(true, 10_000L, 10_000L, p -> {}, () -> {});
        assertThat(k.handleInbound(new Packet.Hello(1))).isFalse();
    }

    @Test void watchdogFiresAfterInactivity() throws Exception {
        CountDownLatch dead = new CountDownLatch(1);
        Keepalive k = new Keepalive(
                /* sendPings = */ false,
                /* pingIntervalMs = */ 1_000L,
                /* timeoutMs = */ 100L,
                p -> {},
                dead::countDown);
        k.start();
        try {
            assertThat(dead.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            k.stop();
        }
    }

    @Test void inboundTrafficResetsWatchdog() throws Exception {
        AtomicInteger deadCalls = new AtomicInteger();
        Keepalive k = new Keepalive(
                /* sendPings = */ false,
                /* pingIntervalMs = */ 1_000L,
                /* timeoutMs = */ 200L,
                p -> {},
                deadCalls::incrementAndGet);
        k.start();
        try {
            for (int i = 0; i < 5; i++) {
                Thread.sleep(80);
                k.handleInbound(new Packet.Hello(1));
            }
            assertThat(deadCalls.get()).isZero();
        } finally {
            k.stop();
        }
    }
}
