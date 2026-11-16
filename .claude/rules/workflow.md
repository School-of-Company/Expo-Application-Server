---
paths:
  - "**"
---

# Workflow

## Run and check

- Copy `.env.example` to `.env`, then run `docker compose up -d --wait` for PostgreSQL 17 and Redis 7. Set `SPRING_PROFILES_ACTIVE=local` for local startup; `application-local.yaml` imports `.env` and needs `POSTGRES_PASSWORD`. Set a nonempty `APPLICATION_INTERNAL_TOKEN`.
- Run one test class with `./gradlew test --tests 'team.startup.application.ClassName'`, or all tests with `./gradlew test`. HTTP and persistence tests use Testcontainers PostgreSQL and need Docker.
- When changing an endpoint, authorization rule, or persistence behavior, update the relevant existing HTTP/contract or persistence test. Before a code PR, run the CI command `./gradlew build ktlintCheck spotlessCheck --no-daemon`. For documentation changes, verify referenced paths and commands and run `git diff --check`.

## Git and PRs

- Check the worktree and current base/head before work or PR creation. Identify actual predecessor PRs and service-contract dependencies; record the intended merge order and deployment prerequisites in the PR body when relevant.
- After a predecessor merges, update remaining branches from the target branch and recheck API compatibility and CI. Preserve other workers' changes. Obtain user approval before committing, pushing, creating a PR, or merging. For requested commits, follow the existing `type(scope): Korean description` style.

Task-specific workflows live in `.agents/skills/` and `.claude/skills/`. Verify skill examples against current source.
