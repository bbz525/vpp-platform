import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import YAML from "yaml";

const contractsDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const schemaDir = path.join(contractsDir, "schemas");
const fixtureDir = path.join(contractsDir, "fixtures");

async function listFiles(directory, suffix) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.map(async (entry) => {
    const absolute = path.join(directory, entry.name);
    return entry.isDirectory() ? listFiles(absolute, suffix) : (entry.name.endsWith(suffix) ? [absolute] : []);
  }));
  return nested.flat();
}

async function readJson(file) {
  return JSON.parse(await readFile(file, "utf8"));
}

const schemaFiles = await listFiles(schemaDir, ".schema.json");
assert(schemaFiles.length >= 9, "expected executable schemas for all core message families");

const schemas = await Promise.all(schemaFiles.map(readJson));
const schemaById = new Map(schemas.map((schema) => [schema.$id, schema]));
assert.equal(schemaById.size, schemas.length, "every JSON Schema must have a unique $id");

const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
for (const schema of schemas) ajv.addSchema(schema);

const manifest = await readJson(path.join(fixtureDir, "manifest.json"));
for (const item of manifest.valid) {
  const fixture = await readJson(path.join(fixtureDir, item.fixture));
  const validate = ajv.getSchema(item.schema);
  assert(validate, `schema not registered: ${item.schema}`);
  assert(validate(fixture), `${item.fixture} should be valid: ${ajv.errorsText(validate.errors)}`);
  assert.deepEqual(JSON.parse(JSON.stringify(fixture)), fixture, `${item.fixture} must JSON round-trip`);
}

for (const item of manifest.invalid) {
  const fixture = await readJson(path.join(fixtureDir, item.fixture));
  const validate = ajv.getSchema(item.schema);
  assert(validate, `schema not registered: ${item.schema}`);
  assert.equal(validate(fixture), false, `${item.fixture} must remain invalid`);
}

const mqtt = await readJson(path.join(fixtureDir, "mqtt/telemetry-up.valid.json"));
const normalized = await readJson(path.join(fixtureDir, "events/normalized-telemetry.valid.json"));
assert.equal(normalized.event_id, mqtt.event_id, "protocol adapters must preserve event_id");
assert.equal(normalized.payload.sequence, mqtt.sequence, "protocol adapters must preserve sequence");
assert.equal(normalized.occurred_at, mqtt.device_time, "protocol adapters must preserve device time");
assert.deepEqual(normalized.payload.metrics, mqtt.metrics, "MQTT and normalized fixture metrics must be semantically equal");

const normalizedValidator = ajv.getSchema("https://vpp.local/schemas/events/normalized-telemetry.v1.schema.json");
assert(normalizedValidator({ ...normalized, future_optional_field: "ignored-by-v1-consumer" }),
  "event envelopes must tolerate additive optional fields within the same major version");

const compatibility = await readJson(path.join(contractsDir, "compatibility/v1-lock.json"));
for (const [schemaId, lock] of Object.entries(compatibility)) {
  const schema = schemaById.get(schemaId);
  assert(schema, `compatibility lock references missing schema: ${schemaId}`);
  const required = new Set(schema.required ?? []);
  for (const field of lock.required) {
    assert(required.has(field), `${schemaId} removed required v1 field ${field}`);
  }
  for (const [field, expectedType] of Object.entries(lock.types)) {
    assert.equal(schema.properties?.[field]?.type, expectedType,
      `${schemaId} changed v1 type for ${field}`);
  }
}

const forbiddenSecretKeys = /^(credential|password|access_token|refresh_token|client_secret|private_key)$/i;
function assertNoSecretField(node, location) {
  if (Array.isArray(node)) return node.forEach((value, index) => assertNoSecretField(value, `${location}[${index}]`));
  if (!node || typeof node !== "object") return;
  for (const [key, value] of Object.entries(node)) {
    assert(!forbiddenSecretKeys.test(key), `secret field ${key} is forbidden in executable contract at ${location}`);
    assertNoSecretField(value, `${location}.${key}`);
  }
}
for (const schema of schemas) assertNoSecretField(schema, schema.$id);
for (const item of manifest.valid) assertNoSecretField(await readJson(path.join(fixtureDir, item.fixture)), item.fixture);

const openapi = YAML.parse(await readFile(path.join(contractsDir, "openapi/platform-api.v1.yaml"), "utf8"));
assert.equal(openapi.openapi, "3.1.0");
for (const requiredPath of ["/health", "/portfolios", "/sites", "/device-models", "/devices",
  "/devices/{deviceId}/status", "/devices/{deviceId}/credentials", "/tariff-plans",
  "/audit-events", "/internal/device-identities", "/forecast-runs", "/forecast-runs/{forecastRunId}",
  "/forecasts/{forecastVersionId}", "/forecasts/{forecastVersionId}/overrides", "/schedules", "/schedules/{scheduleId}",
  "/internal/device-catalog", "/portfolios/{portfolioId}/snapshot", "/telemetry/query",
  "/realtime/tickets",
  "/alarm-rules", "/alarms", "/alarms/coverage", "/alarms/{alarmId}",
  "/alarms/{alarmId}/acknowledge", "/alarms/{alarmId}/notes", "/alarms/{alarmId}/close"]) {
  assert(openapi.paths[requiredPath], `OpenAPI is missing ${requiredPath}`);
}

const asyncapi = YAML.parse(await readFile(path.join(contractsDir, "asyncapi/vpp-events.v1.yaml"), "utf8"));
assert.equal(asyncapi.asyncapi, "3.1.0");
for (const requiredChannel of [
  "telemetryUp", "heartbeatUp", "commandAckUp", "commandDown", "telemetryRawKafka",
  "telemetryNormalizedKafka", "alarmEventsKafka", "scheduleEventsKafka", "forecastEventsKafka",
  "telemetryDlqKafka",
  "commandRequestsKafka", "commandEventsKafka", "realtimeWebSocket"
]) {
  assert(asyncapi.channels[requiredChannel], `AsyncAPI is missing ${requiredChannel}`);
}

console.log(`Validated ${schemas.length} schemas, ${manifest.valid.length} valid fixtures, ` +
  `${manifest.invalid.length} negative fixtures, compatibility locks, OpenAPI and AsyncAPI coverage.`);
