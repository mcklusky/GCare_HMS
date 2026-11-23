import { apiFetch } from './client'

/** Versioned configuration with two-person approval, and facility branding (backend ConfigController; docs/21). */

export type VersionStatus = 'PROPOSED' | 'ACTIVE' | 'SUPERSEDED' | 'REJECTED'

export interface SettingInfo {
  key: string
  title: string
  description: string
  scope: 'ORGANIZATION' | 'FACILITY'
  permission: string
}

export interface SettingVersion {
  id: string
  key: string
  facility_id: string | null
  version_number: number
  value: unknown
  status: VersionStatus
  reason: string
  proposed_by: { id: string; display_name: string }
  proposed_at: string
  decided_by: { id: string; display_name: string } | null
  decided_at: string | null
  decision_note: string | null
  version: number
}

export interface Branding {
  facility_id: string
  display_name: string
  address_lines: string[]
  phone: string | null
  registration_number: string | null
  receipt_footer: string | null
  logo_id: string | null
  configured: boolean
}

export interface TriageValue {
  urgency_levels: { code: string; label: string; rank: number }[]
  alarm_limits: Record<string, { low?: number | string; high?: number | string }>
}

export const MEASURES = [
  ['TEMPERATURE_C', 'Temperature (°C)'],
  ['PULSE_BPM', 'Pulse (bpm)'],
  ['RESPIRATORY_RATE', 'Respiratory rate (/min)'],
  ['SYSTOLIC_MMHG', 'Systolic BP (mmHg)'],
  ['DIASTOLIC_MMHG', 'Diastolic BP (mmHg)'],
  ['SPO2_PCT', 'SpO₂ (%)'],
] as const

export const STATUS_LABEL: Record<VersionStatus, string> = {
  PROPOSED: 'Waiting for approval',
  ACTIVE: 'In use',
  SUPERSEDED: 'Replaced',
  REJECTED: 'Rejected',
}

const enc = encodeURIComponent

export const listSettings = (signal?: AbortSignal) => apiFetch<SettingInfo[]>('/api/v1/settings', { signal })

export const listPendingSettings = (signal?: AbortSignal) =>
  apiFetch<SettingVersion[]>('/api/v1/settings/pending', { signal })

export const listVersions = (key: string, facilityId: string | null, signal?: AbortSignal) =>
  apiFetch<SettingVersion[]>(
    `/api/v1/settings/${enc(key)}/versions${facilityId ? `?facility_id=${enc(facilityId)}` : ''}`,
    { signal },
  )

export const proposeSetting = (key: string, facilityId: string | null, value: unknown, reason: string) =>
  apiFetch<SettingVersion>(`/api/v1/settings/${enc(key)}/versions`, {
    method: 'POST',
    body: { facility_id: facilityId ?? undefined, value, reason },
  })

export const decideSetting = (v: SettingVersion, decision: 'approve' | 'reject', note: string) =>
  apiFetch<SettingVersion>(`/api/v1/settings/versions/${enc(v.id)}/${decision}`, {
    method: 'POST',
    body: { version: v.version, note },
  })

export const getBranding = (facilityId: string, signal?: AbortSignal) =>
  apiFetch<Branding>(`/api/v1/facilities/${enc(facilityId)}/branding`, { signal })

export const uploadLogo = (facilityId: string, contentType: string, dataBase64: string) =>
  apiFetch<{ id: string; facility_id: string }>(`/api/v1/facilities/${enc(facilityId)}/logos`, {
    method: 'POST',
    body: { content_type: contentType, data_base64: dataBase64 },
  })

export const logoUrl = (logoId: string) => `/api/v1/facility-logos/${enc(logoId)}`
