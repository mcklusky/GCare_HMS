# 16 — Requirements Traceability

Maps each PRD requirement ([01-PRD.md §6.1](01-PRD.md#61-requirement-register-fr-001--fr-046)) to pilot scope, the baseline plan task ([06-PLAN.md](06-PLAN.md)), the detailed task with acceptance criteria ([detail/06-TASKS-DETAIL.md](detail/06-TASKS-DETAIL.md)), and the test focus ([09-TEST-STRATEGY.md](09-TEST-STRATEGY.md)).

**Status:** Initial engineering mapping, 2026-11-23. Scope follows ADR-005 (pilot = registration, outpatient, billing, one radiology pathway). Update the *Tests* and *Evidence* columns as tickets are created and tests actually run — leave them blank until then. Never mark evidence that has not been produced.

Scope key: **Pilot** = in pilot MVP; **Pilot (partial)** = only the stated slice is in pilot; **Later** = separately approved module; **Gated** = needs its own legal/safety/business approval before any build; **Workstream** = delivered by a non-code plan document.

Detailed task numbers use the draft's own numbering (its Phase 0–1 both map to baseline Phase 1; see [README](README.md#detail-reconciliation-notes)).

| FR | Requirement | Scope | Baseline task | Detailed task | Test focus | Tests | Evidence |
|---|---|---|---|---|---|---|---|
| FR-001 | Facility and department configuration | Pilot | 1.2 | 1.3 | Scope hierarchy, tenant/facility isolation | `OrganizationIsolationTests`, `LocalSeedTests`, `AccessControlTests` (facility-scoped reads; no create/update API yet) | Local `mvnw verify` 2026-11-23: passed (66 tests) |
| FR-002 | User and access administration | Pilot | 1.3 | 1.4, 1.5 | Deny-by-default, role/scope denial, privileged-action audit | `SecurityBaselineTests`, `AccessControlTests`, `KeycloakSignInTests`, `SessionLifetimeTests`, `IdentitySchemaTests`, `AdminBootstrapTests` (sign-in, deactivation, role/facility denial, sign-out; no user-admin API yet); frontend `App.test.tsx`, `client.test.ts` (sign-in/out UI, session end, permission-filtered navigation, CSRF header) | Local `mvnw verify` 2026-11-23: passed (66 tests); local `vitest run` 2026-11-23: passed (24 tests); CI run on PR #1 passed |
| FR-003 | Patient search and identity | Pilot | 2.1, 2.3 | 2.3, 2.5 | Scoped search, matches shown before create, no auto-merge | `PatientApiTests` (search rules, 409 with candidates before create), `DuplicateScoringScenarioTests` (policy scenarios 1–7), `PatientCorrectionAndReviewTests` (review queue, two-person decision); `PatientLinkTests` (link not merge, two-person approval also as a DB rule, chains blocked, verified-ID override, unlink with history, isolation); frontend `patients.test.tsx` (search, candidate review, link request/approve/reverse UI) | Local `mvnw verify` 2026-11-23: passed (118 tests); local `vitest run`: passed (39 tests). Rules PROVISIONAL (docs/17) |
| FR-004 | Patient registration | Pilot | 2.2 | 2.2, 2.4 | Unknown/incomplete details allowed, provenance | `PatientApiTests` (registration API, unknown patient, identifier provenance, facility scope, audit), `PatientCorrectionAndReviewTests` (corrections with history, stale-edit conflict); frontend `patients.test.tsx` (registration and correction UI, accessibility) | Local `mvnw verify` 2026-11-23: passed (109 tests); local `vitest run`: passed (34 tests). Rules PROVISIONAL (docs/17) |
| FR-005 | Patient identifier | Pilot | 2.1 | 2.1 | Uniqueness, check digit/collision protection, stability | `PatientNumberAndNormalizerTests` (Luhn, typo detection), `PatientApiTests` (sequential numbers), `PatientSchemaTests` (format, isolation), `PatientLinkTests` (retired number still resolves, never reused) | Local `mvnw verify` 2026-11-23: passed (118 tests). Rules PROVISIONAL (docs/17) |
| FR-006 | Encounter management | Pilot | 3.1 | 3.1, 3.2 | Lifecycle transitions, 422 on invalid transition | `EncounterTests` (lifecycle with history, invalid transitions 422, stale version 409, one open visit per patient/facility, permissions and facility scope, linked record refused, isolation, no deletes); frontend `encounters.test.tsx` (queue, call in, transfer, cancel, start visit, accessibility) | Local `mvnw verify` 2026-11-23: passed (127 tests); local `vitest run`: passed (46 tests). Workflow PROVISIONAL (docs/18) |
| FR-007 | Triage and observations | Pilot | 3.1 | 3.3, 3.4 | Units, timestamps, attribution, escalation flags | `EncounterTests` (fixed units, author and time, no urgency or flags without configuration, data-entry limits, entered-in-error keeps values, DB refuses value updates, audit without values), `ConfiguredTriageTests` (configured scale orders queue, limits raise flags; test-only values); frontend `encounters.test.tsx` (triage form, no-scale notice) | Local `mvnw verify` 2026-11-23: passed (127 tests); local `vitest run`: passed (46 tests). Urgency scale and alarm limits NOT CONFIGURED: need a clinical lead (docs/18 §4) |
| FR-008 | Clinical record | Pilot | 3.2, 3.4 | 3.5, 3.6 | Draft/sign/amend, signed immutability | `ClinicalNoteTests` (private drafts, stale save 409, sign, DB refuses changes to signed notes, empty note not signed, discard, amendment with reason after close, permissions/scope, free-text diagnoses without codes, entered in error, DB refuses diagnosis edits, audit without content, isolation); `ChartTests` (chart sections by permission and facility scope, entered-in-error excluded, workspace, patient orders); frontend `clinical.test.tsx` (write and sign, amend, read-only view, add diagnosis, accessibility), `chart.test.tsx` (chart by role, reported-allergy caveat, workspace, accessibility) | Local `mvnw verify` 2026-11-23: passed (141 tests); local `vitest run`: passed (61 tests). Note sections PROVISIONAL; diagnosis coding open (OD-09) |
| FR-009 | Orders and referrals | Pilot | 3.3 | 3.7 | Status, priority, requester, patient linkage | `OrderTests` (admin-managed catalog, duplicate code 409, snapshot kept after rename/deactivate, DB refuses snapshot edits, lifecycle with history, stale version 409, cancel needs reason, urgent first, facility of item, permissions/scope, closed visit refused, audit without indication, isolation); frontend `orders.test.tsx` (place order, empty catalog notice, worklist start/cancel, catalog admin, accessibility) | Local `mvnw verify` 2026-11-23: passed (138 tests); local `vitest run`: passed (57 tests). Catalog EMPTY by design; contents need clinical/department leads |
| FR-010 | Imaging worklist | Pilot (one pathway) | 6.3 | 6.3 | Unlinked study never final | | |
| FR-011 | DICOM receive and link | Pilot (one pathway) | 6.2, 6.3 | 6.2, 6.3 | Correct / ambiguous / mismatched identity, failed transfer | | |
| FR-012 | Image viewing | Pilot (one pathway) | 6.4 | 6.3 | Short-lived scoped launch token, access audit | | |
| FR-013 | Radiology reporting | Pilot (one pathway) | 6.4 | 6.3 | Only authorized signers, version history | | |
| FR-014 | Critical result communication | Pilot (radiology only) | 6.4 | 6.3 | Recipient, acknowledgement, escalation, unresolved state | | |
| FR-015 | Laboratory workflow | Later | Later modules | 6.1 | — | | |
| FR-016 | Medication and pharmacy | Later | Later modules | Phase 7 | — | | |
| FR-017 | Billing and tariff catalog | Pilot | 4.1, 4.2 | 4.1, 4.2 | Effective dating, tariff snapshot, decimal + currency | `TariffTests` (finance-only catalog, two-person approval, DB refuses changes to decided prices, effective-dated price change, ISO 4217 + minor-unit validation, no retroactive approval, tax rates empty until approved, audit without amounts, isolation); `InvoiceTests` (order + manual charges, per-line half-up tax, issued invoice immutable in DB, no price in currency refused, new/cancelled orders block issue, void needs permission and reason, replacement link, facility scope, isolation, audit without amounts); frontend `billing.test.tsx` (money display, decimal-string amounts, approvals, accessibility), `invoices.test.tsx` (start in a currency, server totals, issue, remove with reason, void, accessibility) | Local `mvnw verify` 2026-11-23: passed (154 tests); local `vitest run`: passed (71 tests). Tariff EMPTY by design |
| FR-018 | Payments and receipts | Pilot | 4.3, 4.4 | 4.3, 4.4 | Idempotency, receipt uniqueness, authorized reversal | `PaymentTests` (part payments up to balance, idempotent retry and key reuse refused, gapless receipt numbers, decimals per currency, shift required, inactive method refused, facility scope, paid invoice not voidable, payments not updatable, cash count with difference note, second-person sign-off, isolation, audit without amounts); frontend `payments.test.tsx` (idempotency key + decimal string, provisional receipt, open/close shift, sign-off, accessibility) | Local `mvnw verify` 2026-11-23: passed (158 tests); local `vitest run`: passed (76 tests). Refunds/reversals are 4.4; receipt format PROVISIONAL |
| FR-019 | Program eligibility | Pilot | 4.4 | 4.4 | Evidence, approver, effective dates, audit | | |
| FR-020 | Inventory | Later | Later modules | Phase 7 | — | | |
| FR-021 | Inpatient and bed management | Later | Later modules | Phase 7 | — | | |
| FR-022 | Maternity and newborn record | Later | Later modules | Phase 7 | — | | |
| FR-023 | Notifications | Pilot (partial): in-app task/alert area only; SMS gated | 1.4, 5.1 | 5.1; SMS Phase 8 | No sensitive content in alerts | | |
| FR-024 | Operational dashboards | Pilot | 5.1 | 5.1 | Scope-limited data, refresh status | | |
| FR-025 | Reports and export | Pilot | 5.1 | 5.2 | Report metadata, permissioned + logged exports | | |
| FR-026 | Audit and patient access history | Pilot | 1.2 | 1.6 | Audit on every mutation and sensitive read; redaction | `AuditTrailTests`, `MetadataRedactorTests`, `AccessControlTests` (sign-in/out, refused sign-in, session end, access denied) | Local `mvnw verify` 2026-11-23: passed (66 tests) |
| FR-027 | Downtime and recovery workflow | Pilot | 5.3 | 5.4 | Interrupted/repeated submission, no silent duplicates | | |
| FR-028 | Data import | Workstream | [10-DATA-MIGRATION.md](10-DATA-MIGRATION.md) | — | Reconciliation counts, rollback | | |
| FR-029 | AI decision support | Gated | Later modules | Phase 8 | — | | |
| FR-030 | Configuration and localization | Pilot (partial): English, facility timezone, currencies incl. LRD | 1.4, 5.2 | 5.3 | Timezone display, currency handling | | |
| FR-031 | Country and jurisdiction configuration | Pilot (partial): versioned config; country packs later | 5.2 | 5.3 | Config versioning never rewrites history | | |
| FR-032 | Deployment and tenant isolation | Pilot | 1.2, 1.3 | 1.3 | Cross-tenant access tests on every endpoint | `OrganizationIsolationTests`, `AuditTrailTests`, `IdentitySchemaTests`, `AccessControlTests` (RLS + cross-organization API checks) | Local `mvnw verify` 2026-11-23: passed (66 tests) |
| FR-033 | Standards-based interoperability | Gated (ADR-008) | Later modules | Phase 7 | Contract tests against supplied partner spec only | | |
| FR-034 | Patient access (portal) | Gated | Later modules | Phase 8 | — | | |
| FR-035 | Offline continuity and synchronization | Pilot (partial): downtime procedure + idempotent submission; advanced offline gated | 5.3, 4.3 | 5.4; Phase 8 | Duplicate-submission safety | | |
| FR-036 | Operational monitoring and alerting | Pilot | 1.2, 5.4 | 5.5 | Alerts reach named owners; no patient IDs in alerts | | |
| FR-037 | Accessible user experience | Pilot | 1.4, 5.4 | 1.2, 5.5 | Keyboard, focus, labels; automated + human review | | |
| FR-038 | Biometric-assisted identification | Gated | Later modules | Phase 8 | — | | |
| FR-039 | Hospital workspace provisioning | Later (pilot workspace created by admin bootstrap, 1.3) | 1.3 | 1.3 | — | | |
| FR-040 | Configuration inheritance and versioning | Pilot (partial): facility/department versioning + approval | 5.2 | 5.3 | Approval, effective date, rollback | | |
| FR-041 | Selectable modules and packages | Pilot (partial): module enable/disable per facility | 5.2 | 5.3 | Disabled module endpoints deny access | | |
| FR-042 | Hospital workflow configuration | Pilot (partial): catalogs, tariffs, payer categories, roles | 4.1, 5.2 | 4.1, 5.3 | Validation preserves record meaning | | |
| FR-043 | Customer branding | Pilot | 5.2 | 5.3 | GCare HMS identity retained alongside hospital brand | | |
| FR-044 | Upgrade-safe extensions and integrations | Later | Later modules | — | — | | |
| FR-045 | Customer environment and tenant isolation | Pilot | 1.2, 1.3 | 1.3 | Support access time-limited and logged | | |
| FR-046 | Product release management | Workstream | [13-CHANGE-RELEASE.md](13-CHANGE-RELEASE.md), 1.1 | 0.2 | Migration + rollback rehearsal | | |

## Scope calls made here (confirm or correct)

These mappings interpret the approved pilot scope (ADR-005); they are not new decisions. If any is wrong, correct this table and note it in [REVIEW.md](REVIEW.md).

- FR-023, FR-030, FR-031, FR-035, FR-040, FR-041, FR-042 are split into a pilot slice and a later slice.
- FR-039 (workspace provisioning) is treated as later; the single pilot workspace is created by admin bootstrap.
- FR-014 in the pilot covers radiology critical results only; laboratory critical results arrive with FR-015.
