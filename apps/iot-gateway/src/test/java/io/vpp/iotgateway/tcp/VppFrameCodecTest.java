package io.vpp.iotgateway.tcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.TooLongFrameException;

class VppFrameCodecTest {

    @Test
    void roundTripsVpp1Frame() {
        EmbeddedChannel encoder = new EmbeddedChannel(new VppFrameEncoder());
        var expected = new VppFrame(VppFrame.TELEMETRY, 42, 1_786_512_000_000L,
                "{\"schema_version\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(encoder.writeOutbound(expected)).isTrue();
        ByteBuf encoded = encoder.readOutbound();

        EmbeddedChannel decoder = new EmbeddedChannel(
                new LengthFieldBasedFrameDecoder(65_564, 8, 4, 16, 0),
                new VppFrameDecoder());
        assertThat(decoder.writeInbound(encoded)).isTrue();
        VppFrame actual = decoder.readInbound();

        assertThat(actual.messageType()).isEqualTo(expected.messageType());
        assertThat(actual.sequence()).isEqualTo(expected.sequence());
        assertThat(actual.payload()).isEqualTo(expected.payload());
        encoder.finishAndReleaseAll();
        decoder.finishAndReleaseAll();
    }

    @Test
    void decodesFragmentedFrame() {
        ByteBuf encoded = encode(new VppFrame(VppFrame.HEARTBEAT, 7, 0,
                "{}".getBytes(StandardCharsets.UTF_8)));
        ByteBuf firstFragment = encoded.readRetainedSlice(10);
        ByteBuf secondFragment = encoded.readRetainedSlice(encoded.readableBytes());
        encoded.release();
        EmbeddedChannel decoder = frameDecoder();

        assertThat(decoder.writeInbound(firstFragment)).isFalse();
        assertThat(decoder.writeInbound(secondFragment)).isTrue();
        VppFrame actual = decoder.readInbound();
        assertThat(actual.sequence()).isEqualTo(7);
        decoder.finishAndReleaseAll();
    }

    @Test
    void decodesTwoFramesFromOneBuffer() {
        ByteBuf first = encode(new VppFrame(VppFrame.HEARTBEAT, 8, 0, new byte[0]));
        ByteBuf second = encode(new VppFrame(VppFrame.TELEMETRY, 9, 0,
                "{}".getBytes(StandardCharsets.UTF_8)));
        ByteBuf combined = Unpooled.buffer(first.readableBytes() + second.readableBytes())
                .writeBytes(first).writeBytes(second);
        first.release();
        second.release();
        EmbeddedChannel decoder = frameDecoder();

        assertThat(decoder.writeInbound(combined)).isTrue();
        VppFrame firstActual = decoder.readInbound();
        VppFrame secondActual = decoder.readInbound();
        assertThat(firstActual.sequence()).isEqualTo(8);
        assertThat(secondActual.sequence()).isEqualTo(9);
        decoder.finishAndReleaseAll();
    }

    @Test
    void rejectsOversizedFrameFromHeaderBeforePayloadAllocation() {
        EmbeddedChannel decoder = new EmbeddedChannel(
                new LengthFieldBasedFrameDecoder(65_564, 8, 4, 16, 0));
        ByteBuf header = Unpooled.buffer(28)
                .writeInt(0x56505031).writeByte(1).writeByte(VppFrame.TELEMETRY)
                .writeShort(0).writeInt(65_537).writeLong(1).writeLong(0);

        assertThatThrownBy(() -> decoder.writeInbound(header))
                .isInstanceOf(TooLongFrameException.class);
        decoder.finishAndReleaseAll();
    }

    @Test
    void rejectsMalformedHeaderVariants() {
        assertMalformedHeader(0x00000000, 1, 0, 0);
        assertMalformedHeader(0x56505031, 2, 0, 0);
        assertMalformedHeader(0x56505031, 1, 1, 0);
        assertMalformedHeader(0x56505031, 1, 0, 1);
    }

    private static void assertMalformedHeader(int magic, int version, int flags,
            int declaredPayloadLength) {
        EmbeddedChannel decoder = new EmbeddedChannel(new VppFrameDecoder());
        ByteBuf frame = Unpooled.buffer(28)
                .writeInt(magic).writeByte(version).writeByte(VppFrame.TELEMETRY)
                .writeShort(flags).writeInt(declaredPayloadLength).writeLong(1).writeLong(0);

        assertThatThrownBy(() -> decoder.writeInbound(frame))
                .isInstanceOf(CorruptedFrameException.class);
        decoder.finishAndReleaseAll();
    }

    private static ByteBuf encode(VppFrame frame) {
        EmbeddedChannel encoder = new EmbeddedChannel(new VppFrameEncoder());
        assertThat(encoder.writeOutbound(frame)).isTrue();
        ByteBuf encoded = encoder.readOutbound();
        encoder.finishAndReleaseAll();
        return encoded;
    }

    private static EmbeddedChannel frameDecoder() {
        return new EmbeddedChannel(new LengthFieldBasedFrameDecoder(65_564, 8, 4, 16, 0),
                new VppFrameDecoder());
    }
}
