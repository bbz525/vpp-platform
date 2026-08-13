"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { createForecastRun, getForecastRun, listForecastRuns } from "./realtime-client";
import type { ForecastRun, ForecastRunDetail } from "./realtime-types";

const statusText = { PENDING: "等待执行", RUNNING: "正在预测", SUCCEEDED: "预测完成", INSUFFICIENT_DATA: "数据不足", FAILED: "执行失败" };
const reasonText: Record<string, string> = { MINIMUM_HISTORY_DAYS_NOT_MET: "有效历史不足 28 天", RECENT_COMPLETENESS_BELOW_THRESHOLD: "近 7 天完整率低于 95%" };

export function ForecastCenter({ portfolioId }: { portfolioId: string }) {
  const [runs, setRuns] = useState<ForecastRun[]>([]);
  const [details, setDetails] = useState<Record<string, ForecastRunDetail>>({});
  const [date, setDate] = useState(() => tomorrow());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async (signal?: AbortSignal) => {
    if (!portfolioId) return;
    const next = await listForecastRuns(portfolioId, signal);
    setRuns(next);
    const latest = ["LOAD_KW", "PV_POWER_KW"].flatMap(metric => next.find(run => run.metric === metric) ?? []);
    const loaded = await Promise.all(latest.map(run => getForecastRun(run.id, signal)));
    setDetails(Object.fromEntries(loaded.map(item => [item.run.metric, item])));
    setError(null);
  }, [portfolioId]);

  useEffect(() => {
    const controller = new AbortController();
    refresh(controller.signal).catch((reason: Error) => { if (reason.name !== "AbortError") setError(reason.message); });
    return () => controller.abort();
  }, [refresh]);

  const active = useMemo(() => runs.some(run => run.status === "PENDING" || run.status === "RUNNING"), [runs]);
  useEffect(() => {
    if (!active) return;
    const timer = setInterval(() => void refresh(), 1200);
    return () => clearInterval(timer);
  }, [active, refresh]);

  const generate = async () => {
    setBusy(true); setError(null);
    try {
      await Promise.all([createForecastRun(portfolioId, date, "LOAD_KW"), createForecastRun(portfolioId, date, "PV_POWER_KW")]);
      await refresh();
    } catch (reason) { setError(reason instanceof Error ? reason.message : "预测请求失败"); }
    finally { setBusy(false); }
  };

  return <section id="forecasts" className="forecast-center">
    <div className="section-title forecast-title"><div><p className="eyebrow">DAY-AHEAD / 15 MINUTES</p><h2>次日负荷与光伏预测</h2></div>
      <div className="forecast-action"><label>预测日期<input type="date" min={tomorrow()} value={date} onChange={event => setDate(event.target.value)} /></label><button disabled={!portfolioId || busy || active} onClick={() => void generate()}>{busy || active ? "预测执行中…" : "生成次日预测"}</button></div>
    </div>
    {error && <div className="forecast-error" role="alert">{error}</div>}
    <div className="forecast-grid">
      <ForecastCard title="负荷预测" detail={details.LOAD_KW} color="#4ac6ff" />
      <ForecastCard title="光伏预测" detail={details.PV_POWER_KW} color="#f7b955" />
    </div>
  </section>;
}

function ForecastCard({ title, detail, color }: { title: string; detail?: ForecastRunDetail; color: string }) {
  if (!detail) return <article className="forecast-card empty"><h3>{title}</h3><p>尚未生成预测。系统不会用随机曲线填充空状态。</p></article>;
  const { run, forecast } = detail;
  return <article className="forecast-card">
    <header><div><h3>{title}</h3><small>{run.forecast_date} · {run.timezone}</small></div><span className={`forecast-status status-${run.status.toLowerCase()}`}>{statusText[run.status]}</span></header>
    {forecast ? <><ForecastChart values={forecast.points.map(point => point.value)} color={color} /><div className="forecast-facts">
      <Fact label="模型版本" value={forecast.model_version} /><Fact label="数据截止" value={formatDate(run.data_cutoff)} />
      <Fact label="滚动 MAE" value={metric(run.validation_metrics, "mae_kw", " kW")} /><Fact label="滚动 WAPE" value={metric(run.validation_metrics, "wape", "", 100, "%")} />
      <Fact label="历史有效天数" value={metric(run.dataset_report, "valid_history_days", " 天")} /><Fact label="版本" value={`v${forecast.version} · ${forecast.source}`} />
    </div></> : <div className="forecast-empty"><strong>{statusText[run.status]}</strong><p>{run.reasons.map(reason => reasonText[reason] ?? reason).join("；") || run.failure_code || "运行尚未产生终态结果"}</p><small>截止点：{formatDate(run.data_cutoff)}</small></div>}
  </article>;
}

function ForecastChart({ values, color }: { values: number[]; color: string }) {
  const width = 560, height = 180, pad = 16, max = Math.max(...values, 1), min = Math.min(...values, 0), span = Math.max(max - min, 1);
  const path = values.map((value, index) => `${index ? "L" : "M"}${(pad + index / Math.max(values.length - 1, 1) * (width - pad * 2)).toFixed(1)},${(pad + (1 - (value - min) / span) * (height - pad * 2)).toFixed(1)}`).join(" ");
  return <svg className="forecast-chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="15 分钟预测曲线"><path d={path} stroke={color} /></svg>;
}
function Fact({ label, value }: { label: string; value: string }) { return <div><dt>{label}</dt><dd>{value}</dd></div>; }
function metric(data: Record<string, unknown> | null, key: string, suffix: string, multiplier = 1, end = "") { const value = data?.[key]; return typeof value === "number" ? `${(value * multiplier).toFixed(multiplier === 1 ? 2 : 1)}${suffix}${end}` : "—"; }
function formatDate(value: string) { return new Intl.DateTimeFormat("zh-CN", { dateStyle: "short", timeStyle: "medium", hour12: false }).format(new Date(value)); }
function tomorrow() { const value = new Date(); value.setDate(value.getDate() + 1); return value.toISOString().slice(0, 10); }
