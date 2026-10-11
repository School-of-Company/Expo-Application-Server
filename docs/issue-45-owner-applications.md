# Issue #45: owner applications integration status

Base: Application `439b61c1bc7705518f25bec625c97c552e53a5b9` (develop).
Provider source checks: 2026-10-11. No public owner controller or security allowlist is added.
Expo develop inspected at `cad63429eea3e969eeab4f60f9938e7e15a23358`; Attendance develop
at `3a43254034a8de063738901514100a6e33d0e0c4`. Attendance's QR entry flow validates the
assignment before a separate User entry call, which leaves the cancellation/scan race open.

## Local implementation

`GetOwnerApplicationsService.execute` resolves the opaque participant session through User,
then calls the merged managed-party API. It returns only that party's preregistration and
program ledger entries, immutable question/answer snapshots when available, ordered status
history, and an active `applied` value (CONFIRMED or WAITING). Cancelled applications have
`applied=false`. Transport session IDs, registration phone fields and QR codes are excluded.
Original form answers can themselves contain personal data; they are not redacted by this
service and must not be publicly exposed before the answer-access/retention policy is approved.

`CancelOwnerApplicationService.execute` takes an application ID and type, authenticates the
session, and returns the same 404 for nonexistent, other-expo and other-owner applications.
Cancellation selects only the target's ownership/status metadata by its typed primary key
(BIGINT for preregistration, UUID for programs). It does not read historical answers or the
entire party history, so an unrelated damaged answer cannot block cancellation or lengthen
the cancellation locks. Invalid IDs and a wrong application type also return 404.
It requires an `OwnerCancellationGuard` permit for active applications. No production guard
exists: active cancellation fails with 503. The query never advertises positive cancellation
availability (`cancellable=false`, `EXTERNAL_CONTRACT_REQUIRED`; cancelled rows use `CANCELLED`).

With a valid permit, cancellation locks the Expo, participant and linked trainee, rechecks the
local application, and rejects cancellation at or after the separate cancellation deadline.
Preregistration cancellation rejects active CONFIRMED/WAITING program ledger entries and legacy
standard/linked training rows. A representative cancels only after all active companions,
including companions in another session. Preregistration status/history and Attendance DELETE
outbox commit together. Program cancellation delegates to the existing per-application service,
retaining its deadline checks, locks, waitlist promotion, history and training version update.
Repeated cancellation is a no-op after owner authentication. No person, QR, trainee link or
Attendance record is deleted by this service.

Reapplication continues through the existing preregistration/program services with a new
request/idempotency key. Old keys retain their original receipt; they do not create a new
application. Program reapplication rechecks preregistration, role, current policy, form and
capacity. Preregistration retains its existing reservation/recovery and registration validation
flow; no new owner reapplication endpoint or operational exception is introduced.

V11 links new completed preregistration applications to their exact reservation request ID.
Legacy applications and direct ledger applications have no provable answer link and return null
snapshots. There is no phone/current-party backfill. Purge deletes applications before reservations
to preserve the foreign key, retaining the existing uncertain-delivery recovery behavior.

## Verified provider contracts and blockers

| Dependency | Actual state/source | Required before public exposure |
| --- | --- | --- |
| User managed party | [PR #81](https://github.com/School-of-Company/Expo-User-Server/pull/81) merged into develop, merge `633e7738990a9fa914cc4d25d40b81db9b274201`. POST `/internal/standard-participants/managed` takes `{expoId, phoneNumber}` and returns `{registered, participants:[{participantId,name,occupation,representative}]}`. | Deploy this contract; use the phone obtained from a verified session, never a caller-provided phone. |
| User participant auth | [PR #80](https://github.com/School-of-Company/Expo-User-Server/pull/80) open, inspected head `75e4bc382d8d1cc7817d1482f271079e84ee01fb`. Proposed POST `/internal/participant-sessions/resolve` takes `{expoId,sessionId}` and returns `{expoId,phoneNumber,expiresAt}`, or 401 with SESSION_REQUIRED/SESSION_EXPIRED. | Merge/deploy and agree cookie forwarding, domain, CORS, CSRF, event enablement and public error mapping. Existing gateway currently maps both session errors to a generic 401; public error DTOs are not implemented. |
| User trainee association | [PR #82](https://github.com/School-of-Company/Expo-User-Server/pull/82) open, inspected head `3bae76c4fbd9395abf3202bbfd1f81ba612e211d`. Creation and code-based lookup do not provide an owner read-only batch link lookup. | Resolve existing legacy trainee applications without creating people/links during lookup or cancellation. A guard's null linkedTraineeId must mean authoritative absence, not lookup failure. Legacy program rows without ledger snapshots/owner linkage are not exposed by this query. |
| Preregistration deadline | Existing Expo preregistration definition has startedAt/endedAt/closed, but no separate cancelEndedAt. | Provide an authoritative cancellation deadline. Registration/form/session end must not be substituted. |
| QR entry and Attendance | Attendance develop exposes program attendance GET lists and preregistration assignment PUT/DELETE. User records entry; current contracts do not serialize entry/attendance against owner cancellation. | Supply a cross-service cancellation protocol. Guard acquire must revalidate the current session/entire party and prevent ownership, QR entry and attendance changes until local commit/rollback and permit close. Reject any prior entry/attendance and every uncertain provider response. A read-then-cancel boolean check is insufficient. Attendance outbox DELETE is delivery, not this guard. |
| Retention | No approved numerical retention policy in #45. | Agree answer/person/history access and retention before rollout; this change adds no cleanup policy. |

The guard interface is a required integration boundary, not an implemented provider API. It must
also coordinate overlapping permit acquisitions and delayed/in-flight scans, not merely hold an
Application-local mutex. A lease expiring while the local transaction is open cannot silently
authorize cancellation. Tests use an explicit fake guard and do not establish these remote guarantees.

No #46 administrator lookup files, Client repository, existing public routing, or registration
enablement are changed. Public rollout remains blocked by the contracts above.

## Verification commands

```sh
./gradlew test --tests 'team.startup.application.OwnerApplicationTests' --tests 'team.startup.application.PreregisterRegistrationHttpTests' --tests 'team.startup.application.ProgramSessionApplicationTests' --no-daemon
./gradlew build ktlintCheck spotlessCheck --no-daemon
git diff --check
```

The full build requires `SPRING_PROFILES_ACTIVE=local` and PostgreSQL/Redis, as in CI.
The default context-load test does not provision Testcontainers or a datasource itself.

Final verification: full build with the local profile and isolated PostgreSQL/Redis passed
in 2m 15s: 226 tests passed, 0 failures/errors/skips, including 16 owner tests. ktlintCheck
and spotlessCheck passed. An unrelated malformed historical answer reproduced a cancellation
failure before the targeted metadata query fix and passed afterward. Waiting cancellation uses
a WAITING application created by the real application service after the confirmed seat fills.
Owner tests also verify invalid/overflow IDs, a wrong application type and another authenticated
party's attempted access.
Provider tests use local HTTP fakes and an explicit fake cancellation guard; no deployed
User/Expo/Attendance integration or real QR scan race is claimed to be verified.

## Changed files

Paths below the feature root `src/main/kotlin/team/startup/application/domain/application/`:

- `presentation/dto/OwnerApplicationDto.kt`
- `repository/OwnerApplicationRepository.kt`
- `repository/PreregisterSessionRepository.kt`
- `service/GetOwnerApplicationsService.kt`
- `service/CancelOwnerApplicationService.kt`
- `service/OwnerCancellationGuard.kt`
- `service/impl/GetOwnerApplicationsServiceImpl.kt`
- `service/impl/CancelOwnerApplicationServiceImpl.kt`
- `service/impl/RegistrationGateway.kt`
- `service/impl/PreregisterRegistrationWorkflow.kt`

Other files:

- `src/main/resources/db/migration/V11__link_preregister_application_snapshot.sql`
- `src/test/kotlin/team/startup/application/OwnerApplicationTests.kt`
- `src/test/kotlin/team/startup/application/PreregisterRegistrationHttpTests.kt`
- `docs/issue-45-owner-applications.md`
