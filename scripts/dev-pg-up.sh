#!/usr/bin/env bash
#
# Bring up a containerized PostgreSQL for NexusChain core local integration (CI / Linux).
# Mirrors scripts/dev-pg-up.ps1 (same state machine, minus the Docker Desktop logic -
# CI environments provide a native Docker daemon).
#
# Usage:
#   ./scripts/dev-pg-up.sh                 # ensure healthy PG on 127.0.0.1:55432
#   START_CORE=1 ./scripts/dev-pg-up.sh    # also run core natively (foreground)
#
# Idempotent / non-destructive: reuses an existing healthy PG container that
# publishes port 55432; only creates compose-managed nexus-pgsql when the port is free.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT=55432
DB_USER=nexus
DB_NAME=nexuschain
DOCKER_TIMEOUT="${DOCKER_TIMEOUT:-180}"
START_CORE="${START_CORE:-0}"

log() { echo "[dev-pg] $*"; }

# ---------- [1/3] Docker engine ----------
log "[1/3] Docker engine"
if ! command -v docker >/dev/null 2>&1; then
  echo "[dev-pg] ERROR: docker CLI not found." >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "[dev-pg] ERROR: Docker daemon is not reachable. In CI, make sure a docker service is running." >&2
  exit 1
fi
log "Docker engine reachable."

# ---------- [2/3] PostgreSQL on 127.0.0.1:55432 ----------
log "[2/3] PostgreSQL (127.0.0.1:${PORT}, ${DB_USER}/${DB_NAME})"

CONTAINER="$(docker ps --filter "publish=${PORT}" --format '{{.Names}}' | head -n1)"
if [ -n "$CONTAINER" ]; then
  # Port is held by a Docker container - retry pg_isready briefly, then reuse or fail.
  ready=0
  for _ in $(seq 1 10); do
    if docker exec "$CONTAINER" pg_isready -U "$DB_USER" -d "$DB_NAME" -h localhost >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 3
  done
  if [ "$ready" = "1" ]; then
    log "Reusing existing healthy PostgreSQL container: ${CONTAINER}"
  else
    echo "[dev-pg] ERROR: port ${PORT} is occupied by container '${CONTAINER}' but pg_isready still fails. Check: docker logs ${CONTAINER}" >&2
    exit 1
  fi
elif (echo >/dev/tcp/127.0.0.1/"${PORT}") >/dev/null 2>&1; then
  echo "[dev-pg] ERROR: port ${PORT} is in use by a non-Docker process." >&2
  exit 1
else
  log "Creating compose-managed nexus-pgsql (docker-compose.yml)..."
  (
    cd "$REPO_ROOT"
    docker compose -f docker-compose.yml up -d nexus-pgsql
  )
  deadline=$(( $(date +%s) + 120 ))
  status=""
  while [ "$(date +%s)" -lt "$deadline" ]; do
    status="$(docker inspect -f '{{.State.Health.Status}}' nexus-pgsql 2>/dev/null || true)"
    [ "$status" = "healthy" ] && break
    sleep 3
  done
  if [ "$status" != "healthy" ]; then
    echo "[dev-pg] ERROR: nexus-pgsql not healthy within 120s. Check: docker logs nexus-pgsql" >&2
    exit 1
  fi
  log "nexus-pgsql is healthy."
fi

log "PostgreSQL ready: jdbc:postgresql://127.0.0.1:${PORT}/${DB_NAME} (${DB_USER})"

# ---------- [3/3] Core (optional) ----------
if [ "$START_CORE" = "1" ]; then
  log "[3/3] Starting core with local profile (foreground). Ctrl+C to stop; PG stays up."
  cd "$REPO_ROOT"
  ./gradlew :nexus-core:nexus-core:run --args="--spring.profiles.active=local"
fi

log "Done. Tear down with: docker compose stop nexus-pgsql && docker compose rm -f nexus-pgsql"
