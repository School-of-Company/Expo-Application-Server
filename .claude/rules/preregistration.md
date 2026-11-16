---
paths:
  - "src/main/kotlin/team/startup/application/**"
  - "src/main/resources/db/migration/**"
  - "docs/**"
---

# Preregistration integration

- Base `3cda5b3` already contains #39 and #41. Attendance commit `b580f04` from #40 was reviewed and adapted locally; no additional merge/commit is required to copy it.
- Capacity reuses confirmed applications plus RESERVED seats, observes Expo outside the DB transaction and reads one read-only REPEATABLE READ snapshot without advisory locking or persisting definitions. Responses contain `sessionId`, `remaining`, `maxApplicants` and use `Cache-Control: no-store`.
- #99 decisions (2026-10-13) restore whole-party preregistration waiting and replace the #34 2026-10-09 no-waiting decision; mixed confirmed/waiting parties and waiting SMS remain out of scope. Retain cancelled history and definition-change blocks, including unresolved reservations.
- User's existing-party response may contain only newly texted companions, rather than the submitted party. Hold mismatched reservations for contract/operator reconciliation; never invent missing IDs.
- Verify Client submission, Config/Gateway anonymous routing and actual Expo/User/Attendance integration before public rollout. User QR events can precede Attendance delivery; validate this gap against real entry scans.
- As of 2026-10-10, upstream develop is `afdbe58` (two deployment-only commits after `3cda5b3`); deploy now runs only on main. Client #334/#340 and Attendance #28 remain open. Gateway Config anonymous paths still need `/application` registration/capacity routing. Keep registration disabled until server tokens/URLs and these rollout checks pass.
