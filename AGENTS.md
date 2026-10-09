# Expo Application Server

Single-module Application service built with Kotlin 2.3.21, Spring Boot 4.1.1, Java 21, and Gradle. PostgreSQL with Flyway stores program applications. Local development also starts Redis.

## Where to work

- Kotlin source lives in `src/main/kotlin/team/startup/application`. The feature is `domain/application`; shared internal-token security is in `global/security`.
- Within the feature, use `presentation` for HTTP controllers and request/response DTOs, `service` for one public API operation per service interface, `service/impl` for its single-purpose `*ServiceImpl`, `repository` for JPA, and `entity` for persisted models. Controllers inject the operation interfaces and delegate to them.
- Every API operation has one service interface declaring only `execute(...)` and one matching `*ServiceImpl` overriding it. Controllers delegate each API operation to its interface. `RegistrationWorkflow` is shared registration logic behind the four public registration services; `RegistrationGateway` handles outgoing calls.
- Use constructor injection. Put database transaction boundaries on the public operation methods in the implementations: `@Transactional(readOnly = true)` for reads and `@Transactional` for writes. Registration makes remote calls and has no local database transaction.
- Use explicit `@field:` targets for Jakarta validation on request DTO properties and `@field:Valid` for nested DTOs. Validate request bodies with `@Valid` in controllers. Keep path IDs as scalar parameters.

## Contracts to preserve

- Internal program application APIs live under `/internal/standard-program-applications`, `/internal/training-program-applications`, and `/internal/program-applications`. Keep their existing paths, DTO fields, status codes, list order, and time format. The standard and training application controllers own request validation and HTTP response statuses.
- Standard and training apply/delete and training replace use PostgreSQL transaction-scoped advisory locks. Preserve trainee-before-sorted-program locking, deleted-program markers, duplicate/capacity checks, atomic writes, retry behavior, and concurrent delete behavior. `ProgramApplicationConflictException` returns 409.
- `/internal/**` requires `X-Internal-Token` matching `APPLICATION_INTERNAL_TOKEN`; the value must be configured at startup. `InternalSecurityConfig` permits health and GET Prometheus without that token. GET `/application/expos/{expoId}/preregister-sessions/{sessionId}/capacity` is public independently of registration POST enablement and returns 503 until the ledger query service is ready. The four public `/application/**` registration POST routes are available only when `APPLICATION_REGISTRATION_ENABLED=true`.
- Registration calls Expo, Form, and User through `RegistrationGateway` using the configured `APPLICATION_REGISTRATION_EXPO_URL`, `APPLICATION_REGISTRATION_FORM_URL`, and `APPLICATION_REGISTRATION_USER_URL`. Preserve the optional `Idempotency-Key`, pre/field form rules, form-question snapshot, response/error mapping, and standard-participant count update. `RegistrationHttpTests` covers the integration contract.

## Run and check

- Copy `.env.example` to `.env`, then run `docker compose up -d --wait` for PostgreSQL 17 and Redis 7. Set `SPRING_PROFILES_ACTIVE=local` for local startup; `application-local.yaml` imports `.env` and needs `POSTGRES_PASSWORD`. Set a nonempty `APPLICATION_INTERNAL_TOKEN`.
- Run one test class with `./gradlew test --tests 'team.startup.application.ClassName'`, or all tests with `./gradlew test`. HTTP and persistence tests use Testcontainers PostgreSQL and need Docker.
- When changing an endpoint, authorization rule, or persistence behavior, update the relevant existing HTTP/contract or persistence test. Before a code PR, run the CI command `./gradlew build ktlintCheck spotlessCheck --no-daemon`. For documentation changes, verify referenced paths and commands and run `git diff --check`.
- Put schema changes in versioned `src/main/resources/db/migration` scripts. JPA uses `ddl-auto: validate`.

## Git and PRs

- Check the worktree and current base/head before work or PR creation. Identify actual predecessor PRs and service-contract dependencies; record the intended merge order and deployment prerequisites in the PR body when relevant.
- After a predecessor merges, update remaining branches from the target branch and recheck API compatibility and CI. Preserve other workers' changes. Obtain user approval before committing, pushing, creating a PR, or merging. For requested commits, follow the existing `type(scope): Korean description` style.

Task-specific workflows live in `.agents/skills/` and `.claude/skills/`. Verify skill examples against current source.

## Pending #37 dependency

- Merge order: #34 `feat/preregister-sessions` before #37 `feat/session-capacity-query`. Reuse #34's ledger and capacity calculation; its current definition lookup writes to the DB, so a read-only query boundary is still needed. Do not add fixed counts or a second ledger.
- Once connected, require the query service at startup and test remaining/waiting seats, the five-person cap, and submission rejection after another application takes the last seats. Client #334 and Gateway public GET routing are not yet verified.
- `InternalSecurityConfig.kt` may overlap with #34 changes; preserve its registration and internal-token rules when integrating.
