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
if ! command -v openssl >/dev/null 2>&1; then
  echo "[FAIL] 缺少 openssl，无法生成 MPC mTLS 证书" >&2
  exit 2
fi

ENV_FILE="${NEX_ENV_FILE:-$ROOT/.env.prod}"
"$PY" scripts/prod_prepare.py prepare-env --root "$ROOT" --output "$ENV_FILE"
bash scripts/gen-mpc-certs.sh
bash scripts/gen-mpc-engine-configs.sh --layout compose-prod
NEX_ENV_FILE="$ENV_FILE" bash scripts/prod-check.sh
