#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

: "${COPILOT_POLISH_TARGET:=}"

if [[ -n "$COPILOT_POLISH_TARGET" ]]; then
  if [[ ! -f "$COPILOT_POLISH_TARGET" ]]; then
    echo "Requested COPILOT_POLISH_TARGET does not exist: $COPILOT_POLISH_TARGET" >&2
    exit 1
  fi
  printf '%s\n' "$COPILOT_POLISH_TARGET"
  exit 0
fi

candidate_file="$(mktemp)"
trap 'rm -f "$candidate_file"' EXIT

# Safe-by-default candidate zones. Keep this conservative.
{
  find application/app/src/main/java/com/android/zdtd/service/ui \
    -type f \( -name '*.kt' -o -name '*.java' \) 2>/dev/null || true
  find application/app/src/main/java/com/android/zdtd/service/diagnostics \
    -type f \( -name '*.kt' -o -name '*.java' \) 2>/dev/null || true
  find rust/dpi-detector \
    -type f -name '*.rs' 2>/dev/null || true
  find rust/nfqws-tester \
    -type f -name '*.rs' 2>/dev/null || true
  find docs \
    -type f -name '*.md' 2>/dev/null || true
  [[ -f README.md ]] && printf '%s\n' README.md
} | sed 's#^./##' | sort -u > "$candidate_file"

# Hard exclusions. These are protected even if accidentally matched above.
if [[ -s "$candidate_file" ]]; then
  grep -Ev '^(\.github/workflows/|build\.sh$|module\.prop$|module_template/|prebuilt/|keystores/|zygisk/|rust/zdtd/src/api\.rs$|rust/zdtd/src/runtime\.rs$|rust/zdtd/src/vpn_netd\.rs$|rust/zdtd/src/iptables/)' "$candidate_file" > "${candidate_file}.filtered" || true
  mv "${candidate_file}.filtered" "$candidate_file"
fi

if [[ ! -s "$candidate_file" ]]; then
  echo "No safe Copilot polish candidates found." >&2
  exit 1
fi

shuf -n 1 "$candidate_file"
