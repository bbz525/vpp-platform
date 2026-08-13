# Executable contracts

- `openapi/`: HTTP control-plane contract (OpenAPI 3.1).
- `asyncapi/`: MQTT, Kafka and WebSocket topology (AsyncAPI 3.1).
- `schemas/`: JSON Schema 2020-12 payload contracts. These are the message source of truth.
- `fixtures/`: shared valid and negative examples consumed by all language implementations.
- `compatibility/v1-lock.json`: minimum v1 fields and primitive types that cannot change in place.
- `tests/`: schema, fixture, semantic-equivalence, secret-leak and compatibility gates.

Run:

```bash
npm install
make lint-contract
make test-contract
```

Compatibility policy:

1. Existing required fields and their meaning/type cannot be removed or changed within a major version.
2. Event consumers must tolerate additive optional envelope fields; producers must not start requiring a newly added field from old events.
3. Breaking changes get a new schema ID and major topic/operation contract.
4. JSON Schema is used without a runtime Schema Registry in Phase 0. Adding a registry later requires an ADR and migration plan.
5. Credentials, passwords and tokens are forbidden in event schemas and fixtures.
