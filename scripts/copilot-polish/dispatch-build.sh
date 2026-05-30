#!/usr/bin/env bash
set -euo pipefail

BRANCH="${1:-${COPILOT_POLISH_BRANCH:-}}"
BUILD_TYPE="${2:-Release}"

if [[ -z "$BRANCH" ]]; then
  echo "Usage: dispatch-build.sh <branch> [build_type]" >&2
  exit 2
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI is not available." >&2
  exit 127
fi

rm -f build.log build-tail.log .copilot-polish-build-run-id

echo "Dispatching build.yml for branch: $BRANCH"
gh workflow run build.yml \
  --ref "$BRANCH" \
  -f build_type="$BUILD_TYPE"

echo "Waiting for dispatched build run to appear..."

RUN_ID=""

for _ in $(seq 1 40); do
  RUN_ID="$(
    gh run list \
      --workflow build.yml \
      --branch "$BRANCH" \
      --event workflow_dispatch \
      --limit 1 \
      --json databaseId \
      --jq '.[0].databaseId // ""' 2>/dev/null || true
  )"

  if [[ -n "$RUN_ID" ]]; then
    break
  fi

  sleep 5
done

if [[ -z "$RUN_ID" ]]; then
  echo "Could not find dispatched build.yml run for branch: $BRANCH" >&2
  exit 1
fi

echo "$RUN_ID" > .copilot-polish-build-run-id
echo "Build run id: $RUN_ID"

set +e
gh run watch "$RUN_ID" --exit-status
RC="$?"
set -e

if [[ "$RC" != "0" ]]; then
  echo "Build failed. Saving logs..."
  gh run view "$RUN_ID" --log > build.log 2>/dev/null || true
  tail -n 250 build.log > build-tail.log 2>/dev/null || true
  exit "$RC"
fi

echo "Build succeeded."
