import { apiFetch } from './client'
import type { UserRef } from './encounters'

/** Clinical notes and visit diagnoses (backend ClinicalController; docs/18 §7). */

export const SECTIONS = [
  { key: 'history', label: 'History' },
  { key: 'examination', label: 'Examination' },
  { key: 'assessment', label: 'Assessment' },
  { key: 'plan', label: 'Plan' },
] as const

export type SectionKey = (typeof SECTIONS)[number]['key']
export type Sections = Record<SectionKey, string | null>

export interface Note extends Sections {
  id: string
  encounter_id: string
  status: 'DRAFT' | 'SIGNED'
  author: UserRef
  created_at: string
  updated_at: string
  signed_at: string | null
  amends_note_id: string | null
  amendment_reason: string | null
  amended_by: string[]
  version: number
}

export type Certainty = 'PROVISIONAL' | 'CONFIRMED'

export interface Diagnosis {
  id: string
  encounter_id: string
  description: string
  certainty: Certainty
  code: string | null
  code_system: string | null
  status: 'RECORDED' | 'ENTERED_IN_ERROR'
  recorded_by: UserRef
  recorded_at: string
  error_reason: string | null
  error_by: UserRef | null
  error_at: string | null
}

const enc = encodeURIComponent

export const listNotes = (encounterId: string, signal?: AbortSignal) =>
  apiFetch<Note[]>(`/api/v1/encounters/${enc(encounterId)}/notes`, { signal })

export const startNote = (
  encounterId: string,
  body: Partial<Sections> & { amends_note_id?: string; amendment_reason?: string },
) => apiFetch<Note>(`/api/v1/encounters/${enc(encounterId)}/notes`, { method: 'POST', body })

export const saveNote = (note: Pick<Note, 'id' | 'version'>, sections: Sections) =>
  apiFetch<Note>(`/api/v1/notes/${enc(note.id)}`, { method: 'PATCH', body: { version: note.version, ...sections } })

export const signNote = (note: Pick<Note, 'id' | 'version'>) =>
  apiFetch<Note>(`/api/v1/notes/${enc(note.id)}/sign`, { method: 'POST', body: { version: note.version } })

export const discardNote = (note: Pick<Note, 'id' | 'version'>) =>
  apiFetch<void>(`/api/v1/notes/${enc(note.id)}/discard`, { method: 'POST', body: { version: note.version } })

export const listDiagnoses = (encounterId: string, signal?: AbortSignal) =>
  apiFetch<Diagnosis[]>(`/api/v1/encounters/${enc(encounterId)}/diagnoses`, { signal })

export const recordDiagnosis = (encounterId: string, description: string, certainty: Certainty) =>
  apiFetch<Diagnosis>(`/api/v1/encounters/${enc(encounterId)}/diagnoses`, {
    method: 'POST',
    body: { description, certainty },
  })

export const diagnosisInError = (id: string, reason: string) =>
  apiFetch<Diagnosis>(`/api/v1/diagnoses/${enc(id)}/entered-in-error`, { method: 'POST', body: { reason } })

export const CERTAINTY_LABEL: Record<Certainty, string> = { PROVISIONAL: 'Provisional', CONFIRMED: 'Confirmed' }
