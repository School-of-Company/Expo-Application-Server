---
name: resolve-reviews
description: Collect PR review comments, critically assess each one against project conventions and the actual code, auto-apply valid ones, post refutation replies for invalid ones, and prompt for partial ones.
allowed-tools: Bash(bash *get-pr-data.sh:*), Bash(gh api:*), Bash(gh pr view:*), Bash(gh repo:*), Bash(git add:*), Bash(git commit:*), Bash(git log:*), Bash(git push:*), Bash(git rev-parse:*), Bash(rm:*), Edit, Read
---

Review feedback is a claim to check, not an order. Agreeing with a wrong comment ships a bug as surely as
ignoring a right one — so each comment is verified against the code before anything changes.

## Step 1 — Collect PR Data

```bash
bash .agents/skills/resolve-reviews/scripts/get-pr-data.sh
```

The last line printed is `PR_TMP_DIR=<path>`; every file below lives there, outside the repo so it can
never be swept into a commit.

- `pr_comments.json` — inline comments (id, path, line, body, created_at, user). Replies are filtered
  out, so replies this skill posted earlier don't come back as comments to assess.
- `pr_reviews.json` — PR-level review bodies (id, state, body, submitted_at, user). Bot reviewers post
  their findings here instead of inline, so reading only `pr_comments.json` misses them.
- `last_push.txt` — timestamp of the last push. Entries newer than it are this round's; older ones are a
  previous round — label them in the report instead of silently re-processing.
- `pr_changed_files.txt`, `pr_commits.txt`, `pr_diff.txt` — the PR's files, commits and full diff.

```bash
gh repo view --json nameWithOwner -q .nameWithOwner
gh pr view --json number,baseRefName -q '{number: .number, base: .baseRefName}'
```

## Step 2 — Load Rules and Assess Each Comment

Read every project convention file before judging anything. Search for `CLAUDE.md` too, not only
`.claude/rules/` — it is the highest-authority document, and this catalog never deploys `.claude/rules/`,
so in most repos that directory doesn't exist:

```bash
ls CLAUDE.md AGENTS.md CONTRIBUTING.md 2>/dev/null
ls .gemini/styleguide.md .github/copilot-instructions.md 2>/dev/null
find .claude/rules -name "*.md" 2>/dev/null
```

Priority: `CLAUDE.md` > `.claude/rules/**` > `.gemini/styleguide.md` > `CONTRIBUTING.md`. If none exist,
say so in the report and judge on the secondary criterion alone — an empty rule set is a fact worth
stating, and silence reads as if conventions were applied.

For each entry in `pr_comments.json` and `pr_reviews.json`:

1. **Understand it.** If the comment is unclear, don't guess at a fix — treat it as PARTIAL and ask.
2. **Check it against the code.** Open the cited `path:line` and the code around it. Is the reviewer's
   description true? Would the suggested change break a caller, a test, or behavior the diff is meant to
   keep (`grep` the callers)? A suggestion that is right in general can be wrong here.
3. **Judge by priority.** (1) The project's own rules — DTO annotations, commit scope, logging style,
   exception format. (2) The official guide of the language and framework this project actually uses, only
   when no project rule applies.

Verdicts:

- **VALID** — the reviewer is correct → auto-fix.
- **INVALID** — the reviewer is wrong, with a clear refutation → skip and post the refutation.
- **PARTIAL** — the intent is right but the method or scope is ambiguous → confirm with the user.

Always cite a specific source (`CLAUDE.md §Logging Style`, the guide's own wording, or `path:line` of the
code that contradicts the comment).

## Step 3 — Act on Each Verdict

**VALID** — one comment at a time, never batched:

1. Read the target file, apply the fix with Edit.
2. Run the project's tests (or at least the affected ones) before moving on. A fix that breaks something
   else is not resolved; revert it and fall back to PARTIAL with the reason.
3. Commit if uncommitted, and record the short hash for Step 6: `git rev-parse --short=7 HEAD`.

**INVALID** — change no code; record the refutation for Step 6.

**PARTIAL** — ask the user directly:

```
⚠️ PARTIAL: [file:line] (reviewer)
Review: "..."
Rationale: ...
Accept? (y / n / s = skip for now)
```

`y` → treat as VALID. `n` → treat as INVALID. `s` or anything else → record as PENDING.

## Step 4 — Print Report

```
## resolve-reviews Results

| # | Reviewer | File | Verdict | Rationale | Action |
|---|----------|------|---------|-----------|--------|
| 1 | alice | `<file>:12` | ✅ VALID | CLAUDE.md §Logging Style | Auto-fixed (abc1234) |
| 2 | bob | `<file>:34` | ❌ INVALID | `<file>:30` caller relies on it | Skipped |
| 3 | alice | `<file>:56` | ⚠️ PARTIAL | - | PENDING |
```

## Step 5 — Push Commits

If Step 3 committed any fixes, push before replying, so the referenced hashes are visible on GitHub:

```bash
git push
```

## Step 6 — Post GitHub Replies

One inline reply per comment. Always quote `path` and `comment_id` to prevent shell injection, and start
the body with `@<reviewer>` (the `user` field from `pr_comments.json`):

```bash
gh api "repos/<owner>/<repo>/pulls/<pr_number>/comments/<comment_id>/replies" \
  -f body="<reply_body>"
```

Reply templates are in `.agents/skills/resolve-reviews/references/reply-formats.md`. State the fix or the reason
plainly — no thanks, no agreement you haven't verified.

## Step 7 — Cleanup

```bash
rm -rf "$PR_TMP_DIR"
```
