# T06 实时控制台 UI 契约

## 1. 产品闭环

目标用户是值班运营员。用户进入控制台后选择一个有权限的资源组合，先确认数据是否实时且完整，再查看电网、光伏、储能功率和设备明细；发现 stale、partial 或异常质量时下钻到站点/设备定位数据源。T06 的完成点是“运营员能判断当前数据是否可信”，不包含告警处置、预测、调度审批或设备控制。

## 2. 页面模型

单页实时总览包含：

1. 顶栏：当前组合、最后更新时间、WebSocket 连接状态。
2. 可信度条：`LIVE`、`STALE`、`PARTIAL`、`EMPTY`，并给出原因和 REST 同步状态。
3. KPI：电网功率、光伏功率、储能功率、实时设备数；每项必须显示单位和更新时间，缺失值显示 `—`。
4. 曲线：真实 ClickHouse 查询结果，按设备序列展示；没有点时显示明确空状态，不生成插值或装饰数据。
5. 资源下钻：站点列表及其设备，展示设备类型、状态、质量、最后更新时间和标准测点。

## 3. 状态矩阵

| 状态 | API/运行事实 | UI 行为 |
| --- | --- | --- |
| Loading | 首次快照尚未返回 | 保持布局的骨架，不显示数字 0 |
| Empty | 组合存在但没有任何设备状态 | 显示“尚无实时遥测”，KPI 为 `—` |
| Live | 所有有状态设备均在 stale 阈值内且必要测点存在 | 绿色实时标记，显示确切更新时间 |
| Partial | 只有部分设备有状态、必要设备类型缺测或含可疑质量 | 黄色提示并列出覆盖数，不把缺失当 0 |
| Stale | 最新处理时间超过阈值 | 橙色过期标记，保留最后值及其时间 |
| Error | REST 请求失败 | 显示错误和重试按钮，保留最近一次成功值但标为未同步 |
| WS reconnecting | WebSocket 中断 | 显示“正在重连”，继续按 REST 快照展示，不伪装实时 |
| Resync required | cursor 已超出 Redis 保留窗口 | 清除本地 cursor，重新请求 REST 快照后重新订阅 |
| Forbidden | REST 403 或 WS channel 授权失败 | 不展示该对象数据，显示权限不足 |

## 4. 字段与事实源

| UI 字段 | 事实源 | 空值规则 |
| --- | --- | --- |
| 组合/站点/设备名称与状态 | PostgreSQL 控制面 | 对象不存在或跨租户返回 404 |
| 当前指标、质量、处理时间 | Redis 设备状态投影 | 无 hash 时为缺失，不能当 0 |
| 曲线点 | ClickHouse `telemetry_metric_current` | 无点显示空状态 |
| 实时事件/cursor | Redis bounded Stream，经 `/ws/v1` | 过期触发 REST resync |
| 活动告警 | T07 尚未实现 | T06 不显示虚构数量 |

所有浏览器可见配置只能包含公开 API/WS 地址及本地开发身份标识；Kafka、MQTT、Redis、ClickHouse 地址和内部 token 均不得进入客户端 bundle。

## 5. 接口契约

- `GET /api/v1/portfolios`：返回当前租户可见组合。
- `GET /api/v1/portfolios/{portfolioId}/snapshot`：返回组合、站点、设备实时快照以及 freshness/coverage。
- `GET /api/v1/telemetry/query`：返回授权目标、指标和时间范围内的真实曲线。
- `GET /ws/v1`：订阅 `portfolio:{uuid}:snapshot`；服务端逐 channel 授权，并支持 16 位 cursor 恢复、`resync_required` 和有界慢消费者缓冲。

本地模式使用 tenant/subject 开发身份完成握手；生产模式只允许 TLS，并应使用短期 WS token、安全 cookie 或一次性 ticket。长期 token 不得写入 URL。

## 6. 验收边界

- 首次 REST 快照与 WebSocket 更新均来自真实 Compose 数据链路。
- 断开流处理或超过 freshness 阈值后，页面在一个阈值周期内显示 stale。
- 非所属租户的 REST 对象返回 404，WebSocket channel 返回授权错误且不推送数据。
- cursor 在保留窗口内补发；窗口外明确要求 resync。
- 1440px 桌面与 390px 移动视口无横向溢出，键盘可操作组合选择、重试和下钻按钮。
- T06 不实现 T07 告警生命周期、T08 预测或 T09 调度，不用占位数字暗示这些能力已经完成。
