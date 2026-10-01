#!/bin/bash
set -e

PR_NUMBER=$(gh pr view --json number -q .number 2>/dev/null)
if [ -z "$PR_NUMBER" ]; then
  echo "ERROR: No open PR found for current branch." >&2
  exit 1
fi

REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
BASE=$(gh pr view "$PR_NUMBER" --json baseRefName -q .baseRefName)

# 임시 파일을 저장소 안(`.pr-tmp/`)에 두면 `git add -A` 같은 명령에 휩쓸려 실수로 커밋된다.
# 항상 저장소 바깥에 만들고, 경로를 마지막 줄로 알려 준다.
PR_TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/resolve-reviews-pr.XXXXXX")

git fetch origin "$BASE" --quiet 2>/dev/null || true

# 마지막 커밋 시각. 이보다 나중에 달린 리뷰가 이번 라운드의 새 피드백이다.
git log -1 --format=%cI HEAD > "$PR_TMP_DIR"/last_push.txt

# 인라인 리뷰 코멘트. `in_reply_to_id == null` 필터가 핵심이다 — 이게 없으면 이 스킬이 Step 6에서
# 남긴 답글이 다음 실행에 리뷰 코멘트로 다시 잡혀서, 자기 답글을 리뷰로 판정하게 된다.
gh api "repos/$REPO/pulls/$PR_NUMBER/comments?per_page=100" \
  --jq '[.[] | select(.in_reply_to_id == null) | {id, path, line, body, created_at, user: .user.login}]' \
  > "$PR_TMP_DIR"/pr_comments.json

# PR 레벨 리뷰 본문. 봇 리뷰어(CodeRabbit, Gemini 등)는 지적을 인라인이 아니라 리뷰 본문에 싣기
# 때문에, 위 엔드포인트만 보면 그런 리뷰어의 지적이 통째로 0건으로 보인다.
gh api "repos/$REPO/pulls/$PR_NUMBER/reviews?per_page=100" \
  --jq '[.[] | select(.body | length > 0) | {id, state, body, submitted_at, user: .user.login}]' \
  > "$PR_TMP_DIR"/pr_reviews.json

git log "origin/$BASE..HEAD" --pretty=format:"%H %h %s" > "$PR_TMP_DIR"/pr_commits.txt

git diff "origin/$BASE...HEAD" --name-only > "$PR_TMP_DIR"/pr_changed_files.txt

git diff "origin/$BASE...HEAD" > "$PR_TMP_DIR"/pr_diff.txt

echo "PR #$PR_NUMBER | Repo: $REPO | Base: $BASE | Last push: $(cat "$PR_TMP_DIR"/last_push.txt)"
echo "Inline comments: $(jq length "$PR_TMP_DIR/pr_comments.json"), Review bodies: $(jq length "$PR_TMP_DIR/pr_reviews.json"), Changed files: $(wc -l < "$PR_TMP_DIR/pr_changed_files.txt" | tr -d ' ')"
echo "PR_TMP_DIR=$PR_TMP_DIR"
