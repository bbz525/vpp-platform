# 事件与接口契约

## 1. 契约原则

- MQTT、Netty TCP、Kafka 和 WebSocket 共用同一业务标识与时间语义。
- Schema 使用显式版本；兼容演进只允许新增可选字段或放宽读取，破坏性变更发布新 major topic/schema。
- Kafka 采用至少一次投递。生产者重试、Broker 重发和消费者恢复都可能带来重复，消费者必须按 `event_id` 或业务幂等键处理。
- 事实事件使用过去式；动作命令使用祈使式并在独立主题传输。
- 任何模拟、插值、修正或低质量数据都通过 `data_quality` 明确标识。

## 2. 标准事件信封

```json
{
  "schema": "vpp.telemetry.normalized",
  "schema_version": 1,
  "event_id": "0198c8d7-c8dc-7ae2-a3a1-eef5c6eddb63",
  "event_type": "TelemetryAccepted",
  "tenant_id": "7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941",
  "aggregate_type": "DEVICE",
  "aggregate_id": "bess-001",
  "occurred_at": "2026-08-12T01:00:00.000Z",
  "produced_at": "2026-08-12T01:00:00.120Z",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "correlation_id": "0198c8d7-c8dc-7ae2-a3a1-eef5c6eddb63",
  "causation_id": null,
  "producer": "iot-gateway",
  "data_quality": {
    "status": "VALID",
    "flags": [],
    "source": "SIMULATED"
  },
  "payload": {}
}
```

字段规则：

- `event_id` 推荐 UUIDv7，在一次语义上报的重试中保持不变。
- `occurred_at` 是设备/业务事件时间，`produced_at` 是平台产生标准事件时间。
- `correlation_id` 串联一次业务流程；`causation_id` 指向直接触发本事件的事件/命令。
- `data_quality.status` 为 `VALID | SUSPECT | INVALID | ESTIMATED | OVERRIDDEN`。
- 未知信封字段应被忽略并保留兼容；未知必需 payload 语义应拒绝到 DLQ。

## 3. MQTT 契约

### 3.1 主题

```text
vpp/{tenant_id}/{device_id}/up/telemetry
vpp/{tenant_id}/{device_id}/up/heartbeat
vpp/{tenant_id}/{device_id}/up/event
vpp/{tenant_id}/{device_id}/up/command-ack
vpp/{tenant_id}/{device_id}/down/command
vpp/{tenant_id}/{device_id}/down/config
```

- 上行使用 QoS 1；客户端生成稳定 `event_id` 以消除 PUBACK 丢失导致的重复。
- 命令默认 QoS 1、非 retained；配置快照可 retained，但不得在 retained 消息中放短期命令。
- Broker ACL 只允许设备发布自己的 `up/*` 并订阅自己的 `down/*`。
- MQTT Client ID 使用不可变设备身份，不把密码、租户密钥或站点名称编码进去。

### 3.2 遥测 payload

```json
{
  "schema_version": 1,
  "event_id": "0198c8d7-c8dc-7ae2-a3a1-eef5c6eddb63",
  "sequence": 48121,
  "device_time": "2026-08-12T01:00:00.000Z",
  "metrics": {
    "active_power_kw": 120.5,
    "soc_pct": 63.2,
    "available_capacity_kwh": 452.8
  },
  "status": "RUNNING",
  "extensions": {
    "simulator_scenario": "sunny-weekday",
    "device_type": "BATTERY",
    "data_quality_source": "SIMULATED"
  }
}
```

校验：payload 上限默认 64 KiB；`event_id`、`sequence`、`device_time` 必需；metrics 名称必须存在于设备型号点表，非有限数字（NaN/Infinity）拒绝。

### 3.3 心跳 payload

```json
{
  "schema_version": 1,
  "event_id": "0198c8d7-c8dc-7ae2-a3a1-eef5c6eddb64",
  "sequence": 48122,
  "device_time": "2026-08-12T01:00:05.000Z",
  "firmware_version": "sim-1.0.0",
  "uptime_seconds": 86400,
  "extensions": {
    "data_quality_source": "SIMULATED"
  }
}
```

## 4. Netty TCP 协议

MVP 自定义协议使用 TLS 上的固定头 + UTF-8 JSON payload，网络字节序（big-endian）。后续可将 payload 替换为 Protobuf，但消息语义保持一致。

| 偏移 | 长度 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | 4 | magic | ASCII `VPP1` |
| 4 | 1 | version | 协议 major，MVP 为 1 |
| 5 | 1 | message_type | 1 AUTH、2 TELEMETRY、3 HEARTBEAT、4 DEVICE_EVENT、5 COMMAND_ACK、100 AUTH_OK、101 COMMAND、102 CONFIG |
| 6 | 2 | flags | 保留，MVP 必须为 0 |
| 8 | 4 | payload_length | 无符号长度，上限 65,536 |
| 12 | 8 | sequence | 单设备单会话递增；重连后允许继续或通过 AUTH 声明重置 |
| 20 | 8 | sent_at_epoch_ms | 设备发送 UTC 毫秒时间 |
| 28 | N | payload | UTF-8 JSON |

连接流程：TLS 握手 → AUTH → AUTH_OK → 上报/心跳。认证完成前任何业务帧都关闭连接并记录原因。`LengthFieldBasedFrameDecoder` 必须在分配大块内存前执行最大长度检查；空闲超过配置窗口触发会话离线事件。

AUTH payload：

```json
{
  "device_id": "bess-001",
  "tenant_id": "7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941",
  "credential": "<redacted-on-server>",
  "client_nonce": "c9e88c7c-17af-4931-a1f3-a45f1b096de7"
}
```

服务端日志禁止记录 `credential`。生产连接真实设备时，应升级为双向 TLS 或签名挑战，而不是长期明文共享密钥。

## 5. Kafka 主题目录

| Topic | Key | 生产者 | 消费者 | 默认保留 |
| --- | --- | --- | --- | --- |
| `vpp.telemetry.raw.v1` | `tenant:device` | IoT Gateway | 标准化处理器、归档 | 3 天 |
| `vpp.telemetry.normalized.v1` | `tenant:device` | 标准化处理器 | CH sink、状态/告警、预测特征 | 7 天 |
| `vpp.device-state.changed.v1` | `tenant:device` | 状态处理器 | Redis 投影、WS、调度资格 | compact + 1 天 |
| `vpp.aggregate-snapshot.v1` | `tenant:portfolio` | 聚合处理器 | WS、监控 | 1 天 |
| `vpp.alarm.events.v1` | `tenant:alarm` | 告警处理器/API | PostgreSQL 投影、WS、通知 | 30 天 |
| `vpp.forecast.events.v1` | `tenant:forecast` | Forecast 编排 | API 投影、审计 | 30 天 |
| `vpp.schedule.events.v1` | `tenant:schedule` | Platform API/Outbox | Dispatcher、审计、WS | 90 天 |
| `vpp.command.requests.v1` | `tenant:device` | Dispatcher | IoT Gateway | 7 天 |
| `vpp.command.events.v1` | `tenant:device` | Gateway/Dispatcher | Platform API、告警、WS | 30 天 |
| `vpp.audit.events.v1` | `tenant:object` | 各服务/Outbox | 审计投影 | 365 天 |
| `vpp.*.dlq.v1` | 原 key | 各消费者 | 运维工具 | 30 天 |

保留期是本地/MVP 起点，生产值必须依据重放窗口、法规、存储成本和备份策略重新确认。

## 6. 标准遥测事件

```json
{
  "schema": "vpp.telemetry.normalized",
  "schema_version": 1,
  "event_id": "0198c8d7-c8dc-7ae2-a3a1-eef5c6eddb63",
  "event_type": "TelemetryAccepted",
  "tenant_id": "7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941",
  "aggregate_type": "DEVICE",
  "aggregate_id": "bess-001",
  "occurred_at": "2026-08-12T01:00:00.000Z",
  "produced_at": "2026-08-12T01:00:00.120Z",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "correlation_id": "0198c8d7-c8dc-7ae2-a3a1-eef5c6eddb63",
  "causation_id": null,
  "producer": "telemetry-normalizer",
  "data_quality": {
    "status": "VALID",
    "flags": [],
    "source": "SIMULATED"
  },
  "payload": {
    "site_id": "site-sh-001",
    "device_type": "BATTERY",
    "sequence": 48121,
    "ingested_at": "2026-08-12T01:00:00.080Z",
    "metrics": {
      "active_power_kw": 120.5,
      "soc_pct": 63.2,
      "available_capacity_kwh": 452.8
    }
  }
}
```

## 7. 命令与回执

### 7.1 CommandRequested

```json
{
  "schema": "vpp.command.requested",
  "schema_version": 1,
  "command_id": "0198c8d7-c8dc-7ae2-a3a1-eef5c6eddb70",
  "idempotency_key": "schedule-42:v3:bess-001:2026-08-13T01:00Z:set-power",
  "tenant_id": "7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941",
  "device_id": "bess-001",
  "schedule_id": "schedule-42",
  "schedule_version": 3,
  "action": "SET_POWER",
  "parameters": {
    "active_power_kw": 100.0,
    "duration_seconds": 900
  },
  "not_before": "2026-08-13T01:00:00.000Z",
  "expires_at": "2026-08-13T01:00:20.000Z",
  "safety_config_version": 12,
  "created_at": "2026-08-12T10:02:00.000Z"
}
```

### 7.2 CommandAck

```json
{
  "schema_version": 1,
  "event_id": "0198c8d7-c8dc-7ae2-a3a1-eef5c6eddb71",
  "command_id": "0198c8d7-c8dc-7ae2-a3a1-eef5c6eddb70",
  "idempotency_key": "schedule-42:v3:bess-001:2026-08-13T01:00Z:set-power",
  "device_time": "2026-08-13T01:00:01.200Z",
  "status": "ACCEPTED",
  "reason_code": null,
  "message": null,
  "actual": null
}
```

终态回执可带 `actual`，例如实际设定功率。设备收到重复幂等键时必须返回已知状态，不重复执行动作。

## 8. 计划与告警事件最小字段

### ScheduleApproved

必须包含 `schedule_id`、`schedule_version`、`portfolio_id`、时间范围、`forecast_version`、`tariff_version`、`device_config_snapshot_id`、`algorithm_version`、`approved_by`、`approved_at`、计划摘要哈希。

### ScheduleGenerated

T09 在 `vpp.schedule.events.v1` 发布调度生成终态，包含 schedule/portfolio/date、`VALIDATED|FAILED`、`FEASIBLE|INFEASIBLE|FAILED` 和可选 `schedule_version_id`。该事实只表示候选生成结果，不表示审批、下发或实际效果；T10 的 `ScheduleApproved` 是独立后续事实。

### AlarmOpened/Transitioned

必须包含 `alarm_id`、`rule_id`、`object_type/id`、severity、from/to state、first/last occurrence、occurrence_count、metric evidence、actor/reason（如有）。

## 9. WebSocket 契约

连接：`GET /ws/v1?access_token=<short-lived-token>`。更推荐通过安全 cookie 或一次性 WS ticket，避免长期 token 出现在 URL 日志。

客户端订阅：

```json
{
  "type": "subscribe",
  "request_id": "req-1",
  "channels": [
    "portfolio:portfolio-001:snapshot",
    "tenant:alarms",
    "schedule:schedule-42:execution"
  ],
  "resume_after": "0000000000019234"
}
```

服务端事件：

```json
{
  "type": "event",
  "channel": "portfolio:portfolio-001:snapshot",
  "cursor": "0000000000019235",
  "occurred_at": "2026-08-12T01:00:01.000Z",
  "data": {
    "net_grid_power_kw": 832.1,
    "pv_power_kw": 540.0,
    "battery_power_kw": 120.5,
    "freshness_seconds": 1.2,
    "quality": "VALID"
  }
}
```

- 服务端首先鉴权每个 channel，不接受客户端任意租户 ID。
- 重连窗口内按 cursor 补发；cursor 过期返回 `resync_required`，客户端重新请求 REST 快照。
- 服务端定期发 ping；慢消费者超过队列水位时断开并返回可识别代码，避免拖垮进程。

## 10. REST 错误格式

```json
{
  "code": "SCHEDULE_CONSTRAINT_VIOLATION",
  "message": "计划不满足设备安全约束",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "details": [
    {
      "field": "targets[12].active_power_kw",
      "reason": "ABOVE_DEVICE_MAX",
      "limit": 250.0
    }
  ]
}
```

错误信息不得泄露堆栈、SQL、密钥或其他租户对象。冲突/幂等重放返回稳定业务结果；不可恢复校验错误不应由客户端盲目重试。

## 11. 契约验证门禁

- AsyncAPI/OpenAPI/schema 文件通过 lint。
- 每个生产者在 CI 中对契约运行序列化兼容测试。
- 消费者契约测试覆盖新增可选字段、未知字段、重复事件、乱序事件和不支持的 major 版本。
- MQTT/TCP/Kafka 同一遥测 fixture 标准化后必须得到语义相同的事件。
- 日志快照测试确认 credential/token 不会被序列化。
