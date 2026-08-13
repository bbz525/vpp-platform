# Stream Processor

`stream-processor` 消费 `vpp.telemetry.raw.v1`，使用 Platform API 的设备目录完成身份补全和测点校验，并把结果写入 Kafka、ClickHouse 与 Redis。

## 处理语义

单条消息按以下顺序处理：

1. 解析原始遥测，并按设备型号/`point_schema` 校验指标、物理范围和标准单位。
2. 根据设备时间、网关接收时间生成 `VALID`/`SUSPECT` 质量状态及 `CLOCK_DRIFT`、`LATE_ARRIVAL`、`SIMULATED_SOURCE` 标记。
3. 以 `event_id` 检查 Redis 短期去重键。
4. 写入 ClickHouse 长表，再发布 normalized telemetry。
5. 用 Redis Lua 原子更新设备、站点和组合快照；旧事件保留历史，但不回退当前状态。
6. 发布设备状态/聚合快照，写入去重键，最后手动提交 Kafka offset。

这是 **at-least-once** 流程，不宣称 exactly-once。瞬时依赖故障会阻止 offset 提交并重试；进程若在完成部分 sink 后退出，ClickHouse 的业务读取视图和 Redis 投影仍按稳定 `event_id`/事件时间得到一致逻辑结果。格式错误、未知设备（超过目录 miss grace period）、未知指标和越界值会写入 `vpp.telemetry.dlq.v1`，不会写入曲线表。

## 本地运行

先启动 Compose 和 Platform API，并确保控制面已有设备：

```bash
make infra-up
mvn -B -ntp -pl apps/platform-api -am package -DskipTests

VPP_ENV=local \
PLATFORM_API_PORT=8080 \
PLATFORM_INTERNAL_TOKEN=local-dev-platform-internal-token-only \
java -jar apps/platform-api/target/platform-api-0.1.0-SNAPSHOT.jar
```

再启动处理器：

```bash
mvn -B -ntp -pl apps/stream-processor -am package -DskipTests

VPP_ENV=local \
STREAM_PROCESSOR_ENABLED=true \
STREAM_PROCESSOR_PORT=8082 \
PLATFORM_API_URL=http://127.0.0.1:8080 \
PLATFORM_INTERNAL_TOKEN=local-dev-platform-internal-token-only \
KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092 \
CLICKHOUSE_JDBC_URL=jdbc:clickhouse://127.0.0.1:8123/vpp \
CLICKHOUSE_USER=vpp \
CLICKHOUSE_PASSWORD=local-dev-clickhouse-only \
REDIS_HOST=127.0.0.1 \
java -jar apps/stream-processor/target/stream-processor-0.1.0-SNAPSHOT.jar
```

默认 `STREAM_PROCESSOR_ENABLED=false`，便于只验证应用骨架。启用后，readiness 只有在 ClickHouse 初始化成功、设备目录已加载且 Kafka listener 已启动时才为 `UP`：

```bash
curl -fsS http://127.0.0.1:8082/actuator/health/readiness
```

## 数据读取

- `vpp.telemetry_metric`：允许 at-least-once 物理重复的长表事实。
- `vpp.telemetry_metric_current`：按 tenant/device/event/metric 和 `event_version` 选择最终版本的业务读取视图。
- `vpp.metric_15m`：基于去重视图计算的 15 分钟曲线。
- `vpp:{tenant}:device:{device}:state`：设备当前状态，默认 TTL 2 小时。
- `vpp:{tenant}:site:{site}:snapshot`、`vpp:{tenant}:portfolio:{portfolio}:snapshot`：当前有功功率聚合，默认 TTL 10 分钟。
- `vpp:{tenant}:dedup:telemetry-normalizer:{event_id}`：短期去重键，默认 TTL 7 天。
- `vpp:{tenant}:ws:portfolio:{portfolio}:snapshot`：按组合隔离的有界 Redis Stream，保存 cursor、发生时间和最小设备增量，默认保留 10 分钟/约 1,000 条。

不要直接把 `ReplacingMergeTree` 的异步 merge 当作查询去重保证，业务查询应使用 `telemetry_metric_current`。

## 验证与重放

```bash
make test-stream
make test-contract
```

自动测试使用真实 ClickHouse/Redis Testcontainers 验证逻辑去重、乱序状态保护和聚合增量。需要重放时使用新的 `STREAM_PROCESSOR_GROUP_ID`，先写入影子表/影子投影并核对 offset、计数和抽样值；不得直接清理现有表或 Redis 状态。回滚消费者时保留旧 schema 和投影至少一个发布窗口。

生产环境启用处理器时，配置门禁要求设备目录使用 HTTPS、内部 token 非示例值、Kafka 传输加密、Redis TLS，以及 ClickHouse HTTPS/SSL JDBC 连接。
