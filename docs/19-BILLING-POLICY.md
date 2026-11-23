# 19 — Billing Policy (decisions and PROVISIONAL defaults)

> **Status:** the decisions below were made by the product owner on 2026-11-23. Everything marked *provisional* is an engineering default that the **finance owner** must confirm before real use (Phase 4 exit: "finance owner approves permissions and receipt format"). No tariff, price, tax rate or exchange rate is defined by engineering.

Requirements: FR-017 (billing and tariff catalog), FR-042 (configuration, partial). Code: `backend/.../billing/`, migration `V9__billing_catalog_and_tariffs.sql`, screens `/billing/tariffs`, `/billing/approvals`.

## 1. Decisions (product owner, 2026-11-23)

| Topic | Decision |
|---|---|
| Where prices come from | Entered in the app by finance staff. The catalog **starts empty**. |
| Currency | **Any ISO 4217 currency.** Each price states its currency; a service may have prices in several currencies. Amounts are **never converted**; there is no exchange rate in the system. |
| Tax (OD-11) | A **configurable tax rate**, empty by default. Finance adds rates once the legal position is confirmed. |
| Approval | **Two people**: one proposes a price or tax rate, a different person approves or rejects it. |

## 2. Services, prices and tax rates (task 4.1)

- **Billable service:** code (fixed), name, optional link to one orderable item (used by invoicing later), active/inactive. Never deleted.
- **Price:** service + currency + amount + optional tax rate code + effective-from date + reason. Starts *proposed*; becomes *approved* or *rejected* by a different person with a note. Approved and rejected prices **never change** (database trigger and column grants).
- **Price change:** a new price with a later effective date. The price in effect on a date is the approved price with the latest effective date not after it. Invoices (task 4.2) copy the price, currency and tax rate at the time they are made, so later prices never alter earlier invoices.
- **No retroactive prices:** a price or rate must take effect today or later, and cannot be approved once its effective date has passed (reject and propose again).
- **Amounts:** decimal, at most as many decimals as the currency allows (USD 2, JPY 0, BHD 3, …), not negative, up to 15 digits before the point. The browser sends amounts as decimal strings, never floating-point numbers.
- **Tax rate:** code + name + percentage (0–100, up to 4 decimals) + effective date; same propose/approve rules. A price may name a tax rate code only if that code has an approved rate. How tax is computed on an invoice is task 4.2.
- **Dates** are UTC calendar dates (the pilot facility's time zone, Africa/Monrovia, is UTC). *Provisional*: revisit if a facility in another time zone joins.
- **Scope:** billing is organization-wide; the permission is checked, not the facility. *Provisional*: per-facility tariffs are not supported yet.
- Audit records ids, codes and currencies; never amounts or reasons.

## 3. Provisional roles

| Role | Permissions |
|---|---|
| Finance officer | `BILLING_READ`, `BILLING_CATALOG_MANAGE`, `PRICE_PROPOSE` |
| Finance manager | `BILLING_READ`, `PRICE_PROPOSE`, `PRICE_APPROVE` |

Local demo: `demo.finance`, `demo.financemgr`; services `DEMO-CONSULT`, `DEMO-LAB-A` with **fictional** prices of 1.00 (not a tariff).

## Open questions for the finance owner

1. Which services and prices make up the pilot tariff, in which currencies?
2. Is any tax due on hospital services? If so, which rate, from when, and which services?
3. Should different facilities or patient categories (payer, insurance, staff) have different prices?
4. When a service has prices in two currencies, who chooses the invoice currency?

## Change log
| Date | Change | By |
|---|---|---|
| 2026-11-23 | Decisions recorded; service catalog, dated prices and tax rates with two-person approval | Engineering (Claude Code), task 4.1 |
