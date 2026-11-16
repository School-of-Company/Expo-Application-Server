---
paths:
  - "src/main/kotlin/team/startup/application/**/repository/**"
  - "src/main/kotlin/team/startup/application/**/entity/**"
  - "src/main/resources/db/migration/**"
---

# Database

- Put schema changes in versioned `src/main/resources/db/migration` scripts. JPA uses `ddl-auto: validate`.
