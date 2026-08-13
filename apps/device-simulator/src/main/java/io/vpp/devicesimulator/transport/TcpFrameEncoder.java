package io.vpp.devicesimulator.transport;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

public class TcpFrameEncoder extends MessageToMessageEncoder<TcpFrame> {
    private static final int MAGIC = 0x56505031;

    @Override
    protected void encode(ChannelHandlerContext context, TcpFrame frame, List<Object> output) {
        byte[] payload = frame.payload();
        ByteBuf buffer = context.alloc().buffer(28 + payload.length);
        buffer.writeInt(MAGIC);
        buffer.writeByte(1);
        buffer.writeByte(frame.messageType());
        buffer.writeShort(0);
        buffer.writeInt(payload.length);
        buffer.writeLong(frame.sequence());
        buffer.writeLong(frame.sentAtEpochMs());
        buffer.writeBytes(payload);
        output.add(buffer);
    }
}
