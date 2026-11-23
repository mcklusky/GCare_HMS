import { apiFetch } from './client'
import type { PatientSummary } from './patients'

/** Imaging worklist, radiographer steps and study linking (backend ImagingController; docs/25). */

export type Step = 'ORDERED' | 'ARRIVED' | 'EXAMINED' | 'IMAGES_AVAILABLE' | 'CANCELLED'
export type StudyStatus = 'UNMATCHED' | 'PROPOSED' | 'LINKED'

export const STEP_LABEL: Record<Step, string> = {
  ORDERED: 'Ordered',
  ARRIVED: 'Patient arrived',
  EXAMINED: 'Examination done',
  IMAGES_AVAILABLE: 'Images available',
  CANCELLED: 'Cancelled',
}

interface UserRef {
  id: string
  display_name: string
}

export interface Study {
  id: string
  study_instance_uid: string
  accession_number: string | null
  dicom_patient_id: string | null
  modalities: string[]
  study_date: string | null
  description: string | null
  series_count: number | null
  instance_count: number | null
  first_seen_at: string
  status: StudyStatus
  unmatched_reason: string | null
  order_id: string | null
  link_method: 'AUTOMATIC' | 'REVIEWED' | null
  linked_at: string | null
  linked_by: UserRef | null
  proposed_order_id: string | null
  proposed_accession_number: string | null
  proposed_by: UserRef | null
  proposal_note: string | null
  viewer_url: string | null
  version: number
}

export interface WorklistItem {
  order_id: string
  encounter_id: string
  facility_id: string
  accession_number: string
  patient: PatientSummary
  item_code: string
  item_name: string
  priority: 'ROUTINE' | 'URGENT'
  indication: string | null
  order_status: string
  requested_at: string
  arrived_at: string | null
  arrived_by: UserRef | null
  examined_at: string | null
  examined_by: UserRef | null
  step: Step
  studies: Study[]
  version: number
}

export interface StudyEvent {
  event_type: string
  order_id: string | null
  note: string | null
  by: UserRef | null
  occurred_at: string
}

const enc = encodeURIComponent

export const getWorklist = (includeFinished: boolean, signal?: AbortSignal) =>
  apiFetch<WorklistItem[]>(`/api/v1/imaging/worklist?include_finished=${includeFinished}`, { signal })

export const recordStep = (item: WorklistItem, step: 'arrived' | 'examined') =>
  apiFetch<WorklistItem>(`/api/v1/imaging/orders/${enc(item.order_id)}/${step}`, {
    method: 'POST',
    body: { version: item.version },
  })

export const checkArchive = (item: WorklistItem) =>
  apiFetch<WorklistItem>(`/api/v1/imaging/orders/${enc(item.order_id)}/check-archive`, { method: 'POST' })

export const getUnlinkedStudies = (signal?: AbortSignal) =>
  apiFetch<Study[]>('/api/v1/imaging/studies/unlinked', { signal })

export const getStudyHistory = (id: string, signal?: AbortSignal) =>
  apiFetch<StudyEvent[]>(`/api/v1/imaging/studies/${enc(id)}/history`, { signal })

export const proposeLink = (s: Study, accessionNumber: string, note: string) =>
  apiFetch<Study>(`/api/v1/imaging/studies/${enc(s.id)}/propose`, {
    method: 'POST',
    body: { version: s.version, accession_number: accessionNumber, note },
  })

export const decideStudy = (s: Study, decision: 'approve' | 'send-back' | 'unlink', note: string) =>
  apiFetch<Study>(`/api/v1/imaging/studies/${enc(s.id)}/${decision}`, {
    method: 'POST',
    body: { version: s.version, note },
  })
