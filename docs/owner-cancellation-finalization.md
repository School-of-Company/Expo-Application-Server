# Owner cancellation finalization (Application #45)

Application implements merged Attendance #93 at
`fca726aa7d141a7b96bcfd3aee23261f2cacff02`; #92 was closed by that change.
The supplier source and DTO were checked at that SHA. Application tests use
a loopback reference fixture for the merged contract, not deployed services.
Current Attendance instances without the read-only status route return 503
before Application acquires a fence or changes local state. This is a deployment
blocker until every supplier instance has the merged contract.

## Authorization and eligibility

`POST /application/expos/{expoId}/my-applications/{type}/{applicationId}/cancellation`
requires the existing `PARTICIPANT_SESSION` cookie and CSRF protection. User
resolves the session to a verified phone and checks the selected participant's
ownership. Application checks its own application owner and type. Phone/session
values are absent from the recovery journal and masked in gateway diagnostic
objects. User state is rechecked after acquiring the Attendance fence.

Owner GET returns advisory `cancellable` and blocked reasons using verified User
identity, participant-wide Attendance state, the current Expo deadline and local
application dependencies. It does not acquire a fence. POST independently
rechecks under the fence and local database locks. Missing/unavailable/malformed
providers leave eligibility blocked; cancelled history remains readable.

Preregistration requires a current Expo session's nullable `cancelEndedAt` and
`now < cancelEndedAt`. Missing/null deadlines, equality and past deadlines block
cancellation; session start/end times are never substitutes. STANDARD/TRAINING
use their current program policy's cancellation deadline. Entered or attended
participants are blocked. Confirmed training applications must retain the same
authoritative linked trainee before acquire and at the local transaction,
including a waiting-to-confirmed promotion during acquisition.

## Durable cancellation delivery

V14 adds `tb_owner_cancellation_delivery` after merged predecessor #68/V13.
An ACQUIRING record commits before the acquire HTTP request. A separate
PostgreSQL session advisory lock excludes concurrent attempts/recovery for the
same application without holding a transaction during provider HTTP. Process
death releases that lock. The local transaction retains Expo, participant and
linked-trainee locks, rechecks ownership/deadline and records one cancellation.

For PREREGISTER it checks active programs and representative-last ordering,
then atomically stores CANCELLED history, the Attendance DELETE outbox entry
and COMMIT with `cancellation_revision = 2 * immutable outbox ID + 1`. Attendance
commit runs after that transaction and uses exactly that durable revision.
For STANDARD/TRAINING the existing cancellation service preserves history,
waitlist promotion and training versions; the same local transaction records
ABORT so the participant-global fence is released after commit. Program-only
cancellation never commits the participant-global Attendance fence.

Rollback or abandoned acquisition also records ABORT. Recovery conditionally
updates ACQUIRING and then rereads the decision after any competing transaction
row lock resolves; it cannot abort a cancellation that committed while recovery
waited. A failed remote commit/abort leaves the journal and returns failure.
Repeated owner POST and the scheduler retry the same operation ID/revision,
including already-cancelled applications, without repeating local effects.
Successful delivery removes the journal. Attendance #89's abort tombstone is
required to prevent delayed acquires from recreating an acknowledged old hold.
After a newer ACTIVE assignment supersedes the operation, COMMIT retry can
return 409. Application then sends ABORT for the same operation and removes
the journal only after 204 acknowledges its tombstone. ABORT cannot remove a
different operation and returns 409 for the same COMMITTED operation. Any
ABORT failure retains the COMMIT journal; 409 alone never counts as success.

`APPLICATION_OWNER_CANCELLATION_RECOVERY_DELAY_MS` defaults to 60000; each pass
handles up to 100 due records, and failed delivery is deferred one minute.
Recovery remains active independently of registration POST enablement.

## Merged Attendance #93 contract

Read-only `POST /internal/expos/{expoId}/participants/{participantId}/cancellation-status`
accepts `{ "phoneNumber": "..." }` and returns `participantId`,
`representative`, `entered`, `programAttended`, `traineeLinked`,
`linkedTraineeId` and nullable/omitted `guardState` (`HELD`, `COMMITTED`).
Application normalizes absent/null guard state to internal `NONE`.
It verifies User ownership/linkage without a durable write. Application validates
identity, null/positive trainee rules and every required field, and compares
representative, entry and trainee linkage with verified User state.
Missing route/5xx maps to 503, and invalid responses
block cancellation.

Existing guard acquire/abort routes keep their operation IDs. Preregistration
`POST .../cancellation-guard/{operationId}/commit` receives positive
`{ "revision": ..., "sessionId": ... }`, matching the local cancellation DELETE.
`sessionId` is the cancelled session (stored as `session_id` with the delivery)
so the participant's other ACTIVE sessions stay admissible; it is omitted only for
whole-participant cancellation and deliveries recorded before V35. Application
outbox PUT uses `2 * outbox ID`; DELETE and purge compensation use
`2 * outbox ID + 1`. Retries reuse the immutable revision.

The supplier serializes assignment PUT/DELETE/guard commit under its same
participant lock, retains the fence when accepting a newer ACTIVE assignment
during HELD, and compares that revision when a late COMMIT arrives. Newer ACTIVE
wins and records the superseded operation as aborted. An older PUT cannot
reactivate the cancelled registration or release its committed fence. A newer
PUT releases COMMITTED only when its revision exceeds `cancel_revision`.
Repeated COMMIT succeeds while that operation remains COMMITTED; after release
it returns 409 and requires the reconciliation described above. Legacy
revisionless calls retain supplier compatibility behavior; Application always
sends durable positive revisions. These semantics preserve FIFO delivery
without a second dispatcher gate.

## Merge and deployment prerequisites

The local branch was fast-forwarded to Application develop `58cee9a` (#72),
preserving all dirty files through a retained Git stash. Registration #18/#34
and its Attendance revision delivery are merged prerequisites. The only stash
conflict concerned the revision comment; the upstream comment and revision
formula were retained along with cancellation's returned outbox ID and append
ordering lock. The Attendance gateway and its revision tests now come from #72.

Deploy Expo #80 and its nullable deadline migration before using the deadline.
Complete pending definition changes and avoid mixed Expo versions. Existing
null-deadline sessions remain blocked until configured through approved updates;
deadline-only changes retain Application history protections. User #95/#98
ownership/session/linkage, Attendance #79/#81/#89 guard/scan/tombstone behavior,
and merged Attendance #93 status/revision behavior are prerequisites.
Order User -> Attendance -> Application and verify all supplier instances, not
only merged source. #93 cannot be assumed deployed from its merge.

Set `APPLICATION_ATTENDANCE_ENABLED=true`, Attendance URL/token matching its
receiver, Expo/User URLs and receiver internal tokens. Verify anonymous Gateway
and Client owner routing, CSRF/cookie handling, real QR entry and program scans,
provider failures, recovery and reapplication ordering before public rollout.
Do not enable registration as a substitute for these checks. Deployed-service
manual QA is user-owned and is not a blocker for this implementation PR.

## Verification

Final `SPRING_PROFILES_ACTIVE=local POSTGRES_PASSWORD=expo_local ./gradlew build
ktlintCheck spotlessCheck --no-daemon` passed in 3m 17s on 2026-10-12 after
updating to `58cee9a` (#72) and adapting the actual merged Attendance #93 DTO:
33 test classes, 343 tests, zero failures/errors/skips. Both working-tree and
staged `git diff --check` passed. The new owner HTTP fixture includes the future
session `expiresAt` required by #72. Actual supplier source compatibility is
verified; deployed manual QA remains user-owned.

Baseline exclusive `./gradlew cleanTest build ktlintCheck spotlessCheck --no-daemon`
with the local profile passed in 3m 47s on 2026-10-12: 33 test classes,
331 tests, zero failures/errors/skips. This includes production guard recovery,
the four owner cancellation paths through the real controller/service and
PostgreSQL, nullable/deadline approval checks, read-only eligibility, provider
failures, uncertain commit row-lock recovery, exact cancellation revisions and
old/new assignment ordering before the #93 fixture adaptation.
The #93 adaptation adds representative consistency, actual nullable status DTO,
superseded COMMIT/ABORT acknowledgement and failure retention tests, plus a
real HTTP/PostgreSQL lost-response/newer-reapplication regression. The terminal
owning gateway/GET and the combined PR owns exclusive final Gradle validation;
this worker does not launch a concurrent Gradle run. Deployed cross-service
manual QA remains user-owned.
