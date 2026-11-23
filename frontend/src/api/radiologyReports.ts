import { apiFetch } from './client'
import type { Study } from './imagingWork'
import type { PatientSummary } from './patients'

/** Radiology reports and critical results (backend RadiologyReportController; docs/26). */

interface UserRef {
  id: string
  display_name: string
}

export type CommunicationMethod = 'PHONE' | 'IN_PERSON' | 'OTHER'
export const METHOD_LABEL: Record<CommunicationMethod, string> = {
  PHONE: 'Phone',
  IN_PERSON: 'In person',
  OTHER: 'Other',
}

export interface Sections {
  technique: string | null
  comparison: string | null
  findings: string | null
  impression: string | null
  critical: boolean
}

export interface CriticalResult {
  report_id: string
  order_id: string
  facility_id: string
  encounter_id: string
  accession_number: string
  item_name: string
  patient: PatientSummary
  flagged_by: UserRef
  flagged_at: string
  communicated_to: string
  communication_method: CommunicationMethod
  communicated_at: string
  communication_note: string | null
  acknowledged_by: UserRef | null
  acknowledged_at: string | null
  acknowledgement_note: string | null
  /** CRITICAL, or CHANGED: an amendment (no longer critical) of a report that had a critical result. */
  kind: 'CRITICAL' | 'CHANGED'
  /** The amendment that replaced this result before it was acknowledged. */
  superseded_by: string | null
  superseded_at: string | null
  version: number
}

/** Banner and list wording for a critical result by kind. */
export const CRITICAL_KIND_LABEL = { CRITICAL: 'Critical result', CHANGED: 'Changed critical result (amended)' }

export interface Report extends Sections {
  id: string
  order_id: string
  status: 'DRAFT' | 'SIGNED' | 'DISCARDED'
  author: UserRef
  created_at: string
  updated_at: string
  signed_at: string | null
  amends_report_id: string | null
  amendment_reason: string | null
  critical_result: CriticalResult | null
  /** Signing needs the documented direct contact (critical, or amending a critical result). */
  communication_required: boolean
  version: number
}

export interface ImagingResult {
  order_id: string
  encounter_id: string
  accession_number: string
  item_name: string
  requested_at: string
  step: string
  report_state: 'NOT_YET_REPORTED' | 'REPORTED' | 'AMENDED'
  studies: Study[]
  report: Report | null
  earlier_reports: Report[]
}

export interface ReportingItem {
  order_id: string
  accession_number: string
  patient: PatientSummary
  item_name: string
  priority: 'ROUTINE' | 'URGENT'
  indication: string | null
  examined_at: string
  studies: Study[]
  draft_id: string | null
  draft_author: UserRef | null
  signed_report_id: string | null
}

export interface Communication {
  communicated_to: string
  method: CommunicationMethod
  communicated_at: string
  note?: string
}

const enc = encodeURIComponent

export const getReportingList = (signal?: AbortSignal) =>
  apiFetch<ReportingItem[]>('/api/v1/radiology/reporting', { signal })

export const getOrderReports = (orderId: string, signal?: AbortSignal) =>
  apiFetch<Report[]>(`/api/v1/imaging/orders/${enc(orderId)}/reports`, { signal })

export const startReport = (orderId: string, amendmentReason?: string) =>
  apiFetch<Report>(`/api/v1/imaging/orders/${enc(orderId)}/reports`, {
    method: 'POST',
    body: amendmentReason ? { amendment_reason: amendmentReason } : {},
  })

export const saveReport = (r: Report, sections: Sections) =>
  apiFetch<Report>(`/api/v1/radiology-reports/${enc(r.id)}`, {
    method: 'PUT',
    body: { version: r.version, sections },
  })

export const signReport = (r: Report, communication?: Communication) =>
  apiFetch<Report>(`/api/v1/radiology-reports/${enc(r.id)}/sign`, {
    method: 'POST',
    body: communication ? { version: r.version, communication } : { version: r.version },
  })

export const discardReport = (r: Report) =>
  apiFetch<Report>(`/api/v1/radiology-reports/${enc(r.id)}/discard`, {
    method: 'POST',
    body: { version: r.version },
  })

export const getEncounterImaging = (encounterId: string, signal?: AbortSignal) =>
  apiFetch<ImagingResult[]>(`/api/v1/encounters/${enc(encounterId)}/imaging-results`, { signal })

export const getPatientImaging = (patientId: string, signal?: AbortSignal) =>
  apiFetch<ImagingResult[]>(`/api/v1/patients/${enc(patientId)}/imaging-results`, { signal })

export const getOpenCriticalResults = (signal?: AbortSignal) =>
  apiFetch<CriticalResult[]>('/api/v1/critical-results', { signal })

export const acknowledgeCritical = (c: CriticalResult, note: string) =>
  apiFetch<CriticalResult>(`/api/v1/critical-results/${enc(c.report_id)}/acknowledge`, {
    method: 'POST',
    body: { version: c.version, note },
  })
