package io.vpp.iotgateway.tcp;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import io.vpp.iotgateway.identity.DeviceAuthorizer;
import io.vpp.iotgateway.identity.DeviceIdentity;
import io.vpp.iotgateway.ingress.DeviceIngress;
import io.vpp.iotgateway.ingress.IngressMessageType;
import io.vpp.iotgateway.ingress.IngressProtocol;
import io.vpp.iotgateway.session.DeviceSessionRegistry;

@ChannelHandler.Sharable
@Component
public class TcpDeviceHandler extends SimpleChannelInboundHandler<VppFrame> {
    private static final Logger log = LoggerFactory.getLogger(TcpDeviceHandler.class);
    private static final AttributeKey<DeviceIdentity> IDENTITY =
            AttributeKey.valueOf("vpp-device-identity");
    private static final AttributeKey<Long> LAST_SEQUENCE =
            AttributeKey.valueOf("vpp-last-sequence");
    private final TcpAuthenticationParser authenticationParser;
    private final DeviceAuthorizer authorizer;
    private final DeviceIngress ingressService;
    private final DeviceSessionRegistry sessions;
    private final Counter authenticated;
    private final Counter rejected;

    public TcpDeviceHandler(
            TcpAuthenticationParser authenticationParser,
            DeviceAuthorizer authorizer,
            DeviceIngress ingressService,
            DeviceSessionRegistry sessions,
            MeterRegistry registry) {
        this.authenticationParser = authenticationParser;
        this.authorizer = authorizer;
        this.ingressService = ingressService;
        this.sessions = sessions;
        this.authenticated = registry.counter("vpp.gateway.tcp.authenticated");
        this.rejected = registry.counter("vpp.gateway.tcp.rejected");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, VppFrame frame) {
        DeviceIdentity identity = context.channel().attr(IDENTITY).get();
        if (identity == null) {
            authenticate(context, frame);
            return;
        }
        IngressMessageType type = messageType(frame.messageType());
        if (type == null || !sequenceAccepted(context, frame.sequence())) {
            rejectAndClose(context, "INVALID_BUSINESS_FRAME");
            return;
        }
        boolean accepted = ingressService.accept(identity, IngressProtocol.TCP, type,
                frame.payload(), ignored -> context.channel().eventLoop().execute(context::close));
        if (!accepted) {
            context.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        DeviceIdentity identity = context.channel().attr(IDENTITY).get();
        if (identity != null) {
            sessions.unregisterTcp(identity, context.channel());
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event instanceof IdleStateEvent) {
            context.close();
        } else {
            context.fireUserEventTriggered(event);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        rejected.increment();
        log.warn("Closing invalid TCP device connection reason={}",
                cause.getClass().getSimpleName());
        context.close();
    }

    private void authenticate(ChannelHandlerContext context, VppFrame frame) {
        if (frame.messageType() != VppFrame.AUTH || frame.sequence() != 0) {
            rejectAndClose(context, "AUTH_REQUIRED");
            return;
        }
        try {
            AuthenticationAttempt attempt = authenticationParser.parse(frame.payload());
            DeviceIdentity identity = authorizer.authenticate(attempt.identity(), attempt.credential())
                    .orElse(null);
            if (identity == null) {
                rejectAndClose(context, "AUTH_FAILED");
                return;
            }
            context.channel().attr(IDENTITY).set(identity);
            context.channel().attr(LAST_SEQUENCE).set(0L);
            sessions.registerTcp(identity, context.channel());
            authenticated.increment();
            context.writeAndFlush(new VppFrame(VppFrame.AUTH_OK, 0,
                    Instant.now().toEpochMilli(), new byte[0]));
        } catch (RuntimeException exception) {
            rejectAndClose(context, "AUTH_PAYLOAD_INVALID");
        }
    }

    private static IngressMessageType messageType(int type) {
        return switch (type) {
            case VppFrame.TELEMETRY -> IngressMessageType.TELEMETRY;
            case VppFrame.HEARTBEAT -> IngressMessageType.HEARTBEAT;
            case VppFrame.COMMAND_ACK -> IngressMessageType.COMMAND_ACK;
            default -> null;
        };
    }

    private static boolean sequenceAccepted(ChannelHandlerContext context, long sequence) {
        Long previous = context.channel().attr(LAST_SEQUENCE).get();
        if (sequence <= 0 || previous != null && sequence <= previous) {
            return false;
        }
        context.channel().attr(LAST_SEQUENCE).set(sequence);
        return true;
    }

    private void rejectAndClose(ChannelHandlerContext context, String reason) {
        rejected.increment();
        log.warn("Rejected TCP device connection reason={}", reason);
        context.close();
    }
}
