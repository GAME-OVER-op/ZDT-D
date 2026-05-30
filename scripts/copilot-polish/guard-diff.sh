#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

: "${COPILOT_POLISH_MAX_FILES:=8}"
: "${COPILOT_POLISH_MAX_LINES:=600}"
: "${COPILOT_POLISH_ALLOW_RISKY:=0}"

changed_files="$(git diff --name-only)"

if [[ -z "$changed_files" ]]; then
  echo "No working tree changes detected."
  exit 0
fi

file_count="$(printf '%s\n' "$changed_files" | sed '/^$/d' | wc -l | tr -d ' ')"
if [[ "$file_count" -gt "$COPILOT_POLISH_MAX_FILES" ]]; then
  echo "Rejecting AI diff: too many files changed ($file_count > $COPILOT_POLISH_MAX_FILES)." >&2
  printf '%s\n' "$changed_files" >&2
  exit 2
fi

line_count="$(git diff --numstat | awk '{ add += $1; del += $2 } END { print add + del + 0 }')"
if [[ "$line_count" -gt "$COPILOT_POLISH_MAX_LINES" ]]; then
  echo "Rejecting AI diff: too many changed lines ($line_count > $COPILOT_POLISH_MAX_LINES)." >&2
  git diff --stat >&2
  exit 3
fi

if [[ "$COPILOT_POLISH_ALLOW_RISKY" != "1" ]]; then
  risky_pattern='^(\.github/workflows/|build\.sh$|module\.prop$|module_template/(service|customize|uninstall)\.sh$|prebuilt/|keystores/|zygisk/|rust/zdtd/src/api\.rs$|rust/zdtd/src/runtime\.rs$|rust/zdtd/src/vpn_netd\.rs$|rust/zdtd/src/iptables/)'
  risky_files="$(printf '%s\n' "$changed_files" | grep -E "$risky_pattern" || true)"
  if [[ -n "$risky_files" ]]; then
    echo "Rejecting AI diff: protected files were changed." >&2
    printf '%s\n' "$risky_files" >&2
    exit 4
  fi
fi

echo "AI diff guard passed."
printf '%s\n' "$changed_files" | sed 's/^/- /'
