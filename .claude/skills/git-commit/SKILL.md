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

---

## Commit Message Rules

Format: `type(scope): description`

- **Type**: `feat` / `fix` / `refactor` / `docs` / `chore` / `test`
- **Scope**: 이 프로젝트의 도메인 이름. 고정 목록이 아니라 **레포가 이미 쓰는 어휘를 그대로** 쓴다

  ```bash
  git log --pretty=%s -200 | grep -oE '^[a-z]+\(([^)]+)\)' | sed -E 's/.*\((.*)\)/\1/' | sort | uniq -c | sort -rn
  ```

  히스토리에 어휘가 없으면 변경 경로에서 도메인 세그먼트를 뽑는다 (`.../domain/member/` → `member`,
  `modules/expo/` → `expo`, `apps/web/` → `web`). 계층(`service`, `controller`)보다 도메인이 낫다
- **Description**: 한글, 명사형 종결, 마침표 없음
  - Good: `레포 선택 드롭다운 구현`, `PR 생성 시 base branch 조회 실패 처리`
- Subject line only (no body) — breaking change일 때만 예외적으로 본문에 `BREAKING CHANGE: <설명>` 추가
- Do NOT add AI as co-author

## Commit Flow

1. Inspect changes: `git status`, `git diff`
2. Group changed files by logical unit of change:
   - Same feature or bug fix → one commit
   - Related files that must change together → one commit
   - Unrelated changes → separate commits
3. For each logical group:
   - Stage the relevant files: `git add <file1> <file2> ...`
   - Write a commit message: `type(scope): description`
   - `git commit -m "message"`
4. Verify with `git log --oneline -n <count>`

> **Rule**: One logical change = One commit. Files that must change together belong in the same commit. Unrelated changes must be split.
