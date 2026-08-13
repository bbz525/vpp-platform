package io.vpp.iotgateway.tcp;

import java.io.File;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.vpp.iotgateway.config.GatewayProperties;

@Component
public class TcpGatewayServer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TcpGatewayServer.class);
    private final GatewayProperties properties;
    private final TcpDeviceHandler deviceHandler;
    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile Channel serverChannel;

    public TcpGatewayServer(GatewayProperties properties, TcpDeviceHandler deviceHandler) {
        this.properties = properties;
        this.deviceHandler = deviceHandler;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        if (!properties.tcp().enabled()) {
            log.info("Gateway TCP ingestion is disabled");
            return;
        }
        SslContext sslContext = createSslContext();
        bossGroup = new MultiThreadIoEventLoopGroup(1,
                new DefaultThreadFactory("gateway-tcp-boss"), NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(0,
                new DefaultThreadFactory("gateway-tcp-worker"), NioIoHandler.newFactory());
        int maxFrameLength = 28 + properties.ingress().maxPayloadBytes();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        if (sslContext != null) {
                            channel.pipeline().addLast(sslContext.newHandler(channel.alloc()));
                        }
                        channel.pipeline().addLast(
                                new IdleStateHandler(0, 0,
                                        Math.toIntExact(properties.tcp().idleTimeout().toSeconds())),
                                new LengthFieldBasedFrameDecoder(maxFrameLength, 8, 4, 16, 0),
                                new VppFrameDecoder(),
                                new VppFrameEncoder(),
                                deviceHandler);
                    }
                });
        serverChannel = bootstrap.bind(properties.tcp().bindHost(), properties.tcp().port())
                .sync().channel();
        log.info("Gateway TCP ingestion listening on {}:{} tls={}",
                properties.tcp().bindHost(), properties.tcp().port(), properties.tcp().tls());
    }

    public boolean isRunning() {
        Channel channel = serverChannel;
        return channel != null && channel.isActive();
    }

    @PreDestroy
    public void close() {
        Channel channel = serverChannel;
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }
    }

    private SslContext createSslContext() throws Exception {
        if (!properties.tcp().tls()) {
            return null;
        }
        return SslContextBuilder.forServer(new File(properties.tcp().certificateChain()),
                new File(properties.tcp().privateKey())).build();
    }
}
