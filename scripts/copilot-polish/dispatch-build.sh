#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

BRANCH="${1:-${COPILOT_POLISH_BRANCH:-copilot-polish}}"
BUILD_TYPE="${2:-${COPILOT_POLISH_BUILD_TYPE:-Release}}"
WORKFLOW_FILE="${COPILOT_POLISH_BUILD_WORKFLOW:-build.yml}"

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI is required to dispatch $WORKFLOW_FILE." >&2
  exit 127
fi

if [[ -z "${GH_TOKEN:-}" && -z "${GITHUB_TOKEN:-}" ]]; then
  echo "GH_TOKEN or GITHUB_TOKEN must be set for gh workflow operations." >&2
  exit 1
fi

# Avoid cancelling an active main build because build.yml currently uses a global concurrency group.
active_main_runs="$(gh run list \
  --workflow "$WORKFLOW_FILE" \
  --branch main \
  --status in_progress \
  --limit 5 \
  --json databaseId \
  --jq 'length' 2>/dev/null || echo 0)"
queued_main_runs="$(gh run list \
  --workflow "$WORKFLOW_FILE" \
  --branch main \
  --status queued \
  --limit 5 \
  --json databaseId \
  --jq 'length' 2>/dev/null || echo 0)"

if [[ "${active_main_runs:-0}" != "0" || "${queued_main_runs:-0}" != "0" ]]; then
  echo "Main build is active or queued. Skipping AI validation to avoid interfering with release/service builds." >&2
  exit 78
fi

before_ids="$(mktemp)"
after_ids="$(mktemp)"
trap 'rm -f "$before_ids" "$after_ids"' EXIT

gh run list \
  --workflow "$WORKFLOW_FILE" \
  --branch "$BRANCH" \
  --event workflow_dispatch \
  --limit 20 \
  --json databaseId \
  --jq '.[].databaseId' > "$before_ids" || true

echo "Dispatching $WORKFLOW_FILE on $BRANCH with build_type=$BUILD_TYPE"
gh workflow run "$WORKFLOW_FILE" --ref "$BRANCH" -f build_type="$BUILD_TYPE"

RUN_ID=""
for _ in $(seq 1 30); do
  sleep 5
  gh run list \
    --workflow "$WORKFLOW_FILE" \
    --branch "$BRANCH" \
    --event workflow_dispatch \
    --limit 20 \
    --json databaseId \
    --jq '.[].databaseId' > "$after_ids" || true
  RUN_ID="$(comm -13 <(sort "$before_ids") <(sort "$after_ids") | head -n 1 || true)"
  if [[ -n "$RUN_ID" ]]; then
    break
  fi
  # Fallback: use newest run if GitHub did not list the run quickly enough.
  RUN_ID="$(gh run list \
    --workflow "$WORKFLOW_FILE" \
    --branch "$BRANCH" \
    --event workflow_dispatch \
    --limit 1 \
    --json databaseId \
    --jq '.[0].databaseId' 2>/dev/null || true)"
  if [[ -n "$RUN_ID" ]]; then
    break
  fi
done

if [[ -z "$RUN_ID" ]]; then
  echo "Could not detect dispatched build run id." >&2
  exit 1
fi

echo "Build run id: $RUN_ID"
echo "$RUN_ID" > .copilot-polish-build-run-id

gh run watch "$RUN_ID" --exit-status
