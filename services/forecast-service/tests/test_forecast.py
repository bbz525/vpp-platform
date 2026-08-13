from datetime import UTC, date, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from vpp_forecast.app import app
from vpp_forecast.engine import forecast
from vpp_forecast.models import ForecastRequest, Observation

client = TestClient(app)


def observations(days: int = 35, missing_recent: set[int] | None = None) -> list[Observation]:
    result = []
    start = datetime(2026, 7, 1, tzinfo=UTC)
    missing_recent = missing_recent or set()
    for day in range(days):
        for slot in range(96):
            if day >= days - 7 and slot in missing_recent:
                continue
            at = start + timedelta(days=day, minutes=15 * slot)
            result.append(Observation(observed_at=at, value=100 + at.weekday() * 10 + slot / 10))
    return result


def request(items: list[Observation]) -> ForecastRequest:
    return ForecastRequest(
        forecast_date=date(2026, 8, 6),
        timezone="UTC",
        metric="LOAD_KW",
        data_cutoff=datetime(2026, 8, 5, tzinfo=UTC),
        observations=items,
    )


def test_health_and_model_readiness_are_separate() -> None:
    assert client.get("/livez").json() == {"status": "UP"}
    assert client.get("/readyz").json() == {
        "status": "READY",
        "model": "similar-day-average-v1",
    }


def test_produces_deterministic_96_point_forecast_and_rolling_metrics() -> None:
    result = forecast(request(observations()))
    assert result.status == "SUCCEEDED"
    assert len(result.points) == 96
    assert result.dataset.recent_completeness == 1
    assert result.validation.method == "ROLLING_ORIGIN"
    assert result.validation.evaluated_points == 672
    assert result.points[0].value == pytest.approx(130.0)
    assert all(point.quality == "ESTIMATED" for point in result.points)


def test_returns_insufficient_instead_of_backfilling_missing_history() -> None:
    result = forecast(request(observations(days=14)))
    assert result.status == "INSUFFICIENT_DATA"
    assert result.points == []
    assert "MINIMUM_HISTORY_DAYS_NOT_MET" in result.reasons


def test_recent_completeness_gate_is_explicit() -> None:
    result = forecast(request(observations(missing_recent=set(range(12)))))
    assert result.status == "INSUFFICIENT_DATA"
    assert "RECENT_COMPLETENESS_BELOW_THRESHOLD" in result.reasons


def test_rejects_future_data_to_prevent_leakage() -> None:
    items = observations()
    items.append(Observation(observed_at=datetime(2026, 8, 5, tzinfo=UTC), value=999999))
    with pytest.raises(ValueError, match="strictly before data_cutoff"):
        request(items)


def test_dst_target_day_uses_actual_number_of_intervals() -> None:
    source = observations(days=40)
    run = ForecastRequest(
        forecast_date=date(2026, 11, 1),
        timezone="America/New_York",
        metric="PV_POWER_KW",
        data_cutoff=datetime(2026, 10, 31, tzinfo=UTC),
        observations=source,
        minimum_history_days=7,
        minimum_recent_completeness=0,
    )
    result = forecast(run)
    assert result.status == "SUCCEEDED"
    assert len(result.points) == 100
    assert all(point.value >= 0 for point in result.points)
