from collections import defaultdict
from datetime import UTC, date, datetime, time, timedelta
from math import ceil
from statistics import fmean
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from .models import (
    DatasetReport,
    ForecastPoint,
    ForecastRequest,
    ForecastResponse,
    ValidationMetrics,
)

QUARTER = timedelta(minutes=15)


def forecast(request: ForecastRequest) -> ForecastResponse:
    try:
        zone = ZoneInfo(request.timezone)
    except ZoneInfoNotFoundError as error:
        raise ValueError("timezone must be a valid IANA timezone") from error

    values = _valid_values(request, zone)
    report = _dataset_report(request, zone, values)
    reasons: list[str] = []
    if report.valid_history_days < request.minimum_history_days:
        reasons.append("MINIMUM_HISTORY_DAYS_NOT_MET")
    if report.recent_completeness < request.minimum_recent_completeness:
        reasons.append("RECENT_COMPLETENESS_BELOW_THRESHOLD")
    target_slots = _slots(request.forecast_date, zone)
    if reasons:
        return ForecastResponse(
            status="INSUFFICIENT_DATA",
            data_cutoff=request.data_cutoff,
            dataset=report,
            validation=ValidationMetrics(evaluated_points=0, mae_kw=None, wape=None, nmae=None),
            points=[],
            failure_code="INSUFFICIENT_DATA",
            reasons=reasons,
        )

    points = _predict_day(request.forecast_date, target_slots, values, request.metric)
    validation = _rolling_validation(request, zone, values)
    return ForecastResponse(
        status="SUCCEEDED",
        data_cutoff=request.data_cutoff,
        dataset=report,
        validation=validation,
        points=points,
    )


def _valid_values(
    request: ForecastRequest, zone: ZoneInfo
) -> dict[date, dict[tuple[int, int, int], float]]:
    buckets: dict[date, dict[tuple[int, int, int], list[float]]] = defaultdict(
        lambda: defaultdict(list)
    )
    for observation in request.observations:
        if observation.quality != "VALID":
            continue
        local = observation.observed_at.astimezone(zone)
        key = (local.hour, local.minute // 15 * 15, local.fold)
        buckets[local.date()][key].append(observation.value)
    return {
        day: {key: fmean(items) for key, items in slots.items()}
        for day, slots in buckets.items()
    }


def _dataset_report(
    request: ForecastRequest,
    zone: ZoneInfo,
    values: dict[date, dict[tuple[int, int, int], float]],
) -> DatasetReport:
    cutoff_day = request.data_cutoff.astimezone(zone).date()
    recent_days = [
        cutoff_day - timedelta(days=offset) for offset in range(request.recent_days, 0, -1)
    ]
    expected = sum(len(_slots(day, zone)) for day in recent_days)
    observed = sum(len(values.get(day, {})) for day in recent_days)
    return DatasetReport(
        valid_history_days=sum(
            len(slots) >= ceil(len(_slots(day, zone)) * 0.8)
            for day, slots in values.items()
        ),
        valid_observations=sum(len(day) for day in values.values()),
        recent_expected_intervals=expected,
        recent_observed_intervals=observed,
        recent_completeness=round(observed / expected if expected else 0, 6),
        minimum_history_days=request.minimum_history_days,
        minimum_recent_completeness=request.minimum_recent_completeness,
    )


def _slots(day: date, zone: ZoneInfo) -> list[tuple[datetime, datetime, tuple[int, int, int]]]:
    start = datetime.combine(day, time.min, zone).astimezone(UTC)
    end = datetime.combine(day + timedelta(days=1), time.min, zone).astimezone(UTC)
    result = []
    cursor = start
    while cursor < end:
        local = cursor.astimezone(zone)
        result.append((cursor, cursor + QUARTER, (local.hour, local.minute, local.fold)))
        cursor += QUARTER
    return result


def _predict_day(
    day: date,
    slots: list[tuple[datetime, datetime, tuple[int, int, int]]],
    values: dict[date, dict[tuple[int, int, int], float]],
    metric: str,
) -> list[ForecastPoint]:
    previous_days = sorted(candidate for candidate in values if candidate < day)
    points: list[ForecastPoint] = []
    for start, end, slot in slots:
        same_weekday = [
            values[candidate][slot]
            for candidate in previous_days
            if candidate.weekday() == day.weekday() and slot in values[candidate]
        ]
        fallback = [
            values[candidate][slot]
            for candidate in previous_days[-7:]
            if slot in values[candidate]
        ]
        candidates = same_weekday[-8:] or fallback
        value = fmean(candidates) if candidates else 0.0
        if metric == "PV_POWER_KW":
            value = max(0.0, value)
        points.append(ForecastPoint(interval_start=start, interval_end=end, value=round(value, 6)))
    return points


def _rolling_validation(
    request: ForecastRequest,
    zone: ZoneInfo,
    values: dict[date, dict[tuple[int, int, int], float]],
) -> ValidationMetrics:
    cutoff_day = request.data_cutoff.astimezone(zone).date()
    evaluation_days = [cutoff_day - timedelta(days=offset) for offset in range(7, 0, -1)]
    actuals: list[float] = []
    predicted: list[float] = []
    for day in evaluation_days:
        history = {candidate: slots for candidate, slots in values.items() if candidate < day}
        if not history or day not in values:
            continue
        predictions = _predict_day(day, _slots(day, zone), history, request.metric)
        for point in predictions:
            local = point.interval_start.astimezone(zone)
            key = (local.hour, local.minute, local.fold)
            if key in values[day]:
                actuals.append(values[day][key])
                predicted.append(point.value)
    if not actuals:
        return ValidationMetrics(evaluated_points=0, mae_kw=None, wape=None, nmae=None)
    errors = [abs(actual - estimate) for actual, estimate in zip(actuals, predicted, strict=True)]
    mae = fmean(errors)
    actual_sum = sum(abs(value) for value in actuals)
    scale = max(actuals) - min(actuals)
    return ValidationMetrics(
        evaluated_points=len(actuals),
        mae_kw=round(mae, 6),
        wape=round(sum(errors) / actual_sum, 6) if actual_sum else None,
        nmae=round(mae / scale, 6) if scale else None,
    )
