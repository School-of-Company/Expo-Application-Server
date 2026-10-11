---
name: resolve-reviews
description: Collect PR review comments, critically assess each one against project conventions, auto-apply valid ones, post refutation replies for invalid ones, and prompt for partial ones.
allowed-tools: Bash(bash *get-pr-data.sh:*), Bash(gh api:*), Bash(gh pr view:*), Bash(gh repo:*), Bash(git add:*), Bash(git commit:*), Bash(git log:*), Bash(git push:*), Bash(git rev-parse:*), Bash(rm:*), Edit, Read
---

## Step 1 — Collect PR Data

```bash
bash .agents/skills/resolve-reviews/scripts/get-pr-data.sh
```

The script prints `PR_TMP_DIR=<path>` on its last line — every file below lives in that
directory, which is outside the repo so it can never be swept into a commit.

Output files:

- `$PR_TMP_DIR/pr_comments.json` — inline review comments (id, path, line, body, created_at, user).
  Replies are filtered out (`in_reply_to_id == null`), so the replies this skill posted on an earlier
  run don't come back as comments to assess.
- `$PR_TMP_DIR/pr_reviews.json` — PR-level review bodies (id, state, body, submitted_at, user). Bot
  reviewers post their findings here rather than inline, so a run that reads only `pr_comments.json`
  sees nothing from them.
- `$PR_TMP_DIR/last_push.txt` — timestamp of the last push; anything newer is this round's feedback
- `$PR_TMP_DIR/pr_changed_files.txt` — changed files
- `$PR_TMP_DIR/pr_commits.txt` — commits in this PR
- `$PR_TMP_DIR/pr_diff.txt` — full diff

Assess both `pr_comments.json` and `pr_reviews.json`. Compare each entry's `created_at` /
`submitted_at` against `last_push.txt`: newer entries are this round's, older ones are from a previous
round — label them in the Step 4 report instead of silently re-processing them.

Also fetch repo and PR metadata:

```bash
gh repo view --json nameWithOwner -q .nameWithOwner
gh pr view --json number,baseRefName -q '{number: .number, base: .baseRefName}'
```

## Step 2 — Load Rules and Assess Each Comment

Before assessing any comment, discover and read all project convention files. The priority list below
starts with `CLAUDE.md`, so searching only `.claude/rules/` skips the highest-authority document — and
this catalog never deploys `.claude/rules/`, so in most repos that directory doesn't exist at all:

```bash
ls CLAUDE.md AGENTS.md CONTRIBUTING.md 2>/dev/null
ls .gemini/styleguide.md .github/copilot-instructions.md 2>/dev/null
find .claude/rules -name "*.md" 2>/dev/null
```

Read every file these return, in full. They are the authoritative rules for judging each review comment.

**Rule priority**: `CLAUDE.md` > `.claude/rules/**` > `.gemini/styleguide.md` > `CONTRIBUTING.md`

**If none of them exist**, say so in the Step 4 report and judge on the secondary criterion alone. An
empty rule set is a fact about the repo worth stating — staying quiet about it reads like the project's
conventions were applied when nothing was found to apply.

For each entry in `pr_comments.json` and `pr_reviews.json`, apply the following **layered judgment criteria**:

### Judgment criteria (priority order)

1. **Project conventions** (primary): apply rules discovered above
   - DTO annotation rules, commit scope, logging style, exception message format, etc.
2. **Language/framework best practices** (secondary): the official guide for the language and framework this project actually uses
   - Apply only when no matching project rule exists

### Verdicts

- **VALID**: reviewer is correct → attempt auto code fix
- **INVALID**: reviewer is wrong with a clear refutation → skip, post refutation reply
- **PARTIAL**: intent is correct but application method or scope is ambiguous → confirm with the user

Always cite a specific source in the rationale (e.g. `CLAUDE.md §Logging Style`, or the language guide's own wording).

## Step 3 — Act on Each Verdict

### VALID → Auto fix

1. Read the target file with the Read tool
2. Apply the reviewer's concern with the Edit tool
3. If the changes have not been committed yet, commit them
4. Record the short commit hash for use in Step 5:
   ```bash
   git rev-parse --short=7 HEAD
   ```

On failure: record the reason and fall back to PARTIAL.

### INVALID → Skip

Do not modify any code. Record the refutation rationale for Step 5.

### PARTIAL → Confirm with the user

Ask the user directly:

```
⚠️ PARTIAL: [file:line] (reviewer)
Review: "..."
Rationale: ...
Accept? (y / n / s = skip for now)
```

- `y`: treat as VALID, attempt code fix
- `n`: treat as INVALID, skip
- `s` / other: record as PENDING

## Step 4 — Print Report

```
## resolve-reviews Results

| # | Reviewer | File | Verdict | Rationale | Action |
|---|----------|------|---------|-----------|--------|
| 1 | alice | `<file>:12` | ✅ VALID | CLAUDE.md §Logging Style | Auto-fixed (abc1234) |
| 2 | bob | `<file>:34` | ❌ INVALID | CLAUDE.md §Exception Message | Skipped |
| 3 | alice | `<file>:56` | ⚠️ PARTIAL | - | PENDING |
```

## Step 5 — Push Commits

If any VALID fixes were committed in Step 3, push them before posting replies so the referenced commit hashes are visible on GitHub:

```bash
git push
```

## Step 6 — Post GitHub Replies

Post an inline reply for each comment. Always quote `path` and `comment_id` to prevent shell injection.

```bash
gh api "repos/<owner>/<repo>/pulls/<pr_number>/comments/<comment_id>/replies" \
  -f body="<reply_body>"
```

Always start the reply body with `@<reviewer>` (the `user` field from `pr_comments.json`).

For reply body templates, read `.agents/skills/resolve-reviews/references/reply-formats.md`.

## Step 7 — Cleanup

```bash
rm -rf "$PR_TMP_DIR"
```
