package io.vpp.devicesimulator.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

class TcpFrameCodecTest {

    @Test
    void frameRoundTripsThroughExecutableVpp1Codec() {
        EmbeddedChannel encoder = new EmbeddedChannel(new TcpFrameEncoder());
        TcpFrame expected = new TcpFrame(TcpFrame.TELEMETRY, 42, 1_786_512_000_000L,
                "{\"schema_version\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(encoder.writeOutbound(expected)).isTrue();
        ByteBuf encoded = encoder.readOutbound();

        EmbeddedChannel decoder = new EmbeddedChannel(
                new LengthFieldBasedFrameDecoder(65_564, 8, 4, 16, 0),
                new TcpFrameDecoder());
        assertThat(decoder.writeInbound(encoded)).isTrue();
        TcpFrame actual = decoder.readInbound();

        assertThat(actual.messageType()).isEqualTo(expected.messageType());
        assertThat(actual.sequence()).isEqualTo(expected.sequence());
        assertThat(actual.sentAtEpochMs()).isEqualTo(expected.sentAtEpochMs());
        assertThat(actual.payload()).isEqualTo(expected.payload());
        encoder.finishAndReleaseAll();
        decoder.finishAndReleaseAll();
    }
}
