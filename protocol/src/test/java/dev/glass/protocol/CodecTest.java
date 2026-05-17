package dev.glass.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
// Tests in this class are written to allow checked-exception throw signatures.

class CodecTest {

    @Test void roundTripHello() throws Exception {
        Packet.Hello in = new Packet.Hello(0x0001);
        Packet out = Codec.decode(Codec.encode(in));
        assertThat(out).isEqualTo(in);
    }

    @Test void roundTripRouteStart() throws Exception {
        Packet.RouteStart in = new Packet.RouteStart(0xCAFEBABEL, 42, "Brandenburger Tor");
        assertThat(Codec.decode(Codec.encode(in))).isEqualTo(in);
    }

    @Test void roundTripRouteStartWithUtf8() throws Exception {
        Packet.RouteStart in = new Packet.RouteStart(1L, 7, "Café Müller — straße");
        assertThat(Codec.decode(Codec.encode(in))).isEqualTo(in);
    }

    @ParameterizedTest
    @MethodSource("turnBundleSizes")
    void roundTripTurnBundle(int pngSize) throws Exception {
        byte[] png = randomBytes(pngSize, 0xC0FFEEL ^ pngSize);
        Packet.TurnBundle in = new Packet.TurnBundle(
            0xDEADBEEFL, 5, TurnKind.TSLR, 1234, "Slight right onto Prenzlauer Allee", png);
        Packet.TurnBundle out = (Packet.TurnBundle) Codec.decode(Codec.encode(in));
        assertThat(out).isEqualTo(in);
        assertThat(out.pngBytes).isEqualTo(png); // explicit array compare
    }

    static Stream<Integer> turnBundleSizes() {
        return Stream.of(0, 1, 100, 30_000, 60_000, 120_000, 200_000);
    }

    @Test void roundTripProgress() throws Exception {
        Packet.Progress in = new Packet.Progress(7L, 3, 142, (short) -2750, 28, 4321, 900);
        assertThat(Codec.decode(Codec.encode(in))).isEqualTo(in);
    }

    @Test void roundTripDisplayConfig() throws Exception {
        for (Packet.DisplayConfig.Field top : Packet.DisplayConfig.Field.values()) {
            for (Packet.DisplayConfig.Field bot : Packet.DisplayConfig.Field.values()) {
                Packet.DisplayConfig in = new Packet.DisplayConfig(top, bot);
                assertThat(Codec.decode(Codec.encode(in))).isEqualTo(in);
            }
        }
    }

    @Test void roundTripRouteEnd() throws Exception {
        for (Packet.RouteEnd.Reason r : Packet.RouteEnd.Reason.values()) {
            Packet.RouteEnd in = new Packet.RouteEnd(99L, r);
            assertThat(Codec.decode(Codec.encode(in))).isEqualTo(in);
        }
    }

    @Test void roundTripPingPong() throws Exception {
        Packet.Ping ping = new Packet.Ping(0xFFFFFFFFL);
        Packet.Pong pong = new Packet.Pong(123_456L);
        assertThat(Codec.decode(Codec.encode(ping))).isEqualTo(ping);
        assertThat(Codec.decode(Codec.encode(pong))).isEqualTo(pong);
    }

    @Test void rejectsBadMagic() {
        byte[] frame = Codec.encode(new Packet.Ping(1L));
        frame[0] = 0x00;
        assertThatThrownBy(() -> Codec.decode(frame))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("bad magic");
    }

    @Test void rejectsUnsupportedVersion() {
        byte[] frame = Codec.encode(new Packet.Ping(1L));
        frame[1] = 0x02;
        assertThatThrownBy(() -> Codec.decode(frame))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("protoVersion");
    }

    @Test void rejectsUnknownType() {
        byte[] frame = Codec.encode(new Packet.Ping(1L));
        frame[2] = 0x55;
        assertThatThrownBy(() -> Codec.decode(frame))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("unknown packet type");
    }

    @Test void rejectsTruncatedFrame() {
        byte[] frame = Codec.encode(new Packet.Ping(1L));
        byte[] truncated = new byte[frame.length - 2];
        System.arraycopy(frame, 0, truncated, 0, truncated.length);
        assertThatThrownBy(() -> Codec.decode(truncated))
            .isInstanceOf(ProtocolException.class);
    }

    @Test void packetTypeFromCodeRoundTrip() throws ProtocolException {
        for (PacketType t : PacketType.values()) {
            assertThat(PacketType.fromCode(t.code)).isEqualTo(t);
        }
    }

    @Test void turnKindFromOrdinalRoundTrip() throws ProtocolException {
        for (TurnKind k : TurnKind.values()) {
            assertThat(TurnKind.fromOrdinal(k.ordinal())).isEqualTo(k);
        }
        assertThatThrownBy(() -> TurnKind.fromOrdinal(99)).isInstanceOf(ProtocolException.class);
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }
}
