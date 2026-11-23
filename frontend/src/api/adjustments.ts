import { apiFetch } from './client'
import type { UserRef } from './encounters'
import type { Money } from './invoices'
import type { Receipt } from './payments'

/** Refunds and fee exemptions, each approved by a different person (backend AdjustmentController; docs/19 §5). */

export type RefundStatus = 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'PAID'
export type ExemptionStatus = 'REQUESTED' | 'APPROVED' | 'REJECTED'

export interface ExemptionReason {
  id: string
  code: string
  name: string
  status: 'ACTIVE' | 'INACTIVE'
  version: number
}

export interface Refund {
  id: string
  invoice_id: string
  invoice_number: string
  facility_id: string
  payment_id: string
  payment_receipt_number: string
  method: { id: string; code: string; name: string; is_cash: boolean }
  currency: string
  amount: Money
  reason: string
  status: RefundStatus
  requested_by: UserRef
  requested_at: string
  decided_by: UserRef | null
  decided_at: string | null
  decision_note: string | null
  paid_by: UserRef | null
  paid_at: string | null
  receipt_number: string | null
  balance_after: Money | null
  version: number
}

export interface Exemption {
  id: string
  invoice_id: string
  invoice_number: string
  facility_id: string
  reason: ExemptionReason
  currency: string
  amount: Money
  note: string
  status: ExemptionStatus
  requested_by: UserRef
  requested_at: string
  decided_by: UserRef | null
  decided_at: string | null
  decision_note: string | null
  version: number
}

export interface Adjustments {
  refunds: Refund[]
  exemptions: Exemption[]
}

export const REQUEST_STATUS_LABEL: Record<RefundStatus, string> = {
  REQUESTED: 'Waiting for approval',
  APPROVED: 'Approved, not paid out',
  REJECTED: 'Rejected',
  PAID: 'Paid out',
}

const enc = encodeURIComponent

export const listReasons = (signal?: AbortSignal) =>
  apiFetch<ExemptionReason[]>('/api/v1/exemption-reasons', { signal })

export const createReason = (body: { code: string; name: string }) =>
  apiFetch<ExemptionReason>('/api/v1/exemption-reasons', { method: 'POST', body })

export const updateReason = (r: ExemptionReason, status: ExemptionReason['status']) =>
  apiFetch<ExemptionReason>(`/api/v1/exemption-reasons/${enc(r.id)}`, {
    method: 'PUT',
    body: { version: r.version, name: r.name, status },
  })

export const listAdjustments = (invoiceId: string, signal?: AbortSignal) =>
  apiFetch<Adjustments>(`/api/v1/invoices/${enc(invoiceId)}/adjustments`, { signal })

export const listPendingAdjustments = (facilityId: string, signal?: AbortSignal) =>
  apiFetch<Adjustments>(`/api/v1/adjustments/pending?facility_id=${enc(facilityId)}`, { signal })

export const requestRefund = (paymentId: string, amount: string, reason: string) =>
  apiFetch<Refund>(`/api/v1/payments/${enc(paymentId)}/refunds`, { method: 'POST', body: { amount, reason } })

export const requestExemption = (invoiceId: string, reasonId: string, amount: string, note: string) =>
  apiFetch<Exemption>(`/api/v1/invoices/${enc(invoiceId)}/exemptions`, {
    method: 'POST',
    body: { reason_id: reasonId, amount, note },
  })

export const decideAdjustment = (
  kind: 'refunds' | 'exemptions',
  item: { id: string; version: number },
  decision: 'approve' | 'reject',
  note: string,
) =>
  apiFetch<unknown>(`/api/v1/${kind}/${enc(item.id)}/${decision}`, {
    method: 'POST',
    body: { version: item.version, note },
  })

/** The same key must be re-sent on retry, so a refund is never paid out twice. */
export const payOutRefund = (refund: Refund, idempotencyKey: string) =>
  apiFetch<Refund>(`/api/v1/refunds/${enc(refund.id)}/pay`, {
    method: 'POST',
    body: { version: refund.version, idempotency_key: idempotencyKey },
  })

export const getRefundReceipt = (refundId: string, signal?: AbortSignal) =>
  apiFetch<Receipt>(`/api/v1/refunds/${enc(refundId)}/receipt`, { signal })
