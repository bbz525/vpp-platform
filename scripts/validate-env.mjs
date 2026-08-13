const environment = process.env.VPP_ENV ?? "local";

if (environment !== "production") {
  console.log(`Environment validation: ${environment} mode uses explicit local-only defaults.`);
  process.exit(0);
}

const requiredSecrets = [
  "POSTGRES_PASSWORD",
  "CLICKHOUSE_PASSWORD",
  "GRAFANA_ADMIN_PASSWORD",
  "PLATFORM_INTERNAL_TOKEN",
];

if (process.env.IOT_GATEWAY_MQTT_ENABLED === "true") {
  requiredSecrets.push("IOT_GATEWAY_MQTT_PASSWORD");
}
if (process.env.IOT_GATEWAY_LOCAL_IDENTITY_ENABLED === "false") {
  requiredSecrets.push("IOT_GATEWAY_CONTROL_PLANE_TOKEN");
}
const unsafeMarkers = ["local-dev", "password", "changeme", "example"];
const failures = requiredSecrets.filter((name) => {
  const value = process.env[name]?.trim() ?? "";
  return value.length < 16 || unsafeMarkers.some((marker) => value.toLowerCase().includes(marker));
});

if (failures.length > 0) {
  console.error(`Production startup refused: missing or unsafe secrets: ${failures.join(", ")}`);
  process.exit(1);
}

const gatewayFailures = [];
if (process.env.IOT_GATEWAY_MQTT_ENABLED === "true" && process.env.IOT_GATEWAY_MQTT_TLS !== "true") {
  gatewayFailures.push("IOT_GATEWAY_MQTT_TLS must be true");
}
if (process.env.IOT_GATEWAY_TCP_ENABLED === "true") {
  if (process.env.IOT_GATEWAY_TCP_TLS !== "true") {
    gatewayFailures.push("IOT_GATEWAY_TCP_TLS must be true");
  }
  for (const name of ["IOT_GATEWAY_TCP_CERTIFICATE_CHAIN", "IOT_GATEWAY_TCP_PRIVATE_KEY"]) {
    if (!(process.env[name]?.trim())) {
      gatewayFailures.push(`${name} is required`);
    }
  }
}
if (process.env.IOT_GATEWAY_LOCAL_IDENTITY_ENABLED !== "false") {
  gatewayFailures.push("IOT_GATEWAY_LOCAL_IDENTITY_ENABLED must be false");
}
if (!(process.env.IOT_GATEWAY_CONTROL_PLANE_URL?.startsWith("https://"))) {
  gatewayFailures.push("IOT_GATEWAY_CONTROL_PLANE_URL must use https");
}
if (process.env.PLATFORM_AUTH_MODE !== "jwt") {
  gatewayFailures.push("PLATFORM_AUTH_MODE must be jwt");
}
if (!(process.env.PLATFORM_JWT_ISSUER_URI?.startsWith("https://"))) {
  gatewayFailures.push("PLATFORM_JWT_ISSUER_URI must use https");
}
if (process.env.PLATFORM_LOCAL_BOOTSTRAP_ENABLED !== "false") {
  gatewayFailures.push("PLATFORM_LOCAL_BOOTSTRAP_ENABLED must be false");
}
if (process.env.STREAM_PROCESSOR_ENABLED === "true") {
  if (!(process.env.PLATFORM_API_URL?.startsWith("https://"))) {
    gatewayFailures.push("PLATFORM_API_URL must use https when stream processing is enabled");
  }
  if (process.env.KAFKA_SECURITY_PROTOCOL === "PLAINTEXT") {
    gatewayFailures.push("KAFKA_SECURITY_PROTOCOL must be encrypted when stream processing is enabled");
  }
  if (process.env.REDIS_SSL_ENABLED !== "true") {
    gatewayFailures.push("REDIS_SSL_ENABLED must be true when stream processing is enabled");
  }
  const clickhouseUrl = process.env.CLICKHOUSE_JDBC_URL ?? "";
  if (!(clickhouseUrl.includes("https://") || clickhouseUrl.includes("ssl=true"))) {
    gatewayFailures.push("CLICKHOUSE_JDBC_URL must enable TLS when stream processing is enabled");
  }
}
if (process.env.PLATFORM_REALTIME_ENABLED === "true") {
  if (process.env.REDIS_SSL_ENABLED !== "true") {
    gatewayFailures.push("REDIS_SSL_ENABLED must be true when platform realtime is enabled");
  }
  const clickhouseUrl = process.env.CLICKHOUSE_JDBC_URL ?? "";
  if (!(clickhouseUrl.includes("https://") || clickhouseUrl.includes("ssl=true"))) {
    gatewayFailures.push("CLICKHOUSE_JDBC_URL must enable TLS when platform realtime is enabled");
  }
  const origins = (process.env.PLATFORM_WEB_ALLOWED_ORIGINS ?? "").split(",").filter(Boolean);
  if (origins.length === 0 || origins.some((origin) => !origin.startsWith("https://") || origin.includes("*"))) {
    gatewayFailures.push("PLATFORM_WEB_ALLOWED_ORIGINS must contain explicit HTTPS origins when platform realtime is enabled");
  }
}
if (process.env.PLATFORM_OUTBOX_ENABLED === "true" && process.env.KAFKA_SECURITY_PROTOCOL === "PLAINTEXT") {
  gatewayFailures.push("KAFKA_SECURITY_PROTOCOL must be encrypted when platform outbox relay is enabled");
}
if (process.env.PLATFORM_COMMAND_DISPATCHER_ENABLED === "true" && process.env.PLATFORM_OUTBOX_ENABLED !== "true") {
  gatewayFailures.push("PLATFORM_OUTBOX_ENABLED must be true when the command dispatcher is enabled");
}
if (process.env.PLATFORM_COMMAND_DISPATCHER_ENABLED === "true" && process.env.IOT_GATEWAY_COMMANDS_ENABLED !== "true") {
  gatewayFailures.push("IOT_GATEWAY_COMMANDS_ENABLED must be true when the command dispatcher is enabled");
}
if (process.env.PLATFORM_COMMAND_CONSUMER_ENABLED === "true" && process.env.KAFKA_SECURITY_PROTOCOL === "PLAINTEXT") {
  gatewayFailures.push("KAFKA_SECURITY_PROTOCOL must be encrypted when the command acknowledgement consumer is enabled");
}
if (process.env.PLATFORM_FORECAST_ENABLED === "true") {
  if (!(process.env.FORECAST_SERVICE_URL?.startsWith("https://"))) {
    gatewayFailures.push("FORECAST_SERVICE_URL must use https when platform forecast is enabled");
  }
  const clickhouseUrl = process.env.CLICKHOUSE_JDBC_URL ?? "";
  if (!(clickhouseUrl.includes("https://") || clickhouseUrl.includes("ssl=true"))) {
    gatewayFailures.push("CLICKHOUSE_JDBC_URL must enable TLS when platform forecast is enabled");
  }
}

if (gatewayFailures.length > 0) {
  console.error(`Production startup refused: ${gatewayFailures.join(", ")}`);
  process.exit(1);
}

console.log("Production environment secret checks passed.");
