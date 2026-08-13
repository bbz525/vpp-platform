CREATE TABLE forecast_run (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    target_type varchar(16) NOT NULL CHECK (target_type IN ('SITE', 'PORTFOLIO')),
    target_id uuid NOT NULL,
    forecast_date date NOT NULL,
    timezone varchar(80) NOT NULL,
    metric varchar(24) NOT NULL CHECK (metric IN ('LOAD_KW', 'PV_POWER_KW')),
    status varchar(24) NOT NULL CHECK (status IN
        ('PENDING', 'RUNNING', 'SUCCEEDED', 'INSUFFICIENT_DATA', 'FAILED')),
    data_cutoff timestamptz NOT NULL,
    failure_code varchar(80),
    reasons_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    dataset_report_json jsonb,
    validation_metrics_json jsonb,
    created_by text NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX forecast_run_target_idx
    ON forecast_run (tenant_id, target_type, target_id, forecast_date DESC, created_at DESC);

CREATE TABLE forecast_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    forecast_run_id uuid NOT NULL REFERENCES forecast_run(id),
    version integer NOT NULL CHECK (version > 0),
    source varchar(24) NOT NULL CHECK (source IN ('MODEL', 'MANUAL_OVERRIDE')),
    model_name varchar(120) NOT NULL,
    model_version varchar(120) NOT NULL,
    feature_version varchar(120) NOT NULL,
    weather_source varchar(40) NOT NULL,
    data_cutoff timestamptz NOT NULL,
    overridden_from_id uuid REFERENCES forecast_version(id),
    override_reason varchar(500),
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (forecast_run_id, version)
);
CREATE INDEX forecast_version_run_idx ON forecast_version (tenant_id, forecast_run_id, version DESC);

CREATE TABLE forecast_point (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    forecast_version_id uuid NOT NULL REFERENCES forecast_version(id),
    interval_start timestamptz NOT NULL,
    interval_end timestamptz NOT NULL,
    value numeric(20,6) NOT NULL,
    unit varchar(24) NOT NULL,
    quality varchar(24) NOT NULL CHECK (quality IN ('ESTIMATED', 'OVERRIDDEN')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (forecast_version_id, interval_start),
    CHECK (interval_end > interval_start)
);
CREATE INDEX forecast_point_series_idx
    ON forecast_point (tenant_id, forecast_version_id, interval_start);

CREATE FUNCTION reject_forecast_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'immutable records are append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER forecast_version_append_only BEFORE UPDATE OR DELETE ON forecast_version
    FOR EACH ROW EXECUTE FUNCTION reject_forecast_mutation();
CREATE TRIGGER forecast_point_append_only BEFORE UPDATE OR DELETE ON forecast_point
    FOR EACH ROW EXECUTE FUNCTION reject_forecast_mutation();
