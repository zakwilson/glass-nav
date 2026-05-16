package dev.glass.glass.transport;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import dev.glass.protocol.FrameReader;
import dev.glass.protocol.FrameWriter;
import dev.glass.protocol.Packet;
import dev.glass.protocol.transport.Keepalive;
import dev.glass.protocol.transport.Transport;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Glass-side RFCOMM listener. Accepts a single phone connection at a time. On disconnect, it loops
 * back to {@code accept()} so the phone can reconnect after a transient outage.
 *
 * Hardware-only — Glass emulator BT stack does not surface RFCOMM listeners to a paired host, so
 * this code path is not exercised by container tests; it compiles and is link-checked against API
 * 19 only.
 */
public final class RfcommServerTransport implements Transport {
    private static final String TAG = "RfcommServer";
    public static final UUID GLASS_UUID = UUID.fromString("e8b4c8a0-1f3a-4f7b-9b0d-ee9f2c1a7b00");
    public static final String SERVICE_NAME = "GlassCycling";
    /** Fixed RFCOMM channel used by both sides via the hidden {@code listenUsingInsecureRfcommOn}
     * /{@code createInsecureRfcommSocket} reflection APIs. Bypasses SDP, which on Glass XE-C
     * sometimes hands the phone a stale or wrong channel and causes "phone connects to a system
     * service" problems. RFCOMM has channels 1-30; we use 22 to dodge anything common. */
    public static final int RFCOMM_CHANNEL = 22;

    private final UUID uuid;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;
    private BluetoothServerSocket serverSocket;
    private BluetoothSocket socket;
    private FrameWriter writer;
    private Listener listener;

    public RfcommServerTransport() {
        this(GLASS_UUID);
    }

    public RfcommServerTransport(UUID uuid) {
        this.uuid = uuid;
    }

    @Override public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override public synchronized void start() throws IOException {
        if (!running.compareAndSet(false, true)) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) throw new IOException("Bluetooth not supported");
        if (!adapter.isEnabled()) throw new IOException("Bluetooth disabled");
        serverSocket = listenOnFixedChannel(adapter);
        Log.i(TAG, "listening on RFCOMM channel " + RFCOMM_CHANNEL);
        thread = new Thread(this::run, "RfcommServer");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Bind to a fixed RFCOMM channel via the hidden {@code listenUsingInsecureRfcommOn(int)}
     * BluetoothAdapter API. Falls back to {@code listenUsingInsecureRfcommWithServiceRecord} if
     * reflection fails (so the build still works on stripped Android forks where the hidden API
     * was removed).
     */
    private BluetoothServerSocket listenOnFixedChannel(BluetoothAdapter adapter) throws IOException {
        try {
            Method m = BluetoothAdapter.class.getMethod("listenUsingInsecureRfcommOn", int.class);
            return (BluetoothServerSocket) m.invoke(adapter, RFCOMM_CHANNEL);
        } catch (Throwable t) {
            Log.w(TAG, "listenUsingInsecureRfcommOn(" + RFCOMM_CHANNEL + ") failed via reflection: "
                + t.getMessage() + " — falling back to SDP UUID");
            return adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, uuid);
        }
    }

    @Override public synchronized void stop() {
        if (!running.compareAndSet(true, false)) return;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        if (thread != null) thread.interrupt();
    }

    @Override public void send(Packet p) throws IOException {
        FrameWriter w = writer;
        if (w == null) throw new IOException("RfcommServer not connected");
        w.write(p);
    }

    private void run() {
        while (running.get()) {
            Keepalive keepalive = null;
            try {
                socket = serverSocket.accept();
                writer = new FrameWriter(socket.getOutputStream());
                FrameReader reader = new FrameReader(socket.getInputStream());
                if (listener != null) listener.onConnected();
                final BluetoothSocket sessionSocket = socket;
                keepalive = new Keepalive(
                        /* sendPings = */ false,
                        p -> { FrameWriter w = writer; if (w != null) { try { w.write(p); } catch (Throwable ignored) {} } },
                        () -> { try { sessionSocket.close(); } catch (Throwable ignored) {} });
                keepalive.start();
                while (running.get()) {
                    Packet p = reader.readNext();
                    if (p == null) break;
                    if (keepalive.handleInbound(p)) continue;
                    if (listener != null) listener.onPacket(p);
                }
                if (listener != null) listener.onDisconnected(null);
            } catch (Throwable t) {
                Log.w(TAG, "RFCOMM session error: " + t.getMessage());
                if (listener != null) listener.onDisconnected(t);
            } finally {
                if (keepalive != null) keepalive.stop();
                writer = null;
                try { if (socket != null) socket.close(); } catch (IOException ignored) {}
                socket = null;
            }
        }
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }
}
