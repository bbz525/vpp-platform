CREATE TABLE IF NOT EXISTS vpp.telemetry_metric
(
    tenant_id UUID,
    portfolio_id UUID,
    site_id UUID,
    device_id UUID,
    external_code String,
    device_type LowCardinality(String),
    event_id UUID,
    sequence UInt64,
    device_time DateTime64(3, 'UTC'),
    ingested_at DateTime64(3, 'UTC'),
    metric_name LowCardinality(String),
    metric_value Float64,
    unit LowCardinality(String),
    quality_status LowCardinality(String),
    quality_flags Array(LowCardinality(String)),
    source LowCardinality(String),
    event_version UInt64
)
ENGINE = ReplacingMergeTree(event_version)
PARTITION BY toYYYYMM(device_time)
ORDER BY (tenant_id, site_id, device_id, metric_name, device_time, event_id)
TTL device_time + INTERVAL 365 DAY DELETE;

CREATE VIEW IF NOT EXISTS vpp.telemetry_metric_current AS
SELECT
    tenant_id,
    argMax(portfolio_id, event_version) AS portfolio_id,
    argMax(site_id, event_version) AS site_id,
    device_id,
    argMax(external_code, event_version) AS external_code,
    argMax(device_type, event_version) AS device_type,
    event_id,
    argMax(sequence, event_version) AS sequence,
    argMax(device_time, event_version) AS device_time,
    argMax(ingested_at, event_version) AS ingested_at,
    metric_name,
    argMax(metric_value, event_version) AS metric_value,
    argMax(unit, event_version) AS unit,
    argMax(quality_status, event_version) AS quality_status,
    argMax(quality_flags, event_version) AS quality_flags,
    argMax(source, event_version) AS source,
    max(event_version) AS latest_event_version
FROM vpp.telemetry_metric
GROUP BY tenant_id, device_id, event_id, metric_name;

CREATE VIEW IF NOT EXISTS vpp.metric_15m AS
SELECT tenant_id, site_id, device_id, metric_name,
       toStartOfFifteenMinutes(device_time) AS interval_start,
       avg(metric_value) AS avg_value,
       min(metric_value) AS min_value,
       max(metric_value) AS max_value,
       argMax(metric_value, device_time) AS last_value,
       toUInt32(count()) AS sample_count,
       toUInt32(countIf(quality_status = 'VALID')) AS valid_count,
       max(ingested_at) AS calculated_at
FROM vpp.telemetry_metric_current
GROUP BY tenant_id, site_id, device_id, metric_name, interval_start;
