---
name: systematic-debugging
description: Use when encountering any bug, test failure, or unexpected behavior, before proposing fixes
---

# Systematic Debugging

The feedback-loop, ranked-hypothesis and cleanup steps are adapted from
[mattpocock/skills `diagnosing-bugs`](https://github.com/mattpocock/skills) (MIT).

Random fixes waste time and create new bugs; a patch on the symptom hides the cause.

**ALWAYS find the root cause before attempting a fix.** Until Phase 1 is done, you may not propose one.

Use it for any technical issue — test failures, production bugs, unexpected behavior, performance, build
failures — and **especially** under time pressure, when the fix looks obvious, or after a fix already
failed. Simple bugs have root causes too, and investigating is faster than guess-and-check.

Finish each phase before starting the next.

## Phase 1: Root Cause Investigation

1. **Read the error completely** — warnings, the whole stack trace, line numbers, file paths, error codes.
   They often state the answer.
2. **Build a feedback loop before anything else** — one command you have already run that goes red on
   *this exact symptom* and green once it's fixed: a failing test, a `curl` against a dev server, a CLI
   call on a fixture, a replay of a captured payload, a `git bisect run` harness, or the same input through
   the old and new version. "Runs without erroring" is not red-capable. Then tighten it: faster (seconds),
   sharper (assert the symptom itself), deterministic (pin time, seed randomness). Flaky bug: the goal is a
   higher reproduction rate, so loop the trigger 100×. Once it's red, **shrink it** — cut inputs, callers and
   config one at a time until every remaining piece is load-bearing.
   If you can't build a loop, say so, list what you tried, and ask for access, a redacted captured artifact,
   or permission to add temporary instrumentation. Don't form theories without one.
3. **Check what changed** — `git diff`, recent commits, new dependencies, config, environment differences.
4. **In a multi-component system, find which boundary breaks before fixing anything** (CI → build →
   signing, API → service → database). Log what enters and what leaves each component, and check that
   environment/config propagates. Run once, read where the data goes wrong, then investigate only that
   component.
5. **Trace the data flow** when the error is deep in the call stack: where does the bad value originate,
   what passed it in, and keep going up. Fix at the source, not where it surfaces. The full technique is
   in `root-cause-tracing.md` in this directory.

## Phase 2: Pattern Analysis

1. Find similar code in the same codebase that works.
2. If you're implementing a pattern, read the reference implementation completely — skimming is how
   a detail gets missed.
3. List every difference between working and broken, however small. Don't dismiss one as "can't matter".
4. Note what the code depends on: other components, settings, environment, assumptions.

## Phase 3: Hypothesis and Testing

1. **Write down 3–5 ranked hypotheses before testing any**: a single one anchors you on the first plausible
   idea. Each must be falsifiable — "if X is the cause, changing Y makes the bug disappear / changing Z
   makes it worse". A hypothesis with no prediction is a guess; sharpen it or drop it. Show the list to the
   user first when they're around — they often know which one was just deployed or already ruled out.
2. **Test them with the smallest possible change**, one variable at a time, starting from the top. Tag every
   debug log with a unique prefix (`[DEBUG-a4f2]`) so cleanup is one grep, and prefer a debugger over ten
   logs. For slowness, measure a baseline first and bisect — logs rarely help. Never print secrets: write
   `<REDACTED>` and keep credentials in environment variables.
3. Confirmed → Phase 4. Not confirmed → form a **new** hypothesis; don't stack more fixes on top.
4. When you don't understand something, say so and research or ask. Don't pretend.

## Phase 4: Implementation

1. **Write a failing test first** — the simplest reproduction, automated if the project has a framework,
   a throwaway script if not. Use the `write-test` skill for a test that fails on the current code.
2. **One fix, aimed at the root cause.** No "while I'm here" cleanups, no bundled refactoring.
3. **Verify**: the new test passes, no other test broke, the Phase 1 loop is green against the original
   (un-shrunk) scenario, and every `[DEBUG-…]` log and throwaway script is gone. The `test` skill runs the
   suite. Put the hypothesis that turned out right in the commit or PR message.
4. **If the fix didn't work**, stop and count the attempts so far. Under 3: back to Phase 1 with what you
   learned. At 3 or more: don't try a fourth — go to step 5.
5. **After 3 failed fixes, question the architecture.** The signs: each fix exposes shared state or
   coupling somewhere else, fixes need a large refactor to land, or each one creates a new symptom
   elsewhere. That is a wrong design, not a failed hypothesis. Discuss it with the user before trying
   again.

## Signs You're Skipping the Process

Any of these means stop and return to Phase 1:

- "Quick fix now, investigate later" / "just try changing X and see" / "it's probably X"
- Several changes at once, then running the tests; skipping the test because you'll "verify manually"
- "I don't fully understand this, but it might work"
- Proposing solutions before tracing the data flow
- "One more attempt" after two failures, or each fix surfacing a new problem in a different place
- The user pushing back with "is that not happening?", "stop guessing", or "we're stuck?" — you assumed
  something without checking

## When No Root Cause Exists

If a thorough investigation shows the cause is environmental, timing-dependent, or external: document what
you checked, add appropriate handling (retry, timeout, a clear error message), and add logging for next
time. Most "no root cause" cases are an investigation that stopped early, so be sure before concluding it.

## Supporting Techniques

Files in this directory:

- `root-cause-tracing.md` — trace a bug backward through the call stack to its original trigger
- `defense-in-depth.md` — add validation at multiple layers once the root cause is found
- `condition-based-waiting.md` — replace arbitrary timeouts with condition polling
