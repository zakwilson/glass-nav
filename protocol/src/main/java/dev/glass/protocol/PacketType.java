package dev.glass.protocol;

public enum PacketType {
    HELLO((byte) 0x10),
    ROUTE_START((byte) 0x01),
    TURN_BUNDLE((byte) 0x02),
    PROGRESS((byte) 0x03),
    ROUTE_END((byte) 0x04),
    DISPLAY_CONFIG((byte) 0x05),
    PING((byte) 0x7E),
    PONG((byte) 0x7F);

    public final byte code;

    PacketType(byte code) {
        this.code = code;
    }

    public static PacketType fromCode(byte code) throws ProtocolException {
        for (PacketType t : values()) {
            if (t.code == code) return t;
        }
        throw new ProtocolException(String.format("unknown packet type 0x%02x", code & 0xff));
    }
}
