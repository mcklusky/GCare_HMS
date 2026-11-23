# 18 — Encounter Workflow (PROVISIONAL)

> **Status: provisional engineering defaults, not approved.** Written 2026-11-23 for task 3.1 at the product owner's request, while the Phase 1 security review and the Phase 2 registration sign-off were still outstanding. The outpatient workflow owner and a clinical lead must review this before any real patient use. `project-record/workflows.md` is intentionally still empty: it records *approved* workflows only.
>
> **No clinical rules are defined here.** The triage urgency scale and the vital-sign alarm limits are empty configuration (§4) until a clinical lead supplies them.

Requirements: FR-006 (encounter management), FR-007 (triage and observations). Code: `backend/.../encounter/`, migration `V6__encounters_and_triage.sql`, screens `/queue`, `/encounters/{id}`.

## 1. Visit lifecycle

| From | Action | To | Who (permission) | Reason |
|---|---|---|---|---|
| (none) | Open visit | WAITING (in the department queue) | `ENCOUNTER_OPEN` | no |
| WAITING | Call in | IN_CONSULTATION (caller becomes clinician) | `ENCOUNTER_MANAGE` | no |
| WAITING or IN_CONSULTATION | Transfer to another department of the same facility | WAITING (new queue, no clinician) | `ENCOUNTER_MANAGE` | **yes** |
| IN_CONSULTATION | Close | CLOSED | `ENCOUNTER_MANAGE` | no |
| WAITING | Cancel (left before being seen, or opened in error) | CANCELLED | `ENCOUNTER_OPEN` | **yes** |
| CLOSED | Reopen | IN_CONSULTATION (reopener becomes clinician) | `ENCOUNTER_REOPEN` | **yes** |

- Every change is written to the visit's history (`encounter_events`: who, when, from/to, department, reason) and audited. Any other transition answers 422; a stale `version` answers 409.
- A patient has **at most one open visit per facility** (database rule). A retired (LINKED) patient record cannot start a visit.
- Visit types: `NEW_VISIT`, `FOLLOW_UP`. No time limit on reopening is set (not invented); the permission and reason are the control.

## 2. Queue

- The queue lists open visits (WAITING and IN_CONSULTATION) at the user's current facility, optionally by department, and refreshes every 30 seconds.
- Order: visits with a **configured** urgency first (lower rank first), then by arrival time. With no scale configured (the default), the order is arrival time only.
- Front-desk users see who is waiting and the status; triage details (triaged, urgency, flags) are shown only to users with `OBSERVATION_READ`.

## 3. Triage observations

- Measures and fixed units: temperature (°C), pulse (/min), respiratory rate (/min), blood pressure systolic/diastolic (mmHg), SpO₂ (%), weight (kg), height (cm), pain score (0–10); plus allergies reported, triage notes, and urgency (only once a scale is configured).
- **Data-entry limits** reject impossible values to catch typing errors (for example a temperature of 372). They are wide on purpose and are **not** clinical alarm limits.
- Each set records the author and time and is never changed. A wrong set is marked **entered in error** with a reason; its values stay visible, crossed out. The database allows updates only to the error columns.
- Observations are recorded only on open visits, with `OBSERVATION_RECORD`.

## 4. Configuration a clinical lead must supply

```yaml
gcare:
  triage:
    urgency-levels:        # empty by default: urgency cannot be recorded
      - { code: ..., label: ..., rank: 1 }
    alarm-limits:          # empty by default: nothing is flagged
      PULSE_BPM: { low: ..., high: ... }   # measures: TEMPERATURE_C, PULSE_BPM, RESPIRATORY_RATE,
                                           # SYSTOLIC_MMHG, DIASTOLIC_MMHG, SPO2_PCT
```

Flags are computed when a set is recorded, using the limits in force at that time, and stored with the set. Until values are approved, the screen tells staff that values are not graded or flagged and to follow the local triage procedure.

## 5. Provisional roles

| Role | Permissions |
|---|---|
| Registration clerk / supervisor | + `ENCOUNTER_READ`, `ENCOUNTER_OPEN` |
| Triage nurse | `PATIENT_READ`, `ENCOUNTER_READ`, `OBSERVATION_READ`, `OBSERVATION_RECORD` |
| Clinician | `PATIENT_READ`, `ENCOUNTER_READ`, `ENCOUNTER_MANAGE`, `ENCOUNTER_REOPEN`, `OBSERVATION_READ`, `OBSERVATION_RECORD` |

Local demo users: `demo.nurse`, `demo.clinician` (see `infra/keycloak/README.md`).

## 6. Design notes

- The encounter module reads patient, department and user names through read-only joins for display. Writes go only to its own tables. Revisit if modules move to separate schemas.
- Clinical notes, diagnoses, orders and the clinician workspace are tasks 3.2–3.4.

## 7. Clinical notes and diagnoses (task 3.2)

Decisions: OD-10 fixed sections; OD-09 free-text diagnoses, codes later (`project-record/open-decisions.md`). Section names are provisional.

- **Note sections:** history, examination, assessment, plan. Each is optional free text, but a signed note needs at least one.
- **Draft:** private to its author (others do not see it, even with read permission). Saved with `version`; a stale save answers 409. A draft can be discarded; it is kept but no longer shown.
- **Sign:** only by the author. Afterwards the note **never changes**: the database refuses updates to any note that is not a draft, and the application role can only update draft columns.
- **Amend:** any clinician with `CLINICAL_NOTE_WRITE` starts an amendment of a signed note with a **reason**; it begins as a copy and is signed like a note, with its own author and time. The original stays unchanged and is shown as "amended later". Amendments are allowed after the visit closes; new notes need an open visit.
- **Drafts and closed visits:** a draft started while the visit was open may still be signed after it closes (late signing). Not limited in time (not invented).
- **Diagnoses:** free text (≤500 characters) with certainty *provisional* or *confirmed*; author and time recorded; open visits only. Corrections: "entered in error" with a reason (stays visible, crossed out). `code`/`code_system` stay empty until a coding system is chosen.
- **Permissions (provisional):** `CLINICAL_NOTE_READ` (triage nurse, clinician), `CLINICAL_NOTE_WRITE` (clinician).
- Audit records note and diagnosis ids and actions; never note text or diagnosis wording.

Further questions for the clinical lead:

6. May a clinician amend another clinician's note, or only their own?
7. Should late signing (after the visit closed) be limited in time?
8. Are the section names right for the pilot departments?

## 8. Orders and referrals (task 3.3)

Decision (user, 2026-11-23): the **order catalog is managed by administrators and starts empty**. Engineering adds no tests, imaging studies or referral destinations; only the local demo seed has three clearly fictional items.

- **Catalog item:** code (fixed), name, type (laboratory, imaging, procedure, referral; fixed), optional performing department, active/inactive. Never deleted. Managed with `ORDER_CATALOG_MANAGE` (organization administrator).
- **Order:** placed on an open visit with `ORDER_CREATE` (clinician): item, priority *routine* or *urgent* (labels only, no time targets), optional indication. It keeps a **snapshot** of the item's code, name and type, so catalog changes never alter an order. An item whose performing department belongs to another facility cannot be ordered here.
- **Status:** requested → in progress → completed (`ORDER_PROCESS`, service department staff), or cancelled with a **reason** by the ordering side or the performing department. Every change is in the order's history and audited (ids only; never the indication or item code).
- **Worklist:** open orders at the facility, urgent first, then oldest. Results reporting is out of scope here (laboratory module is "later"; imaging in Phase 6).
- Orders may continue after the visit closes; new orders need an open visit.

Questions for the clinical and department leads:

9. Which items, names and performing departments belong in the pilot catalog?
10. Should "urgent" carry a target time, and who defines it?
11. May performing departments cancel, or only reject back to the clinician?

## Open questions for the workflow owner

1. Visit types beyond new/follow-up? Emergency arrivals in the pilot?
2. Who may close a visit: any clinician, or only the assigned one?
3. Should reopening be limited in time (for example same day)?
4. Is triage mandatory before a clinician may call the patient?
5. Should a transfer keep the clinician (handover) or always return the patient to the queue?

## Change log
| Date | Change | By |
|---|---|---|
| 2026-11-23 | Provisional defaults written at product owner's request; triage scale and alarm limits left empty for a clinical lead | Engineering (Claude Code), task 3.1 |
| 2026-11-23 | Clinical notes (fixed sections, draft/sign/amend) and free-text diagnoses per OD-10/OD-09 decisions | Engineering (Claude Code), task 3.2 |
| 2026-11-23 | Orders and referrals; admin-managed catalog, empty by default (user decision) | Engineering (Claude Code), task 3.3 |
