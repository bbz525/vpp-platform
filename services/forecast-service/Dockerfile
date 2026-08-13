FROM ghcr.io/astral-sh/uv:0.6.13 AS uv
FROM python:3.14-slim

COPY --from=uv /uv /uvx /bin/
WORKDIR /app
COPY pyproject.toml uv.lock README.md ./
COPY src ./src
RUN uv sync --locked --no-dev

ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
EXPOSE 8090
CMD ["uvicorn", "vpp_forecast.app:app", "--host", "0.0.0.0", "--port", "8090"]
