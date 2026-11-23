import { apiFetch } from './client'
import type { Page, PatientSummary } from './patients'

/** Encounter and triage API (backend encounter module; workflow in docs/18, provisional). */

export type EncounterType = 'NEW_VISIT' | 'FOLLOW_UP'
export type EncounterStatus = 'WAITING' | 'IN_CONSULTATION' | 'CLOSED' | 'CANCELLED'

export interface DepartmentRef {
  id: string
  code: string
  name: string
}

export interface UserRef {
  id: string
  display_name: string
}

export interface EncounterSummary {
  id: string
  facility_id: string
  department: DepartmentRef
  patient: PatientSummary
  encounter_type: EncounterType
  status: EncounterStatus
  clinician: UserRef | null
  arrived_at: string
  consultation_started_at: string | null
  ended_at: string | null
  /** Only for users with OBSERVATION_READ; otherwise null. */
  triaged: boolean | null
  urgency: string | null
  flags: string[] | null
  version: number
}

export interface EncounterEvent {
  id: string
  event_type: 'OPENED' | 'CALLED' | 'TRANSFERRED' | 'CLOSED' | 'CANCELLED' | 'REOPENED'
  from_status: EncounterStatus | null
  to_status: EncounterStatus
  department: DepartmentRef
  reason: string | null
  actor: UserRef
  occurred_at: string
}

export interface EncounterDetail {
  encounter: EncounterSummary
  events: EncounterEvent[]
}

export interface ObservationInput {
  temperature_c?: number
  pulse_bpm?: number
  respiratory_rate?: number
  systolic_mmhg?: number
  diastolic_mmhg?: number
  spo2_pct?: number
  weight_kg?: number
  height_cm?: number
  pain_score?: number
  urgency?: string
  allergy_note?: string
  notes?: string
}

export interface Flag {
  measure: string
  direction: 'LOW' | 'HIGH'
  value: number
  limit: number
}

/** Recorded set: every measure present, null when not recorded. */
export type ObservationValues = { [K in keyof ObservationInput]-?: NonNullable<ObservationInput[K]> | null }

export interface Observation extends ObservationValues {
  id: string
  encounter_id: string
  flags: Flag[]
  status: 'RECORDED' | 'ENTERED_IN_ERROR'
  recorded_by: UserRef
  recorded_at: string
  error_reason: string | null
  error_by: UserRef | null
  error_at: string | null
}

export interface UrgencyLevel {
  code: string
  label: string
  rank: number
}

export interface TriageSettings {
  urgency_levels: UrgencyLevel[]
  alarm_limits_configured: boolean
}

const enc = encodeURIComponent

export const listDepartments = (facilityId: string, signal?: AbortSignal) =>
  apiFetch<DepartmentRef[]>(`/api/v1/encounter-departments?facility_id=${enc(facilityId)}`, { signal })

export function listEncounters(
  filter: { facilityId?: string; departmentId?: string; patientId?: string; statuses?: EncounterStatus[] },
  signal?: AbortSignal,
) {
  const params = new URLSearchParams()
  if (filter.facilityId) params.set('facility_id', filter.facilityId)
  if (filter.departmentId) params.set('department_id', filter.departmentId)
  if (filter.patientId) params.set('patient_id', filter.patientId)
  filter.statuses?.forEach((s) => params.append('status', s))
  params.set('size', '100')
  return apiFetch<Page<EncounterSummary>>(`/api/v1/encounters?${params.toString()}`, { signal })
}

export const openEncounter = (body: {
  patient_id: string
  facility_id: string
  department_id: string
  encounter_type: EncounterType
}) => apiFetch<EncounterDetail>('/api/v1/encounters', { method: 'POST', body })

export const getEncounter = (id: string, signal?: AbortSignal) =>
  apiFetch<EncounterDetail>(`/api/v1/encounters/${enc(id)}`, { signal })

export type EncounterAction = 'call' | 'transfer' | 'close' | 'cancel' | 'reopen'

export const encounterAction = (
  e: EncounterSummary,
  action: EncounterAction,
  extra: { reason?: string; department_id?: string } = {},
) =>
  apiFetch<EncounterDetail>(`/api/v1/encounters/${enc(e.id)}/${action}`, {
    method: 'POST',
    body: { version: e.version, ...extra },
  })

export const getTriageSettings = (signal?: AbortSignal) =>
  apiFetch<TriageSettings>('/api/v1/triage/settings', { signal })

export const listObservations = (encounterId: string, signal?: AbortSignal) =>
  apiFetch<Observation[]>(`/api/v1/encounters/${enc(encounterId)}/observations`, { signal })

export const recordObservations = (encounterId: string, body: ObservationInput) =>
  apiFetch<Observation>(`/api/v1/encounters/${enc(encounterId)}/observations`, { method: 'POST', body })

export const markEnteredInError = (observationId: string, reason: string) =>
  apiFetch<Observation>(`/api/v1/observations/${enc(observationId)}/entered-in-error`, {
    method: 'POST',
    body: { reason },
  })

export const STATUS_LABEL: Record<EncounterStatus, string> = {
  WAITING: 'Waiting',
  IN_CONSULTATION: 'With clinician',
  CLOSED: 'Closed',
  CANCELLED: 'Cancelled',
}

export const TYPE_LABEL: Record<EncounterType, string> = { NEW_VISIT: 'New visit', FOLLOW_UP: 'Follow-up' }

export const EVENT_LABEL: Record<EncounterEvent['event_type'], string> = {
  OPENED: 'Visit opened',
  CALLED: 'Called in',
  TRANSFERRED: 'Transferred',
  CLOSED: 'Closed',
  CANCELLED: 'Cancelled',
  REOPENED: 'Reopened',
}

/** Measures with their fixed units (backend EncounterApi.ObservationRequest). */
export const MEASURES = [
  { key: 'temperature_c', label: 'Temperature', unit: '°C', step: 0.1 },
  { key: 'pulse_bpm', label: 'Pulse', unit: '/min', step: 1 },
  { key: 'respiratory_rate', label: 'Respiratory rate', unit: '/min', step: 1 },
  { key: 'systolic_mmhg', label: 'Blood pressure, systolic', unit: 'mmHg', step: 1 },
  { key: 'diastolic_mmhg', label: 'Blood pressure, diastolic', unit: 'mmHg', step: 1 },
  { key: 'spo2_pct', label: 'Oxygen saturation (SpO₂)', unit: '%', step: 1 },
  { key: 'weight_kg', label: 'Weight', unit: 'kg', step: 0.1 },
  { key: 'height_cm', label: 'Height', unit: 'cm', step: 0.1 },
  { key: 'pain_score', label: 'Pain score', unit: '0–10', step: 1 },
] as const

export type MeasureKey = (typeof MEASURES)[number]['key']

/** Flag codes from the queue ("PULSE_BPM_HIGH") as words. */
export function flagLabel(code: string): string {
  const high = code.endsWith('_HIGH')
  const measure = code.replace(/_(HIGH|LOW)$/, '').toLowerCase()
  const m = MEASURES.find((x) => x.key === measure)
  return `${m?.label ?? measure} ${high ? 'high' : 'low'}`
}
