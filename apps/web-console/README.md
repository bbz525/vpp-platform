# Web Console

控制台展示真实 Platform API 快照、ClickHouse 曲线、WebSocket 更新、T07 告警中心、T08 日前预测和 T09 调度中心。预测中心只展示持久化运行和版本，明确区分执行中、数据不足、失败与成功，不包含伪造曲线；调度中心引用冻结的负荷/光伏预测、资费和设备初始 SOC，展示可行性、成本、峰值、96 点计划以及逐设备 SOC/功率目标，不提供 T10 之前的审批或下发入口。

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

实时状态语义见 `docs/08-realtime-ui-contract.md`，告警生命周期见 `docs/09-alarm-center-contract.md`。
