CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE tenant (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    lock_version bigint NOT NULL DEFAULT 0
);

CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    external_subject varchar(200) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, external_subject),
    UNIQUE (tenant_id, id)
);

CREATE TABLE app_user_role (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role varchar(40) NOT NULL CHECK (role IN ('TENANT_ADMIN', 'OPERATOR', 'AUDITOR', 'SITE_ADMIN')),
    PRIMARY KEY (tenant_id, user_id, role),
    FOREIGN KEY (tenant_id, user_id) REFERENCES app_user(tenant_id, id)
);

CREATE TABLE portfolio (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    name varchar(120) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    lock_version bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name)
);

CREATE TABLE site (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    portfolio_id uuid NOT NULL,
    name varchar(120) NOT NULL,
    timezone varchar(80) NOT NULL,
    grid_connection_limit_kw numeric(20,6) NOT NULL CHECK (grid_connection_limit_kw > 0),
    status varchar(24) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    lock_version bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, portfolio_id, name),
    FOREIGN KEY (tenant_id, portfolio_id) REFERENCES portfolio(tenant_id, id)
);

CREATE TABLE device_model (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    name varchar(120) NOT NULL,
    type varchar(32) NOT NULL CHECK (type IN ('METER', 'PV_INVERTER', 'BATTERY', 'EV_CHARGER')),
    schema_version integer NOT NULL CHECK (schema_version > 0),
    point_schema_json jsonb NOT NULL CHECK (jsonb_typeof(point_schema_json) = 'object'),
    status varchar(24) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name, schema_version)
);

CREATE TABLE device (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    site_id uuid NOT NULL,
    model_id uuid NOT NULL,
    external_code varchar(64) NOT NULL,
    name varchar(120) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'MAINTENANCE', 'DISABLED', 'SAFETY_LOCKED')),
    config_version bigint NOT NULL DEFAULT 1 CHECK (config_version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    lock_version bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, external_code),
    FOREIGN KEY (tenant_id, site_id) REFERENCES site(tenant_id, id),
    FOREIGN KEY (tenant_id, model_id) REFERENCES device_model(tenant_id, id)
);
CREATE INDEX device_site_status_idx ON device (tenant_id, site_id, status);

CREATE TABLE device_capability (
    tenant_id uuid NOT NULL,
    device_id uuid NOT NULL,
    capability varchar(80) NOT NULL,
    unit varchar(24) NOT NULL,
    min_value numeric(20,6) NOT NULL,
    max_value numeric(20,6) NOT NULL,
    fallback_value numeric(20,6) NOT NULL,
    config_version bigint NOT NULL CHECK (config_version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, device_id, capability),
    FOREIGN KEY (tenant_id, device_id) REFERENCES device(tenant_id, id),
    CHECK (max_value > min_value),
    CHECK (fallback_value BETWEEN min_value AND max_value)
);

CREATE TABLE credential_ref (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    device_id uuid NOT NULL,
    secret_ref text NOT NULL,
    credential_verifier text NOT NULL CHECK (credential_verifier LIKE 'hmac-sha256$%'),
    status varchar(24) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    rotated_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, device_id) REFERENCES device(tenant_id, id)
);
CREATE UNIQUE INDEX credential_one_active_idx ON credential_ref (tenant_id, device_id) WHERE status = 'ACTIVE';

CREATE TABLE tariff_plan (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    name varchar(120) NOT NULL,
    currency char(3) NOT NULL,
    timezone varchar(80) NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    valid_from timestamptz NOT NULL,
    valid_to timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name, version),
    CHECK (valid_to > valid_from)
);

CREATE TABLE tariff_interval (
    tenant_id uuid NOT NULL,
    tariff_plan_id uuid NOT NULL,
    interval_start timestamptz NOT NULL,
    interval_end timestamptz NOT NULL,
    price_per_kwh numeric(20,6) NOT NULL CHECK (price_per_kwh >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, tariff_plan_id, interval_start),
    FOREIGN KEY (tenant_id, tariff_plan_id) REFERENCES tariff_plan(tenant_id, id),
    CHECK (interval_end > interval_start),
    EXCLUDE USING gist (tariff_plan_id WITH =, tstzrange(interval_start, interval_end, '[)') WITH &&)
);

CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    actor_type varchar(24) NOT NULL,
    actor_id text NOT NULL,
    action text NOT NULL,
    object_type text NOT NULL,
    object_id text NOT NULL,
    reason text,
    before_digest char(64),
    after_digest char(64),
    correlation_id text,
    trace_id char(32),
    result varchar(24) NOT NULL CHECK (result IN ('SUCCEEDED', 'FAILED', 'DENIED')),
    metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL
);
CREATE INDEX audit_object_time_idx ON audit_event (tenant_id, object_type, object_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END $$;
CREATE TRIGGER audit_event_no_update BEFORE UPDATE OR DELETE ON audit_event
FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

CREATE TABLE outbox_event (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    aggregate_type varchar(64) NOT NULL,
    aggregate_id text NOT NULL,
    topic text NOT NULL,
    message_key text NOT NULL,
    payload_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    last_error_code text
);
CREATE INDEX outbox_unpublished_idx ON outbox_event (created_at) WHERE published_at IS NULL;

CREATE TABLE api_idempotency (
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    operation varchar(80) NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    request_digest char(64) NOT NULL,
    object_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, operation, idempotency_key)
);
