#!/usr/bin/env bash
# Prepare a Trivy image without rebuilding an already-published master artifact.
set -euo pipefail

MODULE="${1:-}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
if [[ ! "$MODULE" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
  echo "invalid module name" >&2
  exit 2
fi
IMAGE_PREFIX="${GITHUB_REPOSITORY,,}"
if [[ -z "$IMAGE_PREFIX" || "$IMAGE_PREFIX" == *:* || "$IMAGE_PREFIX" == *..* ]]; then
  echo "invalid image prefix" >&2
  exit 2
fi

if [[ "$GITHUB_EVENT_NAME" == "pull_request" ]]; then
  BUILD_WAIT_SECONDS="${SCAN_BUILD_WAIT_SECONDS:-15}"
  if [[ ! "$BUILD_WAIT_SECONDS" =~ ^[0-9]+$ || "$BUILD_WAIT_SECONDS" -gt 60 ]]; then
    echo "build wait seconds must be 0..60" >&2
    exit 2
  fi
  CACHE_SCOPE="security-scan-$MODULE"
  for attempt in 1 2 3; do
    if "$DOCKER_BIN" buildx build --load -t "$MODULE:scan" \
        --cache-from "type=gha,scope=$CACHE_SCOPE" \
        --cache-to "type=gha,mode=max,scope=$CACHE_SCOPE,ignore-error=true" \
        -f "./$MODULE/Dockerfile" .; then
      echo "Local PR scan image built on attempt $attempt"
      exit 0
    fi
    if [[ "$attempt" -lt 3 ]]; then sleep "$BUILD_WAIT_SECONDS"; fi
  done
  echo "docker build failed three times" >&2
  exit 1
fi

if [[ ! "${GITHUB_SHA:-}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "published scans require a full lowercase commit SHA" >&2
  exit 2
fi
ATTEMPTS="${SCAN_PULL_ATTEMPTS:-60}"
WAIT_SECONDS="${SCAN_WAIT_SECONDS:-30}"
if [[ ! "$ATTEMPTS" =~ ^[0-9]+$ || "$ATTEMPTS" -lt 1 || "$ATTEMPTS" -gt 60 \
      || ! "$WAIT_SECONDS" =~ ^[0-9]+$ || "$WAIT_SECONDS" -gt 60 ]]; then
  echo "pull attempts must be 1..60 and wait seconds must be 0..60" >&2
  exit 2
fi
IMAGE="ghcr.io/$IMAGE_PREFIX/$MODULE:$GITHUB_SHA"
for attempt in $(seq 1 "$ATTEMPTS"); do
  if "$DOCKER_BIN" pull "$IMAGE"; then
    "$DOCKER_BIN" tag "$IMAGE" "$MODULE:scan"
    "$DOCKER_BIN" image inspect "$MODULE:scan" >/dev/null
    echo "Scanning published artifact $IMAGE"
    exit 0
  fi
  if [[ "$attempt" -lt 60 ]]; then
    echo "published image not ready ($attempt/$ATTEMPTS); waiting ${WAIT_SECONDS}s"
    sleep "$WAIT_SECONDS"
  fi
done
echo "published image unavailable; inspect the CI image job and rerun after publication, rather than rebuilding a different artifact" >&2
exit 1
