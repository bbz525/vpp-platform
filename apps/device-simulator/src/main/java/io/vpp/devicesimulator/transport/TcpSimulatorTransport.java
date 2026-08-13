package io.vpp.devicesimulator.transport;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.net.ssl.SSLException;

import org.springframework.stereotype.Component;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.DefaultThreadFactory;

import io.vpp.devicesimulator.config.SimulatorProperties;
import io.vpp.devicesimulator.protocol.CommandAck;
import io.vpp.devicesimulator.protocol.HeartbeatPayload;
import io.vpp.devicesimulator.protocol.TelemetryPayload;
import io.vpp.devicesimulator.simulation.DeviceProfile;
import tools.jackson.databind.ObjectMapper;

@Component
public class TcpSimulatorTransport implements SimulatorTransport {
    private static final int MAX_FRAME_LENGTH = 65_564;
    private final SimulatorProperties properties;
    private final ObjectMapper objectMapper;
    private final EventLoopGroup eventLoopGroup = new MultiThreadIoEventLoopGroup(
            0, new DefaultThreadFactory("simulator-tcp"), NioIoHandler.newFactory());
    private final Map<String, DeviceProfile> devices = new ConcurrentHashMap<>();
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> outboundSequences = new ConcurrentHashMap<>();
    private volatile Consumer<InboundCommand> commandConsumer;
    private volatile SslContext sslContext;

    public TcpSimulatorTransport(SimulatorProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "tcp";
    }

    @Override
    public void start(Collection<DeviceProfile> fleet, Consumer<InboundCommand> commandConsumer) {
        if (properties.tcp().credential() == null || properties.tcp().credential().isBlank()) {
            throw new TransportException("SIMULATOR_TCP_CREDENTIAL is required when TCP is enabled");
        }
        this.commandConsumer = commandConsumer;
        this.sslContext = createSslContext();
        fleet.forEach(device -> {
            devices.put(device.deviceId(), device);
            connect(device);
        });
    }

    @Override
    public void publishTelemetry(DeviceProfile device, TelemetryPayload payload) {
        send(device, TcpFrame.TELEMETRY, payload.sequence(), payload.deviceTime(), payload);
    }

    @Override
    public void publishHeartbeat(DeviceProfile device, HeartbeatPayload payload) {
        send(device, TcpFrame.HEARTBEAT, payload.sequence(), payload.deviceTime(), payload);
    }

    @Override
    public void publishCommandAck(DeviceProfile device, CommandAck payload) {
        send(device, TcpFrame.COMMAND_ACK, 0, payload.deviceTime(), payload);
    }

    @Override
    public void disconnect(DeviceProfile device) {
        Channel channel = channels.remove(device.deviceId());
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
    }

    @Override
    public void close() {
        channels.values().forEach(channel -> channel.close().syncUninterruptibly());
        channels.clear();
        eventLoopGroup.shutdownGracefully().syncUninterruptibly();
    }

    private void send(
            DeviceProfile device, int messageType, long sequence, Instant deviceTime, Object payload) {
        try {
            Channel channel = activeChannel(device);
            long outboundSequence = outboundSequences
                    .computeIfAbsent(device.deviceId(), ignored -> new AtomicLong())
                    .updateAndGet(current -> Math.max(current + 1, sequence));
            channel.writeAndFlush(new TcpFrame(messageType, outboundSequence, deviceTime.toEpochMilli(),
                    objectMapper.writeValueAsBytes(payload))).syncUninterruptibly();
        } catch (Exception exception) {
            throw new TransportException("TCP publish failed for " + device.deviceId(), exception);
        }
    }

    private Channel activeChannel(DeviceProfile device) {
        Channel channel = channels.get(device.deviceId());
        if (channel == null || !channel.isActive()) {
            return connect(device);
        }
        return channel;
    }

    private synchronized Channel connect(DeviceProfile device) {
        Channel existing = channels.get(device.deviceId());
        if (existing != null && existing.isActive()) {
            return existing;
        }
        CompletableFuture<Void> authenticated = new CompletableFuture<>();
        Bootstrap bootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(properties.tcp().connectTimeout().toMillis()))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        if (sslContext != null) {
                            channel.pipeline().addLast(sslContext.newHandler(channel.alloc(),
                                    properties.tcp().host(), properties.tcp().port()));
                        }
                        channel.pipeline().addLast(
                                new LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 8, 4, 16, 0),
                                new TcpFrameDecoder(),
                                new TcpFrameEncoder(),
                                new SimpleChannelInboundHandler<TcpFrame>() {
                                    @Override
                                    protected void channelRead0(
                                            io.netty.channel.ChannelHandlerContext context,
                                            TcpFrame frame) {
                                        if (frame.messageType() == TcpFrame.COMMAND) {
                                            commandConsumer.accept(
                                                    new InboundCommand(device.deviceId(), frame.payload()));
                                        } else if (frame.messageType() == TcpFrame.AUTH_OK) {
                                            authenticated.complete(null);
                                        }
                                    }

                                    @Override
                                    public void channelInactive(
                                            io.netty.channel.ChannelHandlerContext context) {
                                        authenticated.completeExceptionally(
                                                new TransportException("TCP connection closed before AUTH_OK"));
                                    }
                                });
                    }
                });
        Channel channel = bootstrap.connect(properties.tcp().host(), properties.tcp().port())
                .syncUninterruptibly().channel();
        channels.put(device.deviceId(), channel);
        authenticate(channel, device, authenticated);
        return channel;
    }

    private void authenticate(
            Channel channel, DeviceProfile device, CompletableFuture<Void> authenticated) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(Map.of(
                    "device_id", device.deviceId(),
                    "tenant_id", device.tenantId().toString(),
                    "credential", properties.tcp().credential(),
                    "client_nonce", UUID.randomUUID().toString()));
            channel.writeAndFlush(new TcpFrame(TcpFrame.AUTH, 0, Instant.now().toEpochMilli(), payload))
                    .syncUninterruptibly();
            authenticated.get(properties.tcp().connectTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            channel.close();
            channels.remove(device.deviceId(), channel);
            throw new TransportException("TCP authentication frame failed for " + device.deviceId(),
                    exception);
        }
    }

    private SslContext createSslContext() {
        if (!properties.tcp().tls()) {
            return null;
        }
        try {
            SslContextBuilder builder = SslContextBuilder.forClient();
            if (properties.tcp().insecureTrustAll()) {
                builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            }
            return builder.build();
        } catch (SSLException exception) {
            throw new TransportException("Unable to initialize simulator TCP TLS", exception);
        }
    }
}
