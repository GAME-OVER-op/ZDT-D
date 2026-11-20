#!/usr/bin/env bash
# Resolve the latest release tag of a GitHub repository without letting a
# rate-limited API (HTTP 403) fail the whole build. Falls back to git tags and
# finally to a pinned version passed by the caller.
set -euo pipefail

repo=${1:?repository required}
fallback=${2:?fallback tag required}
tag=""

if [[ -n ${GH_TOKEN:-} ]]; then
  tag="$(curl -fsSL --retry 5 --retry-delay 3 \
    -H "Authorization: Bearer ${GH_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/${repo}/releases/latest" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("tag_name",""))' 2>/dev/null || true)"
fi

if [[ -z ${tag} ]]; then
  tag="$(git ls-remote --tags --refs "https://github.com/${repo}.git" \
    | awk -F/ '{print $NF}' \
    | grep -E '^v?[0-9]+(\.[0-9]+)+$' \
    | sort -V \
    | tail -n 1 || true)"
fi

printf '%s\n' "${tag:-${fallback}}"
