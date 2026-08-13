# 阶段进展报告(T00–T09)

> 生成日期:2026-08-13。本报告汇总交付计划([06-delivery-plan.md](./06-delivery-plan.md))中 T00–T09 的实际完成功能、验证证据与已知限制,并记录 T10 的当前进展。代码证据以仓库最新一次验证为准(见 [§5](#5-测试与契约资产汇总))。

## 1. 状态标签

进度标签:`planned → implemented → unit_verified → contract_verified → integration_verified → runtime_verified → product_verified`。

- `integration_verified`(2026-08-13 起用于 T08/T09):单元、契约、数据库集成(Testcontainers)与 UI 构建验证全部通过,但完整运行时/浏览器实链路验证尚未完成。
- `runtime_verified`:本地 Compose(必要时加真实浏览器)实链路验证通过。
- `product_verified`:产品闭环验收(1,000 设备压测、故障恢复演练等),由 T12 承接。

## 2. 总体进展

| 阶段 | 业务结果 | 状态 | 验证时间 |
| --- | --- | --- | --- |
| T00 独立仓库骨架与开发环境 | 全栈可构建、依赖可启动、契约可验证 | `runtime_verified` | 2026-08-12 |
| T01 契约可执行化 | REST/MQTT/TCP/Kafka/WS 契约可 lint、可生成、带兼容锁 | `contract_verified` | 2026-08-12 |
| T02 设备模拟器 | 四类设备可重放仿真、双协议接入、回执状态机 | `runtime_verified`(MQTT + TCP) | 2026-08-12 |
| T03 MQTT/Netty 设备接入网关 | 双协议鉴权接入、统一写 Kafka、下行命令路由 | `runtime_verified`(本地 Compose) | 2026-08-12 |
| T04 资源管理与设备身份 | 控制面事实源、租户隔离、凭证与审计 Outbox | `runtime_verified`(本地 Compose) | 2026-08-12 |
| T05 实时标准化、存储与状态聚合 | 标准事件、去重/乱序保护、ClickHouse 曲线、Redis 快照 | `runtime_verified`(本地 Compose) | 2026-08-12 |
| T06 实时控制台与 WebSocket | 实时总览、stale/partial 呈现、cursor 恢复与 resync | `runtime_verified`(Compose + Chrome) | 2026-08-12 |
| T07 告警中心 | 规则评估、去重生命周期、处置 UI 与审计 | `runtime_verified`(Compose + 浏览器) | 2026-08-12 |
| T08 日前预测服务 | 96 点基线预测、充分性门禁、版本与人工覆盖 | `integration_verified` | 2026-08-13 |
| T09 优化调度内核 | 硬约束候选计划、不可行诊断、版本与输入快照 | `integration_verified` | 2026-08-13 |
| T10 审批、指令与完整审计 | 审批→下发→回执→超时/停止闭环 | `integration_verified`(2026-08-13;未提交,真实运行时闭环待验收) | 2026-08-13 |
| T11 需求响应与效果评估 | — | `planned` | — |
| T12 规模、故障恢复与发布门禁 | — | `planned` | — |

阶段节奏:Phase 0(契约与骨架)、Phase 1(遥测实时闭环)、Phase 2(告警与可靠性)、Phase 3(预测与调度内核)已完成;Phase 4(审批与指令执行)核心切片已达到 `integration_verified`,真实 Compose 命令闭环与恢复演练仍待验收。

## 3. 各阶段完成功能

### 3.1 T00 独立仓库骨架与开发环境

**已完成:**

- 独立 monorepo:4 个 Java 应用(`apps/device-simulator`、`apps/iot-gateway`、`apps/stream-processor`、`apps/platform-api`)、Next.js 控制台(`apps/web-console`)、Python 预测服务(`services/forecast-service`)、契约与基础设施目录,不侵入 Netty 上游构建。
- 10 个 Compose 服务:`infra/compose/compose.yaml` 含 PostgreSQL 17.6、ClickHouse 25.7、Kafka 4.0(KRaft)+ 一次性 `kafka-init`(创建 11 个版本化主题)、Mosquitto 2.0、Redis 8.2、OTel Collector、Prometheus、Grafana、forecast-service;端口仅绑定 127.0.0.1,带健康检查。
- 稳定构建/测试入口:`Makefile`(bootstrap、build、test、test-java、test-web、test-python、test-contract、infra-up/down 等)。
- CI:`.github/workflows/ci.yaml`(Java 21 + Node 24 + uv),含生产环境配置负例测试。
- `scripts/validate-env.mjs`:生产模式缺失/弱 secret 时 fail-fast,本地环境放行。
- 健康端点分层(liveness/readiness);ADR-001(模块化单体边界)、ADR-003(JSON Schema 契约格式)。

**验证:** `make test` 全量通过(见 §5);`docker compose config` 有效;CI 基线已建立。

### 3.2 T01 契约可执行化

**已完成:**

- 15 个 JSON Schema 2020-12 文件:`contracts/schemas/` 下 mqtt(2)、events(7,含 T10 新增 `schedule-approved.v2`)、commands(2)、websocket(3)、common(1)。
- OpenAPI 3.1 `contracts/openapi/platform-api.v1.yaml`(33 个路径);AsyncAPI 3.1 `contracts/asyncapi/vpp-events.v1.yaml`(14 个 channel、17 个操作)。
- Fixture:14 个有效 + 4 个反例,由 `contracts/fixtures/manifest.json` 管理。
- `contracts/tests/validate-contracts.mjs`:Ajv 2020-12 严格模式、MQTT→normalized 语义等价断言、`compatibility/v1-lock.json` 对 7 个 v1 schema 的破坏性变更锁、schema/fixture 密钥字段扫描、OpenAPI/AsyncAPI 必选路径与 channel 断言。
- Lint:`spectral`(OpenAPI)+ `asyncapi validate`(AsyncAPI)入 CI。
- 未引入 Schema Registry(ADR-003 明确决策:Kafka payload 携带 `schema`/`schema_version`)。

**验证:** 契约门禁通过 15 个 schema、14 个有效 fixture、4 个反例及兼容锁;semantic 同义与密钥扫描通过。

### 3.3 T02 设备模拟器

**已完成:**

- 四类设备与能力:电表(METER)、光伏逆变器(PV_INVERTER)、储能(BESS,含 SOC/功率/效率物理模型)、充电桩(EV_CHARGER);`apps/device-simulator`。
- 可重放场景:`SUNNY_WEEKDAY`、`CLOUDY_WEEKDAY`、`SUNNY_WEEKEND`;seed、tick(5s)与起止时间可配置,噪声确定性生成。
- 故障注入:按 tick 间隔与目标设备注入 `DISCONNECT`、`CLOCK_DRIFT`、`DUPLICATE`、`OUT_OF_RANGE`(1.5 倍功率 / 105% SOC)。
- 双协议传输:MQTT(HiveMQ 客户端)+ Netty TCP(`VPP1` 帧、TLS 选项、AUTH 握手等待 AUTH_OK、自动重连);可选 MQTT/TCP/BOTH。
- 指令回执状态机:ACCEPTED → EXECUTING → SUCCEEDED/FAILED(7 种失败原因码);幂等键 LRU 缓存(容量 10,000);支持 `SET_POWER`、`STOP`、`SET_ACTIVE_POWER_LIMIT`、`SET_CHARGE_LIMIT`、`PAUSE`、`RESUME` 六种动作。

**验证:** surefire 实测 16 项测试;历史纵向证据:100 设备 × 360 tick 确定性重放、100 个 MQTT 客户端 1,000 条遥测 + 500 条心跳 0 失败、重复幂等键单终态回执。

**未做/限制:** MQTT 传输无 TLS 选项(TCP 有);DISCONNECT 为单 tick 断开(自动重连随即恢复)。

### 3.4 T03 MQTT/Netty 设备接入网关

**已完成:**

- Netty TCP 服务:`TLS`、IdleState、长度字段帧解码,`VPP1` 帧 magic/version 校验,超长帧预拒绝。
- 认证:连接级 AUTH 帧握手;HMAC-SHA256 凭证快照每 2s 从 platform-api 内部接口刷新;常量时间比较;严格递增序列号强制;认证前拒绝一切业务帧。
- MQTT 上行 + 统一校验:设备前缀点表、未知字段拒绝、metric 名称正则、payload 密钥字段扫描、命令请求动作与时间窗校验。
- Kafka 生产背压:in-flight 信号量上限、acks=all、`vpp-protocol`/`vpp-received-at` 头;队列满拒绝(INGRESS_QUEUE_FULL)而非无限缓存。
- 下行命令路由:TCP 活跃会话优先,MQTT 主题兜底,无会话时 `NO_ACTIVE_DOWNLINK`;会话注册与通道关闭清理;运行时健康指示。

**验证:** surefire 实测 26 项测试(含帧编解码、认证、载荷校验、背压、下行路由);本地 Compose 实测 4 MQTT + 4 TCP 会话写入 Kafka,BESS `SET_POWER` 经 TCP 三阶段回执,设备退出后 TCP 会话归零。

**未做/限制:** 无显式令牌桶限流(以队列容量 + Kafka 信号量 + 序列单调性替代);HEARTBEAT 校验但不转发 Kafka;命令路由失败重试依赖 Spring Kafka 默认重试配置。

### 3.5 T04 资源管理与设备身份

**已完成:**

- 资源 REST:`portfolios`、`sites`、`devices`、`device-models`(创建/列表/详情)、凭证轮换、设备状态变更;`tariff_plan` 创建/列表(含区间无重叠约束)。
- 租户与权限:所有对象带 `tenant_id`;角色 `TENANT_ADMIN`/`OPERATOR`/`AUDITOR`/`SITE_ADMIN`;本地开发身份 + JWT issuer 模式;跨租户访问返回 404。
- 设备身份内部接口:`/internal/device-identities`(HMAC verifier 快照)、`/internal/device-catalog`,由内部 token 保护,供 Gateway 与 Stream Processor 消费。
- 凭证安全:HMAC-SHA256 单向可验证形式,`credential_ref` 表,轮换支持,不回显、不落日志。
- 审计 Outbox:追加式 `audit_event`(触发器禁止更新/删除)+ `outbox_event` SKIP LOCKED 批量 relay。
- 停用语义:停用设备拒绝接入与后续遥测(与 T03 会话注册联动)。

**验证:** platform-api 15 项测试含 PostgreSQL Testcontainers(Flyway、资源创建、跨租户 404、凭据不回显、停用、审计不可变、Outbox、电价区间排斥);Compose 实测停用 BESS 后身份与活跃会话 3→2,后续遥测/重连被拒。

**未做/限制:** 无用户管理 REST(用户仅来自本地 bootstrap/JWT);电价计划无编辑/版本化端点。

### 3.6 T05 实时标准化、存储与状态聚合

**已完成:**

- 处理管线:解析 → 设备目录(带最终一致性宽限期)→ 标准化 → Redis 去重 → ClickHouse 批量写 → normalized 主题 → Lua 投影 → 设备/站点/组合因果事件。
- 标准化:按设备类型点表 + 型号点表覆盖,范围检查,`CLOCK_DRIFT`/`LATE_ARRIVAL`/`SIMULATED_SOURCE` 质量标记,VALID/SUSPECT 分级;未知指标进 DLQ(`vpp.telemetry.dlq`,携带 base64 原文与来源 offset)。
- 去重与乱序保护:Redis Lua 脚本拒绝重复/更旧(时间,序列)事件,幂等更新设备状态与站点/组合聚合(HINCRBYFLOAT 功率增量),同时追加 WebSocket resume stream。
- ClickHouse:`ReplacingMergeTree(event_version)` + `telemetry_metric_current` 业务去重视图 + `metric_15m` 15 分钟聚合视图;应用启动幂等建表,旧 Compose volume 可无损升级。

**验证:** stream-processor 7 项测试含真实 ClickHouse/Redis Testcontainers(物理与业务双重去重、Lua 投影、重复/乱序保护、站点/组合增量);Compose 纵向实测重复事件 ClickHouse 仅写一次,乱序事件保留历史但 Redis 最新状态不回退,`magic_voltage` 以 `UNKNOWN_METRIC` 进 DLQ。

**未做/限制:** 无显式事件时间水位线(以 Redis 脚本 + 质量标记实现序保护);单位仅标注未做换算;心跳不达此层(Gateway 未转发)。

### 3.7 T06 实时控制台与 WebSocket

**已完成:**

- 实时总览:组合选择、连接状态徽章、可信度条(`LIVE`/`STALE`/`PARTIAL`/`EMPTY`)、KPI(电网/光伏/储能/在线设备,带单位与更新时间)、近 60 分钟真实曲线、站点/设备下钻(每设备新鲜度/质量,缺失显示"暂无数据"而非 0)。
- WebSocket:一次性 30 秒 ticket 握手;`portfolio:{id}:snapshot`、`tenant:alarms` 订阅;16 位 cursor 恢复;`resync_required` 时清 cursor 并 REST 重同步;越权订阅返回 `FORBIDDEN_CHANNEL`;指数退避重连(1s–10s)。
- REST 兜底:`GET /portfolios/{id}/snapshot`(Redis 快照)、`GET /telemetry/query`(ClickHouse 曲线)。

**验证:** Web 6 项 node 契约断言 + TypeScript + Next.js 生产构建;Compose 实链路:cursor `0000000000000001`→`0000000000000010` 补发 9 个事件,越权组合不误发订阅确认;Chrome 桌面与 iPhone 14 Pro Max 430×932 视口无横向溢出。

**未做/限制:** 曲线为手绘 SVG(未引入 ECharts);Web 测试为源码级契约断言,无 DOM 交互测试。

### 3.8 T07 告警中心

**已完成:**

- 已执行规则:`OFFLINE`、`STALE`、`SOC_LOW/HIGH`、`POWER_LOW/HIGH`;阈值/滞回校验;定时评估器读取 Redis 设备状态。
- 状态机:`OPEN` → `ACKNOWLEDGED`/`RECOVERED` → `CLOSED`,恢复≠确认,恢复后复发重新打开并清除旧确认,`CLOSED` 后再次触发创建新实例;每规则/对象唯一活动实例(部分唯一索引)。
- 去重:同 `event_id` 不重复计数,只递增 `occurrence_count` 与最近发生时间。
- 集成:Outbox 4/4 发布 `vpp.alarm.events.v1`,WebSocket 推送 + UI 事件;Web 告警页含覆盖不足提示、不可变时间线、确认/备注/关闭(强制原因,无乐观更新)。
- 所有人工动作进入审计。

**验证:** platform-api 15 项测试含告警全生命周期(首次打开、去重、恢复、关闭门禁、跨租户 404、时间线不可变、审计员 RBAC);Compose + 浏览器实测 `OPEN → ACKNOWLEDGED → RECOVERED → CLOSED` 及复发重开;390×844 视口无溢出。

**未做/限制:** `SUDDEN_CHANGE`(需窗口状态)与 `COMMAND_TIMEOUT`(依赖 T10 命令事实)契约保留但如实标注为未支持;通知/静默窗口/升级链属 P2。

### 3.9 T08 日前预测服务

**已完成:**

- 预测服务 `services/forecast-service`(FastAPI v0.2.0):`/livez`、`/readyz`、`POST /v1/forecasts`;`similar-day-average-v1` 基线(过去 8 个同星期日均值,缺失回退最近 7 日,光伏钳制 ≥0)。
- 96 点序列:左闭右开 15 分钟区间,普通日 96 点,夏令时切换日按 IANA 时区产出 92/100 点(测试覆盖 100 点场景)。
- 充分性门禁:至少 28 个 ≥80% 日覆盖率的历史日 + 截止前近 7 天 ≥95% 完整率;不满足返回 `INSUFFICIENT_DATA` 与具体原因,不补零、不生成随机曲线。
- 未来泄漏防护:观测必须严格早于 `data_cutoff`,拒绝 naive 时间;仅使用 `VALID` 质量数据。
- 滚动验证:最近 7 日滚动起点(无随机划分),报告 MAE/WAPE/nMAE 与评估点数。
- Platform API 编排:异步运行(2 worker、队列 50,溢出失败 `FORECAST_QUEUE_CAPACITY_EXCEEDED`)、幂等、租户隔离、响应校验(状态/截止点/96 连续点/非负)、`forecast_version` 只追加、人工覆盖(锁定最新版本、保留原值与原因)。

**验证:** Python 6 项测试(96/100 点、充分性、完整率、泄漏拒绝)+ ruff;platform-api ForecastApiIntegrationTest(Testcontainers);契约门禁;Next.js 类型检查与生产构建。

**未做/限制:** 服务无持久化(状态由 Platform API 保存),无独立训练/推理作业;无天气特征(`weather_source=NONE` 如实报告);树模型(LightGBM/XGBoost)推迟至具备真实天气与样本条件;浏览器实链路验证待本地完整历史数据集准备后执行。

### 3.10 T09 优化调度内核

**已完成:**

- 优化器 `RULE_BASELINE 1.0.0`(未引入 OR-Tools,如实标记为规则基线):四分位电价套利,低价充电/高价放电,并网约束优先分配;按设备追踪能量与 SOC 递推;输出成本/衰减/循环摘要。
- 硬约束复核(`ScheduleConstraintValidator`):目标点完整性、时段对齐、充放电互斥、功率边界、SOC 递推与边界(默认 5% 安全裕度)、并网限值;不满足给出 `GRID_IMPORT_LIMIT_UNSATISFIABLE`/`GRID_EXPORT_LIMIT_UNSATISFIABLE`。
- 不可行语义:不可行计划以 `FAILED` + `SCHEDULE_INFEASIBLE` + 原因持久化,不产生可审批版本;多站点组合显式返回 `MULTI_SITE_OPTIMIZATION_UNSUPPORTED`,不做隐式功率分摊。
- 版本与输入冻结:`schedule_version` 只追加;固定引用负荷/光伏预测版本、电价计划;设备容量/功率/SOC 范围/效率/初始 SOC 捕获为不可变 `device_config_snapshot`(SHA-256);输出 `content_sha256`。

**验证:** RuleBaselineOptimizerTest(golden 手算/不可行/100 组随机属性);ScheduleApiIntegrationTest(PostgreSQL 17.6 Testcontainers,空库应用 V1–V5,96 点版本持久化、HTTP 幂等、只追加触发器、审计与 Outbox);契约门禁;Next.js 16 类型检查与生产构建。

**未做/限制:** 仅支持单站点组合;已审批后的日内滚动修正/版本 2+ 重提交未实现(属于 T10 之后);求解器为启发式基线而非 OR-Tools 线性规划。

### 3.11 T10 审批、指令与完整审计(工作树已完成集成验证)

T10 不在本报告正式范围（T0–T9），但代码与契约已在工作树中就绪（未提交），状态按交付计划最新标注为 `integration_verified`，记录如下：

**已实现部分:** `V6__schedule_approval_and_commands.sql`(审批/命令/attempt/事件/消费去重表,append-only 触发器,`expires_at > not_before` 检查);`POST /schedules/{id}/decisions`(行锁资格复核 VALIDATED+FEASIBLE+当前版本,幂等,APPROVE 生成逐时段 `SET_POWER` + 计划末尾 STOP,REJECT 置 CANCELLED);`schedule-approved` v2 事件(独立负荷/光伏预测版本);命令状态机(CREATED→DISPATCHED→ACCEPTED→EXECUTING→SUCCEEDED/FAILED/TIMED_OUT/CANCELLED)、持久化 dispatcher、回执消费去重与终态不回退、超时和计划级紧急停止;Gateway 强制 Kafka key 与 payload 设备身份一致;执行查询显式分页并返回总数,Web 可翻页查看当前页命令、attempt、回执和审计,不伪造占位或实际值;`docs/11-command-execution-contract.md`。

**已知缺口:** 无未确认阶段的有界自动重试;独立 STOP 权限、站点级对象授权与命令 WebSocket 推送尚未实现;真实 Compose 下发→设备回执→服务重启恢复和在线浏览器 E2E 尚未执行;工作树未提交。

**验证:** 全量 Java reactor 64 项通过,包括 V1–V6 PostgreSQL、Redis 与 ClickHouse Testcontainers;Schedule API 覆盖 96 条 SET_POWER + 1 条计划末尾 STOP、决策幂等、97 条命令分页无重叠重建、迟到回执不回退 TIMED_OUT、真实审计前后摘要及计划级紧急停止;Gateway 覆盖 record key/payload 身份不一致的 fail-closed 回归;契约门禁含 v2 schema 与正/反例 fixture;Web 6 项契约断言、类型检查与生产构建通过。

## 4. 跨阶段已知限制

- **可观测性未接线:** OTel Collector 仅 debug exporter;Prometheus 只抓取自身;Grafana 无仪表盘。应用抓取目标与业务仪表盘按计划在应用容器接入后补充。
- **无 e2e/load/restore-drill 入口:** Makefile 尚无 `test-e2e`、`test-load`、`restore-drill`(T12 交付)。
- **单位换算未实现:** 遥测仅标注单位并存储,未做跨单位换算;点表以统一单位约定规避。
- **显式水位线未实现:** 乱序保护由 Redis Lua 脚本与质量标记承担,无独立事件时间水位线组件。
- **调度求解器:** 当前为 `RULE_BASELINE 1.0.0` 启发式,未引入 OR-Tools;必须继续以 `algorithm_name/version` 如实标注。
- **告警覆盖:** `SUDDEN_CHANGE`、`COMMAND_TIMEOUT` 未接入事实源,UI 如实标注为未支持,不显示虚构覆盖。
- **需求响应(T11)未开始:** DR 状态机、容量评估、基线、达成率与报告模块均为 `planned`。

## 5. 测试与契约资产汇总

2026-08-13 全量验证结果(单机本地,Testcontainers 依赖 Docker):

| 套件 | 范围 | 结果 |
| --- | --- | --- |
| Java(`make test-java`) | platform-api 15 项 / iot-gateway 26 项 / stream-processor 7 项 / device-simulator 16 项 | 64 项全绿,0 失败 |
| Python(`make test-python`) | 预测服务 6 项 + ruff | 全绿 |
| Web(`test-web`) | 6 项 node 契约断言 + `tsc --noEmit` + Next.js 生产构建 | 全绿 |
| 契约(`make test-contract`) | 15 schema / 14 有效 fixture / 4 反例 / 兼容锁 / spectral / asyncapi validate | 全绿 |

主要集成验证依赖:PostgreSQL/ClickHouse/Redis Testcontainers(资源、告警、预测、调度、标准化与投影);Kafka 与 MQTT 集成证据来自本地 Compose 纵向实测(尚未固化为 CI Testcontainers 套件)。

## 6. 下一步

1. **T10 运行时收尾:** 在真实 Compose 中验证下发→逐设备回执→服务重启恢复与在线浏览器 E2E;有界自动重试、独立 STOP 权限、站点级授权和命令 WebSocket 作为后续强化,不阻塞当前 `integration_verified` 切片。
2. **T08 实链路补验:** 准备本地完整历史数据集后执行浏览器实链路验证,升级为 `runtime_verified`。
3. **T11 需求响应与效果评估:** DR 状态机、容量评估与缺口展示、目标分解复用审批/指令流程、达成率与质量等级。
4. **T12 规模、故障恢复与发布门禁:** 1,000 设备 2 小时压测、P95 ≤ 3s、组件重启、备份恢复演练、e2e/load 稳定入口。
