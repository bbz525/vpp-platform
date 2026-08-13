#!/usr/bin/env bash
set -euo pipefail

topics=(
  "vpp.telemetry.raw.v1:3:delete"
  "vpp.telemetry.normalized.v1:7:delete"
  "vpp.device-state.changed.v1:1:compact,delete"
  "vpp.aggregate-snapshot.v1:1:delete"
  "vpp.alarm.events.v1:30:delete"
  "vpp.forecast.events.v1:30:delete"
  "vpp.schedule.events.v1:90:delete"
  "vpp.command.requests.v1:7:delete"
  "vpp.command.events.v1:30:delete"
  "vpp.audit.events.v1:365:delete"
  "vpp.telemetry.dlq.v1:30:delete"
)

for spec in "${topics[@]}"; do
  IFS=: read -r topic days cleanup <<<"${spec}"
  retention_ms=$((days * 24 * 60 * 60 * 1000))
  /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka:19092 \
    --create --if-not-exists \
    --topic "${topic}" \
    --partitions 3 \
    --replication-factor 1 \
    --config "retention.ms=${retention_ms}" \
    --config "cleanup.policy=${cleanup}"
done

