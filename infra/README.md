# Local infrastructure

The Compose stack provides PostgreSQL, ClickHouse, single-node Kafka in KRaft mode, Mosquitto, Redis, OpenTelemetry Collector, Prometheus and Grafana.

```bash
cp .env.example .env
make compose-config
make infra-up
make infra-ps
```

All published ports bind to `127.0.0.1`. Values in `.env.example` are intentionally local-only and must never be reused in shared or production environments. Mosquitto anonymous access and plaintext listeners are also local simulation settings; production startup must fail until TLS, per-device credentials and ACLs are configured.

`kafka-init` creates the versioned topics declared by the executable AsyncAPI contract. It is a one-shot container and should exit successfully.

ClickHouse initialization applies `db/clickhouse/init/001_database.sql` and `002_telemetry.sql` on a fresh volume. The stream processor also applies the telemetry DDL idempotently at startup so an existing local volume can be upgraded without deletion. Query normalized business data through `vpp.telemetry_metric_current` or `vpp.metric_15m`, not directly through the physical table when deduplication matters.

OpenTelemetry Collector exposes its local health endpoint on `http://127.0.0.1:13133/`; application readiness will be wired to required dependencies in Phase 1.

Use `make infra-down` to stop containers without deleting named volumes. Volume deletion is deliberately not part of the default Make targets.
