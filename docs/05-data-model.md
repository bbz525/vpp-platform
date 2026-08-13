# 数据模型

## 1. 存储职责

| 存储 | 保存内容 | 一致性/恢复定位 |
| --- | --- | --- |
| PostgreSQL | 租户、资源、设备配置、电价、预测元数据、计划、指令状态、DR、告警工作流、审计、Outbox | 控制面事实源，事务与约束 |
| ClickHouse | 原始/标准遥测、15 分钟曲线、预测点、计划点、执行与效果分析事实 | 高频历史与分析，可由 Kafka/对象备份重建部分数据 |
| Redis | 设备/站点/组合当前快照、短期去重、WebSocket resume 缓冲、分布式短锁 | 可重建缓存，不保存唯一业务事实 |
| Kafka | 有限保留的事件日志和异步集成 | 重放窗口，不替代永久审计/备份 |

## 2. PostgreSQL 关系模型

### 2.1 资源与配置

| 表 | 主键/关键字段 | 约束与索引 |
| --- | --- | --- |
| `tenant` | `id`, `name`, `status` | 名称可按业务唯一 |
| `app_user` | `id`, `tenant_id`, `external_subject`, `status` | unique `(tenant_id, external_subject)` |
| `portfolio` | `id`, `tenant_id`, `name` | index `(tenant_id, status)` |
| `site` | `id`, `tenant_id`, `portfolio_id`, `timezone`, `grid_connection_limit_kw` | FK 同租户通过应用+RLS保证 |
| `device_model` | `id`, `tenant_id`, `type`, `schema_version`, `point_schema_json` | unique `(tenant_id, name, schema_version)` |
| `device` | `id`, `tenant_id`, `site_id`, `model_id`, `external_code`, `status`, `config_version` | unique `(tenant_id, external_code)`；index `(site_id, status)` |
| `device_capability` | `device_id`, `capability`, bounds/efficiency | unique `(device_id, capability)` |
| `credential_ref` | `id`, `device_id`, `secret_ref`, `status`, `rotated_at` | 不保存/返回明文 secret |
| `tariff_plan` | `id`, `tenant_id`, `name`, `currency`, `timezone`, `version`, `valid_from/to` | unique `(tenant_id, name, version)` |
| `tariff_interval` | `tariff_plan_id`, `interval_start/end`, `price_per_kwh` | 排除重叠区间或在导入时校验 |

### 2.2 预测、调度与执行

| 表 | 关键字段 | 说明 |
| --- | --- | --- |
| `forecast_run` | `id`, tenant/target/metric, `status`, data cutoff, model version, quality JSON | 一次预测任务 |
| `forecast_version` | `id`, `forecast_run_id`, `version`, source, overridden_by/reason | 原始与人工修正版本 |
| `schedule` | `id`, tenant/portfolio, local date/timezone, status/feasibility, current version, input JSON/reasons | T09 候选计划身份；V6 状态允许 `VALIDATED`、`FAILED`、`APPROVED`、`CANCELLED` |
| `schedule_version` | `id`, `schedule_id`, version, input refs, algorithm, status, summary JSON, content hash | 不可变版本 |
| `schedule_interval` | version, interval, load/PV/price, baseline/planned grid and costs | unique `(version, interval_start)` |
| `schedule_target` | version, device, interval, charge/discharge/setpoint and SOC start/end | unique `(version, device, interval_start)` |
| `device_config_snapshot` | tenant/portfolio, canonical JSON, SHA-256, captured at | 生成时能力、站点限值和初始 SOC 的不可变证据 |
| `schedule_approval` | `id`, tenant/schedule/version, `APPROVE|REJECT`, actor, reason, idempotency key, decided at | V6 每个租户计划只有一个不可变终局决定；幂等键租户内唯一 |
| `command` | `id`, tenant/device/schedule/version/parent, idempotency key, action/parameters, safety config version, status/deadlines/terminal, reason/message/actual, lock version | unique `(tenant_id, idempotency_key)`；请求参数和设备 actual 分列 |
| `command_attempt` | `id`, tenant/command, attempt no, dispatch/latest ack/error | unique `(command_id, attempt_no)`；每次物理发送独立追加 |
| `command_event` | `id`, tenant/command/source event, event/from/reported status, applied, reason/message/actual, occurred/received at | 回执与平台转换时间线；`source_event_id` 租户内去重并由触发器禁止更新/删除 |
| `demand_response_event` | identity, target, interval, baseline method/version, status | 响应生命周期 |
| `dr_allocation` | DR event, site/device, committed kW, schedule ref | 分解结果 |
| `evaluation` | object type/id, baseline ref, status, metrics JSON, data quality | 结果事实，不覆盖计划 |

### 2.3 V6 审批与命令事实约束

Flyway `V6__schedule_approval_and_commands.sql` 已把 T10 控制面事实加入 PostgreSQL：

- `schedule_approval` 通过 `(tenant_id, schedule_id)` 唯一约束保证当前 MVP 每个计划只有一个终局决定，通过 `(tenant_id, idempotency_key)` 稳定重放同一写请求；更新和删除由 `schedule_approval_append_only` 拒绝。
- 普通 `SET_POWER` 与独立动作类型 `STOP` 共用 `command` 状态机。`parameters_json` 只保存请求目标，`actual_json` 只保存匹配回执声明；`parent_command_id` 关联人工紧急停止生成的逐设备 STOP 与原控制命令，计划末尾 STOP 则直接关联计划版本。对计划内命令调用 STOP 是计划级动作：未来 `CREATED SET_POWER` 被取消、每台参与设备各有 STOP，计划进入 `CANCELLED`；只有无计划关联命令才生成单设备 STOP。
- `not_before`、`expires_at` 与非终态索引支持数据库恢复扫描；`terminal_at`、`last_reason_code`、`last_message` 保留 canonical 结果。`safety_config_version` 固定下发时所依据的设备配置版本。
- `command_attempt` 保存每次实际下发；`command_event` 保存是否应用的状态声明，`occurred_at` 与 `received_at` 分离，且更新/删除由 `command_event_append_only` 拒绝。`processed_event` 以 `(consumer, event_id)` 作为消费幂等门禁。
- V6 的复合外键把 schedule、version、device、parent command 固定在同一 tenant；`expires_at > not_before` 由数据库检查。自动退避重试、独立 STOP 权限、站点级对象授权和命令 WebSocket 投影仍属于应用层后续强化，不能从这些表或索引推断为已实现。

### 2.4 告警、审计与集成

| 表 | 关键字段 | 说明 |
| --- | --- | --- |
| `alarm_rule` | tenant, code, object type, expression/config, severity, version, enabled | 规则版本化 |
| `alarm` | id, tenant, rule/object, dedup key, state, first/last occurrence, count | 活动生命周期 |
| `alarm_transition` | alarm, from/to, actor, reason, evidence JSON, at | 追加时间线 |
| `audit_event` | id, tenant, actor, action, object, before/after digest, correlation/trace, result, at | 追加式、分区/归档候选 |
| `outbox_event` | id, aggregate, topic/key, payload, created/published/attempts | relay 可重试 |
| `processed_event` | consumer, event id, processed at | 需要强幂等的控制面消费者 |

## 3. PostgreSQL 核心 DDL 草案

以下是实现起点，不替代迁移工具。所有表都应有 `created_at`，可变配置表应有 `updated_at` 和乐观锁版本。

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE schedule (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenant(id),
    portfolio_id        uuid NOT NULL REFERENCES portfolio(id),
    schedule_date       date NOT NULL,
    site_timezone       text NOT NULL,
    status              varchar(24) NOT NULL,
    feasibility         varchar(24) NOT NULL,
    current_version     integer,
    failure_code        varchar(80),
    reasons_json        jsonb NOT NULL DEFAULT '[]'::jsonb,
    input_json          jsonb NOT NULL,
    created_by          uuid NOT NULL REFERENCES app_user(id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    lock_version        bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, portfolio_id, schedule_date, id)
);

CREATE TABLE schedule_version (
    id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                   uuid NOT NULL REFERENCES tenant(id),
    schedule_id                 uuid NOT NULL REFERENCES schedule(id),
    version                     integer NOT NULL CHECK (version > 0),
    load_forecast_version_id    uuid NOT NULL REFERENCES forecast_version(id),
    pv_forecast_version_id      uuid NOT NULL REFERENCES forecast_version(id),
    tariff_plan_id              uuid NOT NULL REFERENCES tariff_plan(id),
    device_config_snapshot_id   uuid NOT NULL,
    algorithm_name              text NOT NULL,
    algorithm_version           text NOT NULL,
    objective_value             numeric(20, 6),
    summary_json                jsonb NOT NULL,
    content_sha256              char(64) NOT NULL,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (schedule_id, version)
);

CREATE TABLE command (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL REFERENCES tenant(id),
    device_id           uuid NOT NULL REFERENCES device(id),
    schedule_version_id uuid REFERENCES schedule_version(id),
    idempotency_key     text NOT NULL,
    action              varchar(48) NOT NULL,
    parameters_json     jsonb NOT NULL,
    status              varchar(24) NOT NULL,
    not_before          timestamptz NOT NULL,
    expires_at          timestamptz NOT NULL,
    terminal_at         timestamptz,
    lock_version        bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, idempotency_key),
    CHECK (expires_at > not_before)
);

CREATE INDEX command_dispatch_idx
    ON command (status, not_before)
    WHERE status IN ('CREATED', 'DISPATCHED', 'ACCEPTED', 'EXECUTING');

CREATE TABLE outbox_event (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL,
    aggregate_type  varchar(64) NOT NULL,
    aggregate_id    text NOT NULL,
    topic           text NOT NULL,
    message_key     text NOT NULL,
    payload_json    jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz,
    attempts        integer NOT NULL DEFAULT 0,
    last_error_code text
);

CREATE INDEX outbox_unpublished_idx
    ON outbox_event (created_at)
    WHERE published_at IS NULL;

CREATE TABLE audit_event (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenant(id),
    actor_type      varchar(24) NOT NULL,
    actor_id        text NOT NULL,
    action          text NOT NULL,
    object_type     text NOT NULL,
    object_id       text NOT NULL,
    reason          text,
    before_digest   char(64),
    after_digest    char(64),
    correlation_id  text,
    trace_id        char(32),
    result          varchar(24) NOT NULL,
    metadata_json   jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at     timestamptz NOT NULL
);

CREATE INDEX audit_object_time_idx
    ON audit_event (tenant_id, object_type, object_id, occurred_at DESC);
```

实现迁移时应使用 Flyway/Liquibase 分阶段建表；跨租户外键需要复合唯一键或触发器/RLS 辅助，不能只依赖调用方传入正确 `tenant_id`。

## 4. ClickHouse 模型

### 4.1 标准遥测长表

采用一测点一行，便于按指标聚合和压缩。原 payload 是否单独归档由保留成本决定。

```sql
CREATE TABLE telemetry_metric
(
    tenant_id       UUID,
    portfolio_id    UUID,
    site_id         UUID,
    device_id       UUID,
    device_type     LowCardinality(String),
    event_id        UUID,
    sequence        UInt64,
    device_time     DateTime64(3, 'UTC'),
    ingested_at     DateTime64(3, 'UTC'),
    metric_name     LowCardinality(String),
    metric_value    Float64,
    unit            LowCardinality(String),
    quality_status  LowCardinality(String),
    quality_flags   Array(LowCardinality(String)),
    source          LowCardinality(String),
    event_version   UInt64
)
ENGINE = ReplacingMergeTree(event_version)
PARTITION BY toYYYYMM(device_time)
ORDER BY (tenant_id, site_id, device_id, metric_name, device_time, event_id)
TTL device_time + INTERVAL 365 DAY DELETE;
```

ReplacingMergeTree 的合并是异步的，普通查询不能假设物理重复已经消失。面向业务的曲线视图应使用稳定业务键与 `argMax(metric_value, event_version)`，或读取已去重聚合表；不要在所有在线查询中滥用 `FINAL`。

T05 已将该草案落为 `db/clickhouse/init/002_telemetry.sql`：`telemetry_metric_current` 按 tenant/device/event/metric 选取最终版本，`metric_15m` 基于该去重视图实时聚合。应用启动也会幂等执行同一 DDL，因此已有 Compose volume 无需删除即可升级。业务读取必须使用这些视图，原表仅作为可重放事实。

### 4.2 15 分钟聚合曲线

```sql
CREATE TABLE metric_15m
(
    tenant_id       UUID,
    site_id         UUID,
    device_id       UUID,
    metric_name     LowCardinality(String),
    interval_start  DateTime('UTC'),
    avg_value       Float64,
    min_value       Float64,
    max_value       Float64,
    last_value      Float64,
    sample_count    UInt32,
    valid_count     UInt32,
    calculated_at   DateTime64(3, 'UTC')
)
ENGINE = ReplacingMergeTree(calculated_at)
PARTITION BY toYYYYMM(interval_start)
ORDER BY (tenant_id, site_id, device_id, metric_name, interval_start);
```

功率的 15 分钟平均用于能量估算；累计电量应按单调计数器差值并处理重置，不能简单求和。

### 4.3 预测、计划与实际对比

`forecast_point`、`schedule_point`、`actual_interval` 使用共同维度：tenant、target type/id、metric、interval_start/end、value、unit、version/source、quality。这样可用同一查询连接预测、计划、基线与实际，但每张事实表仍保留自身不可变 ID。

## 5. Redis 键设计

```text
vpp:{tenant}:device:{device}:state           HASH / TTL 2h
vpp:{tenant}:site:{site}:snapshot            HASH / TTL 10m
vpp:{tenant}:portfolio:{portfolio}:snapshot  HASH / TTL 10m
vpp:{tenant}:dedup:{consumer}:{event_id}      STRING / TTL >= Kafka retry window
vpp:{tenant}:ws:{channel}:resume              STREAM / bounded length + TTL
vpp:{tenant}:lock:schedule:{portfolio}:{date} STRING / short TTL, token-checked release
```

Redis Cluster 下 `{tenant}` hash tag 可保证相关键槽位，但大租户可能形成热点；实现压测后再决定是否以对象 ID 分散。

## 6. 数据质量与血缘

每个进入分析的数据点至少携带：source、device_time、ingested_at、quality status/flags、schema version、event ID。预测额外记录数据截止偏移/时间、特征版本、模型工件哈希；计划记录全部输入引用和内容哈希；人工修正记录原值、新值、原因和操作者。

常见质量标记：

- `CLOCK_DRIFT`, `LATE_ARRIVAL`, `OUT_OF_RANGE`, `DUPLICATE`
- `COUNTER_RESET`, `MISSING_INTERVAL`, `INTERPOLATED`, `MANUAL_OVERRIDE`
- `SIMULATED_SOURCE`, `UNKNOWN_UNIT`, `SCHEMA_MISMATCH`

## 7. 保留、备份与隐私

- 原始/标准遥测默认 365 天，15 分钟聚合和调度效果默认 3 年；生产环境按法规和成本确认。
- PostgreSQL 做每日全量/连续 WAL（生产）；恢复演练必须验证 RPO/RTO，而不是只确认备份文件存在。
- 模型工件、批量导入和长期归档可使用对象存储并保存校验哈希。
- 审计与日志避免保存凭证、完整 token、无关个人信息；用户导出和删除策略需与审计保留义务协调。

## 8. 迁移与重放策略

1. 先部署向后兼容 schema，再部署生产者，最后升级消费者必需字段。
2. 新投影使用影子表和独立 consumer group 重放，核对计数、时间范围、抽样值与业务不变量。
3. 切换读取前记录 Kafka 起止 offset 和校验报告；失败时回到旧投影，不删除旧表。
4. ClickHouse 大规模 backfill 分分区执行并限流，避免影响在线插入。
5. 任何破坏性迁移必须有恢复路径；MVP 阶段禁止把手工 SQL 当作唯一部署步骤。
6. V6 是向前扩展迁移：先放宽 `schedule.status`，再创建审批、命令、attempt、事件和消费去重表；回滚应用前必须停止新审批/命令创建并接管非终态命令，不能通过删除 V6 事实清队列。
