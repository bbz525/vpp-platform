# ADR-001：控制面模块化单体与独立数据面进程

- **状态：** Accepted
- **日期：** 2026-08-12

## 决策

Phase 0 建立四个 Java 进程骨架：`platform-api`、`iot-gateway`、`stream-processor`、`device-simulator`。预测服务为独立 Python 进程，运营控制台为 Next.js 应用。控制面业务后续在 `platform-api` 内按领域模块组织，不提前拆成多个微服务。

接入、流处理和预测因协议、伸缩与故障边界不同而独立。项目保持独立 Maven reactor，不加入 Netty 上游根构建。

## 结果

该边界能展示 Netty/实时计算/AI，同时维持可控的本地运行成本。后续只有在明确的伸缩、发布或故障隔离证据出现时才拆分控制面模块。

