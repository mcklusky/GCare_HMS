import { apiFetch } from './client'

/** Imaging archive status and study look-up (backend ImagingArchiveController; docs/24). */

export interface ArchiveStatus {
  configured: boolean
  reachable: boolean
  latency_ms: number
  message: string
  viewer_configured: boolean
  checked_at: string
}

export interface ArchiveStudy {
  study_instance_uid: string
  accession_number: string | null
  patient_id: string | null
  modalities: string[]
  study_date: string | null
  description: string | null
  series_count: number | null
  instance_count: number | null
  viewer_url: string | null
}

export const getArchiveStatus = (signal?: AbortSignal) =>
  apiFetch<ArchiveStatus>('/api/v1/imaging/archive/status', { signal })

export const findArchiveStudies = (accession: string, signal?: AbortSignal) =>
  apiFetch<ArchiveStudy[]>(`/api/v1/imaging/archive/studies?accession_number=${encodeURIComponent(accession)}`, {
    signal,
  })
