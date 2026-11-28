#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if command -v python3 >/dev/null 2>&1; then
  PY=python3
elif command -v python >/dev/null 2>&1; then
  PY=python
else
  echo "[FAIL] 缺少 python3/python" >&2
  exit 2
fi

ENV_FILE="${NEX_ENV_FILE:-$ROOT/.env.prod}"
CERTS_DIR="${NEX_CERTS_DIR:-$ROOT/mpc-certs}"
CONFIG_DIR="${NEX_CONFIG_DIR:-$ROOT/mpc-certs/config-prod}"
"$PY" scripts/prod_prepare.py check --root "$ROOT" --env-file "$ENV_FILE" \
  --certs-dir "$CERTS_DIR" --config-dir "$CONFIG_DIR" "$@"

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  docker compose -f "$ROOT/docker-compose.prod.yml" --env-file "$ENV_FILE" config --quiet
  echo "[PASS] docker compose production 配置渲染通过（未启动服务）"
else
  echo "[SKIP] Docker 不可用，未执行 compose config；compose-up 前仍会强制检查 Docker"
fi
