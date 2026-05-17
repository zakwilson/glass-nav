package dev.glass.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrameReaderTest {

    @Test void readsMultipleFramesInSequence() throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        Packet[] packets = {
            new Packet.Hello(1),
            new Packet.RouteStart(7L, 5, "Berlin"),
            new Packet.Progress(7L, 0, 200, (short) 0, 25, 0, 0),
            new Packet.RouteEnd(7L, Packet.RouteEnd.Reason.ARRIVED),
        };
        for (Packet p : packets) stream.write(Codec.encode(p));

        FrameReader reader = new FrameReader(new ByteArrayInputStream(stream.toByteArray()));
        for (Packet expected : packets) {
            assertThat(reader.readNext()).isEqualTo(expected);
        }
        assertThat(reader.readNext()).isNull(); // clean EOF
    }

    @Test void readsFrameSplitAcrossManyReads() throws IOException {
        Packet.TurnBundle p = new Packet.TurnBundle(
            1L, 0, TurnKind.TR, 500, "Right",
            randomBytes(50_000, 7));
        byte[] full = Codec.encode(p);

        // Wrap in an InputStream that hands out 1 byte at a time, exercising readFully's loop.
        FrameReader reader = new FrameReader(new SlowInputStream(full, 1));
        assertThat(reader.readNext()).isEqualTo(p);
    }

    @Test void readsFrameSplitAcrossOddChunks() throws IOException {
        Packet.TurnBundle p = new Packet.TurnBundle(
            1L, 0, TurnKind.TL, 1000, "Left",
            randomBytes(80_000, 11));
        byte[] full = Codec.encode(p);
        FrameReader reader = new FrameReader(new SlowInputStream(full, 7919)); // prime chunk
        assertThat(reader.readNext()).isEqualTo(p);
    }

    @Test void rejectsCorruptedMagicMidStream() throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(Codec.encode(new Packet.Ping(1L)));
        // Inject a bad byte that should be the next magic.
        stream.write(new byte[]{ 0x00, 0x01, 0x02, 0x03 });

        FrameReader reader = new FrameReader(new ByteArrayInputStream(stream.toByteArray()));
        assertThat(reader.readNext()).isInstanceOf(Packet.Ping.class);
        assertThatThrownBy(reader::readNext)
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("bad magic");
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static final class SlowInputStream extends InputStream {
        private final byte[] data;
        private final int chunkSize;
        private int pos;

        SlowInputStream(byte[] data, int chunkSize) {
            this.data = data;
            this.chunkSize = Math.max(1, chunkSize);
        }

        @Override public int read() {
            if (pos >= data.length) return -1;
            return data[pos++] & 0xff;
        }

        @Override public int read(byte[] b, int off, int len) {
            if (pos >= data.length) return -1;
            int n = Math.min(Math.min(len, chunkSize), data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override public int available() { return Math.max(0, data.length - pos); }
    }
}
