"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { acknowledgeAlarm, closeAlarm, getAlarmCoverage, getAlarmDetail, listAlarms, noteAlarm } from "./realtime-client";
import type { Alarm, AlarmCoverage, AlarmDetail, AlarmState } from "./realtime-types";

const stateText: Record<AlarmState, string> = { OPEN: "待确认", ACKNOWLEDGED: "已确认", RECOVERED: "已恢复", CLOSED: "已关闭" };

export function AlarmCenter({ portfolioId }: { portfolioId: string }) {
  const [alarms, setAlarms] = useState<Alarm[]>([]); const [coverage, setCoverage] = useState<AlarmCoverage | null>(null);
  const [selected, setSelected] = useState<string | null>(null); const [detail, setDetail] = useState<AlarmDetail | null>(null);
  const [state, setState] = useState<AlarmState | "ACTIVE" | "ALL">("ACTIVE"); const [severity, setSeverity] = useState("ALL");
  const [loading, setLoading] = useState(true); const [error, setError] = useState<string | null>(null); const [reason, setReason] = useState("");

  const refresh = useCallback(async (signal?: AbortSignal) => {
    if (!portfolioId) return;
    const [items, nextCoverage] = await Promise.all([listAlarms(portfolioId, signal), getAlarmCoverage(signal)]);
    setAlarms(items); setCoverage(nextCoverage); setSelected(current => current ?? items[0]?.id ?? null); setError(null);
  }, [portfolioId]);
  useEffect(() => { const controller = new AbortController(); setLoading(true); refresh(controller.signal).catch((e: Error) => setError(e.message)).finally(() => setLoading(false)); return () => controller.abort(); }, [refresh]);
  useEffect(() => { const listener = () => void refresh(); window.addEventListener("vpp:alarm-event", listener); return () => window.removeEventListener("vpp:alarm-event", listener); }, [refresh]);
  useEffect(() => { if (!selected) { setDetail(null); return; } const c = new AbortController(); getAlarmDetail(selected, c.signal).then(setDetail).catch((e: Error) => setError(e.message)); return () => c.abort(); }, [selected, alarms]);

  const visible = useMemo(() => alarms.filter(alarm => {
    const stateMatch = state === "ALL" || (state === "ACTIVE" ? alarm.state !== "CLOSED" : alarm.state === state);
    return stateMatch && (severity === "ALL" || alarm.severity === severity);
  }), [alarms, severity, state]);
  const act = async (kind: "ack" | "note" | "close") => {
    if (!selected || !reason.trim()) return;
    try { if (kind === "ack") await acknowledgeAlarm(selected, reason); else if (kind === "note") await noteAlarm(selected, reason); else await closeAlarm(selected, reason); setReason(""); await refresh(); }
    catch (e) { setError((e as Error).message); }
  };

  return <section id="alarms" className="alarm-center">
    <div className="section-title alarm-title"><div><p className="eyebrow">ALARM OPERATIONS / T07</p><h2>告警中心</h2></div><p>恢复不代表确认；人工动作写入不可变时间线与审计。</p></div>
    {coverage?.status === "PARTIAL" && <div className="coverage-warning" role="status"><strong>规则覆盖不足</strong><span>{coverage.evaluated_rules}/{coverage.enabled_rules} 条启用规则可执行；未接入：{coverage.unsupported_rules.join("、")}</span></div>}
    <div className="alarm-toolbar"><div><label>状态<select value={state} onChange={e => setState(e.target.value as typeof state)}><option value="ACTIVE">活动告警</option><option value="OPEN">待确认</option><option value="ACKNOWLEDGED">已确认</option><option value="RECOVERED">已恢复</option><option value="CLOSED">已关闭</option><option value="ALL">全部</option></select></label><label>严重度<select value={severity} onChange={e => setSeverity(e.target.value)}><option value="ALL">全部</option><option value="CRITICAL">CRITICAL</option><option value="MAJOR">MAJOR</option><option value="WARNING">WARNING</option><option value="INFO">INFO</option></select></label></div><span>{visible.length} 条结果</span></div>
    {error && <div className="error-banner" role="alert"><div><strong>告警同步失败</strong><p>{error}。页面不会乐观修改状态。</p></div><button onClick={() => void refresh()}>重试</button></div>}
    <div className="alarm-layout">
      <div className="alarm-list" aria-label="告警列表">{loading ? <div className="panel empty-state">正在读取告警事实…</div> : visible.length === 0 ? <div className="panel empty-state"><strong>当前筛选无告警</strong><p>这表示没有匹配的持久化告警实例。</p></div> : visible.map(alarm => <button className={`alarm-row ${selected === alarm.id ? "selected" : ""}`} key={alarm.id} onClick={() => setSelected(alarm.id)}>
        <span className={`severity-dot severity-${alarm.severity.toLowerCase()}`} /><span><b>{alarm.rule_name}</b><small>{alarm.object_name} · {alarm.external_code}</small></span><span className={`alarm-state state-${alarm.state.toLowerCase()}`}>{stateText[alarm.state]}</span><span className="alarm-time"><b>×{alarm.occurrence_count}</b><small>{formatDate(alarm.last_occurred_at)}</small></span>
      </button>)}</div>
      <aside className="alarm-detail" aria-label="告警详情">{detail ? <>
        <div className="detail-head"><div><span className={`severity-label severity-${detail.alarm.severity.toLowerCase()}`}>{detail.alarm.severity}</span><h3>{detail.alarm.rule_name}</h3><p>{detail.alarm.object_name} · {detail.alarm.external_code}</p></div><span className={`alarm-state state-${detail.alarm.state.toLowerCase()}`}>{stateText[detail.alarm.state]}</span></div>
        <dl className="alarm-facts"><div><dt>首次发生</dt><dd>{formatDate(detail.alarm.first_occurred_at)}</dd></div><div><dt>最近发生</dt><dd>{formatDate(detail.alarm.last_occurred_at)}</dd></div><div><dt>发生次数</dt><dd>{detail.alarm.occurrence_count}</dd></div><div><dt>证据</dt><dd>{evidence(detail.alarm.evidence)}</dd></div></dl>
        <h4>生命周期时间线</h4><ol className="alarm-timeline">{detail.timeline.map(item => <li key={item.id}><i /><div><b>{eventText(item.event_type)}</b><time>{formatDate(item.occurred_at)}</time>{item.reason && <p>{item.reason}</p>}{item.actor_id && <small>{item.actor_id}</small>}</div></li>)}</ol>
        {detail.alarm.state !== "CLOSED" && <div className="alarm-actions"><label>处置说明<textarea maxLength={500} value={reason} onChange={e => setReason(e.target.value)} placeholder="说明判断、处置或关闭原因" /></label><div><button disabled={!reason.trim()} onClick={() => void act("note")}>添加备注</button>{detail.alarm.acknowledged_at == null && (detail.alarm.state === "OPEN" || detail.alarm.state === "RECOVERED") ? <button className="primary" disabled={!reason.trim()} onClick={() => void act("ack")}>确认告警</button> : null}{detail.alarm.state === "ACKNOWLEDGED" || detail.alarm.state === "RECOVERED" ? <button className="danger" disabled={!reason.trim()} onClick={() => void act("close")}>关闭告警</button> : null}</div></div>}
      </> : <div className="empty-state">选择一条告警查看证据和时间线。</div>}</aside>
    </div>
  </section>;
}

function formatDate(value: string) { return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false }).format(new Date(value)); }
function evidence(value: Record<string, unknown>) { const entries = Object.entries(value); return entries.length ? entries.map(([k, v]) => `${k}=${String(v)}`).join(" · ") : "无附加证据"; }
function eventText(value: string) { return ({ AlarmOpened: "告警触发", AlarmRepeated: "异常重复", AlarmAcknowledged: "人员确认", AlarmRecovered: "条件恢复", AlarmClosed: "生命周期关闭", AlarmNoted: "添加备注" } as Record<string, string>)[value] ?? value; }
