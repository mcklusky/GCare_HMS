# 16 — Requirements Traceability

Maps each PRD requirement ([01-PRD.md §6.1](01-PRD.md#61-requirement-register-fr-001--fr-046)) to pilot scope, the baseline plan task ([06-PLAN.md](06-PLAN.md)), the detailed task with acceptance criteria ([detail/06-TASKS-DETAIL.md](detail/06-TASKS-DETAIL.md)), and the test focus ([09-TEST-STRATEGY.md](09-TEST-STRATEGY.md)).

**Status:** Initial engineering mapping, 2026-11-23. Scope follows ADR-005 (pilot = registration, outpatient, billing, one radiology pathway). Update the *Tests* and *Evidence* columns as tickets are created and tests actually run — leave them blank until then. Never mark evidence that has not been produced.

Scope key: **Pilot** = in pilot MVP; **Pilot (partial)** = only the stated slice is in pilot; **Later** = separately approved module; **Gated** = needs its own legal/safety/business approval before any build; **Workstream** = delivered by a non-code plan document.

Detailed task numbers use the draft's own numbering (its Phase 0–1 both map to baseline Phase 1; see [README](README.md#detail-reconciliation-notes)).

| FR | Requirement | Scope | Baseline task | Detailed task | Test focus | Tests | Evidence |
|---|---|---|---|---|---|---|---|
| FR-001 | Facility and department configuration | Pilot | 1.2 | 1.3 | Scope hierarchy, tenant/facility isolation | | |
| FR-002 | User and access administration | Pilot | 1.3 | 1.4, 1.5 | Deny-by-default, role/scope denial, privileged-action audit | | |
| FR-003 | Patient search and identity | Pilot | 2.1, 2.3 | 2.3, 2.5 | Scoped search, matches shown before create, no auto-merge | | |
| FR-004 | Patient registration | Pilot | 2.2 | 2.2, 2.4 | Unknown/incomplete details allowed, provenance | | |
| FR-005 | Patient identifier | Pilot | 2.1 | 2.1 | Uniqueness, check digit/collision protection, stability | | |
| FR-006 | Encounter management | Pilot | 3.1 | 3.1, 3.2 | Lifecycle transitions, 422 on invalid transition | | |
| FR-007 | Triage and observations | Pilot | 3.1 | 3.3, 3.4 | Units, timestamps, attribution, escalation flags | | |
| FR-008 | Clinical record | Pilot | 3.2, 3.4 | 3.5, 3.6 | Draft/sign/amend, signed immutability | | |
| FR-009 | Orders and referrals | Pilot | 3.3 | 3.7 | Status, priority, requester, patient linkage | | |
| FR-010 | Imaging worklist | Pilot (one pathway) | 6.3 | 6.3 | Unlinked study never final | | |
| FR-011 | DICOM receive and link | Pilot (one pathway) | 6.2, 6.3 | 6.2, 6.3 | Correct / ambiguous / mismatched identity, failed transfer | | |
| FR-012 | Image viewing | Pilot (one pathway) | 6.4 | 6.3 | Short-lived scoped launch token, access audit | | |
| FR-013 | Radiology reporting | Pilot (one pathway) | 6.4 | 6.3 | Only authorized signers, version history | | |
| FR-014 | Critical result communication | Pilot (radiology only) | 6.4 | 6.3 | Recipient, acknowledgement, escalation, unresolved state | | |
| FR-015 | Laboratory workflow | Later | Later modules | 6.1 | — | | |
| FR-016 | Medication and pharmacy | Later | Later modules | Phase 7 | — | | |
| FR-017 | Billing and tariff catalog | Pilot | 4.1, 4.2 | 4.1, 4.2 | Effective dating, tariff snapshot, decimal + currency | | |
| FR-018 | Payments and receipts | Pilot | 4.3, 4.4 | 4.3, 4.4 | Idempotency, receipt uniqueness, authorized reversal | | |
| FR-019 | Program eligibility | Pilot | 4.4 | 4.4 | Evidence, approver, effective dates, audit | | |
| FR-020 | Inventory | Later | Later modules | Phase 7 | — | | |
| FR-021 | Inpatient and bed management | Later | Later modules | Phase 7 | — | | |
| FR-022 | Maternity and newborn record | Later | Later modules | Phase 7 | — | | |
| FR-023 | Notifications | Pilot (partial): in-app task/alert area only; SMS gated | 1.4, 5.1 | 5.1; SMS Phase 8 | No sensitive content in alerts | | |
| FR-024 | Operational dashboards | Pilot | 5.1 | 5.1 | Scope-limited data, refresh status | | |
| FR-025 | Reports and export | Pilot | 5.1 | 5.2 | Report metadata, permissioned + logged exports | | |
| FR-026 | Audit and patient access history | Pilot | 1.2 | 1.6 | Audit on every mutation and sensitive read; redaction | | |
| FR-027 | Downtime and recovery workflow | Pilot | 5.3 | 5.4 | Interrupted/repeated submission, no silent duplicates | | |
| FR-028 | Data import | Workstream | [10-DATA-MIGRATION.md](10-DATA-MIGRATION.md) | — | Reconciliation counts, rollback | | |
| FR-029 | AI decision support | Gated | Later modules | Phase 8 | — | | |
| FR-030 | Configuration and localization | Pilot (partial): English, facility timezone, currencies incl. LRD | 1.4, 5.2 | 5.3 | Timezone display, currency handling | | |
| FR-031 | Country and jurisdiction configuration | Pilot (partial): versioned config; country packs later | 5.2 | 5.3 | Config versioning never rewrites history | | |
| FR-032 | Deployment and tenant isolation | Pilot | 1.2, 1.3 | 1.3 | Cross-tenant access tests on every endpoint | | |
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
