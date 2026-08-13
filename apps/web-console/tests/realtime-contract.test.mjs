import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const dashboard = await readFile(new URL("../app/realtime-dashboard.tsx", import.meta.url), "utf8");
const client = await readFile(new URL("../app/realtime-client.ts", import.meta.url), "utf8");
const alarms = await readFile(new URL("../app/alarm-center.tsx", import.meta.url), "utf8");

test("realtime console exposes truthful missing, stale and reconnect states", () => {
  assert.match(dashboard, /暂无历史曲线/);
  assert.match(dashboard, /数据已过期/);
  assert.match(dashboard, /正在重连/);
  assert.match(dashboard, /不按零值参与汇总/);
  assert.doesNotMatch(dashboard, /Math\.random/);
});

test("alarm center preserves recovery, acknowledgement and audit semantics", () => {
  assert.match(alarms, /恢复不代表确认/);
  assert.match(alarms, /规则覆盖不足/);
  assert.match(alarms, /页面不会乐观修改状态/);
  assert.match(alarms, /生命周期时间线/);
  assert.match(client, /\/api\/v1\/alarms\//);
  assert.match(dashboard, /tenant:alarms/);
  assert.match(alarms, /vpp:alarm-event/);
});

test("browser client only uses public Platform API and one-time websocket ticket", () => {
  assert.match(client, /\/api\/v1\/realtime\/tickets/);
  assert.match(client, /\/ws\/v1/);
  assert.doesNotMatch(client, /KAFKA|MQTT|REDIS|CLICKHOUSE|INTERNAL_TOKEN/);
  assert.doesNotMatch(client, /access_token/);
});
