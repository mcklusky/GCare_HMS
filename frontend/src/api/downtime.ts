import { apiFetch } from './client'
import type { UserRef } from './encounters'
import type { InvoiceSummary } from './invoices'
import type { DuplicateCandidate, PatientSummary, RegistrationSource, Sex } from './patients'
import { zonedLocalToIso } from '../components/time'

/** Downtime episodes, numbered paper forms and reconciliation (backend DowntimeController; docs/22). */

export type FormType = 'REGISTRATION' | 'TRIAGE' | 'ORDER' | 'PAYMENT'
export type EpisodeStatus = 'ACTIVE' | 'ENDED' | 'CLOSED'
export type EntryStatus = 'PENDING' | 'PROPOSED' | 'RECONCILED' | 'VOIDED'

export const FORM_TYPES: { value: FormType; label: string }[] = [
  { value: 'REGISTRATION', label: 'Registration + visit' },
  { value: 'TRIAGE', label: 'Triage + vitals' },
  { value: 'ORDER', label: 'Order' },
  { value: 'PAYMENT', label: 'Payment receipt' },
]

export const formTypeLabel = (t: FormType) => FORM_TYPES.find((f) => f.value === t)?.label ?? t

export const ENTRY_STATUS_LABEL: Record<EntryStatus, string> = {
  PENDING: 'To reconcile',
  PROPOSED: 'Waiting for approval',
  RECONCILED: 'Reconciled',
  VOIDED: 'Voided',
}

export const EPISODE_STATUS_LABEL: Record<EpisodeStatus, string> = {
  ACTIVE: 'Down now',
  ENDED: 'Ended — reconciling',
  CLOSED: 'Closed',
}

export interface Episode {
  id: string
  facility_id: string
  episode_number: number
  started_at: string
  ended_at: string | null
  affected_areas: string
  reason: string
  status: EpisodeStatus
  declared_by: UserRef
  declared_at: string
  closed_by: UserRef | null
  closed_at: string | null
  closing_note: string | null
  counts: Record<EntryStatus, number>
  version: number
}

export interface FormBatch {
  id: string
  facility_id: string
  facility_code: string
  facility_name: string
  form_type: FormType
  first_number: number
  last_number: number
  first_label: string
  last_label: string
  issued_by: UserRef
  issued_at: string
}

export interface Option {
  id: string
  code: string
  name: string
}

export interface FormOptions {
  departments: Option[]
  catalog_items: Option[]
  payment_methods: Option[]
}

export interface RegistrationForm {
  family_name?: string | null
  given_names?: string | null
  birth_date?: string | null
  birth_date_estimated?: boolean
  sex?: Sex
  phone?: string | null
  written_patient_number?: string | null
  registration_source: RegistrationSource
  department_id: string
  encounter_type: 'NEW_VISIT' | 'FOLLOW_UP'
}

export type TriageForm = Record<string, string | number | null>

export interface OrderForm {
  catalog_item_id: string
  priority: 'ROUTINE' | 'URGENT'
  indication?: string | null
  ordered_by_name: string
}

export interface PaymentForm {
  method_id: string
  amount: string
  currency: string
}

export interface EntryRequest {
  form_type: FormType
  form_number: number
  registration_form_number?: number
  occurred_at: string
  registration?: RegistrationForm
  triage?: TriageForm
  order?: OrderForm
  payment?: PaymentForm
}

export interface EntryEvent {
  event_type: 'ENTERED' | 'PROPOSED' | 'SENT_BACK' | 'RECONCILED' | 'VOIDED'
  note: string | null
  by: UserRef
  occurred_at: string
}

export interface Entry {
  id: string
  episode_id: string
  facility_id: string
  form_type: FormType
  form_number: number
  form_label: string
  registration_form_number: number | null
  registration_form_label: string | null
  occurred_at: string
  /** Null when your role may not see this kind of record (and you did not key it). */
  data: Record<string, unknown> | null
  status: EntryStatus
  needs_second_person: boolean
  entered_by: UserRef
  entered_at: string
  proposal: Record<string, unknown> | null
  proposed_by: UserRef | null
  proposed_at: string | null
  proposal_note: string | null
  decided_by: UserRef | null
  decided_at: string | null
  decision_note: string | null
  patient_id: string | null
  encounter_id: string | null
  record_id: string | null
  events: EntryEvent[] | null
  version: number
}

export interface ProposeRequest {
  action: 'APPLY' | 'VOID'
  note: string
  patient_id?: string
  reviewed_candidate_ids?: string[]
  duplicate_reason?: string
  encounter_id?: string
  invoice_id?: string
}

export interface Candidates {
  written_number_match: PatientSummary | null
  candidates: DuplicateCandidate[]
}

export interface DowntimeSource {
  entry_id: string
  form_type: FormType
  form_label: string
  episode_number: number
  occurred_at: string
  reconciled_at: string
  record_id: string | null
}

const enc = encodeURIComponent
const BASE = '/api/v1/downtime'

export const listEpisodes = (signal?: AbortSignal) => apiFetch<Episode[]>(`${BASE}/episodes`, { signal })

export const getEpisode = (id: string, signal?: AbortSignal) =>
  apiFetch<Episode>(`${BASE}/episodes/${enc(id)}`, { signal })

export const declareEpisode = (body: {
  facility_id: string
  started_at: string
  ended_at?: string
  affected_areas: string
  reason: string
}) => apiFetch<Episode>(`${BASE}/episodes`, { method: 'POST', body })

export const endEpisode = (e: Episode, endedAt: string) =>
  apiFetch<Episode>(`${BASE}/episodes/${enc(e.id)}/end`, {
    method: 'POST',
    body: { version: e.version, ended_at: endedAt },
  })

export const closeEpisode = (e: Episode, note: string) =>
  apiFetch<Episode>(`${BASE}/episodes/${enc(e.id)}/close`, { method: 'POST', body: { version: e.version, note } })

export const issueForms = (facilityId: string, formType: FormType, count: number) =>
  apiFetch<FormBatch>(`${BASE}/forms`, {
    method: 'POST',
    body: { facility_id: facilityId, form_type: formType, count },
  })

export const listBatches = (facilityId: string, signal?: AbortSignal) =>
  apiFetch<FormBatch[]>(`${BASE}/forms?facility_id=${enc(facilityId)}`, { signal })

export const getBatch = (id: string, signal?: AbortSignal) =>
  apiFetch<FormBatch>(`${BASE}/forms/${enc(id)}`, { signal })

export const getFormOptions = (facilityId: string, signal?: AbortSignal) =>
  apiFetch<FormOptions>(`${BASE}/form-options?facility_id=${enc(facilityId)}`, { signal })

export const listEntries = (episodeId: string, signal?: AbortSignal) =>
  apiFetch<Entry[]>(`${BASE}/episodes/${enc(episodeId)}/entries`, { signal })

export const keyEntry = (episodeId: string, body: EntryRequest) =>
  apiFetch<Entry>(`${BASE}/episodes/${enc(episodeId)}/entries`, { method: 'POST', body })

export const getEntry = (id: string, signal?: AbortSignal) => apiFetch<Entry>(`${BASE}/entries/${enc(id)}`, { signal })

export const getCandidates = (id: string, signal?: AbortSignal) =>
  apiFetch<Candidates>(`${BASE}/entries/${enc(id)}/candidates`, { signal })

export const proposeEntry = (e: Entry, body: ProposeRequest) =>
  apiFetch<Entry>(`${BASE}/entries/${enc(e.id)}/propose`, { method: 'POST', body: { version: e.version, ...body } })

export type EntryDecision = 'approve' | 'send-back' | 'reconcile' | 'void'

export const decideEntry = (e: Entry, decision: EntryDecision, note: string) =>
  apiFetch<Entry>(`${BASE}/entries/${enc(e.id)}/${decision}`, {
    method: 'POST',
    body: { version: e.version, note },
  })

export const listDowntimeSources = (encounterId: string, signal?: AbortSignal) =>
  apiFetch<DowntimeSource[]>(`/api/v1/encounters/${enc(encounterId)}/downtime-sources`, { signal })

/** A datetime-local input value (facility time) as an ISO instant. */
export const localToIso = (value: string) => zonedLocalToIso(value)

/** Invoices of a visit (needs INVOICE_READ), to pick the one a paper payment belongs to. */
export const listVisitInvoices = (encounterId: string, signal?: AbortSignal) =>
  apiFetch<InvoiceSummary[]>(`/api/v1/encounters/${enc(encounterId)}/invoices`, { signal })
