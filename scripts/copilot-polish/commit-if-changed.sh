#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

BRANCH="${COPILOT_POLISH_BRANCH:-copilot-polish}"
MESSAGE="${1:-AI polish: safe incremental improvement}"

if git diff --quiet; then
  echo "No changes to commit."
  exit 0
fi

git add -A

git commit -m "$MESSAGE" -m "Automated conservative polish for ZDT-D. Behavior-preserving change intended for validation through .github/workflows/build.yml on $BRANCH."
git push origin "$BRANCH"
