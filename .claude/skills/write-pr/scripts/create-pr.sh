#!/bin/bash
set -e

TITLE="${1:?Error: PR title is required. Usage: create-pr.sh <title> <body-file> [label1,label2,...]}"
BODY_FILE="${2:?Error: Body file is required. Usage: create-pr.sh <title> <body-file> [label1,label2,...]}"
LABELS="${3:-}"

if [ ! -f "$BODY_FILE" ]; then
  echo "ERROR: Body file not found: $BODY_FILE" >&2
  exit 1
fi

if [[ "$TITLE" == \[* ]]; then
  echo "ERROR: PR title must not start with a bracketed prefix." >&2
  exit 1
fi

TEMPLATE=.github/PULL_REQUEST_TEMPLATE.md
if [ ! -f "$TEMPLATE" ]; then
  echo "ERROR: PR template not found: $TEMPLATE" >&2
  exit 1
fi
if cmp -s "$TEMPLATE" "$BODY_FILE"; then
  echo "ERROR: Fill in the PR template before creating a PR." >&2
  exit 1
fi
if ! diff -q <(grep '^## ' "$TEMPLATE") <(grep '^## ' "$BODY_FILE") >/dev/null; then
  echo "ERROR: PR body must keep all template sections in order." >&2
  exit 1
fi
if grep -Fq -- '> 이번 PR에서 어떤 작업을 했는지 간단히 요약해주세요.' "$BODY_FILE" ||
  grep -Fq -- '- 리뷰어가 알면 좋은 변경 이유, 배경, 고려했던 점 등을 적어주세요.' "$BODY_FILE" ||
  grep -Fxq -- '- Close #' "$BODY_FILE"; then
  echo "ERROR: Replace PR template placeholders with actual content." >&2
  exit 1
fi
if ! awk '
  /^## / { if (seen && !filled) empty = 1; seen = 1; filled = 0; next }
  /^[[:space:]]*$/ || /^---$/ || /^>/ { next }
  seen { filled = 1 }
  END { if (!seen || !filled || empty) exit 1 }
' "$BODY_FILE"; then
  echo "ERROR: Fill in every PR template section." >&2
  exit 1
fi

# Base branch — ask the repo instead of assuming a branching model.
#
# A hardcoded develop/master pair fails in two directions: it targets a branch that doesn't exist in
# trunk-based repos, and it picks the wrong one where the integration branch has another name. So reuse
# the base of an existing PR for this branch, else prefer an integration branch if the remote has one,
# else fall back to whatever GitHub reports as the default branch.
BASE=$(gh pr view --json baseRefName -q .baseRefName 2>/dev/null || true)

if [ -z "$BASE" ]; then
  DEFAULT=$(gh repo view --json defaultBranchRef -q .defaultBranchRef.name 2>/dev/null || echo main)
  CURRENT=$(git branch --show-current)

  for candidate in develop development dev; do
    if [ "$CURRENT" != "$candidate" ] && git ls-remote --exit-code --heads origin "$candidate" >/dev/null 2>&1; then
      BASE="$candidate"
      break
    fi
  done

  [ -z "$BASE" ] && BASE="$DEFAULT"
  # Standing on the integration branch means this is a release PR — target the default branch.
  [ "$CURRENT" = "$BASE" ] && BASE="$DEFAULT"
fi

ARGS=(gh pr create --title "$TITLE" --body-file "$BODY_FILE" --base "$BASE")

# Labels — only pass ones this repo actually defines. `gh pr create` fails outright on an unknown label,
# which would throw away a finished title and body over a naming difference between repos.
APPLIED=""
if [ -n "$LABELS" ]; then
  EXISTING=$(gh label list --limit 200 --json name -q '.[].name' 2>/dev/null || true)
  IFS=',' read -ra LABEL_ARRAY <<< "$LABELS"
  for label in "${LABEL_ARRAY[@]}"; do
    trimmed=$(echo "$label" | xargs)
    [ -z "$trimmed" ] && continue
    if printf '%s\n' "$EXISTING" | grep -Fxq "$trimmed"; then
      ARGS+=(--label "$trimmed")
      APPLIED="${APPLIED:+$APPLIED, }$trimmed"
    else
      echo "  (label '$trimmed' not defined in this repo — skipped)" >&2
    fi
  done
fi

echo "Creating PR..."
echo "  Title : $TITLE"
echo "  Base  : $BASE"
[ -n "$APPLIED" ] && echo "  Labels: $APPLIED"
echo ""

"${ARGS[@]}"
