import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const center = await readFile(new URL("../app/schedule-center.tsx", import.meta.url), "utf8");
const client = await readFile(new URL("../app/realtime-client.ts", import.meta.url), "utf8");
const types = await readFile(new URL("../app/realtime-types.ts", import.meta.url), "utf8");

test("approval is gated by validated feasible immutable schedule evidence", () => {
  assert.match(center, /status === "VALIDATED"/);
  assert.match(center, /feasibility === "FEASIBLE"/);
  assert.match(center, /detail\.version !== null/);
  assert.match(center, /批准会生成未来设备命令/);
  assert.match(center, /决策原因（必填）/);
  assert.match(center, /已冻结输入与版本证据/);
  assert.match(types, /latest_decision: ScheduleDecision \| null/);
  assert.match(center, /detail\.latest_decision/);
  assert.doesNotMatch(center, /detail\.schedule\.latest_decision/);
});

test("decision and schedule emergency stop use the platform contract and explicit confirmation", () => {
  assert.match(client, /\/api\/v1\/schedules\/\$\{id\}\/decisions/);
  assert.match(client, /schedule-decision-\$\{id\}-\$\{crypto\.randomUUID\(\)\}/);
  assert.match(client, /\/api\/v1\/commands\/\$\{id\}\/stop/);
  assert.match(client, /command-stop-\$\{id\}-\$\{crypto\.randomUUID\(\)\}/);
  assert.match(client, /post<CommandDetail>/);
  assert.match(center, /计划级紧急停止原因（必填）/);
  assert.match(center, /取消本计划所有尚未下发的普通命令/);
  assert.match(center, /为全部参与设备创建独立 STOP/);
  assert.match(center, /把计划标记为 CANCELLED/);
  assert.match(center, /STOP 请求不等于设备已停止/);
  assert.match(center, /我确认该操作影响整个计划/);
  assert.match(center, /!confirmed/);
  assert.doesNotMatch(center, /仅请求取消此条/);
});

test("execution monitor consumes nested command evidence and keeps target separate from actual", () => {
  assert.match(client, /\/api\/v1\/schedules\/\$\{id\}\/execution/);
  assert.match(client, /offset: String\(offset\), limit: String\(limit\)/);
  assert.doesNotMatch(client, /listCommands/);
  assert.match(types, /interface DeviceCommand/);
  assert.match(types, /interface CommandDetail/);
  assert.match(types, /interface ScheduleExecution/);
  assert.match(types, /commands: CommandDetail\[\]; audit: AuditEvent\[\]/);
  assert.match(center, /未知状态：/);
  assert.match(center, /设备回执/);
  assert.match(center, /设备实际值/);
  assert.match(center, /请求目标/);
  assert.match(center, /command\.parameters/);
  assert.match(center, /command\.actual == null/);
  assert.match(center, /下发或目标值不代表执行成功/);
  assert.match(center, /未应用：可能重复、乱序或晚于终态/);
  assert.match(center, /执行超时/);
  assert.match(center, /终态/);
  assert.match(center, /CREATED: "已创建"/);
  assert.match(center, /ACCEPTED: "设备已接受"/);
  assert.match(center, /<DecisionPanel key=\{detail\.schedule\.id\}/);
  assert.match(center, /execution\.commands\.map/);
  assert.match(types, /command_total: number; command_offset: number; command_limit: number/);
  assert.match(center, /const commandPageLimit = 100/);
  assert.match(center, /当前范围 \/ 命令总数/);
  assert.match(center, /上一页/);
  assert.match(center, /下一页/);
  assert.match(center, /loadSelection\(selectedId, commandOffset, undefined, true\)/);
  assert.match(center, /不会生成占位记录/);
  assert.doesNotMatch(center, /Math\.random/);
});
