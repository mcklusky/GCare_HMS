import { ApiRequestError, apiFetch } from './client'

/** Patient API (backend patient module; rules in docs/17-PATIENT-IDENTITY-POLICY.md, provisional). */

export type Sex = 'FEMALE' | 'MALE' | 'OTHER' | 'UNKNOWN'
export type IdentifierType = 'NATIONAL_ID' | 'PASSPORT' | 'INSURANCE' | 'OTHER'
export type RegistrationSource = 'WALK_IN' | 'REFERRAL' | 'EMERGENCY' | 'OTHER'
export type Band = 'LIKELY' | 'POSSIBLE'
export type Evidence =
  | 'IDENTIFIER'
  | 'IDENTIFIER_CONFLICT'
  | 'PHONE'
  | 'FAMILY_NAME'
  | 'FAMILY_NAME_SIMILAR'
  | 'GIVEN_NAME'
  | 'GIVEN_NAME_SIMILAR'
  | 'NAMES_SWAPPED'
  | 'BIRTH_DATE'
  | 'BIRTH_YEAR'
  | 'SEX'
  | 'SEX_DIFFERENT'

export interface PatientSummary {
  id: string
  patient_number: string
  status: 'ACTIVE' | 'LINKED'
  family_name: string | null
  given_names: string | null
  birth_date: string | null
  birth_date_estimated: boolean
  sex: Sex
  details_incomplete: boolean
}

export interface Identifier {
  id: string
  type: IdentifierType
  value: string
  issuer: string | null
  verified: boolean
  source: string
  created_at: string
}

export interface PatientDetail extends PatientSummary {
  phone: string | null
  registration_facility_id: string
  registration_source: RegistrationSource
  identifiers: Identifier[]
  created_at: string
  version: number
}

export interface DuplicateCandidate {
  patient: PatientSummary
  score: number
  band: Band
  evidence: Evidence[]
}

export interface PatientChange {
  id: string
  patient_version: number
  changes: Record<string, { from: unknown; to: unknown }>
  reason: string
  changed_by: string
  changed_at: string
}

export interface DuplicateFlag {
  id: string
  status: 'OPEN' | 'NOT_DUPLICATE' | 'LINK_REQUESTED'
  origin: 'REGISTERED_DESPITE_MATCH' | 'STAFF_REPORT'
  score: number
  reason: string
  patient: PatientSummary
  candidate: PatientSummary
  created_by: string
  created_at: string
  version: number
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export interface IdentifierInput {
  type: IdentifierType
  value: string
  issuer?: string
  verified: boolean
}

export interface RegisterRequest {
  registration_facility_id: string
  registration_source: RegistrationSource
  family_name?: string
  given_names?: string
  birth_date?: string
  birth_date_estimated?: boolean
  sex: Sex
  phone?: string
  identifiers: IdentifierInput[]
  duplicate_review?: { reviewed_candidate_ids: string[]; reason?: string }
}

export interface SearchCriteria {
  number?: string
  name?: string
  birth_year?: string
  birth_date?: string
  sex?: Sex | ''
  phone?: string
  identifier_type?: IdentifierType | ''
  identifier_value?: string
}

export function searchPatients(criteria: SearchCriteria, signal?: AbortSignal) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(criteria)) {
    if (typeof value === 'string' && value.trim() !== '') params.set(key, value.trim())
  }
  params.set('size', '25')
  return apiFetch<Page<PatientSummary>>(`/api/v1/patients?${params}`, { signal })
}

export const registerPatient = (body: RegisterRequest) =>
  apiFetch<PatientDetail>('/api/v1/patients', { method: 'POST', body })

export const getPatient = (id: string, signal?: AbortSignal) =>
  apiFetch<PatientDetail>(`/api/v1/patients/${encodeURIComponent(id)}`, { signal })

export const getHistory = (id: string, signal?: AbortSignal) =>
  apiFetch<PatientChange[]>(`/api/v1/patients/${encodeURIComponent(id)}/history`, { signal })

export const getPossibleDuplicates = (id: string, signal?: AbortSignal) =>
  apiFetch<DuplicateCandidate[]>(`/api/v1/patients/${encodeURIComponent(id)}/possible-duplicates`, { signal })

export const correctPatient = (id: string, version: number, reason: string, changes: Record<string, unknown>) =>
  apiFetch<PatientDetail>(`/api/v1/patients/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: { version, reason, changes },
  })

export const reportDuplicate = (id: string, candidateId: string, reason: string) =>
  apiFetch<void>(`/api/v1/patients/${encodeURIComponent(id)}/duplicate-reports`, {
    method: 'POST',
    body: { candidate_patient_id: candidateId, reason },
  })

export const listDuplicateFlags = (signal?: AbortSignal) =>
  apiFetch<Page<DuplicateFlag>>('/api/v1/patient-duplicate-flags?status=OPEN&size=50', { signal })

export const markNotDuplicate = (flag: DuplicateFlag, note: string) =>
  apiFetch<DuplicateFlag>(`/api/v1/patient-duplicate-flags/${encodeURIComponent(flag.id)}/not-duplicate`, {
    method: 'POST',
    body: { version: flag.version, note },
  })

/** Candidates from a 409 DUPLICATE_CANDIDATES response, or null for any other error. */
export function duplicateCandidatesOf(error: unknown): DuplicateCandidate[] | null {
  if (error instanceof ApiRequestError && error.status === 409 && error.code === 'DUPLICATE_CANDIDATES') {
    const body = error.body as { candidates?: DuplicateCandidate[] } | null
    return body?.candidates ?? []
  }
  return null
}

// --- display helpers ----------------------------------------------------------------------------

export const SEX_LABEL: Record<Sex, string> = { FEMALE: 'Female', MALE: 'Male', OTHER: 'Other', UNKNOWN: 'Unknown' }

export const IDENTIFIER_LABEL: Record<IdentifierType, string> = {
  NATIONAL_ID: 'National ID',
  PASSPORT: 'Passport',
  INSURANCE: 'Insurance number',
  OTHER: 'Other ID',
}

export const SOURCE_LABEL: Record<RegistrationSource, string> = {
  WALK_IN: 'Walk-in',
  REFERRAL: 'Referral',
  EMERGENCY: 'Emergency',
  OTHER: 'Other',
}

export const EVIDENCE_LABEL: Record<Evidence, string> = {
  IDENTIFIER: 'Same ID document',
  IDENTIFIER_CONFLICT: 'Different ID number of the same type',
  PHONE: 'Same phone number',
  FAMILY_NAME: 'Same family name',
  FAMILY_NAME_SIMILAR: 'Similar family name',
  GIVEN_NAME: 'Same given name',
  GIVEN_NAME_SIMILAR: 'Similar given name',
  NAMES_SWAPPED: 'Names in swapped fields',
  BIRTH_DATE: 'Same birth date',
  BIRTH_YEAR: 'Close birth date',
  SEX: 'Same sex',
  SEX_DIFFERENT: 'Different sex',
}

export const FIELD_LABEL: Record<string, string> = {
  family_name: 'Family name',
  given_names: 'Given names',
  birth_date: 'Date of birth',
  birth_date_estimated: 'Birth date estimated',
  sex: 'Sex',
  phone: 'Phone',
}

/** "FAMILY, Given" or a clear placeholder; never an empty string. */
export function displayName(p: Pick<PatientSummary, 'family_name' | 'given_names'>): string {
  if (!p.family_name && !p.given_names) return 'Unknown name'
  return [p.family_name?.toUpperCase(), p.given_names].filter(Boolean).join(', ')
}

/** Age in whole years at `today`, or null. */
export function ageInYears(birthDate: string | null, today = new Date()): number | null {
  if (!birthDate) return null
  const [y, m, d] = birthDate.split('-').map(Number)
  let age = today.getFullYear() - y
  if (today.getMonth() + 1 < m || (today.getMonth() + 1 === m && today.getDate() < d)) age--
  return age
}

/** "1990-05-14 (35 y)" / "about 1990 (about 35 y)" / "Unknown". */
export function birthLabel(p: Pick<PatientSummary, 'birth_date' | 'birth_date_estimated'>): string {
  if (!p.birth_date) return 'Unknown'
  const age = ageInYears(p.birth_date)
  return p.birth_date_estimated
    ? `about ${p.birth_date.slice(0, 4)} (about ${age} y, estimated)`
    : `${p.birth_date} (${age} y)`
}
