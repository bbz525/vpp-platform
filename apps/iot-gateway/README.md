# IoT Gateway

`iot-gateway` 负责把 MQTT 3.1.1 与 VPP1/TCP 设备消息统一校验后写入 Kafka，并将命令请求优先通过活跃 TCP 会话、否则通过 MQTT 下发。

## 本地运行

先启动 Compose 依赖并构建可执行包：

```bash
make infra-up
make test-gateway
mvn -B -ntp -Dmaven.repo.local=/tmp/vpp-platform-m2 \
  -pl apps/iot-gateway -am package -DskipTests
```

启用本地 MQTT、TCP 和命令路由：

```bash
IOT_GATEWAY_MQTT_ENABLED=true \
IOT_GATEWAY_TCP_ENABLED=true \
IOT_GATEWAY_COMMANDS_ENABLED=true \
IOT_GATEWAY_PORT=18080 \
java -jar apps/iot-gateway/target/iot-gateway-0.1.0-SNAPSHOT.jar
```

状态与健康检查：

```bash
curl http://127.0.0.1:18080/api/v1/gateway/status
curl http://127.0.0.1:18080/actuator/health/readiness
```

## 入站契约

- MQTT 上行：`vpp/{tenant_id}/{device_id}/up/{telemetry|heartbeat|command-ack}`，QoS 1。
- MQTT 下行：`vpp/{tenant_id}/{device_id}/down/command`，QoS 1、非 retained。
- TCP：VPP1 固定 28 字节头；首帧必须是 `AUTH` 且序列为 0；认证后业务帧序列必须严格递增。
- 单条载荷上限 64 KiB。TCP 在分配载荷前按长度头拒绝超长帧。
- 遥测原始 JSON 不做字段重排即写入 `vpp.telemetry.raw.v1`，Kafka key 为 `{tenant_id}:{device_id}`，并附加 `vpp-protocol`、`vpp-received-at` 头。
- 指令回执写入 `vpp.command.events.v1`；指令请求消费自 `vpp.command.requests.v1`。

事件循环只负责帧/主题解析和有界任务提交。JSON 校验及 Kafka 发送位于固定大小工作池；工作队列、Kafka in-flight 和生产者缓冲均有硬上限。队列或 in-flight 满时拒绝新消息，不创建无限内存积压。

## 身份与安全边界

默认内置身份表只服务于显式开启的本地联调模式。关闭 `IOT_GATEWAY_LOCAL_IDENTITY_ENABLED` 后，网关在启动线程及调度线程从 Platform API 获取 ACTIVE 设备快照，以不可变 Map 原子替换本地缓存；Netty EventLoop 不访问 HTTP 或数据库。设备停用后，下一次刷新会移除身份，后续消息和重连均被拒绝。

生产模式具有以下失败门禁：

- 禁止 `IOT_GATEWAY_LOCAL_IDENTITY_ENABLED=true`；
- 控制面 URL 必须为 HTTPS，内部令牌必须安全注入；
- 启用 MQTT/TCP 时必须启用 TLS；
- TCP TLS 必须提供证书链与私钥；
- 明文 TCP 只能绑定 loopback 地址；
- `scripts/validate-env.mjs` 同步检查上述配置与 MQTT 密码。

日志只记录协议、设备 ID、Kafka key 和错误类别，不记录消息载荷或凭证明文。网关只接收 HMAC 凭据校验值，不接收控制面存储的凭证明文；MQTT Broker 侧逐设备认证与主题 ACL 仍需在生产接入方案中配置。

## 关键配置

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | `127.0.0.1:9092` | Kafka 地址 |
| `IOT_GATEWAY_MQTT_ENABLED` | `false` | 启用 MQTT 接入 |
| `IOT_GATEWAY_TCP_ENABLED` | `false` | 启用 TCP 接入 |
| `IOT_GATEWAY_COMMANDS_ENABLED` | `false` | 启用 Kafka 下行路由 |
| `IOT_GATEWAY_INGRESS_WORKERS` | `4` | 校验工作线程数 |
| `IOT_GATEWAY_INGRESS_QUEUE_CAPACITY` | `1024` | 有界工作队列 |
| `IOT_GATEWAY_MAX_IN_FLIGHT_KAFKA` | `2048` | Kafka 异步发送并发上限 |
| `IOT_GATEWAY_MAX_PAYLOAD_BYTES` | `65536` | 单消息最大字节数 |
| `IOT_GATEWAY_TCP_IDLE_TIMEOUT` | `30s` | TCP 全空闲断开阈值 |
| `IOT_GATEWAY_LOCAL_IDENTITY_ENABLED` | `true` | 仅本地开发使用内置身份；生产必须为 `false` |
| `IOT_GATEWAY_CONTROL_PLANE_URL` | `http://127.0.0.1:8080` | Platform API 地址 |
| `IOT_GATEWAY_CONTROL_PLANE_TOKEN` | 本地占位值 | 内部身份快照令牌，必须与 Platform API 一致 |
| `IOT_GATEWAY_IDENTITY_REFRESH_INTERVAL` | `2s` | ACTIVE 设备身份快照刷新周期 |

完整本地默认值见根目录 `.env.example` 与 `application.yaml`。

## 已知边界

- 当前本地 MQTT Broker 允许匿名连接，不代表生产 ACL 已完成。
- 控制面快照是短周期拉取模型；控制面不可用时继续使用最后一次成功快照，超过三个刷新周期后 readiness 失败。
- MQTT QoS 1 的 Broker 确认与 Kafka 持久化尚未形成事务边界；Kafka 异步失败会计数并记录，T05/T12 需要补充重放与故障注入门禁。
- 下行路由只保证传输选择和消费重试；命令状态持久化、Outbox、超时和终态幂等属于 T10。
- 当前 Compose 联调已覆盖 MQTT/TCP/Kafka/命令回执；CI 中的容器化集成套件仍需在后续测试任务中固化。
