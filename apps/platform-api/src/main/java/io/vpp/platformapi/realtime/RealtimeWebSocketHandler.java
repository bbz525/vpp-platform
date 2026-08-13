package io.vpp.platformapi.realtime;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.vpp.platformapi.config.RealtimeProperties;
import io.vpp.platformapi.realtime.RealtimeEventStore.EventWindow;
import io.vpp.platformapi.realtime.RealtimeEventStore.RealtimeEvent;
import io.vpp.platformapi.resource.ResourceRepository;
import io.vpp.platformapi.security.ActorPrincipal;

@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {
    private final RealtimeProperties properties;
    private final RealtimeEventStore events;
    private final ResourceRepository resources;
    private final ObjectMapper mapper;
    private final Map<String, ClientState> clients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("realtime-ws-poller").daemon(true).factory());

    public RealtimeWebSocketHandler(RealtimeProperties properties, RealtimeEventStore events,
            ResourceRepository resources, ObjectMapper mapper) {
        this.properties = properties;
        this.events = events;
        this.resources = resources;
        this.mapper = mapper;
        scheduler.scheduleAtFixedRate(this::pollSafely, properties.pollInterval().toMillis(),
                properties.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        ActorPrincipal actor = (ActorPrincipal) session.getAttributes()
                .get(RealtimeHandshakeInterceptor.ACTOR_ATTRIBUTE);
        var bounded = new ConcurrentWebSocketSessionDecorator(session,
                Math.toIntExact(properties.sendTimeLimit().toMillis()), properties.sendBufferBytes());
        ClientState state = new ClientState(bounded, actor, new ConcurrentHashMap<>(), Instant.now());
        clients.put(session.getId(), state);
        sendControl(state, "connected", null, null, "WebSocket connected");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientState state = clients.get(session.getId());
        if (state == null) return;
        try {
            JsonNode request = mapper.readTree(message.getPayload());
            if (!"subscribe".equals(text(request, "type")) || request.get("channels") == null
                    || !request.get("channels").isArray()) {
                sendControl(state, "error", text(request, "request_id"),
                        "INVALID_SUBSCRIPTION", "subscription request is invalid");
                return;
            }
            if (request.get("channels").isEmpty() || request.get("channels").size() > 100) {
                sendControl(state, "error", text(request, "request_id"),
                        "INVALID_SUBSCRIPTION", "channels must contain between 1 and 100 entries");
                return;
            }
            Long resume = parseCursor(request.get("resume_after"));
            boolean subscribed = true;
            for (JsonNode channelNode : request.get("channels")) {
                subscribed &= subscribe(state, channelNode.asString(), resume, text(request, "request_id"));
            }
            if (subscribed) {
                sendControl(state, "subscribed", text(request, "request_id"), null,
                        "authorized channels subscribed");
            }
        } catch (RuntimeException exception) {
            sendControl(state, "error", null, "INVALID_SUBSCRIPTION",
                    "subscription request is invalid");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clients.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        clients.remove(session.getId());
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }

    private boolean subscribe(ClientState client, String channel, Long resume, String requestId) {
        UUID portfolio = portfolioId(channel);
        boolean alarms = "tenant:alarms".equals(channel);
        if ((!alarms && portfolio == null)
                || (portfolio != null && resources.findPortfolio(client.actor().tenantId(), portfolio).isEmpty())) {
            sendControl(client, "error", requestId, "FORBIDDEN_CHANNEL",
                    "channel is unavailable or not authorized");
            return false;
        }
        EventWindow window = alarms ? events.alarmWindow(client.actor().tenantId())
                : events.window(client.actor().tenantId(), portfolio);
        long start = resume == null ? window.globalCursor() : resume;
        if (resume != null && (resume > window.globalCursor()
                || (!window.events().isEmpty() && resume < window.events().getFirst().numericCursor()))) {
            sendControl(client, "resync_required", requestId, "CURSOR_EXPIRED",
                    "resume cursor is outside the retained window");
            return false;
        }
        List<RealtimeEvent> replay = window.events().stream()
                .filter(event -> event.numericCursor() > start).toList();
        if (replay.size() > properties.maxReplayEvents()) {
            sendControl(client, "resync_required", requestId, "REPLAY_LIMIT_EXCEEDED",
                    "too many retained events; request a REST snapshot");
            return false;
        }
        long latest = start;
        for (RealtimeEvent event : replay) {
            sendEvent(client, channel, event);
            latest = event.numericCursor();
        }
        client.channels().put(channel, latest);
        return true;
    }

    private void pollSafely() {
        for (ClientState client : clients.values()) {
            try {
                poll(client);
            } catch (RuntimeException exception) {
                sendControl(client, "error", null, "REALTIME_UNAVAILABLE",
                        "realtime updates are temporarily unavailable");
            }
        }
    }

    private void poll(ClientState client) {
        if (!client.session().isOpen()) return;
        for (Map.Entry<String, Long> subscription : client.channels().entrySet()) {
            UUID portfolio = portfolioId(subscription.getKey());
            boolean alarms = "tenant:alarms".equals(subscription.getKey());
            if (portfolio == null && !alarms) continue;
            EventWindow window = alarms ? events.alarmWindow(client.actor().tenantId())
                    : events.window(client.actor().tenantId(), portfolio);
            List<RealtimeEvent> pending = window.events().stream()
                    .filter(event -> event.numericCursor() > subscription.getValue()).toList();
            if (pending.size() > properties.maxReplayEvents()) {
                client.channels().remove(subscription.getKey());
                sendControl(client, "resync_required", null, "REPLAY_LIMIT_EXCEEDED",
                        "client fell behind the retained realtime window");
                continue;
            }
            for (RealtimeEvent event : pending) {
                sendEvent(client, subscription.getKey(), event);
                client.channels().put(subscription.getKey(), event.numericCursor());
            }
        }
        if (Duration.between(client.lastHeartbeat(), Instant.now())
                .compareTo(properties.heartbeatInterval()) >= 0) {
            send(client, new PingMessage());
            client.lastHeartbeat(Instant.now());
        }
    }

    private void sendEvent(ClientState client, String channel, RealtimeEvent event) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", "event");
        envelope.put("channel", channel);
        envelope.put("cursor", event.cursor());
        envelope.put("occurred_at", event.occurredAt().toString());
        envelope.set("data", event.data());
        send(client, new TextMessage(json(envelope)));
    }

    private void sendControl(ClientState client, String type, String requestId,
            String code, String message) {
        ObjectNode control = mapper.createObjectNode();
        control.put("type", type);
        if (requestId != null) control.put("request_id", requestId);
        if (code != null) control.put("code", code);
        control.put("message", message);
        send(client, new TextMessage(json(control)));
    }

    private void send(ClientState client, org.springframework.web.socket.WebSocketMessage<?> message) {
        try {
            if (client.session().isOpen()) client.session().sendMessage(message);
        } catch (IOException | RuntimeException exception) {
            try {
                client.session().close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException ignored) {
                // The transport is already unusable.
            }
        }
    }

    private String json(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JacksonException exception) {
            throw new IllegalStateException("cannot serialize WebSocket message", exception);
        }
    }

    private static UUID portfolioId(String channel) {
        String[] parts = channel.split(":", -1);
        if (parts.length != 3 || !"portfolio".equals(parts[0]) || !"snapshot".equals(parts[2])) return null;
        try {
            return UUID.fromString(parts[1]);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Long parseCursor(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String value = node.asString();
        if (!value.matches("^[0-9]{16}$")) throw new IllegalArgumentException("invalid cursor");
        return Long.parseLong(value);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private static final class ClientState {
        private final WebSocketSession session;
        private final ActorPrincipal actor;
        private final Map<String, Long> channels;
        private volatile Instant lastHeartbeat;

        private ClientState(WebSocketSession session, ActorPrincipal actor,
                Map<String, Long> channels, Instant lastHeartbeat) {
            this.session = session;
            this.actor = actor;
            this.channels = channels;
            this.lastHeartbeat = lastHeartbeat;
        }

        WebSocketSession session() { return session; }
        ActorPrincipal actor() { return actor; }
        Map<String, Long> channels() { return channels; }
        Instant lastHeartbeat() { return lastHeartbeat; }
        void lastHeartbeat(Instant value) { lastHeartbeat = value; }
    }
}
