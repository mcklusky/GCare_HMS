import { apiFetch } from './client'
import type { UserRef } from './encounters'

/** Billable services, dated prices and tax rates (backend TariffController; docs/19). */

export type DecisionStatus = 'PROPOSED' | 'APPROVED' | 'REJECTED'

export interface Price {
  id: string
  service_id: string
  service_code: string
  service_name: string
  currency: string
  /** Decimal as sent by the server; display only, never used for arithmetic here. */
  amount: number | string
  tax_rate_code: string | null
  effective_from: string
  status: DecisionStatus
  reason: string
  proposed_by: UserRef
  proposed_at: string
  decided_by: UserRef | null
  decided_at: string | null
  decision_note: string | null
  version: number
}

export interface BillableService {
  id: string
  code: string
  name: string
  order_catalog_item_id: string | null
  status: 'ACTIVE' | 'INACTIVE'
  current_prices: Price[]
  version: number
}

export interface TaxRate {
  id: string
  code: string
  name: string
  rate_percent: number | string
  effective_from: string
  status: DecisionStatus
  reason: string
  proposed_by: UserRef
  proposed_at: string
  decided_by: UserRef | null
  decided_at: string | null
  decision_note: string | null
  version: number
}

export interface Approvals {
  prices: Price[]
  tax_rates: TaxRate[]
}

const enc = encodeURIComponent

export const listServices = (signal?: AbortSignal) =>
  apiFetch<BillableService[]>('/api/v1/billing/services', { signal })

export const createService = (body: { code: string; name: string }) =>
  apiFetch<BillableService>('/api/v1/billing/services', { method: 'POST', body })

export const listPrices = (serviceId: string, signal?: AbortSignal) =>
  apiFetch<Price[]>(`/api/v1/billing/services/${enc(serviceId)}/prices`, { signal })

/** {@code amount} is sent as the typed decimal string, so no floating-point rounding happens here. */
export const proposePrice = (
  serviceId: string,
  body: { currency: string; amount: string; effective_from: string; tax_rate_code?: string; reason: string },
) => apiFetch<Price>(`/api/v1/billing/services/${enc(serviceId)}/prices`, { method: 'POST', body })

export const listTaxRates = (signal?: AbortSignal) => apiFetch<TaxRate[]>('/api/v1/billing/tax-rates', { signal })

export const proposeTaxRate = (body: {
  code: string
  name: string
  rate_percent: string
  effective_from: string
  reason: string
}) => apiFetch<TaxRate>('/api/v1/billing/tax-rates', { method: 'POST', body })

export const listApprovals = (signal?: AbortSignal) => apiFetch<Approvals>('/api/v1/billing/approvals', { signal })

export const decide = (
  kind: 'prices' | 'tax-rates',
  item: { id: string; version: number },
  decision: 'approve' | 'reject',
  note: string,
) =>
  apiFetch<unknown>(`/api/v1/billing/${kind}/${enc(item.id)}/${decision}`, {
    method: 'POST',
    body: { version: item.version, note },
  })

/** The currency's usual number of decimals, or null if the browser does not know the code. */
export function currencyDecimals(currency: string): number | null {
  try {
    return new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits ?? null
  } catch {
    return null
  }
}

/** A plain decimal with no more decimals than the currency allows (the server checks again). */
export function validAmount(amount: string, currency: string): boolean {
  const digits = currencyDecimals(currency)
  if (digits === null || !/^\d{1,15}(\.\d+)?$/.test(amount)) return false
  return (amount.split('.')[1] ?? '').length <= digits
}

/** "12.50 USD": the server's decimal, padded to the currency's usual decimals. No arithmetic. */
export function formatMoney(amount: number | string, currency: string): string {
  const text = String(amount)
  let digits: number
  try {
    digits = new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits ?? 2
  } catch {
    return `${text} ${currency}`
  }
  const [whole, fraction = ''] = text.split('.')
  if (digits === 0 || fraction.length >= digits) return `${text} ${currency}`
  return `${whole}.${fraction.padEnd(digits, '0')} ${currency}`
}

export const DECISION_LABEL: Record<DecisionStatus, string> = {
  PROPOSED: 'Waiting for approval',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
}

/** Today as YYYY-MM-DD in UTC (the backend compares UTC dates; docs/19). */
export function todayUtc(): string {
  return new Date().toISOString().slice(0, 10)
}
