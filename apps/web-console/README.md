# Web Console

控制台展示真实 Platform API 快照、ClickHouse 曲线、WebSocket 更新、T07 告警中心、T08 日前预测，以及 T09/T10 调度、审批和命令执行事实。预测中心只展示持久化运行和版本，明确区分执行中、数据不足、失败与成功，不包含伪造曲线；调度中心引用冻结的负荷/光伏预测、资费和设备初始 SOC，展示可行性、成本、峰值、96 点计划以及逐设备 SOC/功率目标。

审批区只允许对 `VALIDATED + FEASIBLE` 且存在当前不可变版本的计划提交决定，批准和拒绝原因均必填；服务端结果返回前不显示成功。批准后，执行区以分页的 `GET /schedules/{scheduleId}/execution?offset=...&limit=...` 作为命令详情、attempt、回执事件与审计事实的唯一页面数据源，依据 `command_total` 显示范围并提供前后翻页，不能把默认前 100 条静默表示为完整执行链。请求参数不能作为实际值，无回执、空列表和缺少 actual 均显示为未知/空状态，不补零或生成占位记录。命令行上的 STOP 入口实际发起计划级紧急停止：取消同计划未来普通命令、为每台参与设备创建 STOP 并把计划改为 `CANCELLED`；无计划关联时才是单设备 STOP。界面必须二次确认影响范围，也不得把提交成功表示为任何设备已经停止。

当前执行监控采用 REST 刷新。独立 STOP 权限、自动重试、WebSocket 命令推送和站点级授权未实现，仍需后续强化与浏览器/跨服务 E2E；当前页面能力不等于完整 T10 产品闭环已经验证。

```bash
NEXT_PUBLIC_PLATFORM_API_BASE=http://127.0.0.1:8080 \
NEXT_PUBLIC_VPP_TENANT_ID=7fdc2ef7-3b7d-4a43-a37c-63cc4b36a941 \
NEXT_PUBLIC_VPP_SUBJECT=local-admin \
npm run dev --workspace @vpp/web-console -- --hostname 127.0.0.1 --port 3000
```

`NEXT_PUBLIC_VPP_*` 仅用于本地开发身份；生产浏览器应使用平台 JWT/cookie 身份，并通过一次性 ticket 握手 WebSocket。任何内部 token、Kafka/MQTT/Redis/ClickHouse 地址都不能配置成 `NEXT_PUBLIC_*`。

验证：

```bash
make test-web
npm run build --workspace @vpp/web-console
```

实时状态语义见 `docs/08-realtime-ui-contract.md`，告警生命周期见 `docs/09-alarm-center-contract.md`，审批与命令真实性边界见 `docs/11-command-execution-contract.md`。
