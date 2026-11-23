# 10 — Data Migration and Reconciliation Plan

## Scope and authority
Migration is a separately approved workstream. No source data is imported until ownership, legal authority, purpose, data-sharing terms, retention, access, and technical security are confirmed. Use synthetic samples for mapping development.

## Source inventory
For each source, record system/owner, data categories, record counts, format, identifiers, quality issues, attachments/images, date coverage, extraction method, update window and retention/exit requirements. Obtain a read-only, controlled extract and preserve source checksums and extraction timestamp.

## Mapping and transformation
Create a versioned mapping for patient demographics/identifiers, encounters, diagnoses, orders/results, tariffs/payments where in scope, and provenance. Preserve original values, source system, source identifiers and effective dates. Normalize only with documented rules. Do not infer missing clinical facts. National identifiers remain optional unless approved. Duplicate candidates are reviewed using the approved policy; no automatic destructive merge.

## Reconciliation and rehearsal
Run at least one full test migration in an isolated environment with approved protected data controls. Compare source and target counts, rejected rows, duplicate candidates, date ranges, financial totals where migrated, document references, and sampled clinical records. Registration, clinical, finance and data owners sign off their domains. Record exceptions and remediation.

## Cutover and rollback
Approve freeze window, final delta extraction, backup/snapshot, migration version, reconciliation tolerances, responsible operators, downtime plan, rollback trigger and decision authority. Keep source read-only or available per approved transition plan. Do not switch users until totals and critical samples pass. Preserve original source archive according to retention and legal requirements.

## Completion evidence
Store migration scripts/version, mapping dictionary, checksums, run logs without unnecessary sensitive content, reconciliation report, exception disposition, approvals and rollback outcome. Restrict access and securely delete temporary extracts under approved policy.
