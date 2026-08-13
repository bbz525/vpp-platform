ALTER TABLE schedule DROP CONSTRAINT schedule_status_check;
ALTER TABLE schedule ADD CONSTRAINT schedule_status_check
    CHECK (status IN ('VALIDATED', 'FAILED', 'APPROVED', 'CANCELLED'));

CREATE TABLE schedule_approval (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    schedule_id uuid NOT NULL,
    schedule_version_id uuid NOT NULL,
    schedule_version integer NOT NULL CHECK (schedule_version > 0),
    decision varchar(16) NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    reason varchar(500),
    actor_id text NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    decided_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, schedule_id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, schedule_id) REFERENCES schedule(tenant_id, id),
    FOREIGN KEY (tenant_id, schedule_version_id) REFERENCES schedule_version(tenant_id, id)
);
CREATE INDEX schedule_approval_version_idx
    ON schedule_approval (tenant_id, schedule_version_id, decided_at);

CREATE TABLE command (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    device_id uuid NOT NULL,
    schedule_id uuid,
    schedule_version_id uuid,
    parent_command_id uuid,
    idempotency_key varchar(256) NOT NULL,
    action varchar(48) NOT NULL CHECK (action IN ('SET_POWER', 'STOP')),
    parameters_json jsonb NOT NULL CHECK (jsonb_typeof(parameters_json) = 'object'),
    safety_config_version bigint NOT NULL CHECK (safety_config_version > 0),
    status varchar(24) NOT NULL CHECK (status IN
        ('CREATED', 'DISPATCHED', 'ACCEPTED', 'EXECUTING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')),
    not_before timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    terminal_at timestamptz,
    last_reason_code varchar(80),
    last_message varchar(500),
    actual_json jsonb,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, device_id) REFERENCES device(tenant_id, id),
    FOREIGN KEY (tenant_id, schedule_id) REFERENCES schedule(tenant_id, id),
    FOREIGN KEY (tenant_id, schedule_version_id) REFERENCES schedule_version(tenant_id, id),
    FOREIGN KEY (tenant_id, parent_command_id) REFERENCES command(tenant_id, id),
    CHECK (expires_at > not_before),
    CHECK ((schedule_id IS NULL) = (schedule_version_id IS NULL) OR action = 'STOP')
);
CREATE INDEX command_dispatch_idx ON command (status, not_before, created_at)
    WHERE status = 'CREATED';
CREATE INDEX command_timeout_idx ON command (status, expires_at)
    WHERE status IN ('CREATED', 'DISPATCHED', 'ACCEPTED', 'EXECUTING');
CREATE INDEX command_schedule_idx ON command (tenant_id, schedule_id, created_at, id);

CREATE TABLE command_attempt (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    command_id uuid NOT NULL,
    attempt_no integer NOT NULL CHECK (attempt_no > 0),
    dispatched_at timestamptz NOT NULL,
    latest_ack_at timestamptz,
    latest_ack_status varchar(24),
    error_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (command_id, attempt_no),
    FOREIGN KEY (tenant_id, command_id) REFERENCES command(tenant_id, id)
);

CREATE TABLE command_event (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    command_id uuid NOT NULL,
    source_event_id uuid,
    event_type varchar(40) NOT NULL,
    from_status varchar(24),
    reported_status varchar(24) NOT NULL,
    applied boolean NOT NULL,
    reason_code varchar(80),
    message varchar(500),
    actual_json jsonb,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, source_event_id),
    FOREIGN KEY (tenant_id, command_id) REFERENCES command(tenant_id, id)
);
CREATE INDEX command_event_timeline_idx
    ON command_event (tenant_id, command_id, received_at, id);

CREATE TABLE processed_event (
    consumer varchar(120) NOT NULL,
    event_id uuid NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

CREATE OR REPLACE FUNCTION reject_execution_fact_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'execution fact is append-only';
END $$;
CREATE TRIGGER schedule_approval_append_only BEFORE UPDATE OR DELETE ON schedule_approval
    FOR EACH ROW EXECUTE FUNCTION reject_execution_fact_mutation();
CREATE TRIGGER command_event_append_only BEFORE UPDATE OR DELETE ON command_event
    FOR EACH ROW EXECUTE FUNCTION reject_execution_fact_mutation();
