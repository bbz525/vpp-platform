UPDATE alarm_rule
SET duration_seconds = 120, updated_at = now()
WHERE code = 'telemetry-offline-v1' AND version = 1 AND duration_seconds = 30;
