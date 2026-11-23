import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import type { CriticalResult, ImagingResult, Report } from '../../api/radiologyReports'
import { FACILITY_A, fakeBackend, me, renderApp } from '../../test/render'

const rules = { rules: { 'color-contrast': { enabled: false } } }
const PATIENT = {
  id: 'p1',
  patient_number: 'P0000001-5',
  status: 'ACTIVE' as const,
  family_name: 'Synthetic',
  given_names: 'Test',
  birth_date: '1960-02-02',
  birth_date_estimated: false,
  sex: 'FEMALE' as const,
  details_incomplete: false,
}
const RADIOLOGIST = me({
  id: 'rad',
  permissions: ['RADIOLOGY_REPORT_WRITE', 'RADIOLOGY_REPORT_READ'],
  facilities: [FACILITY_A],
})
const CLINICIAN = me({
  id: 'doc',
  permissions: ['ENCOUNTER_READ', 'RADIOLOGY_REPORT_READ', 'CRITICAL_RESULT_ACKNOWLEDGE'],
  facilities: [FACILITY_A],
})
const DRAFT: Report = {
  id: 'r1',
  order_id: 'o1',
  status: 'DRAFT',
  technique: null,
  comparison: null,
  findings: 'Test findings',
  impression: 'Test impression',
  critical: true,
  author: { id: 'rad', display_name: 'Test Radiologist' },
  created_at: '2026-11-23T10:00:00Z',
  updated_at: '2026-11-23T10:00:00Z',
  signed_at: null,
  amends_report_id: null,
  amendment_reason: null,
  critical_result: null,
  version: 1,
}
const CRITICAL: CriticalResult = {
  report_id: 'r1',
  order_id: 'o1',
  facility_id: FACILITY_A.id,
  encounter_id: 'e1',
  accession_number: 'ACC000000001',
  item_name: 'Test head CT',
  patient: PATIENT,
  flagged_by: { id: 'rad', display_name: 'Test Radiologist' },
  flagged_at: '2026-11-23T10:05:00Z',
  communicated_to: 'Dr Test (fictional)',
  communication_method: 'PHONE',
  communicated_at: '2026-11-23T10:04:00Z',
  communication_note: null,
  acknowledged_by: null,
  acknowledged_at: null,
  acknowledgement_note: null,
  version: 0,
}

describe('radiology reports', () => {
  it('a critical report can be signed only with the documented direct contact', async () => {
    let sent: Record<string, unknown> | null = null
    fakeBackend({
      'GET /api/v1/me': { body: RADIOLOGIST },
      'GET /api/v1/imaging/orders/o1/reports': { body: [DRAFT] },
      'POST /api/v1/radiology-reports/r1/sign': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { body: { ...DRAFT, status: 'SIGNED' } }
      },
    })
    const view = renderApp('/radiology/orders/o1')
    const form = await screen.findByRole('form', { name: 'Report draft' })
    expect(within(form).getByRole('textbox', { name: /Impression/ })).toHaveValue('Test impression')
    expect((await axe.run(view.container, rules)).violations.map((v) => v.id)).toEqual([])
    await userEvent.click(within(form).getByRole('button', { name: 'Sign' }))
    const dialog = await screen.findByRole('dialog', { name: 'Sign this report?' })
    const confirm = within(dialog).getByRole('button', { name: 'Sign' })
    expect(confirm).toBeDisabled()
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Told to/ }), 'Dr Test (fictional), ward')
    await userEvent.type(within(dialog).getByLabelText(/When/), '2026-11-23T10:04')
    await userEvent.click(confirm)
    await waitFor(() =>
      expect(sent).toMatchObject({
        version: 1,
        communication: { communicated_to: 'Dr Test (fictional), ward', method: 'PHONE' },
      }),
    )
  }, 20_000)

  it('the visit shows "not yet reported", signed reports and lets the clinician acknowledge a critical result', async () => {
    let sent: unknown = null
    const results: ImagingResult[] = [
      {
        order_id: 'o2',
        encounter_id: 'e1',
        accession_number: 'ACC000000002',
        item_name: 'Test chest X-ray',
        requested_at: '2026-11-23T08:00:00Z',
        step: 'IMAGES_AVAILABLE',
        report_state: 'NOT_YET_REPORTED',
        studies: [
          {
            id: 's1',
            study_instance_uid: '1.2.3',
            accession_number: 'ACC000000002',
            dicom_patient_id: 'P0000001-5',
            modalities: ['CR'],
            study_date: '2026-11-23',
            description: null,
            series_count: 1,
            instance_count: 2,
            first_seen_at: '2026-11-23T09:00:00Z',
            status: 'LINKED',
            unmatched_reason: null,
            order_id: 'o2',
            link_method: 'AUTOMATIC',
            linked_at: '2026-11-23T09:00:00Z',
            linked_by: null,
            proposed_order_id: null,
            proposed_accession_number: null,
            proposed_by: null,
            proposal_note: null,
            viewer_url: 'https://viewer.test.invalid/?study=1.2.3',
            version: 1,
          },
        ],
        report: null,
        earlier_reports: [],
      },
      {
        order_id: 'o1',
        encounter_id: 'e1',
        accession_number: 'ACC000000001',
        item_name: 'Test head CT',
        requested_at: '2026-11-23T08:00:00Z',
        step: 'EXAMINED',
        report_state: 'REPORTED',
        studies: [],
        report: { ...DRAFT, status: 'SIGNED', signed_at: '2026-11-23T10:05:00Z', critical_result: CRITICAL },
        earlier_reports: [],
      },
    ]
    fakeBackend({
      'GET /api/v1/me': { body: CLINICIAN },
      'GET /api/v1/critical-results': { body: [CRITICAL] },
      'POST /api/v1/critical-results/r1/acknowledge': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { body: { ...CRITICAL, acknowledged_at: '2026-11-23T10:30:00Z' } }
      },
      'GET /api/v1/encounters/e1/imaging-results': { body: results },
      'GET /api/v1/encounters/e1': {
        body: {
          encounter: {
            id: 'e1',
            facility_id: FACILITY_A.id,
            department: { id: 'd1', code: 'OPD', name: 'Outpatient Department' },
            patient: PATIENT,
            encounter_type: 'NEW_VISIT',
            status: 'WAITING',
            clinician: null,
            arrived_at: '2026-11-23T08:00:00Z',
            consultation_started_at: null,
            ended_at: null,
            triaged: null,
            urgency: null,
            flags: null,
            version: 0,
          },
          events: [],
        },
      },
      'GET /api/v1/encounters/e1/downtime-sources': { body: [] },
    })
    const view = renderApp('/critical-results')
    const table = await screen.findByRole('table', { name: 'Critical results not yet acknowledged' })
    expect(within(table).getByText(/Dr Test \(fictional\)/)).toBeInTheDocument()
    expect((await axe.run(view.container, rules)).violations.map((v) => v.id)).toEqual([])
    view.unmount()

    const visit = renderApp('/encounters/e1')
    const section = await screen.findByRole('region', { name: 'Test chest X-ray, accession ACC000000002' })
    expect(within(section).getByText('Not yet reported')).toBeInTheDocument()
    expect(within(section).getByRole('link', { name: /Open images .* not yet reported/ })).toHaveAttribute(
      'href',
      'https://viewer.test.invalid/?study=1.2.3',
    )
    const ct = screen.getByRole('region', { name: 'Test head CT, accession ACC000000001' })
    expect(within(ct).getByText('Critical result: not yet acknowledged')).toBeInTheDocument()
    expect(within(ct).getByText(/Test impression/)).toBeInTheDocument()
    expect((await axe.run(visit.container, rules)).violations.map((v) => v.id)).toEqual([])
    await userEvent.click(within(ct).getByRole('button', { name: 'Acknowledge' }))
    const dialog = await screen.findByRole('dialog', { name: 'Acknowledge this critical result?' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Patient recalled')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Acknowledge' }))
    await waitFor(() => expect(sent).toEqual({ version: 0, note: 'Patient recalled' }))
  }, 20_000)
})
