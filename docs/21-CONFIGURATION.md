# 21 — Configuration Versioning and Facility Branding (decisions and PROVISIONAL defaults)

> **Status:** the decisions below were made by the product owner on 2026-11-23. **No setting has a built-in value**: the triage scale, alarm limits, branding and receipt text are empty until the responsible people enter and approve them.

Requirements: FR-042 (hospital workflow configuration, partial). Code: `backend/.../configuration/`, setting definitions in their modules (`encounter/TriageConfig`, `billing/BillingOptionsSetting`, `configuration/FacilityBrandingSetting`), migration `V14__configuration_versions.sql`, screen `/admin/settings`.

## 1. Decisions (product owner, 2026-11-23)

| Topic | Decision |
|---|---|
| Settings in scope | Triage urgency scale and alarm limits; facility branding and receipt text; billing options (look-back days for visits without an invoice). |
| Approval | **Two people, versioned**: a change is a new proposed version; a different person approves it and it becomes active; every version is kept. |
| Branding | **Name, address, logo** (plus phone, registration number, receipt footer). Colours stay standard for accessibility. |
| Approvers | **By setting type**: clinical settings need `CONFIG_CLINICAL_MANAGE` (provisional *Clinical lead* role); branding, receipt text and billing options need `CONFIG_ORGANIZATION_MANAGE` (organization administrators). |

## 2. How it works

- **Versions:** each setting (per facility for branding) has numbered versions: *proposed* → *in use* (approved) or *rejected*; approving a new version marks the previous one *replaced*. Values never change (database trigger); at most one version in use and one waiting per setting.
- **Two people:** the proposer cannot decide their own proposal (service and database CHECK). Decisions need a note.
- **Rollback:** propose an earlier version again ("Propose again"); it needs approval like any change.
- **Triage** (organization-wide): urgency levels (code, label, rank 1–99; lower rank is seen first; codes and ranks unique) and low/high alarm limits per vital sign (low below high). The approved version replaces the `gcare.triage.*` deployment properties, which remain empty by default and apply only when no version has been approved. Values are clinical rules: a clinical lead enters them and a second clinical lead approves them (docs/18 §4).
- **Facility branding** (per facility): display name (required), up to 4 address lines, phone, registration or tax number, receipt footer, optional logo. Shown in the app header for the working facility and on payment and refund receipts. Without approved branding the facility name is used.
- **Logo:** PNG or JPEG up to 256 KiB, checked by its file signature, stored in the database. A logo is visible to staff only once approved branding uses it; branding managers can see uploaded logos to review proposals. Served with `Content-Security-Policy: default-src 'none'`.
- **Billing options:** days back for "visits without an invoice" (1–90). Without an approved version, 7 days (an engineering convenience, not a financial rule).
- Audit records the setting key and version number for proposals, decisions and logo uploads; never values.

## 3. Provisional roles

| Role | Permissions |
|---|---|
| Clinical lead (`demo.clinlead`, `demo.clinlead2`) | `CONFIG_CLINICAL_MANAGE` |
| Organization administrator (`demo.admin`, `demo.admin2`) | adds `CONFIG_ORGANIZATION_MANAGE` |

## Not built yet (FR-042)

- Payer categories and other workflow catalogues as versioned settings; scheduled (future-dated) activation; per-facility triage scales.

## Open questions

1. Who are the clinical leads allowed to approve the triage scale and alarm limits, and what are the values (clinical governance)?
2. What must appear on receipts and invoices by law (registration or tax number, wording)? Approve the receipt layout together with docs/19.
3. Should a triage change take effect at a set date and time instead of on approval?

## Change log
| Date | Change | By |
|---|---|---|
| 2026-11-23 | Decisions recorded; versioned settings with two-person approval, branding and logo, billing options | Engineering (Claude Code), task 5.2 |
