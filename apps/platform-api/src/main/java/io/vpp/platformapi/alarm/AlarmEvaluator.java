package io.vpp.platformapi.alarm;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import io.vpp.platformapi.alarm.AlarmDtos.RuleResponse;
import io.vpp.platformapi.config.AlarmProperties;

@Component
public class AlarmEvaluator {
    private final AlarmProperties properties; private final AlarmRepository repository;
    private final AlarmService service; private final StringRedisTemplate redis; private final ObjectMapper mapper;
    public AlarmEvaluator(AlarmProperties properties,AlarmRepository repository,AlarmService service,StringRedisTemplate redis,ObjectMapper mapper) {
        this.properties=properties;this.repository=repository;this.service=service;this.redis=redis;this.mapper=mapper;
    }
    @Scheduled(fixedDelayString="${platform.alarms.evaluation-interval:5s}")
    public void evaluate() {
        if (!properties.enabled()) return;
        Instant now=Instant.now();
        for (var tenantRule:repository.enabledRules()) {
            RuleResponse rule=tenantRule.rule();
            if ("UNSUPPORTED".equals(rule.coverageStatus())) continue;
            for (var target:repository.targets(tenantRule.tenantId(),rule.deviceType())) {
                Map<Object,Object> state=redis.opsForHash().entries("vpp:{"+tenantRule.tenantId()+"}:device:"+target.id()+":state");
                var evidence=mapper.createObjectNode(); evidence.put("rule_type",rule.ruleType());
                boolean active=repository.findActive(tenantRule.tenantId(),rule.id(),target.id()).isPresent();
                boolean triggered=triggered(rule,state,now,evidence,active);
                service.condition(tenantRule.tenantId(),rule,target,triggered,evidence,now);
            }
        }
    }
    private boolean triggered(RuleResponse rule,Map<Object,Object> state,Instant now,tools.jackson.databind.node.ObjectNode evidence,boolean active) {
        if (state.isEmpty() || state.get("produced_at")==null) { evidence.put("state","MISSING"); return ListTypes.offline(rule.ruleType()); }
        Instant observed=Instant.parse(state.get("produced_at").toString()); long age=Math.max(0,Duration.between(observed,now).toSeconds()); evidence.put("age_seconds",age);
        if (state.get("event_id")!=null) evidence.put("event_id",state.get("event_id").toString());
        if (ListTypes.offline(rule.ruleType()) || "STALE".equals(rule.ruleType())) return age>rule.durationSeconds();
        try {
            var metrics=mapper.readTree(state.getOrDefault("metrics_json","{}").toString());
            String metric=rule.metric()!=null?rule.metric():rule.ruleType().startsWith("SOC")?"soc_pct":"active_power_kw";
            if (!metrics.has(metric)||!metrics.get(metric).isNumber()) { evidence.put("metric_missing",metric); return false; }
            double value=metrics.get(metric).asDouble(),threshold=active&&rule.clearThreshold()!=null?rule.clearThreshold().doubleValue():rule.threshold().doubleValue(); evidence.put("metric",metric);evidence.put("value",value);evidence.put("threshold",threshold);
            return rule.ruleType().endsWith("LOW")?value<threshold:value>threshold;
        } catch(Exception ignored) { evidence.put("state","UNREADABLE"); return false; }
    }
    private static final class ListTypes { static boolean offline(String type){return "OFFLINE".equals(type);} }
}
