# 实现交接提示词

下面的提示词可直接交给 Claude Code；若使用 Codex，将 `orchestrator-lead` 对应为 `global-coding-architect`，其他职责保持不变。

```text
请使用 orchestrator-lead 负责整体实施与最终集成，按以下规格实现“虚拟电厂与分布式能源调度平台”：

规格入口：vpp-platform/README.md
必须完整阅读：
- vpp-platform/docs/00-product-initiation.md
- vpp-platform/docs/01-prd.md
- vpp-platform/docs/02-domain-model.md
- vpp-platform/docs/03-architecture.md
- vpp-platform/docs/04-event-contracts.md
- vpp-platform/docs/05-data-model.md
- vpp-platform/docs/06-delivery-plan.md

目标：在独立 vpp-platform monorepo 中完成模拟光伏、储能、充电桩和电表的实时接入、Kafka 流处理、ClickHouse 曲线、次日 96 点预测、电价优化调度、审批/指令回执、需求响应、告警、WebSocket 和完整审计闭环。

关键边界：
1. 不修改或重置 vpp-platform/ 外的 Netty 仓库源码与现有未提交改动，不把业务项目加入 Netty 根 pom。
2. 不连接真实设备、真实市场或资金结算，不提交真实凭证；模拟/插值/修正数据必须明确标记。
3. Kafka 使用至少一次 + 业务幂等，不宣称 exactly-once；指令终态不可回退。
4. 算法输出必须经过硬约束校验和审批才能下发；数据不足不得伪造预测；目标值不得当实际值。
5. 所有时间存 UTC、UI 按站点时区显示；功率 kW、电量 kWh、价格 CNY/kWh。

角色与阶段：
- 产品经理确认 PRD、业务状态和 Phase 0 开放问题。
- 架构负责人先完成 T00/T01，冻结共享 REST/MQTT/TCP/Kafka/WS 与数据契约。
- Java 后端负责 T02–T05、T07、T09–T10；前端 UI 负责 T06、T07、T08/T09/T10 对应页面；AI/数据工程负责 T08；QA/DevOps 贯穿验证并负责 T12。
- 串行：Phase 0 → Phase 1 → Phase 2；并行：T02/T03/T04 的不重叠部分，T08 与 T09；之后串行集成 T10 → T11 → T12。
- 每个并行任务必须声明独占文件、依赖和禁止触碰范围；共享契约由架构负责人合并。

执行要求：
1. 从 docs/06-delivery-plan.md 的 T00 开始，按依赖执行；不要一次性生成所有空壳微服务。
2. 每个阶段先更新计划，明确产品/API/数据/UI/运行时契约，再实现最小纵向闭环。
3. 使用项目内稳定入口提供 build、unit、contract、integration、e2e、load 和 restore drill。
4. 优先测试重复、乱序、越权、数据不足、断流假实时、指令超时/迟到、服务重启和调度硬约束。
5. 每阶段报告状态：implemented、unit_verified、contract_verified、runtime_verified 或 product_verified；未验证不得宣称完成。

MVP 最终验收：
- 1,000 台模拟设备每 5 秒上报，持续 2 小时；有效遥测进入 Kafka ≥ 99.9%。
- 接入至 WebSocket 可见 P95 ≤ 3 秒；断流时 UI 明确 stale。
- MQTT 与 Netty TCP 同一 fixture 得到语义一致的标准事件；重复不重复计效，乱序不回退当前状态。
- 次日预测覆盖 96 点并带模型/数据截止/质量；不足时返回 INSUFFICIENT_DATA。
- 所有计划满足 SOC、功率、容量、效率、可用性等硬约束；不可行结果不能审批。
- 未审批不下发；命令具有幂等、尝试、超时、终态和重启恢复；紧急停止可验证。
- 按 schedule_id 可还原预测、电价、配置、算法、审批、指令、回执、实测和效果。
- 两租户 API、DB 投影和 WebSocket 路径的越权测试通过。

完成每个任务时输出：改了什么、文件列表、验证命令和结果、当前进度状态、剩余风险、下一项依赖。禁止顺手修改无关文件，禁止删除或覆盖用户现有变更。
```

## 首轮建议

首轮只执行 T00 和 T01：确认 Phase 0 开放问题、建立独立构建/Compose 骨架、把文档契约变成可 lint 的 schema 和共享 fixtures。它们通过后再同时启动模拟器、Gateway 与资源控制面，能显著减少后续跨服务返工。
