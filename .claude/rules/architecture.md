---
paths:
  - "src/main/kotlin/team/startup/application/**"
---

# Architecture

Single-module Application service built with Kotlin 2.3.21, Spring Boot 4.1.1, Java 21, and Gradle. PostgreSQL with Flyway stores program applications. Local development also starts Redis.

- Kotlin source lives in `src/main/kotlin/team/startup/application`. The feature is `domain/application`; shared internal-token security is in `global/security`.
- Within the feature, use `presentation` for HTTP controllers and request/response DTOs, `service` for one public API operation per service interface, `service/impl` for its single-purpose `*ServiceImpl`, `repository` for JPA, and `entity` for persisted models. Controllers inject the operation interfaces and delegate to them.
- Every API operation has one service interface declaring only `execute(...)` and one matching `*ServiceImpl` overriding it. Controllers delegate each API operation to its interface. `RegistrationWorkflow` is shared registration logic behind the four public registration services; `RegistrationGateway` handles outgoing calls.
