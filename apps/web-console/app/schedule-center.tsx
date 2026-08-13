"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { ApiError, createSchedule, decideSchedule, emergencyStopScheduleFromCommand, getForecastRun, getSchedule, getScheduleExecution, listDevices, listForecastRuns, listSchedules, listSites, listTariffs } from "./realtime-client";
import type { CommandDetail, DeviceCommand, DeviceResource, ForecastRunDetail, ScheduleDecisionType, ScheduleDetail, ScheduleExecution, ScheduleRecord, TariffPlan } from "./realtime-types";

const reasonText: Record<string, string> = {
  MULTI_SITE_OPTIMIZATION_UNSUPPORTED: "当前聚合预测无法证明每个站点的并网约束，多站点调度暂不生成候选计划。",
  NO_SCHEDULABLE_BATTERIES: "园区内没有可调度的在线储能。",
  BATTERY_STATE_SET_MISMATCH: "必须为园区内全部在线储能提供初始 SOC。",
  GRID_IMPORT_LIMIT_UNSATISFIABLE: "储能可用功率或电量不足，无法满足并网输入上限。",
  GRID_EXPORT_LIMIT_UNSATISFIABLE: "储能吸收能力不足，无法满足反送功率上限。",
};

const commandStatusText: Record<string, string> = {
  CREATED: "已创建", DISPATCHED: "已下发", ACCEPTED: "设备已接受", EXECUTING: "执行中",
  SUCCEEDED: "执行成功", FAILED: "执行失败", TIMED_OUT: "执行超时", CANCELLED: "已取消",
};

const terminalCommandStatuses = new Set(["SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED"]);
const commandPageLimit = 100;

export function isScheduleApprovable(detail: ScheduleDetail): boolean {
  return detail.schedule.status === "VALIDATED"
    && detail.schedule.feasibility === "FEASIBLE"
    && detail.version !== null
    && !detail.latest_decision;
}

export function isTerminalCommandStatus(status: string): boolean {
  return terminalCommandStatuses.has(status);
}

export function ScheduleCenter({ portfolioId }: { portfolioId: string }) {
  const [forecasts, setForecasts] = useState<ForecastRunDetail[]>([]);
  const [devices, setDevices] = useState<DeviceResource[]>([]);
  const [tariffs, setTariffs] = useState<TariffPlan[]>([]);
  const [schedules, setSchedules] = useState<ScheduleRecord[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [commandOffset, setCommandOffset] = useState(0);
  const [selected, setSelected] = useState<ScheduleDetail | null>(null);
  const [execution, setExecution] = useState<ScheduleExecution | null>(null);
  const [soc, setSoc] = useState<Record<string, number>>({});
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [monitorLoading, setMonitorLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [monitorError, setMonitorError] = useState<string | null>(null);
  const [detailPermissionDenied, setDetailPermissionDenied] = useState(false);
  const [monitorPermissionDenied, setMonitorPermissionDenied] = useState(false);

  const refresh = useCallback(async (signal?: AbortSignal) => {
    if (!portfolioId) {
      setForecasts([]); setDevices([]); setTariffs([]); setSchedules([]); setSelectedId(""); setLoading(false);
      return;
    }
    setLoading(true);
    try {
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
      setSelectedId(current => history.some(item => item.id === current) ? current : history[0]?.id ?? "");
      setError(null);
    } finally {
      if (!signal?.aborted) setLoading(false);
    }
  }, [portfolioId]);

  const loadSelection = useCallback(async (id: string, offset: number, signal?: AbortSignal, silent = false) => {
    if (!silent) {
      setDetailLoading(true); setMonitorLoading(true);
    }
    const results = await Promise.allSettled([
      getSchedule(id, signal), getScheduleExecution(id, offset, commandPageLimit, signal),
    ]);
    if (signal?.aborted) return;
    const [detailResult, executionResult] = results;
    if (detailResult.status === "fulfilled") { setSelected(detailResult.value); setDetailError(null); }
    else if (!isAbort(detailResult.reason)) { setDetailError(errorCopy(detailResult.reason)); }
    if (executionResult.status === "fulfilled") setExecution(executionResult.value);
    const monitorFailures = [executionResult].filter(result => result.status === "rejected") as PromiseRejectedResult[];
    setDetailPermissionDenied(detailResult.status === "rejected" && isPermissionError(detailResult.reason));
    setMonitorPermissionDenied(monitorFailures.some(result => isPermissionError(result.reason)));
    setMonitorError(monitorFailures.length ? monitorFailures.map(result => errorCopy(result.reason)).join("；") : null);
    setDetailLoading(false); setMonitorLoading(false);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    refresh(controller.signal).catch((reason: unknown) => { if (!isAbort(reason)) setError(errorCopy(reason)); });
    return () => controller.abort();
  }, [refresh]);

  useEffect(() => { setCommandOffset(0); }, [portfolioId]);

  useEffect(() => {
    if (!selectedId) { setSelected(null); setExecution(null); return; }
    const controller = new AbortController();
    void loadSelection(selectedId, commandOffset, controller.signal);
    return () => controller.abort();
  }, [selectedId, commandOffset, loadSelection]);

  const shouldPoll = Boolean(selectedId && execution?.commands.some(detail => !isTerminalCommandStatus(detail.command.status)));
  useEffect(() => {
    if (!shouldPoll) return;
    const timer = setInterval(() => void loadSelection(selectedId, commandOffset, undefined, true), 5_000);
    return () => clearInterval(timer);
  }, [commandOffset, loadSelection, selectedId, shouldPoll]);

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
  const canGenerate = Boolean(portfolioId && pair && tariff && devices.length && !busy && !loading);

  const generate = async () => {
    if (!pair || !tariff) return;
    setBusy(true); setError(null);
    try {
      const detail = await createSchedule({ portfolio_id: portfolioId, schedule_date: pair[0],
        load_forecast_version_id: pair[1].LOAD_KW!.forecast!.id, pv_forecast_version_id: pair[1].PV_POWER_KW!.forecast!.id,
        tariff_plan_id: tariff.id, battery_states: devices.map(device => ({ device_id: device.id, initial_soc_pct: soc[device.id] })) });
      setCommandOffset(0); setSelectedId(detail.schedule.id);
      await refresh();
    } catch (reason) { setError(errorCopy(reason)); }
    finally { setBusy(false); }
  };

  return <section id="schedules" className="schedule-center">
    <div className="section-title schedule-title"><div><p className="eyebrow">APPROVAL / EXECUTION EVIDENCE</p><h2>日前优化调度</h2></div><p>候选计划经人工决策后才会生成未来设备命令；页面不预演或伪造执行结果。</p></div>
    {!portfolioId && <div className="schedule-state panel">尚未选择授权资源组合，无法读取调度记录。</div>}
    {error && <div className="forecast-error" role="alert">{error}</div>}
    {loading && portfolioId && <div className="schedule-state panel" role="status">正在读取预测、设备、资费和候选记录…</div>}
    <div className="schedule-builder panel" aria-busy={busy || loading}>
      <div className="schedule-inputs">
        <InputFact label="预测日期" value={pair?.[0] ?? "缺少同日负荷/PV 预测"} />
        <InputFact label="负荷 / PV 版本" value={pair ? `v${pair[1].LOAD_KW!.forecast!.version} / v${pair[1].PV_POWER_KW!.forecast!.version}` : "—"} />
        <InputFact label="电价计划" value={tariff ? `${tariff.name} · v${tariff.version}` : "没有完整覆盖当日的同区时电价"} />
        <InputFact label="算法" value="RULE_BASELINE · 1.0.0" />
      </div>
      <div className="soc-inputs">{devices.length ? devices.map(device => <label key={device.id}>{device.name}<span>候选初始 SOC</span><input type="number" min="0" max="100" step="0.1" value={soc[device.id] ?? 50} onChange={event => setSoc(current => ({ ...current, [device.id]: Number(event.target.value) }))} /><b>%</b></label>) : <p>{loading ? "正在读取可调度储能…" : "没有发现具备容量能力的在线储能。"}</p>}</div>
      <button className="schedule-generate" disabled={!canGenerate} onClick={() => void generate()}>{busy ? "约束求解中…" : "生成并校验候选计划"}</button>
    </div>
    <div className="schedule-layout">
      <div className="schedule-history panel"><h3>候选记录</h3>{loading && !schedules.length ? <p className="muted">正在读取候选记录…</p> : schedules.length ? schedules.map(item => <button key={item.id} className={selectedId === item.id ? "selected" : ""} onClick={() => { setCommandOffset(0); setSelectedId(item.id); }}><span><b>{item.schedule_date} · {item.current_version ? `v${item.current_version}` : "无版本"}</b><small>{formatDateTime(item.created_at)}</small></span><em className={`schedule-${item.feasibility.toLowerCase()}`}>{statusLabel(item.feasibility, { FEASIBLE: "约束通过", INFEASIBLE: "不可行" })}</em></button>) : <p className="muted">尚无调度候选。</p>}</div>
      <ScheduleResult detail={selected} loading={detailLoading} error={detailError} detailPermissionDenied={detailPermissionDenied} monitorPermissionDenied={monitorPermissionDenied}
        execution={execution} monitorLoading={monitorLoading} monitorError={monitorError}
        onPageChange={setCommandOffset} onRefresh={() => selectedId ? loadSelection(selectedId, commandOffset) : undefined} />
    </div>
  </section>;
}

function ScheduleResult({ detail, loading, error, detailPermissionDenied, monitorPermissionDenied, execution, monitorLoading, monitorError, onPageChange, onRefresh }: {
  detail: ScheduleDetail | null; loading: boolean; error: string | null; detailPermissionDenied: boolean; monitorPermissionDenied: boolean;
  execution: ScheduleExecution | null; monitorLoading: boolean; monitorError: string | null;
  onPageChange: (offset: number) => void; onRefresh: () => void | Promise<void>;
}) {
  if (loading) return <div className="schedule-result panel empty-state" role="status">正在读取候选版本、冻结输入与执行状态…</div>;
  if (error) return <div className="schedule-result panel"><div className="execution-error" role="alert"><strong>{detailPermissionDenied ? "没有查看调度详情的权限" : "候选详情读取失败"}</strong><p>{error}</p><button onClick={() => void onRefresh()}>重试</button></div></div>;
  if (!detail) return <div className="schedule-result panel empty-state">选择或生成候选后，将显示冻结输入、可行性、审批与真实命令回执。</div>;
  const { version } = detail;
  return <div className="schedule-result panel">
    <header><div><span className={detail.schedule.feasibility === "FEASIBLE" ? "result-feasible" : "result-infeasible"}>{statusLabel(detail.schedule.feasibility, { FEASIBLE: "FEASIBLE / 约束通过", INFEASIBLE: "INFEASIBLE / 不可行" })}</span><h3>{detail.schedule.schedule_date} · {version ? `v${version.version}` : "未生成版本"}</h3></div>{version ? <code title={version.content_sha256}>{version.content_sha256.slice(0, 12)}…</code> : <code>NO VERSION</code>}</header>
    {!version ? <div className="infeasible-reason"><strong>没有可执行版本</strong><p>{detail.schedule.reasons.length ? detail.schedule.reasons.map(reason => reasonText[reason] ?? reason).join("；") : "接口未返回不可行原因。"}</p><small>硬约束没有被自动放宽，此候选不能批准。</small></div> : <>
      <div className="schedule-kpis"><Metric label="基线成本" value={`${version.summary.baselineCost.toFixed(2)} CNY`} /><Metric label="目标成本" value={`${version.summary.objectiveCost.toFixed(2)} CNY`} /><Metric label="预计节省" value={`${version.summary.savings.toFixed(2)} CNY`} /><Metric label="峰值变化" value={`${version.summary.baselinePeakKw.toFixed(1)} → ${version.summary.plannedPeakKw.toFixed(1)} kW`} /></div>
      <ScheduleChart detail={detail} />
    </>}
    <FrozenInputs detail={detail} />
    <DecisionPanel key={detail.schedule.id} detail={detail} onRefresh={onRefresh} />
    <ExecutionMonitor scheduleId={detail.schedule.id} execution={execution} loading={monitorLoading} error={monitorError} permissionDenied={monitorPermissionDenied} onPageChange={onPageChange} onRefresh={onRefresh} />
  </div>;
}

function FrozenInputs({ detail }: { detail: ScheduleDetail }) {
  const version = detail.version;
  return <section className="frozen-evidence" aria-label="已冻结调度输入">
    <div className="subsection-heading"><div><p className="eyebrow">IMMUTABLE INPUTS</p><h4>已冻结输入与版本证据</h4></div><span>审批引用下列不可变版本</span></div>
    {version ? <div className="version-proof">
      <InputFact label="调度版本" value={`v${version.version}`} /><InputFact label="负荷预测版本" value={version.load_forecast_version_id} />
      <InputFact label="光伏预测版本" value={version.pv_forecast_version_id} /><InputFact label="电价版本" value={version.tariff_plan_id} />
      <InputFact label="设备配置快照" value={version.device_config_snapshot_id} /><InputFact label="内容哈希" value={version.content_sha256} />
      <InputFact label="算法版本" value={`${version.algorithm_name} · ${version.algorithm_version}`} /><InputFact label="冻结时间" value={formatDateTime(version.created_at)} />
    </div> : <p className="evidence-missing">不可行候选没有生成冻结执行版本。</p>}
    <details className="raw-evidence"><summary>查看接口返回的候选输入</summary>{Object.keys(detail.schedule.input).length ? <pre>{JSON.stringify(detail.schedule.input, null, 2)}</pre> : <p>接口未返回候选输入明细。</p>}</details>
  </section>;
}

function DecisionPanel({ detail, onRefresh }: { detail: ScheduleDetail; onRefresh: () => void | Promise<void> }) {
  const [reason, setReason] = useState("");
  const [acknowledged, setAcknowledged] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [permissionDenied, setPermissionDenied] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [submittedDecision, setSubmittedDecision] = useState(detail.latest_decision);
  const finalDecision = detail.latest_decision ?? submittedDecision;
  const canApprove = isScheduleApprovable(detail);

  const submit = async (decision: ScheduleDecisionType) => {
    const normalizedReason = reason.trim();
    if (!normalizedReason || (decision === "APPROVE" && (!canApprove || !acknowledged)) || finalDecision) return;
    setBusy(true); setError(null); setNotice(null); setPermissionDenied(false);
    try {
      const decisionRecord = await decideSchedule(detail.schedule.id, decision, normalizedReason);
      setSubmittedDecision(decisionRecord);
      setNotice(decision === "APPROVE" ? "批准已提交，正在读取生成的未来设备命令。" : "拒绝已提交，正在刷新决策记录。");
      setReason(""); setAcknowledged(false); await onRefresh();
    } catch (reasonValue) { setPermissionDenied(isPermissionError(reasonValue)); setError(errorCopy(reasonValue)); }
    finally { setBusy(false); }
  };

  return <section className="decision-panel" aria-label="调度审批">
    <div className="subsection-heading"><div><p className="eyebrow">HUMAN DECISION</p><h4>审批决策</h4></div>{finalDecision && <span className={`decision-${finalDecision.decision.toLowerCase()}`}>{finalDecision.decision === "APPROVE" ? "已批准" : "已拒绝"}</span>}</div>
    {permissionDenied && <p className="permission-state" role="alert">服务端拒绝了当前身份的审批操作；页面不会绕过权限结果。</p>}
    {finalDecision ? <div className="decision-record"><strong>{finalDecision.decision === "APPROVE" ? "批准" : "拒绝"} · 终态决策</strong><p>{finalDecision.reason}</p><small>{finalDecision.actor_id ?? "未知操作者"} · {formatDateTime(finalDecision.decided_at)}{finalDecision.decision === "APPROVE" ? ` · 生成 ${finalDecision.command_count} 条命令` : ""}</small></div> : <form onSubmit={event => { event.preventDefault(); const submitter = (event.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null; void submit(submitter?.value === "APPROVE" ? "APPROVE" : "REJECT"); }}>
      <label htmlFor={`schedule-reason-${detail.schedule.id}`}>决策原因（必填）</label>
      <textarea id={`schedule-reason-${detail.schedule.id}`} required minLength={2} value={reason} onChange={event => setReason(event.target.value)} placeholder="记录业务依据，写入审计链路" />
      <label className="command-warning"><input type="checkbox" checked={acknowledged} onChange={event => setAcknowledged(event.target.checked)} /><span><strong>批准会生成未来设备命令</strong>这些命令将按计划时段面向真实设备执行；批准不是仅保存计划。</span></label>
      {!canApprove && <p className="approval-gate">批准已锁定：仅状态为 VALIDATED、可行性为 FEASIBLE 且存在冻结版本的未决候选可批准。</p>}
      {error && <p className="inline-error" role="alert">{error}</p>}{notice && <p className="inline-notice" role="status">{notice}</p>}
      <div className="decision-actions"><button type="submit" name="decision" value="REJECT" className="danger" disabled={busy || !reason.trim()}>{busy ? "提交中…" : "拒绝候选"}</button><button type="submit" name="decision" value="APPROVE" className="primary" disabled={busy || !reason.trim() || !canApprove || !acknowledged}>{busy ? "提交中…" : "批准并生成设备命令"}</button></div>
    </form>}
  </section>;
}

function ExecutionMonitor({ scheduleId, execution, loading, error, permissionDenied, onPageChange, onRefresh }: { scheduleId: string; execution: ScheduleExecution | null; loading: boolean; error: string | null; permissionDenied: boolean; onPageChange: (offset: number) => void; onRefresh: () => void | Promise<void> }) {
  const visibleDetails = execution?.commands ?? null;
  const summary = execution ? summarizeExecution(execution) : null;
  const rangeStart = execution && execution.command_total > 0 ? execution.command_offset + 1 : 0;
  const rangeEnd = execution ? Math.min(execution.command_offset + execution.commands.length, execution.command_total) : 0;
  const hasPrevious = Boolean(execution && execution.command_offset > 0);
  const hasNext = Boolean(execution && execution.command_offset + execution.command_limit < execution.command_total);
  return <section className="execution-monitor" aria-label="设备命令执行监控" aria-busy={loading}>
    <div className="subsection-heading"><div><p className="eyebrow">COMMAND EXECUTION</p><h4>设备命令与执行回执</h4></div><button className="refresh-monitor" onClick={() => void onRefresh()} disabled={loading}>{loading ? "同步中…" : "刷新"}</button></div>
    {summary ? <div className="execution-summary">
      <InputFact label="执行状态" value={summary.label} />
      <InputFact label="本页终态 / 本页命令" value={`${summary.terminal} / ${summary.pageTotal}`} />
      <InputFact label="本页成功 / 失败 / 超时 / 取消" value={`${summary.succeeded} / ${summary.failed} / ${summary.timedOut} / ${summary.cancelled}`} />
      <InputFact label="当前范围 / 命令总数" value={`${rangeStart}–${rangeEnd} / ${execution?.command_total ?? 0}`} />
      <InputFact label="决定 / 审计事实" value={`${execution?.decision ? (execution.decision.decision === "APPROVE" ? "已批准" : "已拒绝") : "无决定"} / ${execution?.audit.length ?? 0}`} />
    </div> : !loading && !error ? <p className="execution-empty">尚无执行摘要；计划未批准或命令尚未生成时这是正常状态。</p> : null}
    {permissionDenied && <p className="permission-state" role="alert">当前身份无权读取命令或执行摘要。</p>}
    {error && <div className="execution-error" role="alert"><strong>执行证据读取不完整</strong><p>{error}</p></div>}
    {loading && visibleDetails === null ? <p className="execution-empty" role="status">正在读取当前页命令状态与设备回执…</p> : visibleDetails?.length === 0 ? <p className="execution-empty">{execution?.command_total ? `当前页没有命令；总计 ${execution.command_total} 条，请返回上一页。` : `调度 ${shortId(scheduleId)} 当前没有设备命令，页面不会生成占位记录。`}</p> : null}
    {visibleDetails?.length ? <div className="command-list">{visibleDetails.map(detail => <CommandCard key={detail.command.id} detail={detail} onRefresh={onRefresh} />)}</div> : null}
    {execution && execution.command_total > 0 && <nav className="command-pagination" aria-label="命令分页">
      <button type="button" disabled={loading || !hasPrevious} onClick={() => onPageChange(Math.max(0, execution.command_offset - execution.command_limit))}>上一页</button>
      <span>显示 {rangeStart}–{rangeEnd}，共 {execution.command_total} 条 · 每页最多 {execution.command_limit} 条</span>
      <button type="button" disabled={loading || !hasNext} onClick={() => onPageChange(execution.command_offset + execution.command_limit)}>下一页</button>
    </nav>}
  </section>;
}

function CommandCard({ detail, onRefresh }: { detail: CommandDetail; onRefresh: () => void | Promise<void> }) {
  const { command } = detail;
  const terminal = isTerminalCommandStatus(command.status);
  const latestAck = [...detail.events].reverse().find(event => event.event_type === "ACK") ?? null;
  return <article className={`command-card ${terminal ? "terminal" : "active"}`}>
    <header><div><span className={`command-status status-${command.status.toLowerCase()}`}>{statusLabel(command.status, commandStatusText)}</span><strong>{command.device_id}</strong><small>{commandActionLabel(command)} · {shortId(command.id)}</small></div><b className={terminal ? "terminal-marker" : "active-marker"}>{terminal ? "终态" : "非终态"}</b></header>
    <dl className="command-facts"><div><dt>请求窗口</dt><dd>{formatDateTime(command.not_before)} — {formatDateTime(command.expires_at)}</dd></div><div><dt>设备</dt><dd>{command.device_id}</dd></div><div><dt>请求目标</dt><dd>{formatParameters(command.parameters)}</dd></div><div><dt>更新时间</dt><dd>{formatDateTime(command.updated_at)}</dd></div></dl>
    <div className="command-outcome">
      <div><span>设备回执</span>{latestAck ? <><strong>{statusLabel(latestAck.reported_status, commandStatusText)}</strong><p>{latestAck.applied ? "该回执已推进命令主状态。" : "该回执未应用：可能重复、乱序或晚于终态。"}</p>{latestAck.message && <p>{latestAck.message}</p>}<small>{formatDateTime(latestAck.received_at)}</small><details><summary>回执事实</summary><pre>{JSON.stringify(latestAck, null, 2)}</pre></details></> : <p>尚无设备回执；下发或目标值不代表执行成功。</p>}</div>
      <div><span>设备实际值</span>{command.actual == null ? <p>—（接口未返回设备实际值）</p> : <><strong>{formatEvidenceValue(command.actual)}</strong><p>actual 来自设备回执，与请求目标分开保存；主状态仍以命令状态为准。</p></>}</div>
      <div><span>失败 / 超时 / 取消</span>{command.last_reason_code || command.last_message ? <><strong>{command.last_reason_code ?? "未提供原因码"}</strong><p>{command.last_message ?? "接口未返回原因说明。"}</p></> : command.status === "TIMED_OUT" ? <><strong>执行超时</strong><p>在期限内没有收到可接受终态，结果未知。</p></> : command.status === "CANCELLED" ? <><strong>命令已取消</strong><p>控制面取消不等于设备已停止。</p></> : <p>{terminal ? "该终态没有失败、超时或取消说明。" : "尚无失败、超时或取消事件。"}</p>}<small>{formatDateTime(command.terminal_at)}</small></div>
      <div><span>下发尝试</span>{detail.attempts.length ? <><strong>{detail.attempts.length} 次</strong><p>最近回执：{detail.attempts.at(-1)?.latest_ack_status ?? "—"}</p><small>{formatDateTime(detail.attempts.at(-1)?.dispatched_at)}</small></> : <p>尚无持久化下发尝试。</p>}</div>
    </div>
    {!terminal && command.action === "SET_POWER" && <EmergencyStopForm command={command} onRefresh={onRefresh} />}
  </article>;
}

function EmergencyStopForm({ command, onRefresh }: { command: DeviceCommand; onRefresh: () => void | Promise<void> }) {
  const [reason, setReason] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [permissionDenied, setPermissionDenied] = useState(false);
  const submit = async () => {
    if (!reason.trim() || !confirmed) return;
    setBusy(true); setError(null); setPermissionDenied(false);
    try { await emergencyStopScheduleFromCommand(command.id, reason.trim()); setReason(""); setConfirmed(false); await onRefresh(); }
    catch (reasonValue) { setPermissionDenied(isPermissionError(reasonValue)); setError(errorCopy(reasonValue)); }
    finally { setBusy(false); }
  };
  return <form className="stop-command" onSubmit={event => { event.preventDefault(); void submit(); }}>
    <label htmlFor={`stop-${command.id}`}>计划级紧急停止原因（必填）</label>
    <div><input id={`stop-${command.id}`} required minLength={2} value={reason} onChange={event => setReason(event.target.value)} placeholder="说明紧急停止整个计划的依据" /><button type="submit" disabled={busy || !reason.trim() || !confirmed}>{busy ? "STOP 请求中…" : "紧急停止整个计划"}</button></div>
    <p className="stop-impact"><strong>高影响操作：</strong>将取消本计划所有尚未下发的普通命令，为全部参与设备创建独立 STOP，并把计划标记为 CANCELLED。STOP 请求不等于设备已停止，仍需逐设备核对回执。</p>
    <label className="command-warning"><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} /><span><strong>二次确认</strong>我确认该操作影响整个计划，而不只是当前命令。</span></label>
    {permissionDenied && <p role="alert">服务端拒绝了当前身份的紧急停止操作。</p>}{error && <p role="alert">{error}</p>}
  </form>;
}

function ScheduleChart({ detail }: { detail: ScheduleDetail }) {
  const values = detail.version!.intervals.flatMap(point => [point.baseline_grid_kw, point.planned_grid_kw]);
  if (!values.length) return <div className="schedule-chart chart-empty"><strong>版本没有计划时段</strong><p>接口未返回可绘制的基线或计划功率点。</p></div>;
  const width = 800, height = 210, pad = 18, min = Math.min(...values, 0), max = Math.max(...values, 1), span = Math.max(max - min, 1);
  const divisor = Math.max(detail.version!.intervals.length - 1, 1);
  const path = (key: "baseline_grid_kw" | "planned_grid_kw") => detail.version!.intervals.map((point, index) => `${index ? "L" : "M"}${pad + index / divisor * (width - 2 * pad)},${pad + (1 - (point[key] - min) / span) * (height - 2 * pad)}`).join(" ");
  return <div className="schedule-chart"><svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="基线与计划并网功率曲线"><path d={path("baseline_grid_kw")} className="baseline" /><path d={path("planned_grid_kw")} className="planned" /></svg><div><span><i className="baseline" />基线并网功率</span><span><i className="planned" />计划并网功率</span></div></div>;
}

function summarizeExecution(execution: ScheduleExecution) {
  const commands = execution.commands.map(detail => detail.command);
  const terminal = commands.filter(command => isTerminalCommandStatus(command.status)).length;
  const count = (status: DeviceCommand["status"]) => commands.filter(command => command.status === status).length;
  let label = execution.decision?.decision === "REJECT" ? "已拒绝，未执行" : "尚未开始";
  if (commands.length && terminal === commands.length) {
    label = count("SUCCEEDED") === commands.length ? "本页命令均报告成功" : count("CANCELLED") === commands.length ? "本页命令均已取消" : "本页已结束，存在非成功终态";
  } else if (commands.some(command => command.status !== "CREATED")) label = "执行中";
  else if (commands.length) label = "已排期，等待下发";
  else if (execution.decision?.decision === "APPROVE") label = "已批准，等待命令物化";
  return { label, pageTotal: commands.length, terminal, succeeded: count("SUCCEEDED"), failed: count("FAILED"), timedOut: count("TIMED_OUT"), cancelled: count("CANCELLED") };
}
function commandActionLabel(command: DeviceCommand): string {
  if (command.action === "SET_POWER") return "功率设定";
  if (command.action === "STOP") return command.parameters["reason"] === "SCHEDULE_END" ? "计划末尾安全 STOP" : "计划紧急 STOP";
  return `未知动作：${command.action}`;
}
function formatParameters(parameters: Record<string, unknown>): string {
  if (!Object.keys(parameters).length) return "—（接口未返回请求参数）";
  return Object.entries(parameters).map(([key, value]) => `${key}: ${formatEvidenceValue(value)}`).join("；");
}
function formatEvidenceValue(value: unknown): string { return typeof value === "string" || typeof value === "number" || typeof value === "boolean" ? String(value) : JSON.stringify(value) ?? "null"; }
function initialSoc(device: DeviceResource) { return device.capabilities.find(cap => cap.capability === "SOC_RANGE_PCT")?.fallback_value ?? 50; }
function InputFact({ label, value }: { label: string; value: string }) { return <div><span>{label}</span><b>{value}</b></div>; }
function Metric({ label, value }: { label: string; value: string }) { return <div><span>{label}</span><b>{value}</b></div>; }
function shortId(value: string) { return value.length > 12 ? `${value.slice(0, 8)}…` : value; }
function formatDateTime(value: string | null | undefined) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? `未知时间：${value}` : new Intl.DateTimeFormat("zh-CN", { dateStyle: "short", timeStyle: "medium", hour12: false }).format(date);
}
function statusLabel(status: string, labels: Record<string, string>) { return labels[status] ?? `未知状态：${status || "空值"}`; }
function isAbort(reason: unknown) { return reason instanceof Error && reason.name === "AbortError"; }
function isPermissionError(reason: unknown) { return reason instanceof ApiError && (reason.status === 401 || reason.status === 403); }
function errorCopy(reason: unknown) { return reason instanceof Error ? reason.message : "请求失败，接口未返回可识别错误。"; }
