package io.vpp.iotgateway.session;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import io.netty.channel.Channel;
import io.vpp.iotgateway.identity.DeviceIdentity;
import io.vpp.iotgateway.ingress.IngressProtocol;

@Component
public class DeviceSessionRegistry {
    private final Map<DeviceIdentity, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();

    public void seen(DeviceIdentity identity, IngressProtocol protocol) {
        sessions.compute(identity, (key, current) -> {
            if (protocol == IngressProtocol.MQTT && current != null
                    && current.protocol() == IngressProtocol.TCP
                    && current.channel() != null && current.channel().isActive()) {
                return current;
            }
            Channel channel = current == null ? null : current.channel();
            if (protocol == IngressProtocol.MQTT) {
                channel = null;
            }
            return new Session(protocol, clock.instant(), channel);
        });
    }

    public void registerTcp(DeviceIdentity identity, Channel channel) {
        Session previous = sessions.put(identity,
                new Session(IngressProtocol.TCP, clock.instant(), channel));
        if (previous != null && previous.channel() != null && previous.channel() != channel) {
            previous.channel().close();
        }
    }

    public void unregisterTcp(DeviceIdentity identity, Channel channel) {
        sessions.computeIfPresent(identity, (key, current) ->
                current.channel() == channel ? null : current);
    }

    public Optional<Channel> activeTcpChannel(DeviceIdentity identity) {
        Session session = sessions.get(identity);
        if (session == null || session.protocol() != IngressProtocol.TCP
                || session.channel() == null || !session.channel().isActive()) {
            return Optional.empty();
        }
        return Optional.of(session.channel());
    }

    public int activeSessions() {
        return sessions.size();
    }

    public long tcpSessions() {
        return sessions.values().stream().filter(session -> session.protocol() == IngressProtocol.TCP)
                .count();
    }

    public long mqttSessions() {
        return sessions.values().stream().filter(session -> session.protocol() == IngressProtocol.MQTT)
                .count();
    }

    public record Session(IngressProtocol protocol, Instant lastSeen, Channel channel) {
    }
}
