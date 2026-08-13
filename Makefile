SHELL := /bin/sh
COMPOSE := docker compose --env-file .env.example -f infra/compose/compose.yaml
MAVEN_LOCAL_REPO ?= /tmp/vpp-platform-m2
UV_CACHE ?= /tmp/vpp-platform-uv-cache
MAVEN := mvn -B -ntp -Dmaven.repo.local=$(MAVEN_LOCAL_REPO)
UV := UV_CACHE_DIR=$(UV_CACHE) uv

.PHONY: bootstrap build test test-java test-platform test-stream test-simulator test-gateway test-web test-python test-contract lint-contract validate-env \
	compose-config infra-up infra-down infra-ps clean

bootstrap:
	npm ci
	$(UV) sync --directory services/forecast-service --locked

validate-env:
	node scripts/validate-env.mjs

build:
	$(MAVEN) verify
	npm run build --workspace @vpp/web-console
	$(UV) build --directory services/forecast-service

test: test-java test-web test-python test-contract

test-java:
	$(MAVEN) test

test-platform:
	$(MAVEN) -pl apps/platform-api -am test

test-stream:
	$(MAVEN) -pl apps/stream-processor -am test

test-simulator:
	$(MAVEN) -pl apps/device-simulator -am test

test-gateway:
	$(MAVEN) -pl apps/iot-gateway -am test

test-web:
	npm run typecheck --workspace @vpp/web-console
	npm run test --workspace @vpp/web-console

test-python:
	$(UV) run --directory services/forecast-service pytest
	$(UV) run --directory services/forecast-service ruff check .

lint-contract:
	npm run contract:lint

test-contract: lint-contract
	npm run contract:test

compose-config:
	$(COMPOSE) config --quiet

infra-up:
	$(COMPOSE) up -d --wait

infra-down:
	$(COMPOSE) down

infra-ps:
	$(COMPOSE) ps

clean:
	$(MAVEN) -q clean
	npm run clean --workspace @vpp/web-console
