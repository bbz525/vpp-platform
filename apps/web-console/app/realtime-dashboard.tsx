"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { getHistory, getSnapshot, issueTicket, listPortfolios, websocketUrl } from "./realtime-client";
import type { Freshness, Portfolio, PortfolioSnapshot, SocketState, TelemetryQuery, TelemetrySeries } from "./realtime-types";
import { AlarmCenter } from "./alarm-center";
import { ForecastCenter } from "./forecast-center";
import { ScheduleCenter } from "./schedule-center";

const freshnessCopy: Record<Freshness, [string, string]> = {
  LIVE: ["实时", "全部上报设备均在新鲜度阈值内"],
  PARTIAL: ["部分可用", "部分设备缺测、过期或质量可疑"],
  STALE: ["数据已过期", "当前展示最后一次成功值，不代表实时状态"],
  EMPTY: ["暂无遥测", "组合存在，但尚未收到任何设备状态"],
};

const deviceTypeName: Record<string, string> = {
  METER: "电表", PV_INVERTER: "光伏", BATTERY: "储能", EV_CHARGER: "充电桩",
};

export function RealtimeDashboard() {
  const [portfolios, setPortfolios] = useState<Portfolio[]>([]);
  const [selected, setSelected] = useState("");
  const [snapshot, setSnapshot] = useState<PortfolioSnapshot | null>(null);
  const [history, setHistory] = useState<TelemetryQuery | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [socketState, setSocketState] = useState<SocketState>("OFFLINE");
  const cursorRef = useRef<Record<string, string>>({});
  const [clock, setClock] = useState(Date.now());
  const refreshTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const refresh = useCallback(async (portfolioId: string, signal?: AbortSignal) => {
    const [nextSnapshot, nextHistory] = await Promise.all([
      getSnapshot(portfolioId, signal), getHistory(portfolioId, signal),
    ]);
    setSnapshot(nextSnapshot);
    setHistory(nextHistory);
    setError(null);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    listPortfolios(controller.signal)
      .then((items) => {
        setPortfolios(items);
        setSelected((current) => current || items[0]?.id || "");
        if (items.length === 0) setLoading(false);
      })
      .catch((reason: Error) => { setError(reason.message); setLoading(false); });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (!selected) return;
    const controller = new AbortController();
    setLoading(true);
    refresh(selected, controller.signal)
      .catch((reason: Error) => setError(reason.message))
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [selected, refresh]);

  useEffect(() => {
    if (!selected) return;
    let socket: WebSocket | null = null;
    let retry: ReturnType<typeof setTimeout> | null = null;
    let stopped = false;
    let attempts = 0;

    const connect = async () => {
      setSocketState(attempts === 0 ? "CONNECTING" : "RECONNECTING");
      try {
        const ticket = await issueTicket();
        if (stopped) return;
        socket = new WebSocket(websocketUrl(ticket));
        socket.onopen = () => {
          attempts = 0;
          for (const channel of [`portfolio:${selected}:snapshot`, "tenant:alarms"]) {
            socket?.send(JSON.stringify({
              type: "subscribe", request_id: crypto.randomUUID(), channels: [channel],
              resume_after: cursorRef.current[channel] ?? null,
            }));
          }
        };
        socket.onmessage = (message) => {
          const event = JSON.parse(message.data as string) as { type: string; cursor?: string };
          if (event.type === "connected" || event.type === "subscribed") setSocketState("LIVE");
          if (event.type === "event") {
            if (event.cursor) {
              const channel = (event as { channel?: string }).channel;
              if (channel) cursorRef.current[channel] = event.cursor;
            }
            if (refreshTimer.current) clearTimeout(refreshTimer.current);
            refreshTimer.current = setTimeout(() => void refresh(selected), 150);
            const channel = (event as { channel?: string }).channel;
            if (channel === "tenant:alarms") window.dispatchEvent(new CustomEvent("vpp:alarm-event"));
          }
          if (event.type === "resync_required") {
            cursorRef.current = {};
            void refresh(selected);
            window.dispatchEvent(new CustomEvent("vpp:alarm-event"));
          }
        };
        socket.onclose = () => {
          if (stopped) return;
          setSocketState("RECONNECTING");
          attempts += 1;
          retry = setTimeout(connect, Math.min(1000 * 2 ** attempts, 10_000));
        };
        socket.onerror = () => socket?.close();
      } catch {
        if (stopped) return;
        setSocketState("RECONNECTING");
        attempts += 1;
        retry = setTimeout(connect, Math.min(1000 * 2 ** attempts, 10_000));
      }
    };
    void connect();
    return () => {
      stopped = true;
      socket?.close();
      if (retry) clearTimeout(retry);
      if (refreshTimer.current) clearTimeout(refreshTimer.current);
    };
  }, [selected, refresh]);

  useEffect(() => {
    const timer = setInterval(() => setClock(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  const visibleFreshness = useMemo<Freshness>(() => {
    if (!snapshot?.observed_at) return snapshot?.freshness_status ?? "EMPTY";
    const age = (clock - new Date(snapshot.observed_at).getTime()) / 1000;
    return age > 15 ? "STALE" : snapshot.freshness_status;
  }, [clock, snapshot]);

  return (
    <div className="app-shell">
      <aside className="rail" aria-label="主导航">
        <div className="brand-mark" aria-label="VPP">V</div>
        <nav>
          <a className="nav-item active" href="#overview" aria-current="page"><span>总</span>实时总览</a>
          <a className="nav-item" href="#assets"><span>资</span>资源下钻</a>
          <a className="nav-item" href="#alarms"><span>告</span>告警中心</a>
          <a className="nav-item" href="#forecasts"><span>预</span>日前预测</a>
          <a className="nav-item" href="#schedules"><span>调</span>优化调度</a>
        </nav>
        <div className="rail-foot">VPP<br /><small>CONTROL ROOM</small></div>
      </aside>

      <main className="workspace">
        <header className="topbar">
          <div>
            <p className="eyebrow">OPERATIONS / REALTIME</p>
            <h1>能源态势</h1>
          </div>
          <div className="topbar-actions">
            <label className="portfolio-picker">资源组合
              <select value={selected} onChange={(event) => { setSelected(event.target.value); cursorRef.current = {}; }}>
                {portfolios.map((portfolio) => <option key={portfolio.id} value={portfolio.id}>{portfolio.name}</option>)}
              </select>
            </label>
            <SocketBadge state={socketState} />
          </div>
        </header>

        {error && <div className="error-banner" role="alert"><div><strong>数据同步失败</strong><p>{error}。最近一次成功值不会被标记为实时。</p></div><button onClick={() => selected && void refresh(selected)}>重试</button></div>}

        <section className={`truth-bar state-${visibleFreshness.toLowerCase()}`} aria-live="polite">
          <div className="truth-pulse" aria-hidden="true" />
          <div><strong>{freshnessCopy[visibleFreshness][0]}</strong><p>{freshnessCopy[visibleFreshness][1]}</p></div>
          <div className="truth-meta"><span>覆盖</span><b>{snapshot ? `${snapshot.coverage.reporting_devices}/${snapshot.coverage.total_devices}` : "—"}</b></div>
          <div className="truth-meta"><span>最后更新</span><b>{formatTime(snapshot?.observed_at)}</b></div>
        </section>

        <section id="overview" className="kpi-grid" aria-label="实时指标">
          <Kpi title="电网功率" value={snapshot?.power.net_grid_power_kw} unit="kW" accent="blue" loading={loading} observedAt={snapshot?.observed_at} />
          <Kpi title="光伏出力" value={snapshot?.power.pv_power_kw} unit="kW" accent="amber" loading={loading} observedAt={snapshot?.observed_at} />
          <Kpi title="储能功率" value={snapshot?.power.battery_power_kw} unit="kW" accent="violet" loading={loading} observedAt={snapshot?.observed_at} />
          <Kpi title="实时设备" value={snapshot?.coverage.live_devices} unit={`/ ${snapshot?.coverage.total_devices ?? "—"}`} accent="green" loading={loading} digits={0} observedAt={snapshot?.observed_at} />
        </section>

        <section className="panel chart-panel">
          <div className="panel-heading"><div><p className="eyebrow">LAST 60 MINUTES</p><h2>设备有功功率曲线</h2></div><span className="evidence-label">ClickHouse · 真实测点</span></div>
          <PowerChart history={history} />
        </section>

        <section id="assets" className="assets-section">
          <div className="section-title"><div><p className="eyebrow">ASSET DRILLDOWN</p><h2>站点与设备</h2></div><p>缺测显示为“暂无数据”，不按零值参与汇总。</p></div>
          {loading && !snapshot ? <div className="panel empty-state">正在读取授权资源与实时状态…</div> : null}
          {!loading && snapshot?.sites.length === 0 ? <div className="panel empty-state">该组合尚未配置站点。</div> : null}
          <div className="site-list">
            {snapshot?.sites.map((site) => (
              <details className="site-card" key={site.site_id} open>
                <summary>
                  <div><span className={`mini-state state-${site.freshness_status.toLowerCase()}`} /><strong>{site.site_name}</strong><small>{site.timezone}</small></div>
                  <span>{site.coverage.reporting_devices}/{site.coverage.total_devices} 台上报</span>
                </summary>
                <div className="device-table-wrap">
                  <table><thead><tr><th>设备</th><th>类型</th><th>接入状态</th><th>数据状态</th><th>标准测点</th><th>更新时间</th></tr></thead>
                    <tbody>{site.devices.map((device) => <tr key={device.device_id}>
                      <td><strong>{device.device_name}</strong><small>{device.external_code}</small></td>
                      <td>{deviceTypeName[device.device_type] ?? device.device_type}</td>
                      <td><span className="plain-status">{device.device_status}</span></td>
                      <td><span className={`quality state-${device.freshness_status.toLowerCase()}`}>{freshnessCopy[device.freshness_status][0]} · {device.quality}</span></td>
                      <td>{device.metrics.length ? device.metrics.map((metric) => <span className="metric-chip" key={metric.name}>{metric.name} <b>{formatNumber(metric.value, 2)} {metric.unit}</b></span>) : <span className="muted">暂无数据</span>}</td>
                      <td>{formatTime(device.observed_at)}</td>
                    </tr>)}</tbody>
                  </table>
                </div>
              </details>
            ))}
          </div>
        </section>

        <AlarmCenter portfolioId={selected} />
        <ForecastCenter portfolioId={selected} />
        <ScheduleCenter portfolioId={selected} />
      </main>
    </div>
  );
}

function SocketBadge({ state }: { state: SocketState }) {
  const labels: Record<SocketState, string> = { LIVE: "实时通道已连接", CONNECTING: "正在连接", RECONNECTING: "正在重连", OFFLINE: "实时通道离线" };
  return <div className={`socket-badge socket-${state.toLowerCase()}`}><span />{labels[state]}</div>;
}

function Kpi({ title, value, unit, accent, loading, digits = 1, observedAt }: { title: string; value: number | null | undefined; unit: string; accent: string; loading: boolean; digits?: number; observedAt: string | null | undefined }) {
  return <article className={`kpi accent-${accent}`}><div className="kpi-line" /><p>{title}</p><div className="kpi-value">{loading && value == null ? <span className="skeleton" /> : <><strong>{value == null ? "—" : formatNumber(value, digits)}</strong><span>{unit}</span></>}</div><small>更新时间 {formatTime(observedAt)}</small></article>;
}

function PowerChart({ history }: { history: TelemetryQuery | null }) {
  if (!history || history.series.every((series) => series.points.length === 0)) return <div className="chart-empty"><strong>暂无历史曲线</strong><p>ClickHouse 在所选时间范围内没有有效测点。</p></div>;
  const width = 1000, height = 280, pad = 34;
  const points = history.series.flatMap((series) => series.points.map((point) => point.value));
  const min = Math.min(...points), max = Math.max(...points), span = Math.max(max - min, 1);
  const colors = ["#4ac6ff", "#f7b955", "#9b8cff", "#58d6a7", "#ff768a"];
  const path = (series: TelemetrySeries) => series.points.map((point, index) => {
    const x = pad + (index / Math.max(series.points.length - 1, 1)) * (width - pad * 2);
    const y = pad + (1 - (point.value - min) / span) * (height - pad * 2);
    return `${index === 0 ? "M" : "L"}${x.toFixed(1)},${y.toFixed(1)}`;
  }).join(" ");
  return <div className="chart-wrap"><svg className="power-chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="最近一小时设备有功功率曲线">
    {[0, 1, 2, 3, 4].map((line) => <line key={line} x1={pad} x2={width - pad} y1={pad + line * 53} y2={pad + line * 53} />)}
    {history.series.map((series, index) => <path key={series.external_code} d={path(series)} stroke={colors[index % colors.length]} />)}
  </svg><div className="legend">{history.series.map((series, index) => <span key={series.external_code}><i style={{ background: colors[index % colors.length] }} />{series.external_code} · {series.unit}</span>)}</div></div>;
}

function formatNumber(value: number, digits: number) { return new Intl.NumberFormat("zh-CN", { maximumFractionDigits: digits, minimumFractionDigits: digits }).format(value); }
function formatTime(value: string | null | undefined) { return value ? new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false }).format(new Date(value)) : "—"; }
