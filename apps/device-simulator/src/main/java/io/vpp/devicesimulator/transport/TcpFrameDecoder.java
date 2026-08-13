package io.vpp.devicesimulator.transport;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

public class TcpFrameDecoder extends MessageToMessageDecoder<ByteBuf> {
    private static final int MAGIC = 0x56505031;

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        int magic = input.readInt();
        int version = input.readUnsignedByte();
        int type = input.readUnsignedByte();
        int flags = input.readUnsignedShort();
        int payloadLength = input.readInt();
        long sequence = input.readLong();
        long sentAt = input.readLong();
        if (magic != MAGIC || version != 1 || flags != 0 || payloadLength != input.readableBytes()) {
            throw new IllegalArgumentException("Invalid VPP1 TCP frame header");
        }
        byte[] payload = new byte[payloadLength];
        input.readBytes(payload);
        output.add(new TcpFrame(type, sequence, sentAt, payload));
    }
}
