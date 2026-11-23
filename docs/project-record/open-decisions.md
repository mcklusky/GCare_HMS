# Open Decisions

Decisions the documents mark as required before a specific build step. Once decided, record the outcome here and add an ADR row to [REVIEW.md](../REVIEW.md#decision-register).

| ID | Decision | Source | Needed before | Options / notes | Owner | Decision | Date |
|---|---|---|---|---|---|---|---|
| OD-01 | Top-level scope entity name: `tenant` vs `organization` | Baseline 04 vs detail 04 | First Flyway migration | Naming only; pick one and use it in schema, API and code | User (accepted recommendation) | Use `organization` / `organization_id` everywhere (schema, API, code). Baseline `Tenant` wording in 04-DATA-MODEL is read as "organization". See ADR-009. | 2026-11-23 |
| OD-02 | Tenant isolation topology | 03, 04, FR-032, FR-045 | First Flyway migration | Shared DB with tenant keys (optionally row-level security) vs dedicated deployment. FR-032 defaults to isolated deployment until a hosting model is approved | User (accepted recommendation) | Shared database; `organization_id` on every tenant-owned table, enforced by PostgreSQL row-level security plus service-layer scoping. Deploy one hospital per environment until a hosting model is approved (FR-032). See ADR-010. | 2026-11-23 |
| OD-03 | Identity provider and session model | 03, 08 | Task 1.3 | OIDC provider vs Spring Security local accounts; drives CSRF and token handling | User | Keycloak (self-hosted OIDC). Backend-held session, HttpOnly cookie, CSRF on; authorization data in GCare. See ADR-012. Follow-ups for the security owner: production MFA requirement, password/lockout policy, idle and absolute session lengths (code defaults 30 min / 12 h are placeholders) | 2026-11-23 |
| OD-04 | Java LTS / Spring Boot / Node / React versions and upgrade policy | 08 | Task 1.1 | Pin in build files | User (accepted recommendation) | Java 21 LTS (Temurin), Spring Boot 4.2.0, PostgreSQL 18, Node 24 LTS, React 19, Vite 8, TypeScript 7. Upgrades via reviewed PR; Dependabot weekly. See ADR-011. | 2026-11-23 |
| OD-05 | Searchable demographics and normalization | 04 | Phase 2 schema |  |  |  |  |
| OD-06 | Duplicate scoring and manual review rules | 04, ADR-006 | Phase 2 | Must be encoded in acceptance tests |  |  |  |
| OD-07 | Patient merge vs link semantics | 04, 05 | Phase 2 | Includes reversal/unmerge policy |  |  |  |
| OD-08 | Patient identifier format (check digit, issuing scope) | FR-005 | Phase 2 |  |  |  |  |
| OD-09 | Terminology systems (diagnoses, procedures, units) | 04 | Phase 3 |  |  |  |  |
| OD-10 | Clinical note data structure | 04 | Phase 3 | Free text vs structured sections/templates |  |  |  |
| OD-11 | Invoice tax and exemption representation | 04 | Phase 4 |  |  |  |  |
| OD-12 | Retention, deletion and legal hold | 04, 15 | Schema freeze | See [retention-schedule.md](retention-schedule.md) |  |  |  |
| OD-13 | Audit retention and tamper-evidence | 04 | Task 1.2 audit design |  |  |  |  |
| OD-14 | Archive/viewer product and capacity | 03, ADR-004 | Phase 6 | Orthanc is a candidate only |  |  |  |
| OD-15 | Offline behaviour beyond the downtime procedure | 03, FR-035 | Phase 5 | Advanced offline is gated |  |  |  |
