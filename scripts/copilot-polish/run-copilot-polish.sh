#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

if ! command -v copilot >/dev/null 2>&1; then
  echo "GitHub Copilot CLI is not installed or not available in PATH." >&2
  echo "Install with: npm install -g @github/copilot" >&2
  exit 127
fi

TARGET_FILE="${1:-}"
if [[ -z "$TARGET_FILE" ]]; then
  TARGET_FILE="$(scripts/copilot-polish/select-target.sh)"
fi

if [[ ! -f "$TARGET_FILE" ]]; then
  echo "Selected target file does not exist: $TARGET_FILE" >&2
  exit 1
fi

PROMPT_FILE="$(mktemp)"
trap 'rm -f "$PROMPT_FILE"' EXIT

cat > "$PROMPT_FILE" <<EOF_PROMPT
You are maintaining ZDT-D as a conservative optimizer.

Selected target file:
$TARGET_FILE

Task:
Inspect the selected file and its local dependencies. Make exactly one small safe improvement if there is a clearly safe opportunity.

Allowed changes:
- reduce obvious duplication
- extract a small helper
- improve local readability
- improve error context
- improve comments for complex logic
- fix a safe compiler/linter warning
- improve documentation wording

Forbidden changes:
- do not rewrite architecture
- do not change behavior
- do not remove features
- do not mass-format files
- do not touch .github/workflows/build.yml
- do not touch build.sh
- do not touch module_template boot scripts
- do not touch prebuilt, keystores, zygisk
- do not change release logic
- do not change service-build logic
- do not change routing, iptables, NFQUEUE, VPN/netd logic
- do not change app assignment conflict rules
- do not run build.sh

Before editing, inspect nearby code and imports.
After editing, leave a short summary in .copilot-polish-summary.md with:
- selected file
- what changed
- why behavior is preserved
- what must be validated by GitHub build.yml

Keep the diff small.
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
