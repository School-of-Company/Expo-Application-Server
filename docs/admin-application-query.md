# #46 internal application query and rollout blockers

Base: develop `439b61c`. This implements the local #43/#44 program-session ledger read only. It is not the complete #46 dashboard and is not an authorized browser admin API.

## Internal contract

`GET /internal/expos/{expoId}/program-session-applications` requires the existing `X-Internal-Token`. Responses use `Cache-Control: no-store`. The internal caller is trusted for service access; this token does not establish an administrator or their event permissions. Do not route this endpoint anonymously through Gateway or expose the token to Client.

Filters are intersected with the path's UUID expoId:

| Parameter | Meaning |
| --- | --- |
| programType | STANDARD or TRAINING |
| programId | One program/session ID; requires programType because IDs overlap between types |
| groupId | Program group UUID |
| applicationId | Exact UUID ledger application ID; another event's ID yields an empty result |
| status | CONFIRMED, WAITING or CANCELLED; omitted includes all retained states |
| search | Trimmed, case-insensitive literal substring of the submitted definition's title only; blank values are rejected; `%` and `_` are literal |
| from / until | ISO Instant on application created_at, inclusive from and exclusive until; from must precede until |
| page / size | Zero-based page, default 0; size 1–100, default 20 |
| sort | CREATED_ASC or CREATED_DESC (default); UUID application ID breaks equal-time ties in the same direction |

Unknown, repeated and empty query parameters fail with 400; invalid enums, IDs and date ranges also fail with 400. No unsupported role/person-name/phone filter is silently accepted. Database errors propagate as errors; no external sources are queried or represented by zero counts.

`items`, `totalElements`, and `statusCounts` use exactly the same predicate and a read-only REPEATABLE_READ transaction. Counts include the status filter itself and do not depend on the requested page. An empty page can have a nonzero total. Counts measure applications, not unique people, preregistration reservations, attendance or completion. Separate requests can observe subsequent changes; offset paging is not an export snapshot.

Each item includes UUID applicationId, participantId, nullable traineeId, programType/programId, groupId, status, createdAt, formId/formVersion, submitted definition/questions/answers snapshots and stored history ordered by created_at/id. History contains recorded transitions only, not invented cancellation reasons or actors. An applicationId filter retrieves detail using the same response shape. Program titles are historical, not current Expo definitions. No registration-form snapshot, personal/representative phone or legacy Long application ID relationship is inferred.

Both item and history createdAt values use UTC Instant serialization, independent of the database session time zone. V10_1 adds full-state event/date/ID paging and application/date/ID history indexes; CANCELLED rows are included in the indexes.

## Confirmed blockers and #50 reuse

Read upstream User [JwtProvider.kt](https://github.com/School-of-Company/Expo-User-Server/blob/633e7738990a9fa914cc4d25d40b81db9b274201/src/main/kotlin/team/startup/expo/global/security/jwt/JwtProvider.kt) and [AccessTokenAuthenticationFilter.kt](https://github.com/School-of-Company/Expo-User-Server/blob/633e7738990a9fa914cc4d25d40b81db9b274201/src/main/kotlin/team/startup/expo/global/security/AccessTokenAuthenticationFilter.kt): RS256 tokens identify Admin.id via sub, but User resolves current authority from its database rather than trusting a role header or token alone. Expo [SecurityConfig.kt](https://github.com/School-of-Company/Expo-Expo-Server/blob/cad63429eea3e969eeab4f60f9938e7e15a23358/src/main/kotlin/team/startup/expo/global/security/SecurityConfig.kt) validates RS256 with JWT_PUBLIC_KEY and requires ROLE_ADMIN. Application currently has only internal-token security and no JWT verifier, live admin-authority lookup or event-level permission contract. An authoritative event permission endpoint, public key/configuration and revoked/changed-admin behavior must be agreed before a public admin API is implemented. Internal reads do not satisfy #46's event authorization acceptance criterion.

User participant name/search/role/phone and registration-answer lookup, Expo current session definitions/capacity and event permissions, and Attendance check-in/attendance sources still need scoped normal/failure contracts. Until those exist, this endpoint does not supply preregistration summary, cross-source operational dashboard or person filters. An empty local ledger does not prove the event has no legacy or preregistered participants. #45 owns cancellation/reapplication mutations and richer processing history; those files are untouched.

User already exposes cursor-paged `/internal/expos/{expoId}/standard-participants/details` and batch `/internal/standard-participants/details` (missing or cross-event IDs fail with 404). Those existing detail sources should be reused when composition is enabled; they do not yet define the shared cross-source name/role filtering and count snapshot needed here. User `/admin/my` resolves the authenticated administrator, while Expo's current entity has no admin ownership field; neither inspected contract establishes per-event access for Application.

#50 can reuse the predicates above after the person/role and authorization contracts are settled. This change creates no Report API, cursor or export target contract. Report must still agree selected-ID missing/cross-event behavior, stable cursor/batch bounds and mutation consistency, legacy Long-to-UUID mapping, per-template included statuses, registration-answer references, and participantId deduplication for person lists. Screen page/size must not become an export limit. Do not interpret legacy attendance boolean status as application status.

## Validation

`AdminApplicationHttpTests` uses real PostgreSQL migrations and HTTP requests to check filters/counts, deterministic paging, event isolation, saved snapshots/history, date bounds, token protection and invalid/unsupported parameters. Run `./gradlew test --tests 'team.startup.application.AdminApplicationHttpTests'` and the CI command `./gradlew build ktlintCheck spotlessCheck --no-daemon`.
