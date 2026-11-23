import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import type { Study, WorklistItem } from '../../api/imagingWork'
import { FACILITY_A, fakeBackend, me, renderApp } from '../../test/render'

const RAD = me({
  id: 'rad1',
  permissions: ['PATIENT_READ', 'ORDER_READ', 'ORDER_PROCESS', 'IMAGING_WORKLIST', 'IMAGING_LINK'],
  facilities: [FACILITY_A],
})
const ITEM: WorklistItem = {
  order_id: 'o1',
  encounter_id: 'e1',
  facility_id: FACILITY_A.id,
  accession_number: 'ACC000000001',
  patient: {
    id: 'p1',
    patient_number: 'P0000001-5',
    status: 'ACTIVE',
    family_name: 'Synthetic',
    given_names: 'Test',
    birth_date: '1970-01-05',
    birth_date_estimated: false,
    sex: 'MALE',
    details_incomplete: false,
  },
  item_code: 'T-XR',
  item_name: 'Test chest X-ray',
  priority: 'URGENT',
  indication: null,
  order_status: 'REQUESTED',
  requested_at: '2026-11-23T08:00:00Z',
  arrived_at: null,
  arrived_by: null,
  examined_at: null,
  examined_by: null,
  step: 'ORDERED',
  studies: [],
  version: 0,
}
const STUDY: Study = {
  id: 's1',
  study_instance_uid: '1.2.3',
  accession_number: 'ACC000000001',
  dicom_patient_id: 'TYPO-1',
  modalities: ['CR'],
  study_date: '2026-11-23',
  description: 'Chest',
  series_count: 1,
  instance_count: 2,
  first_seen_at: '2026-11-23T09:00:00Z',
  status: 'UNMATCHED',
  unmatched_reason: "The patient ID on the study differs from the order's patient number",
  order_id: null,
  link_method: null,
  linked_at: null,
  linked_by: null,
  proposed_order_id: null,
  proposed_accession_number: null,
  proposed_by: null,
  proposal_note: null,
  viewer_url: null,
  version: 0,
}
const rules = { rules: { 'color-contrast': { enabled: false } } }

describe('imaging worklist', () => {
  it('shows accession numbers, records arrival and opens linked studies', async () => {
    let sent: unknown = null
    fakeBackend({
      'GET /api/v1/me': { body: RAD },
      'GET /api/v1/imaging/worklist?include_finished=false': {
        body: [
          ITEM,
          {
            ...ITEM,
            order_id: 'o2',
            accession_number: 'ACC000000002',
            priority: 'ROUTINE',
            step: 'IMAGES_AVAILABLE',
            studies: [{ ...STUDY, status: 'LINKED', viewer_url: 'https://viewer.test.invalid/?study=1.2.3' }],
          },
        ],
      },
      'POST /api/v1/imaging/orders/o1/arrived': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { body: { ...ITEM, step: 'ARRIVED' } }
      },
    })
    const view = renderApp('/imaging')
    const table = await screen.findByRole('table', { name: 'Imaging orders' })
    expect(within(table).getByText('ACC000000001')).toBeInTheDocument()
    expect(within(table).getByRole('link', { name: 'Open CR (2 images)' })).toHaveAttribute(
      'href',
      'https://viewer.test.invalid/?study=1.2.3',
    )
    expect((await axe.run(view.container, rules)).violations.map((v) => v.id)).toEqual([])
    await userEvent.click(within(table).getByRole('button', { name: 'Patient arrived' }))
    const dialog = await screen.findByRole('dialog', { name: 'Record that the patient arrived?' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Record' }))
    await waitFor(() => expect(sent).toEqual({ version: 0 }))
  }, 20_000)

  it('a study that did not match is proposed with a reason; the proposer cannot approve', async () => {
    let sent: unknown = null
    fakeBackend({
      'GET /api/v1/me': { body: RAD },
      'GET /api/v1/imaging/studies/unlinked': {
        body: [
          STUDY,
          {
            ...STUDY,
            id: 's2',
            status: 'PROPOSED',
            proposed_order_id: 'o1',
            proposed_accession_number: 'ACC000000001',
            proposed_by: { id: 'rad1', display_name: 'Me' },
            proposal_note: 'Typo at scanner',
            unmatched_reason: null,
          },
        ],
      },
      'POST /api/v1/imaging/studies/s1/propose': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { body: { ...STUDY, status: 'PROPOSED' } }
      },
    })
    const view = renderApp('/imaging/studies')
    const table = await screen.findByRole('table', { name: 'Studies waiting for review' })
    expect(within(table).getByText(/patient ID on the study differs/)).toBeInTheDocument()
    expect(within(table).getByText('Your proposal: another person approves.')).toBeInTheDocument()
    expect(within(table).queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    await userEvent.click(within(table).getByRole('button', { name: 'Propose link' }))
    const form = await screen.findByRole('form', { name: 'Propose a link' })
    expect((await axe.run(view.container, rules)).violations.map((v) => v.id)).toEqual([])
    await userEvent.type(within(form).getByRole('textbox', { name: /Why this is the right order/ }), 'Checked')
    await userEvent.click(within(form).getByRole('button', { name: 'Propose' }))
    await waitFor(() => expect(sent).toEqual({ version: 0, accession_number: 'ACC000000001', note: 'Checked' }))
  }, 20_000)
})
