#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

BUILD_LOG="${1:-build.log}"

if [[ ! -f "$BUILD_LOG" ]]; then
  echo "Build log not found: $BUILD_LOG" >&2
  exit 1
fi

if ! command -v copilot >/dev/null 2>&1; then
  echo "GitHub Copilot CLI is not installed or not available in PATH." >&2
  echo "Install with: npm install -g @github/copilot" >&2
  exit 127
fi

LOG_TAIL="$(tail -n 250 "$BUILD_LOG")"

PROMPT_FILE="$(mktemp)"
trap 'rm -f "$PROMPT_FILE"' EXIT

cat > "$PROMPT_FILE" <<EOF_PROMPT
You are maintaining ZDT-D as a conservative repair agent.

The GitHub build workflow failed.

Task:
Fix only the compilation/build error shown in the build log.

Forbidden:
- do not add new features
- do not refactor unrelated code
- do not rewrite architecture
- do not touch build.sh
- do not touch release workflow logic
- do not touch service-build logic
- do not touch routing, iptables, NFQUEUE, VPN/netd logic
- do not touch zygisk, prebuilt, keystores
- do not create .copilot-polish-summary.md
- do not create repository report files

Build log tail:
\`\`\`text
$LOG_TAIL
\`\`\`
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
