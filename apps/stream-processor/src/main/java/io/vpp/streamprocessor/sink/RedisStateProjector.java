package io.vpp.streamprocessor.sink;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.vpp.streamprocessor.config.StreamProperties;
import io.vpp.streamprocessor.normalization.NormalizedTelemetry;

@Component
public class RedisStateProjector {
    private static final DefaultRedisScript<Long> PROJECT = new DefaultRedisScript<>("""
            local current_event = redis.call('HGET', KEYS[1], 'event_id')
            if current_event == ARGV[3] then return 2 end
            local current_time = tonumber(redis.call('HGET', KEYS[1], 'event_epoch_ms') or '-1')
            local current_seq = tonumber(redis.call('HGET', KEYS[1], 'sequence') or '-1')
            local new_time = tonumber(ARGV[1])
            local new_seq = tonumber(ARGV[2])
            if current_time > new_time or (current_time == new_time and current_seq >= new_seq) then
              return 0
            end
            local old_power = tonumber(redis.call('HGET', KEYS[1], 'active_power_kw') or '0')
            local new_power = old_power
            if ARGV[10] == '1' then new_power = tonumber(ARGV[11]) end
            redis.call('HSET', KEYS[1],
              'event_epoch_ms', ARGV[1], 'sequence', ARGV[2], 'event_id', ARGV[3],
              'occurred_at', ARGV[4], 'produced_at', ARGV[5], 'quality_status', ARGV[6],
              'device_status', ARGV[7], 'metrics_json', ARGV[8], 'active_power_kw', new_power)
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[9]))
            local delta = new_power - old_power
            for index = 2, 3 do
              if delta ~= 0 then redis.call('HINCRBYFLOAT', KEYS[index], 'active_power_kw', delta) end
              local aggregate_time = tonumber(redis.call('HGET', KEYS[index], 'event_epoch_ms') or '-1')
              if new_time > aggregate_time then
                redis.call('HSET', KEYS[index], 'event_epoch_ms', ARGV[1],
                  'occurred_at', ARGV[4], 'produced_at', ARGV[5])
              end
              redis.call('EXPIRE', KEYS[index], tonumber(ARGV[12]))
            end
            local cursor = redis.call('INCR', KEYS[4])
            local cursor_text = string.format('%016d', cursor)
            redis.call('XADD', KEYS[5], 'MAXLEN', '~', tonumber(ARGV[14]), '*',
              'cursor', cursor_text, 'occurred_at', ARGV[4], 'data', ARGV[13])
            redis.call('EXPIRE', KEYS[5], tonumber(ARGV[15]))
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final StreamProperties properties;

    public RedisStateProjector(StringRedisTemplate redis, ObjectMapper mapper,
            StreamProperties properties) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
    }

    public boolean wasProcessed(NormalizedTelemetry telemetry) {
        return Boolean.TRUE.equals(redis.hasKey(dedupKey(telemetry)));
    }

    public void markProcessed(NormalizedTelemetry telemetry) {
        redis.opsForValue().set(dedupKey(telemetry), "1", properties.state().dedupTtl());
    }

    public RedisProjectionResult project(NormalizedTelemetry telemetry, String deviceStatus) {
        Double activePower = telemetry.payload().metrics().get("active_power_kw");
        Long result = redis.execute(PROJECT, keys(telemetry),
                Long.toString(telemetry.occurredAt().toEpochMilli()),
                Long.toString(telemetry.payload().sequence()), telemetry.eventId().toString(),
                telemetry.occurredAt().toString(), telemetry.producedAt().toString(),
                telemetry.dataQuality().status(), deviceStatus, metricsJson(telemetry),
                Long.toString(properties.state().deviceTtl().toSeconds()),
                activePower == null ? "0" : "1",
                activePower == null ? "0" : activePower.toString(),
                Long.toString(properties.state().aggregateTtl().toSeconds()),
                realtimeDataJson(telemetry, deviceStatus),
                Long.toString(properties.state().resumeMaxLength()),
                Long.toString(properties.state().resumeTtl().toSeconds()));
        if (result == null || result == 0) return RedisProjectionResult.OLDER_IGNORED;
        if (result == 2) return RedisProjectionResult.ALREADY_PROJECTED;
        return RedisProjectionResult.UPDATED;
    }

    private List<String> keys(NormalizedTelemetry telemetry) {
        String prefix = "vpp:{" + telemetry.tenantId() + "}:";
        return List.of(prefix + "device:" + telemetry.payload().deviceUuid() + ":state",
                prefix + "site:" + telemetry.payload().siteId() + ":snapshot",
                prefix + "portfolio:" + telemetry.payload().portfolioId() + ":snapshot",
                prefix + "ws:portfolio:" + telemetry.payload().portfolioId() + ":cursor",
                prefix + "ws:portfolio:" + telemetry.payload().portfolioId() + ":snapshot");
    }

    private String dedupKey(NormalizedTelemetry telemetry) {
        return "vpp:{" + telemetry.tenantId() + "}:dedup:telemetry-normalizer:"
                + telemetry.eventId();
    }

    private String metricsJson(NormalizedTelemetry telemetry) {
        try {
            return mapper.writeValueAsString(telemetry.payload().metrics());
        } catch (JacksonException exception) {
            throw new IllegalStateException("cannot serialize Redis state metrics", exception);
        }
    }

    private String realtimeDataJson(NormalizedTelemetry telemetry, String deviceStatus) {
        var data = mapper.createObjectNode();
        data.put("device_id", telemetry.payload().deviceUuid().toString());
        data.put("external_code", telemetry.aggregateId());
        data.put("site_id", telemetry.payload().siteId().toString());
        data.put("device_type", telemetry.payload().deviceType());
        data.put("device_status", deviceStatus);
        data.put("quality", telemetry.dataQuality().status());
        data.set("metrics", mapper.valueToTree(telemetry.payload().metrics()));
        try {
            return mapper.writeValueAsString(data);
        } catch (JacksonException exception) {
            throw new IllegalStateException("cannot serialize realtime event", exception);
        }
    }
}
