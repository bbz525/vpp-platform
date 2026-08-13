# ADR-003：事件契约采用 JSON Schema 2020-12

- **状态：** Accepted
- **日期：** 2026-08-12

## 背景

MQTT、Kafka、WebSocket、Java、TypeScript 和 Python 需要共享同一消息语义。Phase 0 不能假设已部署 Schema Registry，也不能让某一语言生成物成为契约事实源。

## 决策

- Payload 使用 JSON Schema 2020-12，稳定 `$id` 与 major 版本文件名。
- AsyncAPI 3.1.0 描述 MQTT/Kafka/WebSocket channel、方向和 payload 引用。
- OpenAPI 3.1 描述控制面 HTTP API。
- Kafka 使用 JSON payload + `schema`/`schema_version`，不在 Phase 0 引入 Registry。
- CI 用共享 fixture、负例和 `compatibility/v1-lock.json` 阻止 v1 必需字段删除与类型变化。

## 结果

优点是三种语言可以直接校验、契约可离线测试、基础设施简单。代价是没有 Registry 的服务端兼容策略与二进制压缩；消费者仍需实现未知字段容忍和 DLQ。若吞吐、治理或跨团队规模需要 Avro/Protobuf Registry，必须通过新 ADR 评估兼容与迁移，不能静默替换现有 v1。
