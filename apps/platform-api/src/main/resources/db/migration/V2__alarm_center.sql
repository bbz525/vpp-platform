CREATE TABLE alarm_rule (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    code varchar(128) NOT NULL,
    name varchar(160) NOT NULL,
    rule_type varchar(32) NOT NULL CHECK (rule_type IN
        ('OFFLINE', 'STALE', 'SOC_LOW', 'SOC_HIGH', 'POWER_LOW', 'POWER_HIGH',
         'SUDDEN_CHANGE', 'COMMAND_TIMEOUT')),
    object_type varchar(24) NOT NULL DEFAULT 'DEVICE' CHECK (object_type = 'DEVICE'),
    device_type varchar(32),
    metric varchar(64),
    threshold numeric(20,6),
    clear_threshold numeric(20,6),
    duration_seconds integer NOT NULL DEFAULT 0 CHECK (duration_seconds >= 0),
    severity varchar(16) NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'MAJOR', 'CRITICAL')),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code, version)
);
CREATE INDEX alarm_rule_enabled_idx ON alarm_rule (tenant_id, enabled, rule_type);

CREATE TABLE alarm (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    rule_id uuid NOT NULL REFERENCES alarm_rule(id),
    object_type varchar(24) NOT NULL CHECK (object_type = 'DEVICE'),
    object_id uuid NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN ('OPEN', 'ACKNOWLEDGED', 'RECOVERED', 'CLOSED')),
    severity varchar(16) NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'MAJOR', 'CRITICAL')),
    first_occurred_at timestamptz NOT NULL,
    last_occurred_at timestamptz NOT NULL,
    occurrence_count bigint NOT NULL DEFAULT 1 CHECK (occurrence_count > 0),
    acknowledged_at timestamptz,
    acknowledged_by text,
    recovered_at timestamptz,
    closed_at timestamptz,
    evidence_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX alarm_one_active_instance_idx
    ON alarm (tenant_id, rule_id, object_id) WHERE state <> 'CLOSED';
CREATE INDEX alarm_tenant_state_time_idx ON alarm (tenant_id, state, last_occurred_at DESC);

CREATE TABLE alarm_transition (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    alarm_id uuid NOT NULL REFERENCES alarm(id),
    event_type varchar(32) NOT NULL CHECK (event_type IN
        ('AlarmOpened', 'AlarmRepeated', 'AlarmAcknowledged', 'AlarmRecovered', 'AlarmClosed', 'AlarmNoted')),
    from_state varchar(24),
    to_state varchar(24) NOT NULL,
    actor_id text,
    reason varchar(500),
    evidence_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL
);
CREATE INDEX alarm_transition_timeline_idx ON alarm_transition (tenant_id, alarm_id, occurred_at, id);

CREATE TRIGGER alarm_transition_no_update BEFORE UPDATE OR DELETE ON alarm_transition
FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

INSERT INTO alarm_rule
    (id, tenant_id, code, name, rule_type, metric, threshold, clear_threshold,
     duration_seconds, severity, enabled)
SELECT gen_random_uuid(), id, 'telemetry-stale-v1', '遥测过期', 'STALE', NULL, NULL, NULL,
       30, 'MAJOR', true FROM tenant
ON CONFLICT DO NOTHING;

INSERT INTO alarm_rule
    (id, tenant_id, code, name, rule_type, metric, threshold, clear_threshold,
     duration_seconds, severity, enabled)
SELECT gen_random_uuid(), id, 'telemetry-offline-v1', '设备离线', 'OFFLINE', NULL, NULL, NULL,
       30, 'CRITICAL', true FROM tenant
ON CONFLICT DO NOTHING;
