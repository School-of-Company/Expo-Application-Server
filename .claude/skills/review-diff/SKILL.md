---
name: review-diff
description: Review the local diff for real defects — bugs, security holes, missing tests, performance traps. Fans the work out to parallel subagents so each lens gets its own context, then verifies the findings before reporting. Not a style checker; formatters and linters own that.
allowed-tools: Agent, Bash(git *:*), Bash(grep *:*), Bash(ls *:*), Bash(find *:*), Read, Glob, Grep
---

# Review the Local Diff

## Step 1 — Gather the Diff and This Project's Rules

`BASE` is the repo's integration branch if it has one, otherwise its default branch — find it the way
the `write-pr` skill's Step 1 does instead of assuming `develop` or `main`.

```bash
git branch --show-current
git diff --stat                          # uncommitted
git diff --stat "origin/$BASE...HEAD"    # whole branch, if reviewing before a PR
ls CLAUDE.md AGENTS.md CONTRIBUTING.md 2>/dev/null
find .claude/rules -name "*.md" 2>/dev/null
```

Read the rule files. Generic review finds generic bugs; the findings that land are violations of rules
this project already wrote down (error contract, layering, where config may be read, what must never be
logged). A finding that cites the project's own rule is actionable — "I'd have written this differently"
is not.

## Step 2 — Decide Whether to Fan Out

Measure before deciding — the stat from Step 1 already has the numbers:

```bash
git diff --shortstat "origin/$BASE...HEAD"
```

| Diff size                                                              | How to review                                              |
| ---------------------------------------------------------------------- | ---------------------------------------------------------- |
| Up to ~10 files and ~500 changed lines, or a single concern            | Review directly. Spawning agents costs more than it saves  |
| More than that **and** at least two independent concerns mixed together | Fan out — one subagent per lens that applies (Step 3)      |

**Every subagent re-reads the diff and the rules in its own context, so fanning out multiplies the
input cost by the number of lenses.** A large diff of one concern (a rename, a generated file, a bulk
config change) is still a direct review — the gain below only exists when the lenses really differ.

The reason to fan out isn't speed. **One context holding bugs, security, tests, and performance at once
reviews all four shallowly** — attention gets split and the later lenses get whatever is left. A
subagent per lens reads the same diff with one question in mind, and a large diff doesn't have to fit in
the main context at all.

## Step 3 — Fan Out, One Lens per Subagent

Spawn only the lenses the diff actually touches, in **one message** so they run concurrently. Use
`subagent_type: "general-purpose"` — a target repo won't have custom agent types defined — and
`model: "sonnet"`: a lens is a focused read, not open-ended reasoning, so the largest model isn't worth
its price here.

Which lenses to spawn:

- **correctness** — whenever the diff changes logic
- **security** — only if it touches input handling, auth, secrets, logging of values, or shell/path/query construction
- **tests** — only if it adds or changes behavior, or touches test files
- **performance** — only if it touches data access, loops over I/O, or a hot path

Give every subagent the same things, **as pointers rather than pasted content** — the diff range
(`origin/$BASE...HEAD`) and the changed-file list from Step 1, its single lens, and the rule file *paths*
(not their text). Each reads the diff itself and opens only the rule sections its lens needs.
Tell each one explicitly:

- **Read only, change nothing, report `파일:줄` for every finding.**
- Read the files the diff touches, not the whole repository; open a callee only to confirm a suspicion.
- **Reply in at most 10 lines**: one line per finding (`파일:줄 — what breaks`), no code quoted back, no
  restating the task. "No findings" is a complete answer and should be one line.

| Lens            | What it looks for                                                                                                                                                                                                                                                                        |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **correctness** | Promise created but not awaited; a failure path that returns success; an exception swallowed into a generic 500; a status code contradicting the documented contract; off-by-one, empty collection, `null`, first and last iteration; a value read before it's set; an interface, protocol, or DTO changed without its implementations or callers updated |
| **security**    | Input reaching a filesystem path, URL path, query, or shell command without validation; secrets or tokens in source, logs, or client-facing errors; an endpoint whose shape changed but not its authorization check; whether changing an id in the request can reach another user's data |
| **tests**       | New behavior with no test; failure paths with no test; a test that would pass even if the code were wrong — the classic being an assertion that "X wins over Y" with fixtures that don't collide, which proves union and not precedence; a test that depends on another having run first |
| **performance** | Blocking I/O on an async path; a network or filesystem call inside a loop that could be batched; a query added without an index; an N+1. Skip this lens entirely when the diff has no data access or hot path                                                                            |

Ask each subagent to flag violations of the Step 1 rules by name.

## Step 4 — Verify Before You Report

Subagent findings are **claims, not results.** Four agents reading in isolation produce duplicates,
findings about code the diff didn't touch, and confident descriptions of behavior that isn't there.

For each finding:

1. Open the cited `파일:줄` and confirm the code says what the finding says
2. Confirm it's inside this diff — not pre-existing code the agent wandered into
3. Merge duplicates (the same missing `await` will come back from two lenses)
4. Drop anything you can't confirm, or mark it `미확인` with what you'd need to check it

Reporting an unverified finding is worse than missing one: the author loses trust in the whole review
after the first phantom.

## Step 5 — Report

```
[HIGH] path/to/file.ts:42 — what breaks, and when
[MED]  path/to/file.ts:88 — …
[LOW]  path/to/file.ts:13 — …

미확인: <검증하지 못한 항목과 필요한 확인>
누락된 테스트: 있음 / 없음
리뷰 범위: <검사한 diff 범위, 생략한 렌즈와 이유>
```

- **Severity by consequence, not by ease of fixing.** HIGH is "this is wrong in production"; LOW is
  "this will bite someone later"
- **State what breaks.** "Handle the error" is not a finding; "a timeout here returns 200 with an empty
  body and the caller caches it" is
- **Skip style.** Formatting, import order, and naming-by-preference belong to the formatter and linter.
  Repeating them buries what matters
- **Say the diff looks fine when it does.** A review that invents findings to look thorough trains
  people to ignore reviews

## If Subagents Aren't Available

Some setups have no `general-purpose` agent type, and `Agent` may be missing from the tool set entirely.
Don't stop — review directly with the same four lenses, **one pass per lens** rather than all at once,
and say in the report that it was a single-context review.
