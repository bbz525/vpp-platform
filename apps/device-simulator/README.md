# 设备模拟器

T02 模拟器以固定 seed 生成电表、光伏逆变器、储能和充电桩的可重放时序数据。默认关闭，启用后通过 MQTT、VPP1 TCP 或两种协议同时上报。

## 快速运行

先启动本地依赖并构建：

```bash
make infra-up
make test-simulator
mvn -B -ntp -Dmaven.repo.local=/tmp/vpp-platform-m2 \
  -pl apps/device-simulator -am package -DskipTests
```

启动 4 台 MQTT 模拟设备：

```bash
SIMULATOR_ENABLED=true \
SIMULATOR_DEVICE_COUNT=4 \
SIMULATOR_SEED=20260812 \
java -jar apps/device-simulator/target/device-simulator-0.1.0-SNAPSHOT.jar
```

状态端点：`GET http://127.0.0.1:8083/api/v1/simulator/status`。Actuator 继续提供 `/livez`、`/readyz` 和 `/actuator/prometheus`。

## 场景与故障

- 场景：`SUNNY_WEEKDAY`、`CLOUDY_WEEKDAY`、`SUNNY_WEEKEND`。
- 故障：`NONE`、`DISCONNECT`、`CLOCK_DRIFT`、`DUPLICATE`、`OUT_OF_RANGE`。
- `SIMULATOR_FAULT_EVERY_NTH_TICK` 控制注入频率，`SIMULATOR_FAULT_TARGET_DEVICE_INDEX` 指定目标设备。
- 重复注入会重发相同 `event_id`；越界注入只污染上报值，不突破模拟器内部 SOC/功率安全状态。
- 遥测、心跳和命令回执都带 `data_quality_source=SIMULATED`，不得作为真实测量或模型精度证据。

主要配置均在 `application.yaml` 中定义，可由同名环境变量覆盖：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `SIMULATOR_DEVICE_COUNT` | `4` | 最大 10,000；按四种类型轮转建模 |
| `SIMULATOR_SEED` | `20260812` | 相同 seed、场景和起始时间产生相同曲线 |
| `SIMULATOR_TICK_INTERVAL` | `5s` | 上报与仿真步长 |
| `SIMULATOR_HEARTBEAT_INTERVAL` | `15s` | 不得短于 tick |
| `SIMULATOR_MAX_TICKS` | `0` | `0` 表示持续运行 |
| `SIMULATOR_PROTOCOL` | `MQTT` | `MQTT`、`TCP` 或 `BOTH` |

## 指令与 TCP 边界

MQTT 客户端订阅每台设备自己的 `down/command`，支持 `SET_POWER`、`STOP`、`SET_ACTIVE_POWER_LIMIT`、`SET_CHARGE_LIMIT`、`PAUSE` 和 `RESUME`。首次执行发布 `ACCEPTED → EXECUTING → SUCCEEDED/FAILED`；重复 `idempotency_key` 只返回缓存终态，不再次执行。

TCP 使用 VPP1 固定头、65,536 字节 payload 上限和可选 TLS。启用 TCP 时必须提供 `SIMULATOR_TCP_CREDENTIAL`，非回环地址禁止明文或 `insecure-trust-all`。客户端收到 `AUTH_OK(100)` 后才上报。TCP 真实联调要等 T03 网关服务落地；当前由 codec 和边界测试覆盖。
