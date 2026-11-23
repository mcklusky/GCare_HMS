# 08 — First Sprint Checklist

Goal: establish a runnable, reviewable foundation for the approved Java + React product. Sprint length and staffing are team decisions; do not infer estimates here.

## Before implementation
- [ ] Create repository and protect the main branch; require reviewed changes. *(Repo created 2026-11-23; branch protection still to be set in GitHub.)*
- [x] Confirm Java LTS/Spring Boot and Node/React versions; pin versions and document upgrade policy. *(ADR-011, [DEVELOPMENT.md](DEVELOPMENT.md))*
- [ ] Record approved deployment topology, identity provider, tenant isolation model, timezone, RPO/RTO and support owner from the approved pilot artifacts. *(Isolation model decided: ADR-010. Identity provider OD-03 and the rest remain open.)*
- [x] Create synthetic-only development data policy and secret-handling checklist. *([DEVELOPMENT.md](DEVELOPMENT.md#data-and-secrets-policy))*

## Backend foundation
- [x] Create Spring Boot application with module/package boundaries and health endpoint.
- [x] Configure PostgreSQL for local development and Flyway migrations. *(No migrations yet; first one in task 1.2.)*
- [x] Add tenant, facility and department seed configuration using synthetic values. *(`db/seed-local`, local profile only.)*
- [ ] Add authentication integration using the approved identity setup; deny access by default. *(Deny-by-default done; authentication blocked on OD-03.)*
- [ ] Implement authorization context and scoped repository/service pattern before patient APIs. *(Organization scoping done: `OrganizationContext` + PostgreSQL RLS, ADR-010. Facility/department scope and wiring it to the logged-in user come with task 1.3.)*
- [x] Add audit event interface and persistence for security-relevant actions; redact sensitive fields. *(Append-only `audit_events`; callers are added per feature.)*
- [x] Add consistent validation/error response and correlation IDs.
- [ ] Add OpenAPI generation and `/api/v1` conventions. *(Deferred to the first real endpoint, task 1.3.)*

## Frontend foundation
- [ ] Create React + TypeScript application with approved router and component library.
- [ ] Implement application shell, sign-in/session handling, facility context and role-filtered navigation.
- [ ] Add shared loading, empty, error, forbidden and confirmation patterns.
- [ ] Establish accessible form, table, dialog and focus patterns; verify keyboard use.
- [ ] Do not persist clinical content or tokens in local storage unless the approved auth design explicitly requires a reviewed mechanism.

## Quality and operations
- [ ] CI runs formatting/static analysis, compile/build, unit tests, migration checks and dependency scan. *(Workflow added; Java dependency scanning is Dependabot only, and CI has not yet run on GitHub.)*
- [ ] Add automated tests for unauthenticated access, role denial, tenant/facility isolation, audit creation and error shape. *(Done: unauthenticated access, organization isolation, audit, error shape. Role denial needs roles, task 1.3.)*
- [x] Add `.env.example` with placeholders only; verify secrets and real patient data are excluded from Git.
- [x] Document local setup, test commands, architecture map, migration workflow and backup responsibilities. *([DEVELOPMENT.md](DEVELOPMENT.md))*
- [ ] Demonstrate a clean setup from a fresh checkout using synthetic data.

## Sprint exit review
- [x] Backend and frontend start locally with documented commands. *(Verified 2026-11-23 against Compose PostgreSQL 18.)*
- [ ] CI passes and results are attached to the change review.
- [ ] Security owner reviews authorization/audit foundation before patient data features begin.
- [ ] Product owner accepts the application shell and foundation scope.
- [ ] Create Phase 2 patient registration tickets from approved field and duplicate-resolution specifications.

Do not connect production systems, real scanners or real patient data during this foundation sprint unless explicitly covered by the approved environment and change controls.
