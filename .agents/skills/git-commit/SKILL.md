---
name: git-commit
description: Create Git commits following this project's Conventional Commits style. Splits changes into logical units and writes concise Korean-description commit messages.
allowed-tools: Bash
---

## Step 0 — Branch Check (Required)

Never commit onto a shared branch. Which branch that is differs per project, so **ask the repo** instead
of assuming `develop` (checking only for `develop` means committing straight onto `main` in a
trunk-based repo — the exact mistake this step exists to prevent):

```bash
git branch --show-current
gh repo view --json defaultBranchRef -q .defaultBranchRef.name 2>/dev/null
git ls-remote --heads origin develop development dev 2>/dev/null | sed 's#.*refs/heads/##'
```

The shared branches are the default branch plus any integration branch the remote has.

**If the current branch is one of them:**

1. Analyze all changes with `git status` and `git diff`
2. Infer a branch name from the changes:
   - Format: `<type>/<kebab-case-description>` — same type as the planned commit
   - Specific enough to identify the work: `feat/repo-select-dropdown`, `fix/base-branch-check`,
     never `update` or `changes`
3. Branch off the **remote** tip, so you don't inherit a stale local state:
   ```bash
   git fetch origin <shared-branch> --quiet
   git checkout -b <type>/<inferred-name> "origin/<shared-branch>"
   ```
   Use the integration branch when the repo has one, otherwise the default branch.
4. Proceed with the commit flow below

**Otherwise** (already on a work branch): proceed directly to the commit flow. Don't reuse a branch
that belongs to work someone already merged — start a new one.

Either way, compare the branch name against what actually changed. If they describe clearly different
work — the branch says `feat/repo-select-dropdown` but the diff is an unrelated hotfix — stop and branch
again. If they're only loosely related, go ahead but say so.

---

## Commit Message Rules

Read `.agents/shared/commit-conventions.md` in full before writing any message. It holds the type and
scope vocabulary — both read off this repo's own history rather than a fixed list — the description
format, and the rule that the repo's own `CLAUDE.md` wins over all of it. It ships with this skill, so
it is always present.

## Commit Flow

Commit when the user asks for it. Finishing a piece of work is not itself a request to commit.

1. Inspect changes: `git status`, `git diff --staged`
   - Stop if anything secret-shaped is staged — `.env` / `.env.*`, key or certificate files, a literal
     token or password. Committed secrets stay in history after the file is deleted, so this check is
     worth more than the seconds it costs.
2. Group changed files by logical unit of change:
   - Same feature or bug fix → one commit
   - Related files that must change together → one commit
   - Unrelated changes → separate commits
3. For each logical group:
   - Stage the relevant files **by name**: `git add <file1> <file2> ...`
   - Never `git add -A` or `git add .` — they sweep in whatever else is in the tree, which is how an
     unrelated file or a secret ends up in someone's commit. Leave the rest unstaged and tell the user
     what you skipped.
   - Write a commit message: `type(scope): description`
   - `git commit -m "message"`
4. Verify with `git log --oneline -n <count>`

## Push

Push only when asked, as its own step — never bundled into the commit. Never push to the shared branch
found in Step 0; push the work branch and let the PR carry it.

```bash
git push -u origin <branch-name>
```

> **Rule**: One logical change = One commit. Files that must change together belong in the same commit. Unrelated changes must be split.
