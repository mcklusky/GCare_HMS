import { apiFetch } from './client'
import type { UserRef } from './encounters'
import type { Money } from './invoices'
import type { PatientSummary } from './patients'
import type { Branding } from './settings'

/**
 * Payment methods, payments, receipts and cashier shifts (backend PaymentController; docs/19 §4).
 * Amounts are sent as the typed decimal string and shown with formatMoney; no arithmetic here.
 */

export interface PaymentMethod {
  id: string
  code: string
  name: string
  is_cash: boolean
  status: 'ACTIVE' | 'INACTIVE'
  version: number
}

export interface Payment {
  id: string
  invoice_id: string
  shift_id: string
  method: { id: string; code: string; name: string; is_cash: boolean }
  currency: string
  amount: Money
  balance_after: Money
  reference: string | null
  receipt_number: string
  received_by: UserRef
  received_at: string
}

export interface Receipt {
  format: string
  kind: 'PAYMENT' | 'REFUND'
  receipt_number: string
  facility_name: string
  branding: Branding
  received_at: string
  patient: PatientSummary
  invoice_number: string
  invoice_total: Money
  currency: string
  amount: Money
  method_name: string
  reference: string | null
  balance_after: Money
  cashier_name: string
}

export type ShiftStatus = 'OPEN' | 'CLOSED' | 'REVIEWED'

export interface Shift {
  id: string
  facility_id: string
  cashier: UserRef
  status: ShiftStatus
  opened_at: string
  closed_at: string | null
  close_note: string | null
  reviewed_by: UserRef | null
  reviewed_at: string | null
  review_note: string | null
  totals: {
    method_code: string
    method_name: string
    is_cash: boolean
    currency: string
    amount: Money
    payments: number
  }[]
  refunds: Shift['totals']
  expected_cash: { currency: string; amount: Money }[]
  counts: { currency: string; expected: Money; counted: Money; difference: Money }[]
  version: number
}

export const SHIFT_STATUS_LABEL: Record<ShiftStatus, string> = {
  OPEN: 'Open',
  CLOSED: 'Closed, waiting for sign-off',
  REVIEWED: 'Signed off',
}

const enc = encodeURIComponent

export const listPaymentMethods = (signal?: AbortSignal) =>
  apiFetch<PaymentMethod[]>('/api/v1/payment-methods', { signal })

export const createPaymentMethod = (body: { code: string; name: string; is_cash: boolean }) =>
  apiFetch<PaymentMethod>('/api/v1/payment-methods', { method: 'POST', body })

export const updatePaymentMethod = (m: PaymentMethod, status: PaymentMethod['status']) =>
  apiFetch<PaymentMethod>(`/api/v1/payment-methods/${enc(m.id)}`, {
    method: 'PUT',
    body: { version: m.version, name: m.name, status },
  })

/** The same idempotency key must be sent again if the request is retried. */
export const recordPayment = (
  invoiceId: string,
  body: { idempotency_key: string; method_id: string; amount: string; reference?: string },
) => apiFetch<Payment>(`/api/v1/invoices/${enc(invoiceId)}/payments`, { method: 'POST', body })

export const listPayments = (invoiceId: string, signal?: AbortSignal) =>
  apiFetch<Payment[]>(`/api/v1/invoices/${enc(invoiceId)}/payments`, { signal })

export const getReceipt = (paymentId: string, signal?: AbortSignal) =>
  apiFetch<Receipt>(`/api/v1/payments/${enc(paymentId)}/receipt`, { signal })

export const myShifts = (signal?: AbortSignal) => apiFetch<Shift[]>('/api/v1/shifts/mine', { signal })

export const openShift = (facilityId: string) =>
  apiFetch<Shift>('/api/v1/shifts', { method: 'POST', body: { facility_id: facilityId } })

export const closeShift = (shift: Shift, counts: { currency: string; counted: string }[], note: string) =>
  apiFetch<Shift>(`/api/v1/shifts/${enc(shift.id)}/close`, {
    method: 'POST',
    body: { version: shift.version, counts, note: note.trim() || undefined },
  })

export const shiftsToReview = (facilityId: string, signal?: AbortSignal) =>
  apiFetch<Shift[]>(`/api/v1/shifts/to-review?facility_id=${enc(facilityId)}`, { signal })

export const reviewShift = (shift: Shift, note: string) =>
  apiFetch<Shift>(`/api/v1/shifts/${enc(shift.id)}/review`, {
    method: 'POST',
    body: { version: shift.version, note },
  })
