#!/usr/bin/env bash
set -euo pipefail

# Publish a tiny workflow status document as a stable GitHub release asset.
# The Android app reads this asset over ordinary HTTPS, avoiding frequent
# unauthenticated GitHub REST API polling and its low per-IP rate limit.

STAGE="${1:-}"
STAGE_STATE="${2:-running}"
OVERALL="${3:-preparing}"
MESSAGE="${4:-}"

if [[ -z "$STAGE" ]]; then
  echo "usage: $0 <binaries|archives|apk|release|ready> [running|done|failed] [preparing|ready|failed] [message]" >&2
  exit 2
fi

if [[ "${GITHUB_REF:-}" != "refs/heads/main" ]]; then
  echo "[ZDT-D] Build status asset is published only for main; skipping ${GITHUB_REF:-unknown}."
  exit 0
fi

case "$STAGE" in
  binaries|archives|apk|release|ready) ;;
  *) echo "invalid build stage: $STAGE" >&2; exit 2 ;;
esac
case "$STAGE_STATE" in
  waiting|running|done|failed) ;;
  *) echo "invalid stage state: $STAGE_STATE" >&2; exit 2 ;;
esac
case "$OVERALL" in
  preparing|ready|failed) ;;
  *) echo "invalid overall state: $OVERALL" >&2; exit 2 ;;
esac

: "${GH_TOKEN:?GH_TOKEN must be set}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must be set}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID must be set}"
: "${GITHUB_RUN_NUMBER:?GITHUB_RUN_NUMBER must be set}"
: "${GITHUB_SHA:?GITHUB_SHA must be set}"

VERSION_NAME="$(grep -m1 '^version=' module.prop | cut -d'=' -f2- | tr -d '\r' | xargs)"
VERSION_CODE="$(grep -m1 '^versionCode=' module.prop | cut -d'=' -f2- | tr -d '[:space:]')"
[[ -n "$VERSION_NAME" ]] || { echo "version not found in module.prop" >&2; exit 1; }
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || { echo "numeric versionCode not found in module.prop" >&2; exit 1; }

case "$STAGE" in
  binaries) STAGE_INDEX=0 ;;
  archives) STAGE_INDEX=1 ;;
  apk) STAGE_INDEX=2 ;;
  release) STAGE_INDEX=3 ;;
  ready) STAGE_INDEX=4 ;;
esac

status_for_index() {
  local index="$1"
  if [[ "$OVERALL" == "ready" ]]; then
    printf 'done'
  elif (( index < STAGE_INDEX )); then
    printf 'done'
  elif (( index == STAGE_INDEX )); then
    printf '%s' "$STAGE_STATE"
  else
    printf 'waiting'
  fi
}

BINARIES_STATUS="$(status_for_index 0)"
ARCHIVES_STATUS="$(status_for_index 1)"
APK_STATUS="$(status_for_index 2)"
RELEASE_STATUS="$(status_for_index 3)"
READY_STATUS="$(status_for_index 4)"
UPDATED_AT="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
RUN_URL="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
TMP_DIR="${RUNNER_TEMP:-/tmp}/zdt-build-status"
STATUS_FILE="$TMP_DIR/zdt-build-status.json"
mkdir -p "$TMP_DIR"

export ZDT_STATUS_STAGE="$STAGE"
export ZDT_STATUS_OVERALL="$OVERALL"
export ZDT_STATUS_MESSAGE="$MESSAGE"
export ZDT_STATUS_VERSION_NAME="$VERSION_NAME"
export ZDT_STATUS_VERSION_CODE="$VERSION_CODE"
export ZDT_STATUS_UPDATED_AT="$UPDATED_AT"
export ZDT_STATUS_RUN_URL="$RUN_URL"
export ZDT_STATUS_BINARIES="$BINARIES_STATUS"
export ZDT_STATUS_ARCHIVES="$ARCHIVES_STATUS"
export ZDT_STATUS_APK="$APK_STATUS"
export ZDT_STATUS_RELEASE="$RELEASE_STATUS"
export ZDT_STATUS_READY="$READY_STATUS"

python3 - "$STATUS_FILE" <<'PY'
import json
import os
import sys

path = sys.argv[1]
payload = {
    "schema": 1,
    "repository": os.environ["GITHUB_REPOSITORY"],
    "workflow": "build.yml",
    "runId": int(os.environ["GITHUB_RUN_ID"]),
    "runNumber": int(os.environ["GITHUB_RUN_NUMBER"]),
    "runAttempt": int(os.environ.get("GITHUB_RUN_ATTEMPT", "1")),
    "runUrl": os.environ["ZDT_STATUS_RUN_URL"],
    "headSha": os.environ["GITHUB_SHA"],
    "branch": os.environ.get("GITHUB_REF_NAME", "main"),
    "buildType": os.environ.get("BUILD_TYPE", "Release"),
    "version": os.environ["ZDT_STATUS_VERSION_NAME"],
    "versionCode": int(os.environ["ZDT_STATUS_VERSION_CODE"]),
    "status": os.environ["ZDT_STATUS_OVERALL"],
    "currentStage": os.environ["ZDT_STATUS_STAGE"],
    "message": os.environ.get("ZDT_STATUS_MESSAGE", ""),
    "updatedAt": os.environ["ZDT_STATUS_UPDATED_AT"],
    "stages": {
        "binaries": os.environ["ZDT_STATUS_BINARIES"],
        "archives": os.environ["ZDT_STATUS_ARCHIVES"],
        "apk": os.environ["ZDT_STATUS_APK"],
        "release": os.environ["ZDT_STATUS_RELEASE"],
        "ready": os.environ["ZDT_STATUS_READY"],
    },
}
with open(path, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
PY

TECHNICAL_TAG="Technical_Assets"
TECHNICAL_TITLE="ZDT-D Technical Assets"
if ! gh release view "$TECHNICAL_TAG" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  gh release create "$TECHNICAL_TAG" \
    --repo "$GITHUB_REPOSITORY" \
    --target "$GITHUB_SHA" \
    --title "$TECHNICAL_TITLE" \
    --notes "Technical assets used by ZDT-D optional components and build status." \
    --prerelease \
    --latest=false
fi

gh release upload "$TECHNICAL_TAG" \
  "$STATUS_FILE#zdt-build-status.json" \
  --repo "$GITHUB_REPOSITORY" \
  --clobber

echo "[ZDT-D] Published workflow status: overall=$OVERALL stage=$STAGE stageState=$STAGE_STATE run=$GITHUB_RUN_ID"
cat "$STATUS_FILE"
