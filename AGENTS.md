# Expo Application Server

Single-module Application service built with Kotlin 2.3.21, Spring Boot 4.1.1, Java 21, and Gradle. PostgreSQL with Flyway stores program applications. Local development also starts Redis.

## Where to work

- Kotlin source lives in `src/main/kotlin/team/startup/application`. The feature is `domain/application`; shared internal-token security is in `global/security`.
- Within the feature, use `presentation` for HTTP controllers and request/response DTOs, `service` for one public API operation per service interface, `service/impl` for its single-purpose `*ServiceImpl`, `repository` for JPA, and `entity` for persisted models. Controllers inject the operation interfaces and delegate to them.
- Every API operation has one service interface declaring only `execute(...)` and one matching `*ServiceImpl` overriding it. Controllers delegate each API operation to its interface. `RegistrationWorkflow` is shared registration logic behind the four public registration services; `RegistrationGateway` handles outgoing calls.
- Use constructor injection. Put database transaction boundaries on the public operation methods in the implementations: `@Transactional(readOnly = true)` for reads and `@Transactional` for writes. Registration makes remote calls and has no local database transaction.
- Use explicit `@field:` targets for Jakarta validation on request DTO properties and `@field:Valid` for nested DTOs. Validate request bodies with `@Valid` in controllers. Keep path IDs as scalar parameters.

## Contracts to preserve

- Internal program application APIs live under `/internal/standard-program-applications`, `/internal/training-program-applications`, and `/internal/program-applications`. Keep their paths, application ID fields, status codes, and list order. Standard and training application lists contain no attendance fields; Attendance owns program attendance times. The standard and training application controllers own request validation and HTTP response statuses.
- Standard and training apply/delete and training replace use PostgreSQL transaction-scoped advisory locks. Preserve trainee-before-sorted-program locking, deleted-program markers, duplicate/capacity checks, atomic writes, retry behavior, and concurrent delete behavior. `ProgramApplicationConflictException` returns 409.
- `/internal/**` requires `X-Internal-Token` matching `APPLICATION_INTERNAL_TOKEN`; the value must be configured at startup. `InternalSecurityConfig` permits health and GET Prometheus without that token. GET `/application/expos/{expoId}/preregister-sessions/{sessionId}/capacity` is public independently of registration POST enablement and uses the required ledger query service. The four public `/application/**` registration POST routes are available only when `APPLICATION_REGISTRATION_ENABLED=true`.
- 관리자 그룹 발송 공개 프록시 계약과 대표자 번호, 대상 fingerprint 규칙은 `docs/admin-group-messaging.md`에 있다. 이 경로는 전달받은 Bearer JWT를 Expo 관리자 권한 공급자에 매번 검증한 뒤 User 대표 참가자와 Notification 내부 API를 호출한다.
- 관리자 취소는 Expo의 별도 `APPLICATION_CANCEL` 권한과 Attendance 관리자 acquire를 검증하고 프로그램 취소 뒤 abort한다. 미전달 abort는 기존 durable 복구 큐에 남긴다. Expo/Notification 이력은 원천을 대리조회하며 중복 저장하지 않는다.
- Registration calls Expo, Form, and User through `RegistrationGateway` using the configured `APPLICATION_REGISTRATION_EXPO_URL`, `APPLICATION_REGISTRATION_FORM_URL`, and `APPLICATION_REGISTRATION_USER_URL`. Preserve the optional `Idempotency-Key`, pre/field form rules, form-question snapshot, response/error mapping, and standard-participant count update. `RegistrationHttpTests` covers the integration contract.
- Outgoing internal calls use only per-target tokens: `APPLICATION_REGISTRATION_{EXPO,FORM,USER}_INTERNAL_TOKEN` (also used by admin, cancellation and capacity gateways) and `APPLICATION_ADMIN_GROUP_MESSAGE_{EXPO,USER,NOTIFICATION}_INTERNAL_TOKEN`. Never fall back to `APPLICATION_INTERNAL_TOKEN`. Startup fails when registration is enabled without its Expo/Form/User tokens, or when an admin group messaging URL is set without its token; disabled features still start. `OutboundInternalTokenConfigTests` covers this.
- General preregistration requires `sessionId` and enabled Attendance delivery. Accept the whole party (maximum five) into confirmed seats or reject before User saving. Commit the reservation and original User/form snapshot first; use its fixed User requestId on every recovery. Timeouts, malformed success responses and uncertain failures retain seats. Confirmed applications and Attendance/count outboxes commit together. Field and trainee registration retain their existing flow.
- Attendance uses `APPLICATION_ATTENDANCE_URL` and `APPLICATION_ATTENDANCE_INTERNAL_TOKEN` matching Attendance `INTERNAL_TOKEN`. Durable PUT/DELETE delivery preserves per-participant order; remote delayed requests cannot be fenced by the current provider contract. Never fabricate a successful response when participant IDs do not match the reserved party.
- Do not modify V5 or V5_1. V6 adds Attendance outbox; V7 removes live waiting/promotion and adds reservations/count retries. V7 refuses deployment with live WAITING rows; an operator must resolve them explicitly.
- V8 indexes normalized reservation phone numbers. Capacity readers use a shared Expo lock; submissions use the exclusive lock and recheck capacity. Outbox HTTP uses a separate session lock per participant, without an open transaction or Expo lock. Purge queues cancellations and an in-flight PUT is followed by DELETE when the tombstone is observed.
- A saved User ID mismatch or active-participant conflict retains reserved seats with `error_status` and infinite `next_attempt_at`. Internal GET `/internal/expos/{expoId}/preregister-reservations/{requestId}` exposes recovery status without form/phone data. After fixing the authoritative User result, POST the same path plus `/retry` to reuse the original User requestId and snapshot; never fabricate IDs or release ambiguous seats.

## Run and check

- Copy `.env.example` to `.env`, then run `docker compose up -d --wait` for PostgreSQL 17 and Redis 7. Set `SPRING_PROFILES_ACTIVE=local` for local startup; `application-local.yaml` imports `.env` and needs `POSTGRES_PASSWORD`. Set a nonempty `APPLICATION_INTERNAL_TOKEN`.
- Run one test class with `./gradlew test --tests 'team.startup.application.ClassName'`, or all tests with `./gradlew test`. HTTP and persistence tests use Testcontainers PostgreSQL and need Docker.
- When changing an endpoint, authorization rule, or persistence behavior, update the relevant existing HTTP/contract or persistence test. Before a code PR, run the CI command `./gradlew build ktlintCheck spotlessCheck --no-daemon`. For documentation changes, verify referenced paths and commands and run `git diff --check`.
- Put schema changes in versioned `src/main/resources/db/migration` scripts. JPA uses `ddl-auto: validate`.

## Git and PRs

- Check the worktree and current base/head before work or PR creation. Identify actual predecessor PRs and service-contract dependencies; record the intended merge order and deployment prerequisites in the PR body when relevant.
- After a predecessor merges, update remaining branches from the target branch and recheck API compatibility and CI. Preserve other workers' changes. Obtain user approval before committing, pushing, creating a PR, or merging. For requested commits, follow the existing `type(scope): Korean description` style.

Task-specific workflows live in `.agents/skills/` and `.claude/skills/`. Verify skill examples against current source.

## Preregistration integration

- Base `3cda5b3` already contains #39 and #41. Attendance commit `b580f04` from #40 was reviewed and adapted locally; no additional merge/commit is required to copy it.
- Capacity reuses confirmed applications plus RESERVED seats, observes Expo outside the DB transaction and reads under the same advisory lock without persisting definitions. Responses contain `sessionId`, `remaining`, `maxApplicants` and use `Cache-Control: no-store`.
- Latest #34 decisions (2026-10-09) supersede waiting and all promotion policies. Retain cancelled history and definition-change blocks, including unresolved reservations.
- User's existing-party response may contain only newly texted companions, rather than the submitted party. Hold mismatched reservations for contract/operator reconciliation; never invent missing IDs.
- Verify Client submission, Config/Gateway anonymous routing and actual Expo/User/Attendance integration before public rollout. User QR events can precede Attendance delivery; validate this gap against real entry scans.
- As of 2026-10-10, upstream develop is `afdbe58` (two deployment-only commits after `3cda5b3`); deploy now runs only on main. Client #334/#340 and Attendance #28 remain open. Gateway Config anonymous paths still need `/application` registration/capacity routing. Keep registration disabled until server tokens/URLs and these rollout checks pass.
