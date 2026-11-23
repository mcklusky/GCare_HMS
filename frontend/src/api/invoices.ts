import { apiFetch } from './client'
import type { UserRef } from './encounters'
import type { PatientSummary } from './patients'

/**
 * Visit invoices (backend InvoiceController; docs/19 §3). Amounts are decimals from the server,
 * shown with formatMoney; the browser never does money arithmetic.
 */

export type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'VOID'
export type Money = number | string

export interface InvoiceLine {
  id: string
  source: 'ORDER' | 'MANUAL'
  order_id: string | null
  order_status: string | null
  service_id: string
  service_code: string
  service_name: string
  quantity: number
  unit_price: Money
  tax_rate_code: string | null
  tax_rate_percent: Money | null
  net_amount: Money
  tax_amount: Money
  line_total: Money
  status: 'ACTIVE' | 'REMOVED'
  reason: string | null
  added_by: UserRef
  added_at: string
  removed_reason: string | null
  removed_by: UserRef | null
  removed_at: string | null
}

export interface UnbilledOrder {
  order_id: string
  item_name: string
  service_code: string
  problem: 'NO_PRICE' | 'NO_TAX_RATE' | null
}

export interface Invoice {
  id: string
  invoice_number: string | null
  status: InvoiceStatus
  currency: string
  facility_id: string
  encounter: { id: string; status: string; department_name: string; arrived_at: string }
  patient: PatientSummary
  lines: InvoiceLine[]
  unbilled_orders: UnbilledOrder[]
  subtotal: Money
  tax_total: Money
  total: Money
  replaces_invoice_id: string | null
  created_by: UserRef
  created_at: string
  issued_by: UserRef | null
  issued_at: string | null
  void_reason: string | null
  voided_by: UserRef | null
  voided_at: string | null
  version: number
}

export interface InvoiceSummary {
  id: string
  invoice_number: string | null
  status: InvoiceStatus
  currency: string
  facility_id: string
  encounter_id: string
  patient: PatientSummary
  total: Money
  created_at: string
  issued_at: string | null
  version: number
}

export interface BillableVisit {
  encounter_id: string
  facility_id: string
  patient: PatientSummary
  department_name: string
  status: string
  arrived_at: string
}

const enc = encodeURIComponent

export const INVOICE_STATUS_LABEL: Record<InvoiceStatus, string> = {
  DRAFT: 'Draft',
  ISSUED: 'Issued',
  VOID: 'Void',
}

export const UNBILLED_LABEL: Record<NonNullable<UnbilledOrder['problem']> | 'READY', string> = {
  READY: 'Not on the invoice yet',
  NO_PRICE: 'No approved price in this currency',
  NO_TAX_RATE: 'Tax rate not approved',
}

export const listInvoices = (facilityId: string, statuses: InvoiceStatus[], signal?: AbortSignal) =>
  apiFetch<InvoiceSummary[]>(
    `/api/v1/invoices?facility_id=${enc(facilityId)}${statuses.map((s) => `&status=${s}`).join('')}`,
    { signal },
  )

export const listBillableVisits = (facilityId: string, signal?: AbortSignal) =>
  apiFetch<BillableVisit[]>(`/api/v1/invoices/billable-visits?facility_id=${enc(facilityId)}`, { signal })

export const getInvoice = (id: string, signal?: AbortSignal) =>
  apiFetch<Invoice>(`/api/v1/invoices/${enc(id)}`, { signal })

export const startInvoice = (encounterId: string, currency: string) =>
  apiFetch<Invoice>(`/api/v1/encounters/${enc(encounterId)}/invoices`, { method: 'POST', body: { currency } })

export const addOrderCharges = (invoice: Invoice) =>
  apiFetch<Invoice>(`/api/v1/invoices/${enc(invoice.id)}/order-charges`, {
    method: 'POST',
    body: { version: invoice.version },
  })

export const addInvoiceLine = (invoice: Invoice, serviceId: string, quantity: number, reason: string) =>
  apiFetch<Invoice>(`/api/v1/invoices/${enc(invoice.id)}/lines`, {
    method: 'POST',
    body: { version: invoice.version, service_id: serviceId, quantity, reason },
  })

export const removeInvoiceLine = (invoice: Invoice, lineId: string, reason: string) =>
  apiFetch<Invoice>(`/api/v1/invoices/${enc(invoice.id)}/lines/${enc(lineId)}/remove`, {
    method: 'POST',
    body: { version: invoice.version, reason },
  })

export const issueInvoice = (invoice: Invoice) =>
  apiFetch<Invoice>(`/api/v1/invoices/${enc(invoice.id)}/issue`, {
    method: 'POST',
    body: { version: invoice.version },
  })

export const voidInvoice = (invoice: Invoice, reason: string) =>
  apiFetch<Invoice>(`/api/v1/invoices/${enc(invoice.id)}/void`, {
    method: 'POST',
    body: { version: invoice.version, reason },
  })
