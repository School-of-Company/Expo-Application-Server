---
name: planning
argument-hint: [instructions]
description: Conduct an in-depth structured interview with the user to uncover non-obvious requirements, tradeoffs, and constraints, then produce a detailed implementation spec file.
allowed-tools: AskUserQuestion, Read, Grep, Glob, Write, Agent, Bash(git log:*)
---

Interview me about every aspect of this plan until we reach a shared understanding. Walk down each branch of
the design tree, resolving dependencies between decisions one-by-one. After I respond to each question,
provide your evaluation and recommended answer.

## Step 1 — Research Before the First Question

Never ask what the codebase already answers. Look first, so the questions that reach me are the ones only
I can answer: existing patterns and code worth reusing, constraints the change must not break (tests,
callers, config), and earlier attempts at the same thing (`git log -S`, `git log --grep`).

Work directly when the task touches a few files. When it spans many areas, fan out **one subagent per lens
that applies** (`Agent`, `subagent_type: "general-purpose"`, `model: "sonnet"`, at most 3):

- **Patterns** — what already exists that this should follow or reuse
- **Constraints** — what depends on the code being changed
- **History** — what was tried before, and why it was dropped

Pass each one the question and the paths to start from, not pasted content, and cap the reply at ~150
words with `file:line` for every claim. Treat what comes back as claims to verify, not facts. If `Agent`
isn't available, do the same three passes yourself, one after another.

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

## Step 4 — Independent Review

Before showing me the spec, have it read by someone who wasn't in the interview: one subagent
(`general-purpose`, `model: "sonnet"`), given only the spec's path. Judge it against:

- every decision lists the rejected alternative and the reason
- every open question says what would settle it
- every outline step can be verified on its own
- nothing contradicts another section, or the code

Reply format: `PASS`, or a list of gaps with the section for each, under ~150 words. Verify each gap
before acting on it — fix the ones that hold and note why you left the rest. Review **once**; don't loop.
Without `Agent`, re-read the spec against the same four checks yourself.

Don't start implementing. The spec is the deliverable.
