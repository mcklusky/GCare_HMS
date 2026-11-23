# 28 — Laboratory: Discovery and Proposed Scope

> **Status:** scope **proposal** for approval, 2026-11-23. The laboratory is a "later module" (06-PLAN): it needs discovery, a risk review, an approved scope and a gate before any build. **No code has been written.** The four direction decisions below were made by the product owner; everything else is a proposal for the laboratory lead. No test list, unit, reference range or critical limit is invented here: the laboratory enters and approves them.

Requirement: FR-015 (laboratory workflow), plus FR-014 (critical results) extended from radiology to the laboratory.

## 1. Direction decisions (product owner, 2026-11-23)

| Topic | Decision |
|---|---|
| Results in | **Manual entry first.** Lab staff type results into the HMS. Analyzer interfaces (HL7/ASTM) come later, after the instruments are inventoried, as for the scanners in 6.2. |
| Specimen identity | **The HMS issues the specimen number and prints a barcode label** at collection. Collection, receipt in the lab and rejection are recorded. |
| Release | **A second person validates.** A technician enters results; a different, authorised laboratory scientist validates and releases them. Corrections after release need a reason and keep the earlier result visible. |
| Ranges and critical limits | **The laboratory lead enters them, with two-person approval**, as an approved setting (like the triage scale). They are empty by default. Critical results use the same closed loop as radiology: documented direct contact and clinician acknowledgement. |

## 2. Proposed workflow

1. **Order:** a clinician orders a test from the catalog (category `LAB`, which already exists) on the visit, with priority and indication. This is the existing order flow; nothing new.
2. **Collect:** at collection, staff confirm the patient's identity, and the HMS issues a specimen number and prints a label (patient number, name, specimen number as a barcode, specimen type, time). One order can need several specimens; one specimen can serve several tests of the same type.
3. **Receive:** the lab scans the label on arrival. The lab may reject a specimen with a reason (for example haemolysed or unlabelled), which sends a recollection request back to the ward. The order shows "rejected – recollect".
4. **Enter:** a technician enters results per test component (value, unit, flag). The approved reference range is snapshotted with the result, so a later range change never alters it. Results outside the range are flagged, and results outside a critical limit are marked critical.
5. **Validate and release:** a different laboratory scientist reviews and releases. Clinicians see only released results. Unreleased results are visible only in the lab.
6. **Critical result:** before release, the validator records the direct contact (who, how, when). The clinician acknowledges in the HMS, and open items appear on the existing "Critical results" list.
7. **Correct:** a released result is corrected by a new version with a reason. The earlier one stays visible as "corrected". A correction of a critical result is communicated and acknowledged again, using the radiology rule from docs/26.

The order moves through the existing order statuses (requested, in progress, completed). Lab-specific steps (collected, received, entered, released) are recorded alongside, as imaging does with arrived and examined.

## 3. What is reused

| Need | Reused from |
|---|---|
| Orders, catalog, priority, cancellation | `orders` module (category `LAB` exists) |
| Number issuing and step recording beside the order | Imaging accession numbers and steps (V17) |
| Two-person release, correction with history | Radiology sign/amend (V18), clinical notes |
| Critical results with contact, acknowledgement, replacement on correction | `critical_results` (V18, V20); extend to laboratory |
| Approved ranges and critical limits | Versioned settings with second approver (docs/21) |
| Results on the visit and chart, "not yet released" | Imaging results section (6.4) |
| Downtime paper forms | Downtime module (docs/22); a lab result form would be added later |
| Facility time, audit, RLS, isolation tests | Platform conventions |

**New:** specimens (number, type, collection and receipt, rejection), result components with units and flags, a lab worklist, label printing, and the laboratory roles.

## 4. Proposed roles (provisional)

| Role | Can |
|---|---|
| Phlebotomist / collector (or nurse) | Collect, print labels |
| Laboratory technician | Receive and reject specimens, enter results |
| Laboratory scientist | Everything a technician can do, plus validate and release (never their own entry), correct released results, record critical contacts |
| Laboratory lead | Propose and approve ranges and critical limits (a second lead approves) |
| Clinician / nurse | See released results; clinicians acknowledge critical results |

## 5. Proposed tasks (each a branch and PR, once the scope is approved)

| Task | Content | Acceptance (summary) |
|---|---|---|
| L1 | Lab catalog details: specimen type, components, units, decimal places; approved ranges and critical limits as settings | Nothing usable until approved by two leads; history kept |
| L2 | Specimens: numbers, labels (printable page), collection, receipt, rejection and recollection | Unique numbers; label scan finds the specimen; rejection needs a reason |
| L3 | Lab worklist, result entry, validation and release by a second person, corrections | Enterer ≠ releaser (DB); released results immutable; range snapshotted |
| L4 | Results on the visit and chart, critical-result closed loop for the lab | Unreleased never visible to clinicians; critical needs contact and acknowledgement |
| L5 | Downtime lab form, reports (turnaround times), UAT and drills | Paper results reconcile through the normal release |

Analyzer interfaces are a separate, later task, after an instrument inventory (like 6.2).

## 6. Main hazards (for the hazard log)

- **Wrong patient on a specimen:** handled by labels printed at the bedside after identity checks, scanning on receipt, and no hand-written numbers.
- **Wrong unit or decimal:** fixed units and precision per component, approved by the lab lead, and a plausibility check on entry.
- **Unvalidated result acted on:** clinicians see nothing before release.
- **Missed critical result:** closed loop with acknowledgement. Escalation time limits are still for clinical governance (as docs/26).
- **Range change altering old results:** each result keeps a snapshot of its range.

## 7. Questions for the laboratory lead

1. Which tests are in the first release (the catalog), with specimen type, components, units and decimal places?
2. Reference ranges by sex and age, and critical limits: who approves them, and from which source?
3. Which rejection reasons apply, and who requests the recollection?
4. Label printer: which printer and label size? Is there a barcode scanner in the lab?
5. Who may validate (by qualification)? May a scientist validate at night alone, or is a second person not available then?
6. Must the clinician read back critical results? What time limit and escalation apply (with docs/26 Q1)?
7. Turnaround-time targets to report on, per priority?
8. Are any tests sent to an external laboratory? (Not in this scope; they would be recorded as referrals.)

## Not in this scope

Analyzer interfaces, external laboratory referral results, microbiology culture and sensitivity workflows, blood bank, quality control (QC) records, billing changes beyond the existing order-to-invoice flow, and result delivery to patients.

## Change log
| Date | Change | By |
|---|---|---|
| 2026-11-23 | Discovery and proposed scope; four direction decisions recorded | Engineering (Claude Code) |
