# 08 — First Sprint Checklist

Goal: establish a runnable, reviewable foundation for the approved Java + React product. Sprint length and staffing are team decisions; do not infer estimates here.

## Before implementation
- [ ] Create repository and protect the main branch; require reviewed changes.
- [ ] Confirm Java LTS/Spring Boot and Node/React versions; pin versions and document upgrade policy.
- [ ] Record approved deployment topology, identity provider, tenant isolation model, timezone, RPO/RTO and support owner from the approved pilot artifacts.
- [ ] Create synthetic-only development data policy and secret-handling checklist.

## Backend foundation
- [ ] Create Spring Boot application with module/package boundaries and health endpoint.
- [ ] Configure PostgreSQL for local development and Flyway migrations.
- [ ] Add tenant, facility and department seed configuration using synthetic values.
- [ ] Add authentication integration using the approved identity setup; deny access by default.
- [ ] Implement authorization context and scoped repository/service pattern before patient APIs.
- [ ] Add audit event interface and persistence for security-relevant actions; redact sensitive fields.
- [ ] Add consistent validation/error response and correlation IDs.
- [ ] Add OpenAPI generation and `/api/v1` conventions.

## Frontend foundation
- [ ] Create React + TypeScript application with approved router and component library.
- [ ] Implement application shell, sign-in/session handling, facility context and role-filtered navigation.
- [ ] Add shared loading, empty, error, forbidden and confirmation patterns.
- [ ] Establish accessible form, table, dialog and focus patterns; verify keyboard use.
- [ ] Do not persist clinical content or tokens in local storage unless the approved auth design explicitly requires a reviewed mechanism.

## Quality and operations
- [ ] CI runs formatting/static analysis, compile/build, unit tests, migration checks and dependency scan.
- [ ] Add automated tests for unauthenticated access, role denial, tenant/facility isolation, audit creation and error shape.
- [ ] Add `.env.example` with placeholders only; verify secrets and real patient data are excluded from Git.
- [ ] Document local setup, test commands, architecture map, migration workflow and backup responsibilities.
- [ ] Demonstrate a clean setup from a fresh checkout using synthetic data.

## Sprint exit review
- [ ] Backend and frontend start locally with documented commands.
- [ ] CI passes and results are attached to the change review.
- [ ] Security owner reviews authorization/audit foundation before patient data features begin.
- [ ] Product owner accepts the application shell and foundation scope.
- [ ] Create Phase 2 patient registration tickets from approved field and duplicate-resolution specifications.

Do not connect production systems, real scanners or real patient data during this foundation sprint unless explicitly covered by the approved environment and change controls.
