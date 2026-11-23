# 03 — System Architecture

Version 1.0 | Approved direction: React + Java; modular monolith for initial releases.

> Detailed draft (logical diagram, frontend/backend folder structure, auth, audit, DICOM, deployment, readiness definition): [detail/03-ARCHITECTURE-DETAIL.md](detail/03-ARCHITECTURE-DETAIL.md). This page is the approved baseline and wins on conflict.

## Architecture
Browser → HTTPS reverse proxy → React/TypeScript SPA → versioned REST API → Spring Boot modular monolith → PostgreSQL. Background jobs/outbox process notifications, reports and integrations. Imaging modalities communicate on restricted hospital network with DICOM archive/PACS; application exchanges order/study metadata and launches an approved viewer. Binary DICOM data stays in archive/object storage, not PostgreSQL. External systems connect only through documented adapters and approved network boundaries.

## Technology baseline
Frontend: React, TypeScript, React Router, approved component library, accessible design system. Backend: supported Java LTS, Spring Boot, Spring Security, Spring Data JPA, Bean Validation, Flyway, OpenAPI. Database: supported PostgreSQL. Tests: JUnit; frontend Vitest/RTL; integration tests against PostgreSQL. Exact versions are pinned in repository build files after compatibility review. Docker Compose may support local development; production topology follows site readiness and security review.

## Backend modules and boundaries
identity/access; organization/configuration; patients; encounters/clinical; orders/referrals; diagnostics/radiology; billing; reporting; audit; integrations. Each module owns its service and persistence boundary. Avoid cross-module repository writes; expose application services/domain events. Start as one deployable backend and one relational database. Extract services only for a measured operational or scaling need.

## Security and operations
Use OIDC-compatible identity provider if approved; otherwise Spring Security integration with secure password/session controls. Enforce authorization in service/API layer, tenant and facility scope on every query, CSRF/session policy appropriate to auth mode, rate limits, TLS, secret management, protected logs, audit events, dependency scanning and patch policy. DICOM is isolated to trusted LAN segments and allowlisted devices. Provide health checks, structured redacted logs, metrics/alerts, encrypted backups, restore drills, migration/rollback plan, incident response and downtime procedure. Define RPO/RTO with operator and facility before production.

## Data and integration
PostgreSQL stores transactional records, not images. Use UUID internal IDs, timezone-aware timestamps, decimal money plus ISO currency code, effective-dated configuration and immutable audit history. API adapters use versioned contracts; national HMIS/FHIR/other interface profile is not chosen until authority requirements are confirmed. DICOM archive candidate (including Orthanc) requires conformance, security, support, capacity, resilience, restore and diagnostic viewer validation.

## Environments and release
Separate local/dev, synthetic-data demo, staging/UAT and production. Production data never copied to lower environments. CI gates compilation, static checks, migrations, tests, dependency/security checks and artifact build. Release by reviewed migration and rollback plan; feature/module activation requires owner approval. Customer-specific behavior uses configuration, not forks.

## Decisions still gated
Hosting model/data residency; identity provider; tenancy deployment model (shared database with strict tenant keys vs dedicated deployment); archive product and capacity; national integration profile; offline behavior; RPO/RTO and service levels. No production architecture sizing can be inferred from the public hospital website.
