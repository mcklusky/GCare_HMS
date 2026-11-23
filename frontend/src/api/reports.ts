import { apiFetch } from './client'
import type { UserRef } from './encounters'

/** Operational reports, dashboard and CSV export (backend ReportController; docs/20). Aggregates only. */

export type ColumnType = 'DATE' | 'TEXT' | 'COUNT' | 'DECIMAL' | 'MINUTES'

export interface ReportInfo {
  slug: string
  title: string
  exportable: boolean
}

export interface ReportView {
  report: string
  title: string
  facility_id: string
  facility_name: string
  timezone: string
  from: string
  to: string
  generated_at: string
  generated_by: UserRef
  columns: { key: string; label: string; type: ColumnType }[]
  rows: Record<string, string | number | null>[]
  notes: string[]
}

export interface Dashboard {
  facility_id: string
  facility_name: string
  day: string
  generated_at: string
  tiles: { key: string; label: string; value: string | number | null; currency: string | null }[]
}

const enc = encodeURIComponent

export const listReports = (signal?: AbortSignal) => apiFetch<ReportInfo[]>('/api/v1/reports', { signal })

export const getDashboard = (facilityId: string, signal?: AbortSignal) =>
  apiFetch<Dashboard>(`/api/v1/reports/dashboard?facility_id=${enc(facilityId)}`, { signal })

const query = (facilityId: string, from: string, to: string) =>
  `facility_id=${enc(facilityId)}&from=${enc(from)}&to=${enc(to)}`

export const runReport = (slug: string, facilityId: string, from: string, to: string, signal?: AbortSignal) =>
  apiFetch<ReportView>(`/api/v1/reports/${enc(slug)}?${query(facilityId, from, to)}`, { signal })

/** Same-origin link; the session cookie authorizes it and the server audits the export. */
export const exportUrl = (slug: string, facilityId: string, from: string, to: string) =>
  `/api/v1/reports/${enc(slug)}/export?${query(facilityId, from, to)}`
