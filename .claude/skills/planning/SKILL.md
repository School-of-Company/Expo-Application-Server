---
name: planning
argument-hint: [instructions]
description: Conduct an in-depth structured interview with the user to uncover non-obvious requirements, tradeoffs, and constraints, then produce a detailed implementation spec file.
allowed-tools: AskUserQuestion, Read, Grep, Glob, Write
---

Interview me relentlessly about every aspect of this plan until we reach a shared understanding. Walk down each branch of the design tree, resolving dependencies between decisions one-by-one. After I respond to each question, provide your evaluation and recommended answer.

Ask the questions one at a time.

If a question can be answered by exploring the codebase, explore the codebase instead.

## Write the Spec

When the interview stops producing new decisions, write the spec to `<feature-name>.spec.md` and tell me
the path. It exists to survive the conversation, so record the reasoning and not just the conclusion:

- **Goal** — what changes for the user, and what is explicitly out of scope
- **Decisions** — each decision, the alternatives rejected, and why. This is the part that keeps the same
  debate from reopening halfway through implementation
- **Constraints** — what the implementation must not break, whether I stated it or you found it in the
  codebase
- **Open questions** — what we deliberately left unresolved, and what would settle each one
- **Implementation outline** — the steps in order, each small enough to verify on its own

Don't start implementing. The spec is the deliverable.
