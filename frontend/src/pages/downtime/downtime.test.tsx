import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import type { Entry, Episode, FormBatch } from '../../api/downtime'
import { FACILITY_A, fakeBackend, me, renderApp } from '../../test/render'

const USER = { id: 'u1', display_name: 'Test Keyer' }
const EPISODE: Episode = {
  id: 'ep1',
  facility_id: FACILITY_A.id,
  episode_number: 1,
  started_at: '2026-11-23T08:00:00Z',
  ended_at: '2026-11-23T10:00:00Z',
  affected_areas: 'Whole facility',
  reason: 'Network outage (drill)',
  status: 'ENDED',
  declared_by: USER,
  declared_at: '2026-11-23T11:00:00Z',
  closed_by: null,
  closed_at: null,
  closing_note: null,
  counts: { PENDING: 1, PROPOSED: 0, RECONCILED: 0, VOIDED: 0 },
  version: 1,
}
const REG: Entry = {
  id: 'x1',
  episode_id: 'ep1',
  facility_id: FACILITY_A.id,
  form_type: 'REGISTRATION',
  form_number: 1,
  form_label: 'REG-000001',
  registration_form_number: null,
  registration_form_label: null,
  occurred_at: '2026-11-23T09:00:00Z',
  data: { family_name: 'Paper', given_names: 'Test', birth_date: '1985-02-03', sex: 'FEMALE' },
  status: 'PENDING',
  needs_second_person: true,
  entered_by: USER,
  entered_at: '2026-11-23T11:10:00Z',
  proposal: null,
  proposed_by: null,
  proposed_at: null,
  proposal_note: null,
  decided_by: null,
  decided_at: null,
  decision_note: null,
  patient_id: null,
  encounter_id: null,
  record_id: null,
  events: [{ event_type: 'ENTERED', note: null, by: USER, occurred_at: '2026-11-23T11:10:00Z' }],
  version: 0,
}
const CANDIDATE = {
  id: 'p9',
  patient_number: 'P-000009',
  status: 'ACTIVE' as const,
  family_name: 'Paper',
  given_names: 'Test',
  birth_date: '1985-02-03',
  birth_date_estimated: false,
  sex: 'FEMALE' as const,
  details_incomplete: false,
}
const CLERK = me({
  id: 'clerk',
  permissions: ['DOWNTIME_RECORD', 'PATIENT_READ', 'ENCOUNTER_READ'],
  facilities: [FACILITY_A],
})

describe('downtime', () => {
  it('prints one numbered page per issued form', async () => {
    const batch: FormBatch = {
      id: 'b1',
      facility_id: FACILITY_A.id,
      facility_code: 'TEST-A',
      facility_name: FACILITY_A.name,
      form_type: 'TRIAGE',
      first_number: 4,
      last_number: 6,
      first_label: 'TRI-000004',
      last_label: 'TRI-000006',
      issued_by: USER,
      issued_at: '2026-11-23T07:00:00Z',
    }
    fakeBackend({
      'GET /api/v1/me': { body: me({ permissions: ['DOWNTIME_MANAGE'], facilities: [FACILITY_A] }) },
      'GET /api/v1/downtime/forms/b1': { body: batch },
    })
    const view = renderApp('/downtime/forms/b1/print')
    expect(await screen.findByRole('region', { name: 'Form TRI-000004' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Form TRI-000006' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Form TRI-000007' })).not.toBeInTheDocument()
    expect(screen.getAllByText("Patient's registration form number (REG-…)")).toHaveLength(3)
    expect(
      (await axe.run(view.container, { rules: { 'color-contrast': { enabled: false } } })).violations.map((v) => v.id),
    ).toEqual([])
  }, 20_000)

  it('keys a triage form exactly as written, with the patient’s registration form number', async () => {
    let sent: Record<string, unknown> | null = null
    fakeBackend({
      'GET /api/v1/me': { body: CLERK },
      'GET /api/v1/downtime/episodes/ep1': { body: EPISODE },
      'GET /api/v1/downtime/episodes/ep1/entries': { body: [] },
      [`GET /api/v1/downtime/form-options?facility_id=${encodeURIComponent(FACILITY_A.id)}`]: {
        body: { departments: [], catalog_items: [], payment_methods: [] },
      },
      'POST /api/v1/downtime/episodes/ep1/entries': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { status: 201, body: { ...REG, form_type: 'TRIAGE', form_label: 'TRI-000002' } }
      },
    })
    renderApp('/downtime/episodes/ep1')
    const form = await screen.findByRole('form', { name: 'Key a paper form' })
    const select = within(form)
      .getAllByLabelText(/^Form$/)
      .find((el) => el.tagName === 'INPUT')
    await userEvent.click(select!)
    await userEvent.click(await screen.findByRole('option', { name: 'Triage + vitals' }))
    await userEvent.type(within(form).getByRole('textbox', { name: /Form number/ }), '2')
    await userEvent.type(within(form).getByRole('textbox', { name: /Registration form number/ }), '1')
    await userEvent.type(within(form).getByLabelText(/Time on the paper/), '2026-11-23T09:30')
    await userEvent.type(within(form).getByRole('textbox', { name: 'Pulse (bpm)' }), '88')
    await userEvent.click(within(form).getByRole('button', { name: 'Key form' }))
    await waitFor(() =>
      expect(sent).toMatchObject({
        form_type: 'TRIAGE',
        form_number: 2,
        registration_form_number: 1,
        triage: { pulse_bpm: '88' },
      }),
    )
    expect(await screen.findByText('TRI-000002 is in the queue.')).toBeInTheDocument()
  }, 20_000)

  it('a new patient can be proposed only after reviewing possible existing records', async () => {
    let sent: Record<string, unknown> | null = null
    fakeBackend({
      'GET /api/v1/me': { body: CLERK },
      'GET /api/v1/downtime/episodes/ep1': { body: EPISODE },
      'GET /api/v1/downtime/episodes/ep1/entries': { body: [REG] },
      [`GET /api/v1/downtime/form-options?facility_id=${encodeURIComponent(FACILITY_A.id)}`]: {
        body: { departments: [], catalog_items: [], payment_methods: [] },
      },
      'GET /api/v1/downtime/entries/x1': { body: REG },
      'GET /api/v1/downtime/entries/x1/candidates': {
        body: {
          written_number_match: null,
          candidates: [{ patient: CANDIDATE, score: 90, band: 'LIKELY', evidence: [] }],
        },
      },
      'POST /api/v1/downtime/entries/x1/propose': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { body: { ...REG, status: 'PROPOSED' } }
      },
    })
    const view = renderApp('/downtime/episodes/ep1')
    await userEvent.click(await screen.findByRole('button', { name: 'REG-000001' }))
    await screen.findByRole('radio', { name: /register a new patient/ })
    expect(
      (await axe.run(view.container, { rules: { 'color-contrast': { enabled: false } } })).violations.map((v) => v.id),
    ).toEqual([])
    const section = await screen.findByRole('region', { name: 'Propose reconciliation of REG-000001' })
    await userEvent.click(await within(section).findByRole('radio', { name: /register a new patient/ }))
    await userEvent.type(within(section).getByRole('textbox', { name: /Note for the approver/ }), 'Not the same')
    const propose = within(section).getByRole('button', { name: 'Propose' })
    expect(propose).toBeDisabled()
    await userEvent.click(within(section).getByRole('checkbox', { name: /I reviewed all 1 possible/ }))
    expect(propose).toBeDisabled()
    await userEvent.type(within(section).getByRole('textbox', { name: /different person/ }), 'Different mother')
    await userEvent.click(propose)
    await waitFor(() =>
      expect(sent).toEqual({
        version: 0,
        action: 'APPLY',
        note: 'Not the same',
        reviewed_candidate_ids: ['p9'],
        duplicate_reason: 'Different mother',
      }),
    )
  }, 20_000)

  it('another person approves a proposal with a note; the proposer cannot', async () => {
    let sent: unknown = null
    const proposed = (by: { id: string; display_name: string }): Entry => ({
      ...REG,
      status: 'PROPOSED',
      proposal: { action: 'APPLY', patient_id: 'p9', patient_label: 'P0000009-1 · Synthetic, Nine · 1970-01-01' },
      proposed_by: by,
      proposed_at: '2026-11-23T11:20:00Z',
      proposal_note: 'Same person',
      version: 1,
    })
    const routes = (entry: Entry) => ({
      'GET /api/v1/me': { body: CLERK },
      'GET /api/v1/downtime/episodes/ep1': { body: EPISODE },
      'GET /api/v1/downtime/episodes/ep1/entries': { body: [entry] },
      [`GET /api/v1/downtime/form-options?facility_id=${encodeURIComponent(FACILITY_A.id)}`]: {
        body: { departments: [], catalog_items: [], payment_methods: [] },
      },
      'GET /api/v1/downtime/entries/x1': { body: entry },
      'POST /api/v1/downtime/entries/x1/approve': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { body: { ...entry, status: 'RECONCILED' } }
      },
    })
    fakeBackend(routes(proposed({ id: 'clerk', display_name: 'Me' })))
    const view = renderApp('/downtime/episodes/ep1')
    await userEvent.click(await screen.findByRole('button', { name: 'REG-000001' }))
    expect(await screen.findByText('Your proposal: another person approves.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    view.unmount()

    fakeBackend(routes(proposed({ id: 'other', display_name: 'Other Clerk' })))
    renderApp('/downtime/episodes/ep1')
    await userEvent.click(await screen.findByRole('button', { name: 'REG-000001' }))
    expect(
      await screen.findByText(
        /match to existing patient P0000009-1 · Synthetic, Nine · 1970-01-01 and open a new visit/,
      ),
    ).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Approve this proposal?' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Checked against paper')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(sent).toEqual({ version: 1, note: 'Checked against paper' }))
  }, 20_000)

  it('has no automated accessibility violations', async () => {
    fakeBackend({
      'GET /api/v1/me': { body: me({ permissions: ['DOWNTIME_MANAGE', 'DOWNTIME_RECORD'], facilities: [FACILITY_A] }) },
      'GET /api/v1/downtime/episodes': { body: [EPISODE] },
      [`GET /api/v1/downtime/forms?facility_id=${encodeURIComponent(FACILITY_A.id)}`]: { body: [] },
    })
    const view = renderApp('/downtime')
    await screen.findByRole('table', { name: 'Downtime episodes' })
    await screen.findByText('No forms issued yet.')
    const rules = { rules: { 'color-contrast': { enabled: false } } }
    expect((await axe.run(view.container, rules)).violations.map((v) => v.id)).toEqual([])
  }, 20_000)
})
