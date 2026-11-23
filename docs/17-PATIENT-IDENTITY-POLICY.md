# 17 — Patient Identity Policy (provisional defaults)

> **Status: PROVISIONAL. Invented by the engineering team at the product owner's request on 2026-11-23 to unblock Phase 2. Not approved by registration leadership, clinical safety or privacy.**
> Every number and rule below is a starting default, not a local requirement. They are built as configuration where marked, so the hospital can change them without code changes. The Phase 2 exit gate ([06-PLAN.md](06-PLAN.md)) requires registration leadership to sign off the tested duplicate scenarios in §4 before any real patient is registered. Replace this note with the approval record when that happens.

Decisions covered: OD-05 (searchable demographics and normalization), OD-06 (duplicate scoring and manual review), OD-07 (merge vs link). Requirements: FR-003, FR-004, FR-005; ADR-006.

## Safety principles (fixed, not configurable)
1. **The system suggests, people decide.** No record is ever merged, linked or matched automatically.
2. **Nothing is destroyed.** No patient record, identifier or name history is deleted. Corrections keep the previous value, who changed it, when and why.
3. **Every identity decision is attributable.** Creating a record despite a likely duplicate, linking, and unlinking each need a reason and are audited.
4. **Linking needs two people.** The person who requests a link cannot approve it.
5. **Care is never blocked by missing details.** Unknown name, date of birth or sex can be registered (FR-004), and such records are flagged for later completion.

## 1. Patient number (FR-005)
- **Format:** `P` + 7-digit sequence + 1 check digit, shown as `P1234567-3`. The sequence is per organization, starting at 1000001.
- **Check digit:** Luhn (mod 10) over the 7 digits. It catches every single-digit typo and most swaps of neighbouring digits. The search accepts the number with or without `P`, the hyphen and spaces.
- **Uniqueness:** unique per organization (database constraint). Never reused, even if the record is later linked as a duplicate.
- **Independence:** the patient number never contains or depends on a national ID, name, birth date or facility.

## 2. Searchable details and normalization (OD-05)
| Field | How it is compared |
|---|---|
| Patient number | Exact, after removing `P`, hyphens and spaces; check digit validated |
| Identifier (national ID, passport, NHIS/insurance no., other) | Exact on type + normalized value (upper case, spaces and hyphens removed) |
| Phone | Digits only, compared on the last 9 digits (drops country code and leading 0). *Configurable digit count per organization.* |
| Family and given names | Normalized: Unicode accents removed, lower case, apostrophes and full stops removed, hyphens become spaces, spaces collapsed (`Mary-Jane` = `Mary Jane`). Fuzzy match with trigram similarity |
| Date of birth | Exact, or by year when either record's date is estimated |
| Sex | Exact (`FEMALE`, `MALE`, `OTHER`, `UNKNOWN`) |

**Minimum search (privacy):** a search must include a patient number, an identifier, a phone number, or a name **plus** a birth year/date or sex. Name-only searches are refused, so nobody can browse the patient list. Results are capped at 50 per page.

**Audit:** every search is audited as `PATIENT_SEARCHED` with the *types* of criteria used (for example `name+birth_year`), never the values.

## 3. Duplicate scoring (OD-06)
Candidates are scored when a new registration is about to be saved and on request for an existing record. Points add up. *Weights and thresholds are organization configuration.*

| Evidence | Points |
|---|---|
| Same identifier type and value | +100 |
| Same phone (normalized) | +30 |
| Family name equal / similar (trigram ≥ 0.6) | +20 / +10 |
| Given name equal / similar | +15 / +8 |
| Given and family names swapped | scored as if they were in the right fields |
| Date of birth equal | +25 |
| Same birth year (either date estimated), or dates within 1 day | +10 |
| Sex equal (both known) | +5 |
| Sex different (both known) | −20 |
| Same identifier type, **different** value (both verified) | −50 |

| Score | Band | What the registration screen does |
|---|---|---|
| ≥ 80 | **Likely duplicate** | Shown first, highlighted. To create a new record anyway the clerk must type a reason; the new record is put in the duplicate review queue. |
| 50–79 | **Possible duplicate** | Shown for review. Creating a new record needs an explicit "None of these is the patient" confirmation (no reason required). |
| < 50 | Not shown | — |

**Cap:** a score can only reach *Likely* if an identifier matched or the given names match (equal or similar). Otherwise it is capped at 79. This keeps twins and relatives who share a surname, birth date and phone from being flagged as the same person without stronger evidence.

**Review queue:** records created despite a likely duplicate, and pairs reported by staff ("this may be the same person"), go to a queue for users with permission `PATIENT_DUPLICATE_REVIEW` (registration supervisor). The reviewer chooses **not a duplicate** (the pair is not suggested again) or **request link** (§5).

## 4. Acceptance scenarios (to be signed off by registration leadership)
| # | Situation | Expected |
|---|---|---|
| 1 | Same national ID, different spelling of name | Likely duplicate (≥ 80) |
| 2 | Same name, same date of birth, same phone | Likely duplicate |
| 3 | Same name and DOB, different phone, no identifiers | Possible duplicate (50–79) |
| 4 | Given/family names swapped, same DOB | Possible or likely duplicate |
| 5 | Same name, different sex, different DOB (e.g. parent and child with same name) | Not shown, or possible at most; never likely |
| 6 | Twins: same family name, same DOB, same phone, different given names | Possible duplicate, never likely without an identifier match; staff decide |
| 7 | Same identifier type, different verified values | Not shown as likely, whatever the names |
| 8 | Unknown patient (no name, estimated age) | Registers without blocking; flagged "details incomplete" |
| 9 | Clerk creates a new record despite a likely duplicate | Reason required; audited; pair enters review queue |
| 10 | Name-only search | Refused with a message asking for one more detail |

## 5. Link instead of merge (OD-07)
GCare **links** duplicate records; it never merges them by moving or deleting data.

- **Link:** the duplicate record ("retired") points to the record that stays in use ("surviving"). The retired record's status becomes `LINKED` and it disappears from search; opening it shows a banner and a link to the surviving record. All its data stays where it is and is shown in the surviving record's history.
- **Two-person rule:** a user with `PATIENT_LINK_REQUEST` requests the link with a reason; a **different** user with `PATIENT_LINK_APPROVE` approves or rejects it. Both steps are audited.
- **Blocked:** linking records of different organizations; linking a record that is already retired; chains (a surviving record that is itself retired); and pairs with the same identifier type but different verified values, unless the approver records an override reason.
- **Unlink (reversal):** a user with `PATIENT_LINK_APPROVE` can reverse a link with a reason. Both records become active again; the link history is kept forever and shows both events.
- **Patient number:** the surviving record keeps its number; the retired number keeps resolving to the surviving record and is never reused.

## 6. Corrections
Changing a name, birth date, sex or identifier creates a new version with the previous value, user, time and reason (FR-003, ADR-006). Changing a *verified* identifier needs permission `PATIENT_IDENTIFIER_CORRECT` and a reason.

## Change log
| Date | Change | By |
|---|---|---|
| 2026-11-23 | Provisional defaults written at product owner's request | Engineering (Claude Code) |
| 2026-11-23 | Swapped names score as if in the right fields (scenario 4 was otherwise missed); cap at 79 without identifier or given-name evidence (scenario 6); hyphens become spaces | Engineering (Claude Code), found while encoding §4 as tests |
