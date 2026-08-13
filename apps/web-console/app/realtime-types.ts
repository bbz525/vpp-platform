export type Freshness = "LIVE" | "PARTIAL" | "STALE" | "EMPTY";

export interface Portfolio {
  id: string;
  name: string;
  status: string;
  created_at: string;
}

export interface MetricValue {
  name: string;
  value: number;
  unit: string;
}

export interface DeviceSnapshot {
  device_id: string;
  external_code: string;
  device_name: string;
  device_type: string;
  device_status: string;
  freshness_status: Freshness;
  quality: string;
  observed_at: string | null;
  freshness_seconds: number | null;
  metrics: MetricValue[];
}

export interface Coverage {
  total_devices: number;
  reporting_devices: number;
  live_devices: number;
}

export interface SiteSnapshot {
  site_id: string;
  site_name: string;
  timezone: string;
  freshness_status: Freshness;
  coverage: Coverage;
  devices: DeviceSnapshot[];
}

export interface PortfolioSnapshot {
  portfolio_id: string;
  portfolio_name: string;
  observed_at: string | null;
  freshness_seconds: number | null;
  freshness_status: Freshness;
  quality: string;
  coverage: Coverage;
  power: {
    net_grid_power_kw: number | null;
    pv_power_kw: number | null;
    battery_power_kw: number | null;
  };
  sites: SiteSnapshot[];
}

export interface TelemetryPoint {
  observed_at: string;
  value: number;
  quality: string;
}

export interface TelemetrySeries {
  external_code: string;
  device_type: string;
  unit: string;
  points: TelemetryPoint[];
}

export interface TelemetryQuery {
  target_type: string;
  target_id: string;
  metric: string;
  from: string;
  to: string;
  bucket_seconds: number;
  series: TelemetrySeries[];
}

export type SocketState = "CONNECTING" | "LIVE" | "RECONNECTING" | "OFFLINE";

export type AlarmState = "OPEN" | "ACKNOWLEDGED" | "RECOVERED" | "CLOSED";
export interface Alarm {
  id: string; rule_id: string; rule_code: string; rule_name: string;
  object_type: string; object_id: string; object_name: string; external_code: string;
  portfolio_id: string; site_id: string; state: AlarmState;
  severity: "INFO" | "WARNING" | "MAJOR" | "CRITICAL";
  first_occurred_at: string; last_occurred_at: string; occurrence_count: number;
  acknowledged_at: string | null; acknowledged_by: string | null;
  recovered_at: string | null; closed_at: string | null; evidence: Record<string, unknown>;
}
export interface AlarmTransition { id: string; event_type: string; from_state: string | null; to_state: string; actor_id: string | null; reason: string | null; evidence: Record<string, unknown>; occurred_at: string; }
export interface AlarmDetail { alarm: Alarm; timeline: AlarmTransition[]; }
export interface AlarmCoverage { enabled_rules: number; evaluated_rules: number; unsupported_rules: string[]; status: "FULL" | "PARTIAL"; }

export type ForecastStatus = "PENDING" | "RUNNING" | "SUCCEEDED" | "INSUFFICIENT_DATA" | "FAILED";
export interface ForecastRun {
  id: string; status: ForecastStatus; target_type: "SITE" | "PORTFOLIO"; target_id: string;
  forecast_date: string; timezone: string; metric: "LOAD_KW" | "PV_POWER_KW"; data_cutoff: string;
  failure_code: string | null; reasons: string[]; dataset_report: Record<string, unknown> | null;
  validation_metrics: Record<string, unknown> | null; forecast_version_id: string | null;
  created_at: string; completed_at: string | null;
}
export interface ForecastPoint { interval_start: string; interval_end: string; value: number; unit: string; quality: "ESTIMATED" | "OVERRIDDEN"; }
export interface ForecastVersion {
  id: string; forecast_run_id: string; version: number; source: "MODEL" | "MANUAL_OVERRIDE";
  model_name: string; model_version: string; feature_version: string; weather_source: string;
  data_cutoff: string; overridden_from_id: string | null; override_reason: string | null;
  created_at: string; points: ForecastPoint[];
}
export interface ForecastRunDetail { run: ForecastRun; forecast: ForecastVersion | null; }

export interface DeviceCapability { capability: string; unit: string; min_value: number; max_value: number; fallback_value: number; config_version: number; }
export interface SiteResource { id: string; portfolio_id: string; name: string; timezone: string; grid_connection_limit_kw: number; status: string; created_at: string; }
export interface DeviceResource { id: string; site_id: string; name: string; status: string; config_version: number; capabilities: DeviceCapability[]; }
export interface TariffPlan { id: string; name: string; currency: string; timezone: string; version: number; valid_from: string; valid_to: string; interval_count: number; created_at: string; }
export interface ScheduleSummary { baselineCost: number; plannedEnergyCost: number; degradationCost: number; objectiveCost: number; savings: number; baselinePeakKw: number; plannedPeakKw: number; throughputKwh: number; equivalentCycles: number; }
export interface ScheduleInterval { interval_start: string; interval_end: string; load_kw: number; pv_kw: number; price_per_kwh: number; baseline_grid_kw: number; planned_grid_kw: number; baseline_cost: number; planned_cost: number; }
export interface ScheduleTarget { device_id: string; interval_start: string; interval_end: string; charge_kw: number; discharge_kw: number; setpoint_kw: number; soc_start_pct: number; soc_end_pct: number; }
export interface ScheduleRecord { id: string; portfolio_id: string; schedule_date: string; timezone: string; status: "VALIDATED" | "FAILED"; feasibility: "FEASIBLE" | "INFEASIBLE"; current_version: number | null; failure_code: string | null; reasons: string[]; input: Record<string, unknown>; created_at: string; updated_at: string; }
export interface ScheduleVersion { id: string; version: number; load_forecast_version_id: string; pv_forecast_version_id: string; tariff_plan_id: string; device_config_snapshot_id: string; algorithm_name: "RULE_BASELINE"; algorithm_version: string; objective_value: number; summary: ScheduleSummary; content_sha256: string; created_at: string; intervals: ScheduleInterval[]; targets: ScheduleTarget[]; }
export interface ScheduleDetail { schedule: ScheduleRecord; version: ScheduleVersion | null; }
