import type { Diagnosis } from './clinical'
import { apiFetch } from './client'
import type { EncounterSummary, Observation, UserRef } from './encounters'
import type { PatientSummary } from './patients'

/** Patient chart and clinician workspace (backend ChartController; task 3.4). */

export interface AllergyNote {
  note: string
  encounter_id: string
  recorded_by: UserRef
  recorded_at: string
}

/** Sections are null when the user may not see them, empty when nothing is recorded. */
export interface Chart {
  patient_id: string
  visits: EncounterSummary[]
  diagnoses: Diagnosis[] | null
  latest_observation: Observation | null
  reported_allergies: AllergyNote[] | null
}

export interface DraftRef {
  note_id: string
  encounter_id: string
  patient: PatientSummary
  amendment: boolean
  updated_at: string
}

export interface Workspace {
  in_consultation: EncounterSummary[]
  drafts: DraftRef[]
}

export const getChart = (patientId: string, signal?: AbortSignal) =>
  apiFetch<Chart>(`/api/v1/patients/${encodeURIComponent(patientId)}/chart`, { signal })

export const getWorkspace = (signal?: AbortSignal) => apiFetch<Workspace>('/api/v1/workspace', { signal })
