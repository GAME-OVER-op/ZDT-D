#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

BRANCH="${COPILOT_POLISH_BRANCH:-copilot-polish}"
MESSAGE="${1:-AI polish: safe incremental improvement}"

# Never commit Copilot runtime/report files.
git rm -f --ignore-unmatch .copilot-polish-summary.md >/dev/null 2>&1 || true
rm -f \
  .copilot-polish-summary.md \
  .copilot-polish-build-run-id \
  build.log \
  build-tail.log

if git diff --quiet && git diff --cached --quiet; then
  echo "No changes to commit."
  exit 0
fi

git add -A

# Safety cleanup after git add, in case a tool recreated a runtime file.
git reset -- \
  .copilot-polish-summary.md \
  .copilot-polish-build-run-id \
  build.log \
  build-tail.log >/dev/null 2>&1 || true

git rm -f --ignore-unmatch .copilot-polish-summary.md >/dev/null 2>&1 || true

if git diff --cached --quiet; then
  echo "No commit-worthy changes after excluding runtime files."
  exit 0
fi

git commit -m "$MESSAGE" -m "Automated conservative polish for ZDT-D. Behavior-preserving change intended for validation through .github/workflows/build.yml on $BRANCH."
git push origin "$BRANCH"
