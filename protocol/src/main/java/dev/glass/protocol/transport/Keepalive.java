package dev.glass.protocol.transport;

import dev.glass.protocol.Packet;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Application-level keepalive shared by every Transport implementation. Bluetooth RFCOMM can
 * drop silently — the OS may take minutes (or never, if the peer process froze) to surface a
 * link failure as an IOException on read, so without a heartbeat both sides block forever in
 * {@link dev.glass.protocol.FrameReader#readNext()} and the existing reconnect path never fires.
 *
 * <p>Per session:
 * <ul>
 *   <li>The active side (typically the phone client) sends a {@link Packet.Ping} every
 *       {@code pingIntervalMs}.</li>
 *   <li>The passive side replies to every Ping with a {@link Packet.Pong}; both sides reset their
 *       receive-watchdog on every inbound packet (Ping/Pong included).</li>
 *   <li>If no inbound packet arrives for {@code timeoutMs}, the watchdog invokes {@code onDead}
 *       which the owning Transport implements as "close the socket" so the blocking read errors
 *       out and the listener's {@code onDisconnected} fires.</li>
 * </ul>
 */
public final class Keepalive {
    public interface Sender { void send(Packet p); }

    public static final long DEFAULT_PING_INTERVAL_MS = 5_000L;
    public static final long DEFAULT_TIMEOUT_MS = 15_000L;

    private final boolean sendPings;
    private final long pingIntervalMs;
    private final long timeoutMs;
    private final Sender sender;
    private final Runnable onDead;
    private final AtomicLong lastInboundMs = new AtomicLong();
    private volatile boolean running;
    private Thread thread;

    public Keepalive(boolean sendPings, Sender sender, Runnable onDead) {
        this(sendPings, DEFAULT_PING_INTERVAL_MS, DEFAULT_TIMEOUT_MS, sender, onDead);
    }

    public Keepalive(boolean sendPings, long pingIntervalMs, long timeoutMs,
                     Sender sender, Runnable onDead) {
        this.sendPings = sendPings;
        this.pingIntervalMs = pingIntervalMs;
        this.timeoutMs = timeoutMs;
        this.sender = sender;
        this.onDead = onDead;
    }

    public void start() {
        running = true;
        lastInboundMs.set(System.currentTimeMillis());
        thread = new Thread(this::run, "Keepalive");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    /**
     * Filter inbound packets: reset the watchdog, auto-reply to Ping with Pong, and report
     * whether the packet was a Ping/Pong (which the caller should NOT forward to its listener).
     */
    public boolean handleInbound(Packet p) {
        lastInboundMs.set(System.currentTimeMillis());
        if (p instanceof Packet.Ping) {
            try { sender.send(new Packet.Pong(((Packet.Ping) p).timestampMs)); } catch (Throwable ignored) {}
            return true;
        }
        return p instanceof Packet.Pong;
    }

    private void run() {
        long nextPingAt = System.currentTimeMillis() + pingIntervalMs;
        long tickMs = Math.max(200L, Math.min(pingIntervalMs, timeoutMs / 3));
        while (running) {
            long now = System.currentTimeMillis();
            if (now - lastInboundMs.get() > timeoutMs) {
                try { onDead.run(); } catch (Throwable ignored) {}
                return;
            }
            if (sendPings && now >= nextPingAt) {
                try { sender.send(new Packet.Ping(now & 0xffffffffL)); } catch (Throwable ignored) {}
                nextPingAt = now + pingIntervalMs;
            }
            try { Thread.sleep(tickMs); } catch (InterruptedException e) { return; }
        }
    }
}
