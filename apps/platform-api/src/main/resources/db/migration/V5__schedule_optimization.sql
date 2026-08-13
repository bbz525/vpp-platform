ALTER TABLE forecast_version ADD CONSTRAINT forecast_version_tenant_id_unique UNIQUE (tenant_id, id);

CREATE TABLE device_config_snapshot (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    portfolio_id uuid NOT NULL,
    content_json jsonb NOT NULL,
    content_sha256 char(64) NOT NULL,
    captured_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, portfolio_id) REFERENCES portfolio(tenant_id, id)
);

CREATE TABLE schedule (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    portfolio_id uuid NOT NULL,
    schedule_date date NOT NULL,
    timezone varchar(80) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('VALIDATED', 'FAILED')),
    feasibility varchar(24) NOT NULL CHECK (feasibility IN ('FEASIBLE', 'INFEASIBLE', 'FAILED')),
    current_version integer,
    failure_code varchar(80),
    input_json jsonb NOT NULL,
    reasons_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, portfolio_id) REFERENCES portfolio(tenant_id, id)
);
CREATE INDEX schedule_portfolio_date_idx
    ON schedule (tenant_id, portfolio_id, schedule_date DESC, created_at DESC);

CREATE TABLE schedule_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    schedule_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    load_forecast_version_id uuid NOT NULL,
    pv_forecast_version_id uuid NOT NULL,
    tariff_plan_id uuid NOT NULL,
    device_config_snapshot_id uuid NOT NULL,
    algorithm_name varchar(80) NOT NULL,
    algorithm_version varchar(80) NOT NULL,
    objective_value numeric(20,6) NOT NULL,
    summary_json jsonb NOT NULL,
    content_sha256 char(64) NOT NULL,
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (schedule_id, version),
    FOREIGN KEY (tenant_id, schedule_id) REFERENCES schedule(tenant_id, id),
    FOREIGN KEY (tenant_id, load_forecast_version_id) REFERENCES forecast_version(tenant_id, id),
    FOREIGN KEY (tenant_id, pv_forecast_version_id) REFERENCES forecast_version(tenant_id, id),
    FOREIGN KEY (tenant_id, tariff_plan_id) REFERENCES tariff_plan(tenant_id, id),
    FOREIGN KEY (tenant_id, device_config_snapshot_id) REFERENCES device_config_snapshot(tenant_id, id)
);

CREATE TABLE schedule_interval (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    schedule_version_id uuid NOT NULL,
    interval_start timestamptz NOT NULL,
    interval_end timestamptz NOT NULL,
    load_kw numeric(20,6) NOT NULL CHECK (load_kw >= 0),
    pv_kw numeric(20,6) NOT NULL CHECK (pv_kw >= 0),
    price_per_kwh numeric(20,6) NOT NULL CHECK (price_per_kwh >= 0),
    baseline_grid_kw numeric(20,6) NOT NULL,
    planned_grid_kw numeric(20,6) NOT NULL,
    baseline_cost numeric(20,6) NOT NULL CHECK (baseline_cost >= 0),
    planned_cost numeric(20,6) NOT NULL CHECK (planned_cost >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (schedule_version_id, interval_start),
    FOREIGN KEY (tenant_id, schedule_version_id) REFERENCES schedule_version(tenant_id, id),
    CHECK (interval_end > interval_start)
);

CREATE TABLE schedule_target (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    schedule_version_id uuid NOT NULL,
    device_id uuid NOT NULL,
    interval_start timestamptz NOT NULL,
    interval_end timestamptz NOT NULL,
    charge_kw numeric(20,6) NOT NULL CHECK (charge_kw >= 0),
    discharge_kw numeric(20,6) NOT NULL CHECK (discharge_kw >= 0),
    setpoint_kw numeric(20,6) NOT NULL,
    soc_start_pct numeric(10,6) NOT NULL,
    soc_end_pct numeric(10,6) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (schedule_version_id, device_id, interval_start),
    FOREIGN KEY (tenant_id, schedule_version_id) REFERENCES schedule_version(tenant_id, id),
    FOREIGN KEY (tenant_id, device_id) REFERENCES device(tenant_id, id),
    CHECK (interval_end > interval_start),
    CHECK (charge_kw = 0 OR discharge_kw = 0),
    CHECK (setpoint_kw = discharge_kw - charge_kw)
);
CREATE INDEX schedule_target_series_idx
    ON schedule_target (tenant_id, schedule_version_id, device_id, interval_start);

CREATE TRIGGER device_config_snapshot_append_only BEFORE UPDATE OR DELETE ON device_config_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_forecast_mutation();
CREATE TRIGGER schedule_version_append_only BEFORE UPDATE OR DELETE ON schedule_version
    FOR EACH ROW EXECUTE FUNCTION reject_forecast_mutation();
CREATE TRIGGER schedule_interval_append_only BEFORE UPDATE OR DELETE ON schedule_interval
    FOR EACH ROW EXECUTE FUNCTION reject_forecast_mutation();
CREATE TRIGGER schedule_target_append_only BEFORE UPDATE OR DELETE ON schedule_target
    FOR EACH ROW EXECUTE FUNCTION reject_forecast_mutation();
