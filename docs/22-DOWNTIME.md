# 22 — Downtime Forms and Reconciliation (decisions and PROVISIONAL defaults)

> **Status:** the decisions below were made by the product owner on 2026-11-23. The facility still approves the downtime procedure itself (docs/12): trigger, decision authority, communication, custody of paper and conditions for restoration. The paper form layout is **provisional** until the facility approves it.

Requirements: FR-027 (downtime and recovery workflow), FR-035 (partial: downtime procedure and duplicate-safe submission). Code: `backend/.../downtime/`, migration `V15__downtime_and_reconciliation.sql`, screens `/downtime`, `/downtime/episodes/{id}`, `/downtime/forms/{id}/print`.

## 1. Decisions (product owner, 2026-11-23)

| Topic | Decision |
|---|---|
| Paper forms | Registration + visit, triage + vitals, orders, payment receipts. Printed in advance with a unique number per facility and form type (`REG-000001`, `TRI-…`, `ORD-…`, `PAY-…`). |
| Entry after the outage | **Queue, then reconcile**: each paper form is keyed into the episode's queue under its number. Nothing becomes a patient, visit, observation, order or payment until it is reconciled. |
| Review | **Second person for high-risk**: registrations (patient matching or new patients), orders and payments need a proposal by one person and approval by another; triage and vitals need one person. A coordinator closes the episode once the queue is empty. |
| Who declares | New provisional **Downtime coordinator** role (`DOWNTIME_MANAGE` + `DOWNTIME_RECORD`); organization administrators also get `DOWNTIME_MANAGE`. |

## 2. How it works

- **Episodes:** the coordinator records start, end (or "still down"), affected areas and reason; usually after recovery, so times may lie in the past. Episodes of a facility cannot overlap. *Down now* → *Ended — reconciling* → *Closed* (with a closing note, only when every keyed form is reconciled or voided).
- **Numbered forms:** the coordinator issues blocks of up to 500 numbers per form type and prints them (one page per form). Numbers are never reused. Triage, order and payment forms carry the patient's **registration form number**, which links them to the patient during the outage.
- **Keying:** clerks, nurses, clinicians, cashiers and the coordinator (`DOWNTIME_RECORD`) type the form exactly as written, with the time on the paper, which must fall inside the episode. Only issued numbers are accepted. Keying the same form again with the same values returns the existing entry (interrupted or double submission); a different keying of a number already keyed is refused (void the wrong one first). Keyed values never change (database trigger).
- **Reconciling** goes through the normal services, so their permissions, duplicate checks, state rules and audit apply unchanged. Everything happens in one transaction: if any step fails, nothing is created and the form keeps waiting.
  - *Registration:* the proposer (needs patient access) sees possible existing records (same scoring as registration) and the patient number written on the form; they choose an existing patient (optionally their open visit) or a new patient after reviewing every candidate (likely duplicates need a reason). The approver (needs `PATIENT_REGISTER` and `ENCOUNTER_OPEN`) creates the patient and/or opens the visit. If new candidates appeared since the proposal, approval is refused.
  - *Triage:* one person with `OBSERVATION_RECORD` records the observations on the reconciled visit (the visit must still be open).
  - *Order:* approved by a person with `ORDER_CREATE`; the written clinician's name stays on the form.
  - *Payment:* the proposer picks the visit's issued invoice (same currency as the paper receipt); the approver (`PAYMENT_RECORD`, open shift) records it in their shift with the form as reference. The form's id is the payment's idempotency key, so a paper payment can be recorded only once.
  - A proposal can be sent back with a note; wrongly keyed forms are voided (by proposal and approval for high-risk forms, by one person for triage). Every step is kept in the form's history.
- **Times:** real records keep the time they were entered in the system. The paper time stays on the keyed form and is shown on the visit ("Entered after downtime from paper forms").
- Audit records form type and number, episode number and counts, never keyed values.

## 3. Provisional roles

| Role | Permissions |
|---|---|
| Downtime coordinator (`demo.downtime`) | `DOWNTIME_MANAGE`, `DOWNTIME_RECORD` |
| Organization administrator | adds `DOWNTIME_MANAGE` |
| Registration clerk and supervisor, triage nurse, clinician, cashier | add `DOWNTIME_RECORD` |

## Not built yet

- Clinical notes and diagnoses on paper (forms beyond the four above); offline entry on devices (OD-15, gated).
- Downtime report for drills (duration, discrepancies, corrective actions) beyond the episode list.

## Open questions

1. Facility approval of the paper form layout and the downtime procedure (docs/12): who may declare, where blank forms are kept, how completed forms are stored and for how long.
2. Should reconciled observations and orders show the paper time as their clinical time (instead of the entry time plus the notice)?
3. Paper payments: is the cash handed to the reconciling cashier's shift the right custody rule, and who signs off the difference?
4. Which second-person roles are acceptable per form type in practice (e.g. may a clerk approve another clerk's patient match)?

## Change log
| Date | Change | By |
|---|---|---|
| 2026-11-23 | Decisions recorded; episodes, numbered forms, keyed queue and two-person reconciliation | Engineering (Claude Code), task 5.3 |
