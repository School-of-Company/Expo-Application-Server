---
paths:
  - "**/*.kt"
---

# Kotlin conventions

- Use constructor injection. Put database transaction boundaries on the public operation methods in the implementations: `@Transactional(readOnly = true)` for reads and `@Transactional` for writes. Registration makes remote calls and has no local database transaction.
- Use explicit `@field:` targets for Jakarta validation on request DTO properties and `@field:Valid` for nested DTOs. Validate request bodies with `@Valid` in controllers. Keep path IDs as scalar parameters.
