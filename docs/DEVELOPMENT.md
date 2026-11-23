# Development Guide

Local setup and day-to-day commands. Requirements and rules live in the numbered docs; start with [README.md](README.md).

## Prerequisites
| Tool | Version | Notes |
|---|---|---|
| JDK | 21 (Temurin) | Set `JAVA_HOME`. Maven is provided by the wrapper (`backend/mvnw`). |
| Node.js | 24 (see `frontend/.nvmrc`) | npm ships with it |
| Docker | Docker Desktop or Engine | Local PostgreSQL and backend integration tests (Testcontainers) |

Pinned versions (OD-04): Spring Boot 4.2.0, Java 21, PostgreSQL 18, React 19, Vite 8, TypeScript 7. Upgrades go through a reviewed PR; Dependabot proposes weekly updates.

## First run from a fresh checkout
```sh
cp .env.example .env                 # local-only values; never commit .env
docker compose up -d postgres        # PostgreSQL 18 on 127.0.0.1:5432

cd backend && ./mvnw spring-boot:run # http://localhost:8080/actuator/health
cd frontend && npm ci && npm run dev # http://localhost:5173 (proxies /api and /actuator to :8080)
```
On Windows use `mvnw.cmd` from PowerShell/cmd, or `./mvnw` from Git Bash.

**Port already in use?** Run the backend on another port and point the frontend proxy at it:
`SERVER_PORT=8082 ./mvnw spring-boot:run`, then `GCARE_BACKEND_URL=http://localhost:8082 npm run dev`.

Alternatively, `./mvnw spring-boot:test-run` starts the backend against a throwaway Testcontainers database without Compose.

## Checks (same as CI)
| Area | Command | What it runs |
|---|---|---|
| Backend | `cd backend && ./mvnw verify` | Spotless format check, compile, tests against real PostgreSQL |
| Backend format fix | `./mvnw spotless:apply` | google-java-format |
| Frontend | `npm run typecheck && npm run lint && npm test && npm run build` | tsc, oxlint, Vitest + Testing Library, Vite build |
| Frontend deps | `npm audit --audit-level=high` | Known-vulnerability check |

Never report a check as passing unless it ran ([07-CLAUDE.md](07-CLAUDE.md)).

## Architecture map
- `backend/src/main/java/com/gcare/hms/<module>/`: one package per module (identity, organization, patient, encounter, orders, radiology, billing, reporting, audit, integration). Each `package-info.java` states the module's purpose. Modules talk through application services or events, never each other's repositories.
- `backend/.../shared/`: cross-cutting infrastructure only (security config, errors, correlation IDs).
- `backend/src/main/resources/db/migration/`: Flyway migrations, the only owner of the schema.
- `frontend/src/`: React app. Routing, the component library and the app shell arrive in task 1.4.

## Migration workflow
1. Add `V<n>__<description>.sql` under `db/migration`. Never edit a merged migration.
2. Run `./mvnw verify`. Tests start a fresh PostgreSQL and apply every migration, and Hibernate validates the entities against the schema.
3. In the PR, describe the data impact and the rollback/forward-fix plan ([13-CHANGE-RELEASE.md](13-CHANGE-RELEASE.md)).

## Data and secrets policy
- **Synthetic data only** in development, demo, test and CI. Use obviously fictional values (names like "Test Patient", phone `0000000000`, IDs from test generators). Never copy, paste, screenshot or "anonymize by hand" real patient records into the repo, issues, PRs or chat.
- No production extracts in lower environments ([03-ARCHITECTURE.md](03-ARCHITECTURE.md)).
- Secrets come from environment variables or a secret store, never from committed files. `.env` is git-ignored; `.env.example` holds placeholders and throwaway local values only.
- If real data or a secret is committed by mistake: stop, tell the security owner, and rotate or purge. Deleting it in a later commit is not enough.

## Backups
Local Compose data (`gcare-pgdata` volume) is disposable. Backup and restore for shared environments are owned by the operator named in [project-record/deployment-record.md](project-record/deployment-record.md) and tested before pilot ([06-PLAN.md](06-PLAN.md) Phase 5).
