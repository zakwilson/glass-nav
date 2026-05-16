package dev.glass.protocol.transport;

import dev.glass.protocol.FrameReader;
import dev.glass.protocol.FrameWriter;
import dev.glass.protocol.Packet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure-JDK TCP transport used in container/emulator. Acts as either listening server or client.
 *
 * Server mode: accepts a single connection, then services it. Reconnect on disconnect is the
 * caller's responsibility (call {@link #stop()} then {@link #start()} again).
 */
public final class TcpTransport implements Transport {
    public enum Role { SERVER, CLIENT }

    private final Role role;
    private final String host;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Listener listener;
    private Thread thread;
    private ServerSocket serverSocket;
    private Socket socket;
    private FrameWriter writer;

    public TcpTransport(Role role, String host, int port) {
        this.role = role;
        this.host = host;
        this.port = port;
    }

    @Override public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override public synchronized void start() throws IOException {
        if (!running.compareAndSet(false, true)) return;
        if (role == Role.SERVER) {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(host, port));
        }
        thread = new Thread(this::run, "TcpTransport-" + role + "-" + port);
        thread.setDaemon(true);
        thread.start();
    }

    @Override public synchronized void stop() {
        if (!running.compareAndSet(true, false)) return;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        if (thread != null) thread.interrupt();
    }

    @Override public void send(Packet p) throws IOException {
        FrameWriter w = writer;
        if (w == null) throw new IOException("not connected");
        w.write(p);
    }

    public boolean isConnected() {
        Socket s = socket;
        return s != null && s.isConnected() && !s.isClosed();
    }

    private void run() {
        if (role == Role.SERVER) runServer();
        else runClient();
    }

    private void runServer() {
        // Accept loop: serve one connection at a time, re-accept after disconnect.
        while (running.get()) {
            Throwable cause = null;
            Keepalive keepalive = null;
            try {
                socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                writer = new FrameWriter(socket.getOutputStream());
                FrameReader reader = new FrameReader(socket.getInputStream());
                if (listener != null) listener.onConnected();
                final Socket sessionSocket = socket;
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
            } catch (Throwable t) {
                cause = t;
            } finally {
                if (keepalive != null) keepalive.stop();
                try { if (socket != null) socket.close(); } catch (IOException ignored) {}
                writer = null;
                if (listener != null) listener.onDisconnected(cause);
                socket = null;
            }
        }
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    private void runClient() {
        Throwable cause = null;
        Keepalive keepalive = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5_000);
            socket.setTcpNoDelay(true);
            writer = new FrameWriter(socket.getOutputStream());
            FrameReader reader = new FrameReader(socket.getInputStream());
            if (listener != null) listener.onConnected();
            final Socket sessionSocket = socket;
            keepalive = new Keepalive(
                    /* sendPings = */ true,
                    p -> { FrameWriter w = writer; if (w != null) { try { w.write(p); } catch (Throwable ignored) {} } },
                    () -> { try { sessionSocket.close(); } catch (Throwable ignored) {} });
            keepalive.start();
            while (running.get()) {
                Packet p = reader.readNext();
                if (p == null) break;
                if (keepalive.handleInbound(p)) continue;
                if (listener != null) listener.onPacket(p);
            }
        } catch (Throwable t) {
            cause = t;
        } finally {
            if (keepalive != null) keepalive.stop();
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
            writer = null;
            running.set(false);
            if (listener != null) listener.onDisconnected(cause);
        }
    }
}
