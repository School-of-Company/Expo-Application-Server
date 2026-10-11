---
name: review-diff
description: Review the local diff for real defects — bugs, security holes, missing tests, performance traps. Fans the work out to parallel subagents so each lens gets its own context, then verifies the findings before reporting. Not a style checker; formatters and linters own that.
allowed-tools: Agent, Bash(git *:*), Bash(grep *:*), Bash(ls *:*), Bash(find *:*), Read, Glob, Grep
---

# Review the Local Diff

## Step 1 — Gather the Diff and This Project's Rules

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

| Diff size                                      | How to review                                             |
| ---------------------------------------------- | --------------------------------------------------------- |
| A handful of files, one concern                | Review directly. Spawning agents costs more than it saves |
| Many files, or several concerns mixed together | Fan out — one subagent per lens (Step 3)                  |

The reason to fan out isn't speed. **One context holding bugs, security, tests, and performance at once
reviews all four shallowly** — attention gets split and the later lenses get whatever is left. A
subagent per lens reads the same diff with one question in mind, and a large diff doesn't have to fit in
the main context at all.

## Step 3 — Fan Out, One Lens per Subagent

Spawn these in **one message** so they run concurrently (the limit is 20; four is plenty). Use
`subagent_type: "general-purpose"` — a target repo won't have custom agent types defined.

Give every subagent the same three things: the diff range, its single lens, and the output format below.
Tell each one explicitly: **read only, change nothing, and report `파일:줄` for every finding.**

| Lens            | What it looks for                                                                                                                                                                                                                                                                        |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **correctness** | Promise created but not awaited; a failure path that returns success; an exception swallowed into a generic 500; a status code contradicting the documented contract; off-by-one, empty collection, `null`, first and last iteration; a value read before it's set                       |
| **security**    | Input reaching a filesystem path, URL path, query, or shell command without validation; secrets or tokens in source, logs, or client-facing errors; an endpoint whose shape changed but not its authorization check; whether changing an id in the request can reach another user's data |
| **tests**       | New behavior with no test; failure paths with no test; a test that would pass even if the code were wrong — the classic being an assertion that "X wins over Y" with fixtures that don't collide, which proves union and not precedence; a test that depends on another having run first |
| **performance** | Blocking I/O on an async path; a network or filesystem call inside a loop that could be batched; a query added without an index; an N+1. Skip this lens entirely when the diff has no data access or hot path                                                                            |

Also pass each subagent the rules from Step 1, and ask it to flag violations of those by name.

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
