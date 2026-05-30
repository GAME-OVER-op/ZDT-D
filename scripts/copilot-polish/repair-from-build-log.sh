#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

BUILD_LOG="${1:-build.log}"

if [[ ! -s "$BUILD_LOG" ]]; then
  echo "Build log does not exist or is empty: $BUILD_LOG" >&2
  exit 1
fi

if ! command -v copilot >/dev/null 2>&1; then
  echo "GitHub Copilot CLI is not installed or not available in PATH." >&2
  exit 127
fi

PROMPT_FILE="$(mktemp)"
trap 'rm -f "$PROMPT_FILE"' EXIT

cat > "$PROMPT_FILE" <<EOF_PROMPT
The ZDT-D GitHub build workflow failed.

Build log file:
$BUILD_LOG

Task:
Read the build log and fix only the compilation, lint, or packaging error caused by the current AI diff.

Rules:
- make the minimum fix only
- do not add new features
- do not refactor unrelated code
- do not touch .github/workflows/build.yml
- do not touch build.sh
- do not touch release/service-build logic
- do not touch routing, iptables, NFQUEUE, VPN/netd, Zygisk, or module boot logic
- do not use build.sh

After editing, update .copilot-polish-summary.md with a short repair note.
EOF_PROMPT

copilot -p "$(cat "$PROMPT_FILE")" \
  --allow-tool='shell(git:*)' \
  --allow-tool='shell(grep:*)' \
  --allow-tool='shell(sed:*)' \
  --allow-tool='shell(find:*)' \
  --allow-tool='shell(cat:*)' \
  --allow-tool='shell(head:*)' \
  --allow-tool='shell(tail:*)' \
  --allow-tool='shell(awk:*)' \
  --allow-tool='shell(wc:*)' \
  --allow-tool=write \
  --no-ask-user
