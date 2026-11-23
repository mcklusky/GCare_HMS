# 11 — Interoperability and DICOM Integration Plan

## Interface governance
No external interface is assumed. For every partner, approve system owner, purpose, data controller/processor roles, lawful basis, data elements, identifiers, transport, authentication, consent, retention, error ownership and conformance tests. Version all contracts and maintain an interface register.

## National and facility systems
Inventory HMIS/EMR, laboratory, finance, identity and reporting systems. Obtain authoritative specifications and sandbox access. Map internal resources to the agreed partner profile; do not assume a FHIR release or national implementation guide. Define retries, idempotency, acknowledgements, correction handling, downtime queue and reconciliation. Minimize data exchanged and audit access/exports.

## DICOM workflow
Inventory each modality make/model/software, conformance statement, AE title, IP/port, supported C-STORE, Modality Worklist, MPPS, Storage Commitment and DICOMweb, transfer syntaxes, licensing and support. Select archive/viewer only after capacity, security, resilience, support and diagnostic validation.

Test network allowlists and C-ECHO; send representative studies; verify patient/order identifiers, image/series counts, orientation, timestamps and compression; test worklist mapping, duplicate/mismatched studies, resend/retry, archive failure, viewer launch and access expiry. Uncertain matches go to reconciliation. Never auto-link a study on weak identity evidence.

## Acceptance and operation
Radiology and IT approve end-to-end results, diagnostic display suitability, storage growth/retention, backups/restore, alerts, vendor escalation and downtime fallback. Monitor failed/stalled transfers, low storage, archive availability and viewer launch failures. Record owner and response action for each alert. Keep DICOM payload outside the HMS relational database.
