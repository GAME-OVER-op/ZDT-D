#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

BRANCH="${COPILOT_POLISH_BRANCH:-copilot-polish}"
BASE_BRANCH="${COPILOT_POLISH_BASE_BRANCH:-main}"
TITLE="${COPILOT_POLISH_PR_TITLE:-Copilot polish: review AI improvements}"

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI is not available." >&2
  exit 127
fi

git fetch origin "$BASE_BRANCH" "$BRANCH" --prune >/dev/null 2>&1 || true

if git diff --quiet "origin/$BASE_BRANCH...HEAD"; then
  echo "No diff between $BRANCH and $BASE_BRANCH. PR is not needed."
  exit 0
fi

HEAD_SHA="$(git rev-parse --short HEAD)"
FULL_SHA="$(git rev-parse HEAD)"
CHANGED_FILES="$(git diff --name-only "origin/$BASE_BRANCH...HEAD" | sed 's/^/- /')"
COMMITS="$(git log --oneline --no-merges "origin/$BASE_BRANCH..HEAD" | sed 's/^/- /' | head -n 30)"

BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

cat > "$BODY_FILE" <<EOF_BODY
## Copilot Polish

This PR contains conservative AI-assisted improvements from the \`$BRANCH\` branch.

### Validation

The latest accepted change was validated by:

\`\`\`text
.github/workflows/build.yml
build_type=Release
\`\`\`

### Latest head

\`\`\`text
$FULL_SHA
\`\`\`

### Changed files

$CHANGED_FILES

### Recent commits

$COMMITS

### Review notes

Please review manually before merging.

Expected change type:
- small behavior-preserving refactor
- readability improvement
- safe error handling improvement
- minor UI/code cleanup
- documentation wording improvement

Forbidden change type:
- architecture rewrite
- release workflow behavior change
- service-build logic change
- routing / iptables / NFQUEUE behavior change
- Zygisk / prebuilt / keystore changes
- app assignment conflict logic changes
EOF_BODY

EXISTING_PR_URL="$(
  gh pr list \
    --state open \
    --base "$BASE_BRANCH" \
    --head "$BRANCH" \
    --json url \
    --jq '.[0].url // ""' 2>/dev/null || true
)"

if [[ -n "$EXISTING_PR_URL" ]]; then
  echo "Updating existing PR: $EXISTING_PR_URL"
  gh pr edit "$EXISTING_PR_URL" \
    --title "$TITLE" \
    --body-file "$BODY_FILE"

  gh pr comment "$EXISTING_PR_URL" \
    --body "Copilot polish branch updated after successful build.yml validation. Latest head: \`$HEAD_SHA\`." >/dev/null 2>&1 || true

  echo "PR_URL=$EXISTING_PR_URL"
  {
    echo "### Copilot Polish PR"
    echo ""
    echo "Updated existing PR: $EXISTING_PR_URL"
  } >> "$GITHUB_STEP_SUMMARY" 2>/dev/null || true
else
  echo "Creating new PR: $BRANCH -> $BASE_BRANCH"
  PR_URL="$(
    gh pr create \
      --base "$BASE_BRANCH" \
      --head "$BRANCH" \
      --title "$TITLE" \
      --body-file "$BODY_FILE"
  )"

  echo "PR_URL=$PR_URL"
  {
    echo "### Copilot Polish PR"
    echo ""
    echo "Created PR: $PR_URL"
  } >> "$GITHUB_STEP_SUMMARY" 2>/dev/null || true
fi
