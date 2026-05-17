package dev.glass.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Encode/decode {@link Packet}s to/from raw byte frames.
 *
 * Frame format (big-endian):
 *   uint8 magic = 0x47, uint8 protoVersion = 0x01, uint8 type, uint32 payloadLen,
 *   byte[payloadLen] payload
 *
 * pstr (UTF-8 string): uint16 lenBytes, bytes...
 */
public final class Codec {
    public static final byte MAGIC = 0x47; // 'G'
    public static final byte PROTO_VERSION = 0x01;
    public static final int MAX_PAYLOAD_LEN = 2 * 1024 * 1024; // 2 MiB
    public static final int FRAME_HEADER_LEN = 1 + 1 + 1 + 4;

    private Codec() {}

    public static byte[] encode(Packet p) {
        try {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(payload);
            writePayload(out, p);
            out.flush();
            byte[] payloadBytes = payload.toByteArray();
            if (payloadBytes.length > MAX_PAYLOAD_LEN) {
                throw new IllegalArgumentException("payload too large: " + payloadBytes.length);
            }
            ByteArrayOutputStream frame = new ByteArrayOutputStream(FRAME_HEADER_LEN + payloadBytes.length);
            DataOutputStream fout = new DataOutputStream(frame);
            fout.writeByte(MAGIC);
            fout.writeByte(PROTO_VERSION);
            fout.writeByte(p.type().code);
            fout.writeInt(payloadBytes.length);
            fout.write(payloadBytes);
            return frame.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("encode failed for " + p, e);
        }
    }

    public static Packet decode(byte[] frame) throws ProtocolException {
        if (frame.length < FRAME_HEADER_LEN) {
            throw new ProtocolException("frame too short: " + frame.length);
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
            byte magic = in.readByte();
            if (magic != MAGIC) throw new ProtocolException(String.format("bad magic 0x%02x", magic & 0xff));
            byte ver = in.readByte();
            if (ver != PROTO_VERSION) throw new ProtocolException("unsupported protoVersion " + (ver & 0xff));
            byte typeCode = in.readByte();
            int payloadLen = in.readInt();
            if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_LEN) {
                throw new ProtocolException("invalid payloadLen " + payloadLen);
            }
            if (frame.length - FRAME_HEADER_LEN != payloadLen) {
                throw new ProtocolException("frame length mismatch: header says "
                    + payloadLen + " payload bytes, frame has " + (frame.length - FRAME_HEADER_LEN));
            }
            PacketType type = PacketType.fromCode(typeCode);
            return readPayload(in, type);
        } catch (IOException e) {
            if (e instanceof ProtocolException) throw (ProtocolException) e;
            throw new ProtocolException("decode failed: " + e.getMessage(), e);
        }
    }

    /** Decode a payload buffer when the frame header has already been consumed. */
    public static Packet decodePayload(PacketType type, byte[] payload) throws ProtocolException {
        try {
            return readPayload(new DataInputStream(new ByteArrayInputStream(payload)), type);
        } catch (IOException e) {
            if (e instanceof ProtocolException) throw (ProtocolException) e;
            throw new ProtocolException("decode payload failed: " + e.getMessage(), e);
        }
    }

    private static void writePayload(DataOutputStream out, Packet p) throws IOException {
        switch (p.type()) {
            case HELLO: {
                Packet.Hello h = (Packet.Hello) p;
                out.writeShort(h.protoVersionAccepted & 0xffff);
                break;
            }
            case ROUTE_START: {
                Packet.RouteStart r = (Packet.RouteStart) p;
                out.writeInt((int) (r.routeId & 0xffffffffL));
                out.writeShort(r.totalTurns & 0xffff);
                writePstr(out, r.destinationLabel);
                break;
            }
            case TURN_BUNDLE: {
                Packet.TurnBundle t = (Packet.TurnBundle) p;
                out.writeInt((int) (t.routeId & 0xffffffffL));
                out.writeShort(t.turnIndex & 0xffff);
                out.writeByte(t.kind.ordinal() & 0xff);
                out.writeShort(t.distanceFromStartM & 0xffff);
                writePstr(out, t.instructionText);
                out.writeInt(t.pngBytes.length);
                out.write(t.pngBytes);
                break;
            }
            case PROGRESS: {
                Packet.Progress pr = (Packet.Progress) p;
                out.writeInt((int) (pr.routeId & 0xffffffffL));
                out.writeShort(pr.turnIndex & 0xffff);
                out.writeShort(pr.distanceToTurnM & 0xffff);
                out.writeShort(pr.bearingDelta100);
                out.writeShort(pr.speedKmh & 0xffff);
                out.writeShort(pr.remainingDistanceM & 0xffff);
                out.writeShort(pr.etaSec & 0xffff);
                break;
            }
            case DISPLAY_CONFIG: {
                Packet.DisplayConfig d = (Packet.DisplayConfig) p;
                out.writeByte(d.topSlot.ordinal() & 0xff);
                out.writeByte(d.bottomSlot.ordinal() & 0xff);
                break;
            }
            case ROUTE_END: {
                Packet.RouteEnd r = (Packet.RouteEnd) p;
                out.writeInt((int) (r.routeId & 0xffffffffL));
                out.writeByte(r.reason.ordinal() & 0xff);
                break;
            }
            case PING: {
                Packet.Ping ping = (Packet.Ping) p;
                out.writeInt((int) (ping.timestampMs & 0xffffffffL));
                break;
            }
            case PONG: {
                Packet.Pong pong = (Packet.Pong) p;
                out.writeInt((int) (pong.echoTimestampMs & 0xffffffffL));
                break;
            }
            default:
                throw new IllegalArgumentException("unhandled packet type " + p.type());
        }
    }

    private static Packet readPayload(DataInputStream in, PacketType type) throws IOException {
        switch (type) {
            case HELLO:
                return new Packet.Hello(in.readUnsignedShort());
            case ROUTE_START: {
                long routeId = in.readInt() & 0xffffffffL;
                int totalTurns = in.readUnsignedShort();
                String label = readPstr(in);
                return new Packet.RouteStart(routeId, totalTurns, label);
            }
            case TURN_BUNDLE: {
                long routeId = in.readInt() & 0xffffffffL;
                int turnIndex = in.readUnsignedShort();
                int kindOrd = in.readUnsignedByte();
                int distFromStart = in.readUnsignedShort();
                String text = readPstr(in);
                int pngLen = in.readInt();
                if (pngLen < 0 || pngLen > MAX_PAYLOAD_LEN) {
                    throw new ProtocolException("invalid pngLen " + pngLen);
                }
                byte[] png = new byte[pngLen];
                in.readFully(png);
                return new Packet.TurnBundle(routeId, turnIndex, TurnKind.fromOrdinal(kindOrd),
                    distFromStart, text, png);
            }
            case PROGRESS: {
                long routeId = in.readInt() & 0xffffffffL;
                int turnIndex = in.readUnsignedShort();
                int distToTurn = in.readUnsignedShort();
                short bearing100 = in.readShort();
                int speedKmh = in.readUnsignedShort();
                int remainingM = in.readUnsignedShort();
                int etaSec = in.readUnsignedShort();
                return new Packet.Progress(routeId, turnIndex, distToTurn, bearing100, speedKmh,
                    remainingM, etaSec);
            }
            case DISPLAY_CONFIG: {
                int topOrd = in.readUnsignedByte();
                int bottomOrd = in.readUnsignedByte();
                return new Packet.DisplayConfig(
                    Packet.DisplayConfig.Field.fromCode(topOrd),
                    Packet.DisplayConfig.Field.fromCode(bottomOrd));
            }
            case ROUTE_END: {
                long routeId = in.readInt() & 0xffffffffL;
                int reasonOrd = in.readUnsignedByte();
                return new Packet.RouteEnd(routeId, Packet.RouteEnd.Reason.fromCode(reasonOrd));
            }
            case PING:
                return new Packet.Ping(in.readInt() & 0xffffffffL);
            case PONG:
                return new Packet.Pong(in.readInt() & 0xffffffffL);
            default:
                throw new ProtocolException("unhandled packet type " + type);
        }
    }

    private static void writePstr(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xffff) {
            throw new IllegalArgumentException("pstr too long: " + bytes.length);
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readPstr(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
