---
name: planning
argument-hint: [instructions]
description: Conduct an in-depth structured interview with the user to uncover non-obvious requirements, tradeoffs, and constraints, then produce a detailed implementation spec file.
allowed-tools: Read, Grep, Glob, Write
---

Interview me about every aspect of this plan until we reach a shared understanding. Walk down each branch of
the design tree, resolving dependencies between decisions one-by-one. After I respond to each question,
provide your evaluation and recommended answer.

The Claude version fans research and review out to subagents; here the same passes run one after another.

## Step 1 — Research Before the First Question

Never ask what the codebase already answers. Look first, so the questions that reach me are the ones only
I can answer. Make three passes, skipping any that doesn't apply:

- **Patterns** — existing code this should follow or reuse
- **Constraints** — what depends on the code being changed (tests, callers, config)
- **History** — earlier attempts at the same thing (`git log -S`, `git log --grep`) and why they were dropped

Cite `file:line` for every claim you rely on.

## Step 2 — Interview

- Ask **one question per message**, and end the message on that question.
- Give your recommended answer with each question, so I can reply "yes".
- Ask only what research couldn't settle. If I told you something earlier, don't ask it again.
- Stop interviewing when the questions you have left would not change the spec — or when I say to. If the
  scope turns out far bigger than it first looked, stop and say so before going on.

## Step 3 — Write the Spec

Write it to `<feature-name>.spec.md` and tell me the path. It exists to survive the conversation, so record
the reasoning and not just the conclusion:

- **Goal** — what changes for the user, and what is explicitly out of scope
- **Decisions** — each decision, the alternatives rejected, and why. This is the part that keeps the same
  debate from reopening halfway through implementation
- **Constraints** — what the implementation must not break, whether I stated it or you found it in the
  codebase
- **Open questions** — what we deliberately left unresolved, and what would settle each one
- **Implementation outline** — the steps in order, each one action with a result you can check

A line that decides nothing ("handle edge cases", "add appropriate validation", "TBD") doesn't belong in
the spec — either make the decision or move it to Open questions.

## Step 4 — Review

Before showing me the spec, re-read it as someone who wasn't in the interview, using only the file, and
check:

- every decision lists the rejected alternative and the reason
- every open question says what would settle it
- every outline step can be verified on its own
- nothing contradicts another section, or the code

Fix the gaps that hold up. Review once; don't loop.

Don't start implementing. The spec is the deliverable.
