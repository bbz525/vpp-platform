# Forecast Service

T08 FastAPI prediction engine for deterministic next-day load and photovoltaic baselines. It accepts only timestamped history frozen before `data_cutoff`, filters to `VALID` observations, enforces the 28-day/recent-completeness gates and returns actual timezone-aware 15-minute intervals (96 on an ordinary day).

```bash
uv sync --locked
uv run uvicorn vpp_forecast.app:app --host 0.0.0.0 --port 8090
```

`similar-day-average-v1` is an interpretable baseline, not a claimed LightGBM/XGBoost model. Validation uses rolling origins and never random train/test splitting. Missing weather is reported as `weather_source=NONE`; insufficient input returns `INSUFFICIENT_DATA` with no generated points.
