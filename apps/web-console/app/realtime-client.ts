import type { Alarm, AlarmCoverage, AlarmDetail, CommandDetail, DeviceResource, ForecastRun, ForecastRunDetail, Portfolio, PortfolioSnapshot, ScheduleDecision, ScheduleDecisionType, ScheduleDetail, ScheduleExecution, ScheduleRecord, SiteResource, TariffPlan, TelemetryQuery } from "./realtime-types";

const apiBase = process.env.NEXT_PUBLIC_PLATFORM_API_BASE ?? "http://127.0.0.1:8080";
const tenant = process.env.NEXT_PUBLIC_VPP_TENANT_ID ?? "7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941";
const subject = process.env.NEXT_PUBLIC_VPP_SUBJECT ?? "local-admin";

function headers(): HeadersInit {
  return {
    "X-VPP-Tenant-Id": tenant,
    "X-VPP-Subject": subject,
  };
}

async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${apiBase}${path}`, { headers: headers(), signal, cache: "no-store" });
  if (!response.ok) throw new ApiError(response.status, await errorMessage(response));
  return response.json() as Promise<T>;
}

async function post<T>(path: string, body: object, idempotencyKey?: string): Promise<T> {
  const response = await fetch(`${apiBase}${path}`, { method: "POST", headers: { ...headers(), "Content-Type": "application/json", ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {}) }, body: JSON.stringify(body) });
  if (!response.ok) throw new ApiError(response.status, await errorMessage(response));
  return response.json() as Promise<T>;
}

export function listPortfolios(signal?: AbortSignal) {
  return get<Portfolio[]>("/api/v1/portfolios", signal);
}

export function getSnapshot(portfolioId: string, signal?: AbortSignal) {
  return get<PortfolioSnapshot>(`/api/v1/portfolios/${portfolioId}/snapshot`, signal);
}

export function getHistory(portfolioId: string, signal?: AbortSignal) {
  const to = new Date();
  const from = new Date(to.getTime() - 60 * 60 * 1000);
  const query = new URLSearchParams({
    targetType: "PORTFOLIO",
    targetId: portfolioId,
    metric: "active_power_kw",
    from: from.toISOString(),
    to: to.toISOString(),
  });
  return get<TelemetryQuery>(`/api/v1/telemetry/query?${query}`, signal);
}

export async function issueTicket(): Promise<string> {
  const response = await fetch(`${apiBase}/api/v1/realtime/tickets`, {
    method: "POST",
    headers: headers(),
  });
  if (!response.ok) throw new Error(await errorMessage(response));
  const body = (await response.json()) as { ticket: string };
  return body.ticket;
}

export function listAlarms(portfolioId: string, signal?: AbortSignal) { return get<Alarm[]>(`/api/v1/alarms?portfolioId=${portfolioId}&limit=200`, signal); }
export function getAlarmCoverage(signal?: AbortSignal) { return get<AlarmCoverage>("/api/v1/alarms/coverage", signal); }
export function getAlarmDetail(id: string, signal?: AbortSignal) { return get<AlarmDetail>(`/api/v1/alarms/${id}`, signal); }
export function acknowledgeAlarm(id: string, reason: string) { return post<Alarm>(`/api/v1/alarms/${id}/acknowledge`, { reason }); }
export function noteAlarm(id: string, reason: string) { return post<AlarmDetail>(`/api/v1/alarms/${id}/notes`, { reason }); }
export function closeAlarm(id: string, reason: string) { return post<Alarm>(`/api/v1/alarms/${id}/close`, { reason }); }
export function listForecastRuns(targetId: string, signal?: AbortSignal) { return get<ForecastRun[]>(`/api/v1/forecast-runs?targetId=${targetId}&limit=20`, signal); }
export function getForecastRun(id: string, signal?: AbortSignal) { return get<ForecastRunDetail>(`/api/v1/forecast-runs/${id}`, signal); }
export function createForecastRun(targetId: string, date: string, metric: "LOAD_KW" | "PV_POWER_KW") {
  return post<ForecastRun>("/api/v1/forecast-runs", { target_type: "PORTFOLIO", target_id: targetId, forecast_date: date, metric }, `forecast-${targetId}-${date}-${metric}`);
}
export function listDevices(signal?: AbortSignal) { return get<DeviceResource[]>("/api/v1/devices", signal); }
export function listSites(portfolioId: string, signal?: AbortSignal) { return get<SiteResource[]>(`/api/v1/portfolios/${portfolioId}/sites`, signal); }
export function listTariffs(signal?: AbortSignal) { return get<TariffPlan[]>("/api/v1/tariff-plans", signal); }
export function listSchedules(portfolioId: string, signal?: AbortSignal) { return get<ScheduleRecord[]>(`/api/v1/schedules?portfolioId=${portfolioId}&limit=20`, signal); }
export function getSchedule(id: string, signal?: AbortSignal) { return get<ScheduleDetail>(`/api/v1/schedules/${id}`, signal); }
export function createSchedule(input: { portfolio_id: string; schedule_date: string; load_forecast_version_id: string; pv_forecast_version_id: string; tariff_plan_id: string; battery_states: { device_id: string; initial_soc_pct: number }[]; }) {
  return post<ScheduleDetail>("/api/v1/schedules", input, `schedule-${input.portfolio_id}-${input.schedule_date}-${crypto.randomUUID()}`);
}
export function decideSchedule(id: string, decision: ScheduleDecisionType, reason: string) {
  return post<ScheduleDecision>(`/api/v1/schedules/${id}/decisions`, { decision, reason }, `schedule-decision-${id}-${crypto.randomUUID()}`);
}
export function emergencyStopScheduleFromCommand(id: string, reason: string) {
  return post<CommandDetail>(`/api/v1/commands/${id}/stop`, { reason }, `command-stop-${id}-${crypto.randomUUID()}`);
}
export function getScheduleExecution(id: string, offset: number, limit: number, signal?: AbortSignal) {
  const query = new URLSearchParams({ offset: String(offset), limit: String(limit) });
  return get<ScheduleExecution>(`/api/v1/schedules/${id}/execution?${query}`, signal);
}

export function websocketUrl(ticket: string): string {
  const url = new URL(apiBase);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.pathname = "/ws/v1";
  url.search = new URLSearchParams({ ticket }).toString();
  return url.toString();
}

async function errorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string };
    return body.message ?? `请求失败 (${response.status})`;
  } catch {
    return `请求失败 (${response.status})`;
  }
}

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "ApiError";
  }
}
