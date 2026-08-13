"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { createSchedule, getForecastRun, getSchedule, listDevices, listForecastRuns, listSchedules, listSites, listTariffs } from "./realtime-client";
import type { DeviceResource, ForecastRunDetail, ScheduleDetail, ScheduleRecord, TariffPlan } from "./realtime-types";

const reasonText: Record<string, string> = {
  MULTI_SITE_OPTIMIZATION_UNSUPPORTED: "当前聚合预测无法证明每个站点的并网约束，多站点调度暂不生成候选计划。",
  NO_SCHEDULABLE_BATTERIES: "园区内没有可调度的在线储能。",
  BATTERY_STATE_SET_MISMATCH: "必须为园区内全部在线储能提供初始 SOC。",
  GRID_IMPORT_LIMIT_UNSATISFIABLE: "储能可用功率或电量不足，无法满足并网输入上限。",
  GRID_EXPORT_LIMIT_UNSATISFIABLE: "储能吸收能力不足，无法满足反送功率上限。",
};

export function ScheduleCenter({ portfolioId }: { portfolioId: string }) {
  const [forecasts, setForecasts] = useState<ForecastRunDetail[]>([]);
  const [devices, setDevices] = useState<DeviceResource[]>([]);
  const [tariffs, setTariffs] = useState<TariffPlan[]>([]);
  const [schedules, setSchedules] = useState<ScheduleRecord[]>([]);
  const [selected, setSelected] = useState<ScheduleDetail | null>(null);
  const [soc, setSoc] = useState<Record<string, number>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async (signal?: AbortSignal) => {
    if (!portfolioId) return;
    const [runs, allDevices, plans, sites, history] = await Promise.all([
      listForecastRuns(portfolioId, signal), listDevices(signal), listTariffs(signal),
      listSites(portfolioId, signal), listSchedules(portfolioId, signal),
    ]);
    const siteIds = new Set(sites.map(site => site.id));
    const batteries = allDevices.filter(device => siteIds.has(device.site_id) && device.status === "ACTIVE" && device.capabilities.some(cap => cap.capability === "ENERGY_CAPACITY_KWH"));
    const completed = runs.filter(run => run.status === "SUCCEEDED" && run.forecast_version_id);
    const details = await Promise.all(completed.map(run => getForecastRun(run.id, signal)));
    setForecasts(details); setDevices(batteries); setTariffs(plans); setSchedules(history);
    setSoc(current => Object.fromEntries(batteries.map(device => [device.id, current[device.id] ?? initialSoc(device)])));
    if (history[0]) setSelected(await getSchedule(history[0].id, signal)); else setSelected(null);
    setError(null);
  }, [portfolioId]);

  useEffect(() => {
    const controller = new AbortController();
    refresh(controller.signal).catch((reason: Error) => { if (reason.name !== "AbortError") setError(reason.message); });
    return () => controller.abort();
  }, [refresh]);

  const pair = useMemo(() => {
    const grouped = new Map<string, Partial<Record<"LOAD_KW" | "PV_POWER_KW", ForecastRunDetail>>>();
    forecasts.forEach(detail => grouped.set(detail.run.forecast_date, { ...grouped.get(detail.run.forecast_date), [detail.run.metric]: detail }));
    return [...grouped.entries()].filter(([, value]) => value.LOAD_KW?.forecast && value.PV_POWER_KW?.forecast).sort(([a], [b]) => b.localeCompare(a))[0] ?? null;
  }, [forecasts]);
  const tariff = useMemo(() => pair ? tariffs.find(plan => {
    const points = pair[1].LOAD_KW?.forecast?.points;
    return plan.timezone === pair[1].LOAD_KW?.run.timezone && points?.length
      && new Date(plan.valid_from) <= new Date(points[0].interval_start)
      && new Date(plan.valid_to) >= new Date(points[points.length - 1].interval_end);
  }) : undefined, [pair, tariffs]);
  const canGenerate = Boolean(pair && tariff && devices.length && !busy);

  const generate = async () => {
    if (!pair || !tariff) return;
    setBusy(true); setError(null);
    try {
      const detail = await createSchedule({ portfolio_id: portfolioId, schedule_date: pair[0],
        load_forecast_version_id: pair[1].LOAD_KW!.forecast!.id, pv_forecast_version_id: pair[1].PV_POWER_KW!.forecast!.id,
        tariff_plan_id: tariff.id, battery_states: devices.map(device => ({ device_id: device.id, initial_soc_pct: soc[device.id] })) });
      setSelected(detail); await refresh(); setSelected(detail);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "调度计算失败"); }
    finally { setBusy(false); }
  };

  return <section id="schedules" className="schedule-center">
    <div className="section-title schedule-title"><div><p className="eyebrow">RULE BASELINE / HARD CONSTRAINTS</p><h2>日前优化调度</h2></div><p>仅生成经硬约束复核的候选版本；审批与设备下发属于下一阶段。</p></div>
    {error && <div className="forecast-error" role="alert">{error}</div>}
    <div className="schedule-builder panel">
      <div className="schedule-inputs">
        <InputFact label="预测日期" value={pair?.[0] ?? "缺少同日负荷/PV 预测"} />
        <InputFact label="负荷 / PV 版本" value={pair ? `v${pair[1].LOAD_KW!.forecast!.version} / v${pair[1].PV_POWER_KW!.forecast!.version}` : "—"} />
        <InputFact label="电价计划" value={tariff ? `${tariff.name} · v${tariff.version}` : "没有完整覆盖当日的同区时电价"} />
        <InputFact label="算法" value="RULE_BASELINE · 1.0.0" />
      </div>
      <div className="soc-inputs">{devices.length ? devices.map(device => <label key={device.id}>{device.name}<span>初始 SOC</span><input type="number" min="0" max="100" step="0.1" value={soc[device.id] ?? 50} onChange={event => setSoc(current => ({ ...current, [device.id]: Number(event.target.value) }))} /><b>%</b></label>) : <p>没有发现具备容量能力的在线储能。</p>}</div>
      <button className="schedule-generate" disabled={!canGenerate} onClick={() => void generate()}>{busy ? "约束求解中…" : "生成并校验候选计划"}</button>
    </div>
    <div className="schedule-layout">
      <div className="schedule-history panel"><h3>候选记录</h3>{schedules.length ? schedules.map(item => <button key={item.id} className={selected?.schedule.id === item.id ? "selected" : ""} onClick={() => void getSchedule(item.id).then(setSelected)}><span><b>{item.schedule_date}</b><small>{new Date(item.created_at).toLocaleString("zh-CN")}</small></span><em className={`schedule-${item.feasibility.toLowerCase()}`}>{item.feasibility === "FEASIBLE" ? "约束通过" : "不可行"}</em></button>) : <p className="muted">尚无调度候选。</p>}</div>
      <ScheduleResult detail={selected} />
    </div>
  </section>;
}

function ScheduleResult({ detail }: { detail: ScheduleDetail | null }) {
  if (!detail) return <div className="schedule-result panel empty-state">生成候选后，将显示成本、峰值、SOC 和不可变输入版本。</div>;
  if (!detail.version) return <div className="schedule-result panel infeasible"><span>INFEASIBLE</span><h3>没有生成可审批版本</h3><p>{detail.schedule.reasons.map(reason => reasonText[reason] ?? reason).join("；")}</p><small>硬约束没有被自动放宽。</small></div>;
  const { version } = detail, summary = version.summary;
  return <div className="schedule-result panel"><header><div><span>VALIDATED CANDIDATE</span><h3>{detail.schedule.schedule_date} · v{version.version}</h3></div><code>{version.content_sha256.slice(0, 12)}…</code></header>
    <div className="schedule-kpis"><Metric label="基线成本" value={`${summary.baselineCost.toFixed(2)} CNY`} /><Metric label="目标成本" value={`${summary.objectiveCost.toFixed(2)} CNY`} /><Metric label="预计节省" value={`${summary.savings.toFixed(2)} CNY`} /><Metric label="峰值变化" value={`${summary.baselinePeakKw.toFixed(1)} → ${summary.plannedPeakKw.toFixed(1)} kW`} /></div>
    <ScheduleChart detail={detail} />
    <div className="version-proof"><span>负荷 {version.load_forecast_version_id.slice(0, 8)}</span><span>光伏 {version.pv_forecast_version_id.slice(0, 8)}</span><span>电价 {version.tariff_plan_id.slice(0, 8)}</span><span>配置快照 {version.device_config_snapshot_id.slice(0, 8)}</span><span>等效循环 {summary.equivalentCycles.toFixed(3)}</span></div>
  </div>;
}

function ScheduleChart({ detail }: { detail: ScheduleDetail }) {
  const values = detail.version!.intervals.flatMap(point => [point.baseline_grid_kw, point.planned_grid_kw]);
  const width = 800, height = 210, pad = 18, min = Math.min(...values, 0), max = Math.max(...values, 1), span = Math.max(max - min, 1);
  const path = (key: "baseline_grid_kw" | "planned_grid_kw") => detail.version!.intervals.map((point, index) => `${index ? "L" : "M"}${pad + index / 95 * (width - 2 * pad)},${pad + (1 - (point[key] - min) / span) * (height - 2 * pad)}`).join(" ");
  return <div className="schedule-chart"><svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="基线与计划并网功率曲线"><path d={path("baseline_grid_kw")} className="baseline" /><path d={path("planned_grid_kw")} className="planned" /></svg><div><span><i className="baseline" />基线并网功率</span><span><i className="planned" />计划并网功率</span></div></div>;
}

function initialSoc(device: DeviceResource) { return device.capabilities.find(cap => cap.capability === "SOC_RANGE_PCT")?.fallback_value ?? 50; }
function InputFact({ label, value }: { label: string; value: string }) { return <div><span>{label}</span><b>{value}</b></div>; }
function Metric({ label, value }: { label: string; value: string }) { return <div><span>{label}</span><b>{value}</b></div>; }
