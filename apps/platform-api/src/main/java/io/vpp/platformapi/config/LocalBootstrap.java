package io.vpp.platformapi.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LocalBootstrap implements ApplicationRunner {
    private final PlatformProperties properties;
    private final JdbcTemplate jdbc;

    public LocalBootstrap(PlatformProperties properties, JdbcTemplate jdbc) {
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var bootstrap = properties.bootstrap();
        if (!bootstrap.enabled()) {
            return;
        }
        jdbc.update("""
                INSERT INTO tenant (id, name, status) VALUES (?, ?, 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """, bootstrap.tenantId(), bootstrap.tenantName());
        jdbc.update("""
                INSERT INTO app_user (id, tenant_id, external_subject, status)
                VALUES (?, ?, ?, 'ACTIVE') ON CONFLICT (tenant_id, external_subject) DO NOTHING
                """, bootstrap.userId(), bootstrap.tenantId(), bootstrap.subject());
        for (String role : new String[] {"TENANT_ADMIN", "OPERATOR", "AUDITOR"}) {
            jdbc.update("""
                    INSERT INTO app_user_role (tenant_id, user_id, role) VALUES (?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, bootstrap.tenantId(), bootstrap.userId(), role);
        }
        jdbc.update("""
                INSERT INTO alarm_rule
                    (id, tenant_id, code, name, rule_type, duration_seconds, severity, version, enabled)
                VALUES (gen_random_uuid(), ?, 'telemetry-stale-v1', '遥测过期', 'STALE', 30, 'MAJOR', 1, true)
                ON CONFLICT (tenant_id, code, version) DO NOTHING
                """, bootstrap.tenantId());
        jdbc.update("""
                INSERT INTO alarm_rule
                    (id, tenant_id, code, name, rule_type, duration_seconds, severity, version, enabled)
                VALUES (gen_random_uuid(), ?, 'telemetry-offline-v1', '设备离线', 'OFFLINE', 120, 'CRITICAL', 1, true)
                ON CONFLICT (tenant_id, code, version) DO NOTHING
                """, bootstrap.tenantId());
    }
}
