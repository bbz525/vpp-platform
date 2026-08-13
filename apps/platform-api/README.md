# Platform API

## T09 optimization schedule

`POST /api/v1/schedules` freezes a portfolio load forecast version, PV forecast version, tariff plan,
battery configuration snapshot, and operator-supplied initial SOC values. The deterministic
`RULE_BASELINE 1.0.0` optimizer returns either a hard-constraint-validated immutable version or an
explicit infeasible result without a version. `GET /api/v1/schedules` and `GET /api/v1/schedules/{id}`
expose the audit-ready outcome. Approval and dispatch are intentionally deferred to T10.

Battery capability conventions are `SET_POWER` (`kW`, negative min charge / positive max discharge),
`ENERGY_CAPACITY_KWH` (`kWh`, fallback is rated capacity), `SOC_RANGE_PCT` (`%`), and
`CHARGE_EFFICIENCY` / `DISCHARGE_EFFICIENCY` (`ratio`, fallback in `(0,1]`).

`platform-api` 是资源与设备身份控制面的事实源。T04 已实现资源、身份、资费与审计；T06 增加授权实时出口；T07 增加告警；T08 增加日前预测编排和不可变版本。

## 本地运行

先启动 PostgreSQL，再构建并运行：

```bash
make infra-up
make test-platform
mvn -B -ntp -Dmaven.repo.local=/tmp/vpp-platform-m2 \
  -pl apps/platform-api -am package -DskipTests
java -jar apps/platform-api/target/platform-api-0.1.0-SNAPSHOT.jar
```

本地模式会创建固定演示租户和 `TENANT_ADMIN`。业务 API 请求必须带本地身份头；角色始终从 PostgreSQL 读取，不信任客户端传入的角色：

```text
X-VPP-Tenant-Id: 7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941
X-VPP-Subject: local-admin
Idempotency-Key: 至少八个字符的业务幂等键
```

接口定义以 `contracts/openapi/platform-api.v1.yaml` 为准。健康检查为 `/actuator/health/readiness`。

## T06 实时出口

本地启用实时功能：

```bash
PLATFORM_REALTIME_ENABLED=true \
REDIS_HOST=127.0.0.1 \
CLICKHOUSE_JDBC_URL=jdbc:clickhouse://127.0.0.1:8123/vpp \
java -jar apps/platform-api/target/platform-api-0.1.0-SNAPSHOT.jar
```

- `GET /api/v1/portfolios/{id}/snapshot` 从 PostgreSQL 读取授权资源、从 Redis 读取当前状态，明确返回 `LIVE/PARTIAL/STALE/EMPTY`、质量、覆盖数和各指标单位。
- `GET /api/v1/telemetry/query` 只读取 ClickHouse `telemetry_metric_current`，查询范围最多 7 天，不补造缺失曲线。
- `POST /api/v1/realtime/tickets` 生成 30 秒、单次使用的随机 WS ticket；`GET /ws/v1?ticket=...` 完成握手后逐 channel 校验组合归属。
- `portfolio:{uuid}:snapshot` 支持 16 位 cursor 恢复。cursor 超出 Redis Stream 保留窗口或补发超过上限时返回 `resync_required`，客户端必须重新请求 REST 快照。
- 每个会话使用有界发送缓冲和发送超时；慢消费者会被断开，不能拖垮发布线程。

UI 状态和字段事实源见 `docs/08-realtime-ui-contract.md`。

## T07 告警中心

`PLATFORM_ALARMS_ENABLED=true` 后，平台按 `PLATFORM_ALARM_EVALUATION_INTERVAL` 读取 Redis 最新状态并评估启用规则。PostgreSQL 的 `alarm_rule`、`alarm`、`alarm_transition` 是事实源；每次生命周期转换同时写入 Outbox，并推送 `tenant:alarms` WebSocket 恢复流；人工确认、备注和关闭另写不可变审计。

- 已执行规则：离线/过期、SOC 上下界、功率上下界；阈值规则支持 `clear_threshold` 回差。
- `SUDDEN_CHANGE` 与 `COMMAND_TIMEOUT` 会明确返回 `UNSUPPORTED` 覆盖状态，后者等 T10 命令事实源完成后接入。
- Redis 或 WebSocket 短暂失败不会回滚告警事实；客户端通过 REST 重新同步。
- `PLATFORM_OUTBOX_ENABLED=true` 启用 PostgreSQL `FOR UPDATE SKIP LOCKED` relay，将告警与审计事件至少一次发布到 Kafka；崩溃窗口允许重复，消费者必须按 `event_id` 幂等。
- 告警状态语义和 UI 决策见 `docs/09-alarm-center-contract.md`。

## T08 日前预测

`PLATFORM_FORECAST_ENABLED=true` 后，平台冻结 `data_cutoff`，从 ClickHouse 读取截止点之前的 56 天 `VALID` 电表或光伏逆变器 15 分钟聚合值，再异步调用 Forecast Service。成功结果必须完整覆盖目标本地日；普通日为 96 点，夏令时日按实际时段数校验。

- `POST /api/v1/forecast-runs` 幂等创建负荷或光伏运行；`GET /forecast-runs/{id}` 返回数据充分性、滚动验证指标和当前版本。
- 28 天历史和近 7 天 95% 完整率任一未满足即落为 `INSUFFICIENT_DATA`，不创建虚假曲线。
- `forecast_version` 和 `forecast_point` 由数据库触发器禁止更新/删除。人工覆盖生成新版本并记录原因、操作者、原版本和审计事件。
- 终态与 `vpp.forecast.events.v1` Outbox 同事务提交。预测依赖失败只阻止新预测，不影响实时监控与已存在版本。

本地启动 Forecast Service 后，将 `PLATFORM_FORECAST_ENABLED=true` 和 `FORECAST_SERVICE_URL=http://127.0.0.1:8090` 注入 Platform API。

## 身份与凭据边界

- `PLATFORM_AUTH_MODE=local` 仅限本地开发；生产必须使用 `jwt` 与 HTTPS OIDC issuer，并禁用本地 bootstrap。
- 跨租户对象按不存在处理并返回 404，避免泄露对象存在性。
- 凭据只在写请求中出现一次；响应、日志、审计和数据库均不保存明文。数据库保存由内部令牌加键的 HMAC 校验值。
- 网关通过 `/api/v1/internal/device-identities` 拉取 ACTIVE 身份快照，请求必须携带 `X-VPP-Internal-Token`。该接口不面向浏览器或设备。
- 设备必须先配置凭据才能转为 ACTIVE；DISABLED、MAINTENANCE 与 SAFETY_LOCKED 设备不会进入网关身份快照。

## 数据一致性

Flyway migration `V1__control_plane.sql` 创建租户资源、资费、审计、Outbox 与幂等表。每次资源变更的业务写入、审计事件和 Outbox 在同一 PostgreSQL 事务中提交；数据库触发器禁止修改或删除审计记录。资费区间同时由服务校验和 PostgreSQL 排斥约束防止重叠。

## 生产门禁

生产配置至少需要：

```bash
VPP_ENV=production
PLATFORM_AUTH_MODE=jwt
PLATFORM_JWT_ISSUER_URI=https://identity.example.com/issuer
PLATFORM_LOCAL_BOOTSTRAP_ENABLED=false
PLATFORM_INTERNAL_TOKEN=<安全注入的至少 32 字符随机值>
PLATFORM_REALTIME_ENABLED=true
PLATFORM_ALARMS_ENABLED=true
PLATFORM_OUTBOX_ENABLED=true
KAFKA_SECURITY_PROTOCOL=SSL
REDIS_SSL_ENABLED=true
CLICKHOUSE_JDBC_URL=jdbc:clickhouse:https://clickhouse.internal:8443/vpp
PLATFORM_WEB_ALLOWED_ORIGINS=https://console.example.com
```

运行 `node scripts/validate-env.mjs` 可在启动部署前检查平台与网关的生产安全配置。
