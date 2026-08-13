from datetime import date, datetime
from typing import Literal

from pydantic import BaseModel, Field, model_validator


class Observation(BaseModel):
    observed_at: datetime
    value: float
    quality: Literal["VALID", "SUSPECT", "ESTIMATED"] = "VALID"


class ForecastRequest(BaseModel):
    forecast_date: date
    timezone: str
    metric: Literal["LOAD_KW", "PV_POWER_KW"]
    data_cutoff: datetime
    observations: list[Observation]
    minimum_history_days: int = Field(default=28, ge=7, le=365)
    recent_days: int = Field(default=7, ge=1, le=30)
    minimum_recent_completeness: float = Field(default=0.95, ge=0, le=1)

    @model_validator(mode="after")
    def cutoff_is_timezone_aware(self) -> ForecastRequest:
        if self.data_cutoff.tzinfo is None:
            raise ValueError("data_cutoff must include a timezone")
        if any(item.observed_at.tzinfo is None for item in self.observations):
            raise ValueError("observation timestamps must include a timezone")
        if any(item.observed_at >= self.data_cutoff for item in self.observations):
            raise ValueError("observations must be strictly before data_cutoff")
        return self


class DatasetReport(BaseModel):
    valid_history_days: int
    valid_observations: int
    recent_expected_intervals: int
    recent_observed_intervals: int
    recent_completeness: float
    minimum_history_days: int
    minimum_recent_completeness: float
    quality_filter: str = "VALID_ONLY"
    weather_source: str = "NONE"


class ValidationMetrics(BaseModel):
    method: str = "ROLLING_ORIGIN"
    evaluated_points: int
    mae_kw: float | None
    wape: float | None
    nmae: float | None


class ForecastPoint(BaseModel):
    interval_start: datetime
    interval_end: datetime
    value: float
    unit: str = "kW"
    quality: str = "ESTIMATED"


class ForecastResponse(BaseModel):
    status: Literal["SUCCEEDED", "INSUFFICIENT_DATA"]
    model_name: str = "SIMILAR_DAY_AVERAGE"
    model_version: str = "similar-day-average-v1"
    feature_version: str = "quarter-hour-local-calendar-v1"
    data_cutoff: datetime
    dataset: DatasetReport
    validation: ValidationMetrics
    points: list[ForecastPoint]
    failure_code: str | None = None
    reasons: list[str] = Field(default_factory=list)
