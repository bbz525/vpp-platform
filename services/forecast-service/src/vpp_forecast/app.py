from fastapi import FastAPI, HTTPException

from .engine import forecast
from .models import ForecastRequest, ForecastResponse

app = FastAPI(title="VPP Forecast Service", version="0.2.0")


@app.get("/livez", tags=["health"])
def liveness() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/readyz", tags=["health"])
def readiness() -> dict[str, str]:
    return {"status": "READY", "model": "similar-day-average-v1"}


@app.post("/v1/forecasts", response_model=ForecastResponse, tags=["forecasts"])
def create_forecast(request: ForecastRequest) -> ForecastResponse:
    try:
        return forecast(request)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
