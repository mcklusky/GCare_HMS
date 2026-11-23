# 19 — Billing Policy (decisions and PROVISIONAL defaults)

> **Status:** the decisions below were made by the product owner on 2026-11-23. Everything marked *provisional* is an engineering default that the **finance owner** must confirm before real use (Phase 4 exit: "finance owner approves permissions and receipt format"). No tariff, price, tax rate or exchange rate is defined by engineering.

Requirements: FR-017 (billing and tariff catalog), FR-018 (payments and receipts), FR-019 (exemptions, partial), FR-042 (configuration, partial). Code: `backend/.../billing/`, migrations `V9__billing_catalog_and_tariffs.sql`, `V10__invoices.sql`, `V11__payments_and_shifts.sql`, `V12__refunds_and_exemptions.sql`, screens `/billing/tariffs`, `/billing/approvals`, `/billing/invoices`, `/billing/shift`, `/billing/shift-reviews`, `/billing/receipts/{id}`, `/billing/refund-receipts/{id}`, `/billing/adjustment-approvals`, `/admin/payment-methods`, `/admin/exemption-reasons`.

## 1. Decisions (product owner, 2026-11-23)

| Topic | Decision |
|---|---|
| Where prices come from | Entered in the app by finance staff. The catalog **starts empty**. |
| Currency | **Any ISO 4217 currency.** Each price states its currency; a service may have prices in several currencies. Amounts are **never converted**; there is no exchange rate in the system. |
| Tax (OD-11) | A **configurable tax rate**, empty by default. Finance adds rates once the legal position is confirmed. |
| Approval | **Two people**: one proposes a price or tax rate, a different person approves or rejects it. |
| Invoice charges (task 4.2) | **Orders + cashier**: each order whose orderable item is linked to a billable service is charged automatically; a cashier adds or removes other charges with a reason. |
| Invoice currency | **Cashier picks one currency per invoice.** Each charge needs an approved price in that currency; nothing is converted. |
| Tax on invoices | **Per line, rounded half-up** to the currency's decimals; totals are the sums of the rounded lines. |
| Payment methods (task 4.3) | **Admin-managed**, empty by default. Each method is marked cash or not; cash counts toward the drawer. |
| Part payments | **Allowed up to the balance.** A payment larger than the balance is refused; change is given outside the system. |
| Shift close | **Cash count + second-person sign-off.** A difference needs a note. |
| Receipt | **Basic, provisional** layout until the finance owner approves it. |
| Refunds (task 4.4) | **Two people**: requested with a reason, approved by a different person, then paid out by a cashier in an open shift. |
| Refund pay-out | **In a shift**: cash refunds reduce that shift's expected cash. |
| Exemptions | **Full or partial amount** up to the balance, with an admin-managed reason (empty by default) and a note. |
| Exemption approver | **Finance manager** (separate permission `EXEMPTION_APPROVE`, so another role can be given it later); never the requester. |
| Corrections | **Void + reissue**: an issued invoice never changes; it is voided with a reason and a new invoice is made. Credit notes and refunds are task 4.4. |

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

## 3. Invoices (task 4.2)

- **One live invoice per visit** (draft or issued). A cancelled visit cannot be billed.
- **Starting an invoice:** the cashier chooses the currency; the charges of the visit's orders are added at once. Orders placed later are added with "Add new order charges". Orders with no approved price (or tax rate) in that currency are listed as not charged.
- **Each line copies** the service code and name, the approved price, and the tax rate code and percentage in effect on the UTC date the line is added. Later price changes never alter it.
- **Arithmetic:** net = unit price × quantity (whole number 1–999); tax = net × rate ÷ 100 rounded half-up to the currency's decimals; line total = net + tax; invoice totals are sums of active lines. The database checks net = price × quantity and total = subtotal + tax.
- **Removing a charge** (draft only) needs a reason; the line stays visible as removed and its order is not charged again on that invoice.
- **Issuing** fixes the totals and assigns the next number for the organization, `INV-000001` and so on, with no gaps. Refused while the invoice is empty, while a charge is for a cancelled order, or while orders that could be charged are not on it.
- **Void:** a cashier may discard a draft (it never gets a number); voiding an issued invoice needs `INVOICE_VOID`, with a reason. The next invoice for the visit records which voided invoice it replaces. Issued invoices never change (database trigger).
- *Provisional:* invoice number format; the 7-day window of "visits without an invoice"; one currency per visit at a time.
- Audit records ids, codes, currencies and invoice numbers; never amounts or reasons.

## 4. Payments, receipts and shifts (task 4.3)

- **Payments** go only on issued invoices, in the invoice's currency, from 0.01 (or the currency's smallest unit) up to the balance due. Several payments per invoice are allowed; each states its method and an optional reference (e.g. a mobile money transaction number).
- **Idempotent:** the browser sends a random key with each payment form; a retry with the same key returns the payment already recorded (no double charge). The same key with a different amount or method is refused.
- **Shift:** a cashier must have an open shift at the invoice's facility to take payments; one open shift per cashier. Closing the shift records the cash counted per currency against the expected cash (sum of cash-method payments). Every currency with cash payments must be counted; any difference needs a note. A **different person** with `SHIFT_REVIEW` signs the shift off with a note (database CHECK as well).
- **Receipt:** numbered `RCT-000001` and up per organization, with no gaps. Shows facility, date, patient name and number, invoice number and total, amount, method, reference, balance after, and cashier. Every view or reprint is audited. Layout is **provisional**.
- **Never changed:** payments and cash counts cannot be updated or deleted (grants). An invoice with payments can no longer be voided (service and database trigger); refunds and reversals are task 4.4.
- Audit records ids, receipt numbers, method codes and currencies; never amounts or notes.

## 5. Refunds and exemptions (task 4.4)

- **Balance due** = invoice total − approved exemptions − payments + refunds paid out.
- **Refund** of a payment: requested with an amount and reason (up to what is left of that payment after other refund requests); approved or rejected with a note by a **different person** (service and database CHECK); then **paid out** by a cashier in an open shift at the invoice's facility, using the payment's method. Pay-out is idempotent (key per attempt) and takes a receipt number from the same `RCT` sequence; the refund receipt shows the balance after. The original payment never changes.
- **Cash refunds** come from the drawer: the shift must have taken at least that much cash in the currency, and they reduce the shift's expected cash.
- **Exemption:** an amount up to the balance due (minus exemptions already requested), with a reason from the admin-managed list and a note; approved or rejected by a **different person**. Approval re-checks the balance (a payment in between can make the request too large). Approved exemptions reduce the balance at once.
- A refund raises the balance again: for a service not given, request an exemption after the refund so the invoice does not show the amount as owing.
- An invoice with payments or an approved exemption cannot be voided (service and trigger). Decided exemptions and paid refunds never change.
- *Not built (FR-019, open):* eligibility schemes with evidence and effective dates; reasons carry no eligibility rules.
- Audit records ids, codes and receipt numbers; never amounts, reasons or notes.

## 6. Provisional roles

| Role | Permissions |
|---|---|
| Finance officer | `BILLING_READ`, `BILLING_CATALOG_MANAGE`, `PRICE_PROPOSE`, `INVOICE_READ` |
| Finance manager | `BILLING_READ`, `PRICE_PROPOSE`, `PRICE_APPROVE`, `INVOICE_READ`, `INVOICE_VOID`, `SHIFT_REVIEW`, `REFUND_APPROVE`, `EXEMPTION_APPROVE` |
| Cashier | `BILLING_READ`, `INVOICE_READ`, `INVOICE_MANAGE`, `PAYMENT_RECORD`, `REFUND_REQUEST`, `EXEMPTION_REQUEST` |
| Organization administrator | adds `PAYMENT_METHOD_MANAGE`, `EXEMPTION_REASON_MANAGE` |

Local demo: `demo.finance`, `demo.financemgr`, `demo.cashier`; services `DEMO-CONSULT`, `DEMO-LAB-A` with **fictional** prices of 1.00 (not a tariff); payment methods `DEMO-CASH`, `DEMO-NONCASH` and exemption reason `DEMO-REASON` (demo only).

## Open questions for the finance owner

1. Which services and prices make up the pilot tariff, in which currencies?
2. Is any tax due on hospital services? If so, which rate, from when, and which services?
3. Should different facilities or patient categories (payer, insurance, staff) have different prices?
4. ~~Who chooses the invoice currency?~~ Decided 2026-11-23: the cashier, per invoice.
5. Is the invoice number format (`INV-000001`, per organization) acceptable, or is a legal format required?
6. Must a voided invoice be approved by a second person?
7. Which payment methods are accepted (cash, mobile money, card, bank transfer…), and which need a reference number?
8. Approve the receipt layout (Phase 4 exit), including any legal wording or tax number.
9. Who may sign off cashier shifts, and what difference (if any) needs escalation?
10. Which exemption reasons and eligibility schemes apply (FR-019), what evidence is needed, and who besides the finance manager may approve?
11. Are there limits above which a refund or exemption needs a further approver?

## Change log
| Date | Change | By |
|---|---|---|
| 2026-11-23 | Decisions recorded; service catalog, dated prices and tax rates with two-person approval | Engineering (Claude Code), task 4.1 |
| 2026-11-23 | Invoice decisions recorded; visit invoices with per-line tax, issue and void | Engineering (Claude Code), task 4.2 |
| 2026-11-23 | Payment decisions recorded; idempotent part payments, receipts, shifts with cash count and sign-off | Engineering (Claude Code), task 4.3 |
| 2026-11-23 | Refund and exemption decisions recorded; two-person refunds paid out in shifts, amount exemptions | Engineering (Claude Code), task 4.4 |
