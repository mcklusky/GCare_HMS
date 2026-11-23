import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import type { Diagnosis, Note } from '../../api/clinical'
import type { EncounterDetail } from '../../api/encounters'
import { FACILITY_A, fakeBackend, me, renderApp } from '../../test/render'

const DOCTOR = me({
  id: 'doc',
  permissions: ['ENCOUNTER_READ', 'ENCOUNTER_MANAGE', 'CLINICAL_NOTE_READ', 'CLINICAL_NOTE_WRITE'],
  facilities: [FACILITY_A],
})
const NURSE = me({ id: 'nurse', permissions: ['ENCOUNTER_READ', 'CLINICAL_NOTE_READ'], facilities: [FACILITY_A] })

const DETAIL: EncounterDetail = {
  encounter: {
    id: 'e1',
    facility_id: FACILITY_A.id,
    department: { id: 'd', code: 'OPD', name: 'Outpatient Department' },
    patient: {
      id: 'p1',
      patient_number: 'P1000001-6',
      status: 'ACTIVE',
      family_name: 'Testperson',
      given_names: 'Alpha',
      birth_date: '1985-03-02',
      birth_date_estimated: false,
      sex: 'FEMALE',
      details_incomplete: false,
    },
    encounter_type: 'NEW_VISIT',
    status: 'IN_CONSULTATION',
    clinician: { id: 'doc', display_name: 'Test Doctor' },
    arrived_at: '2026-11-23T08:00:00Z',
    consultation_started_at: '2026-11-23T08:30:00Z',
    ended_at: null,
    triaged: null,
    urgency: null,
    flags: null,
    version: 1,
  },
  events: [],
}

const DRAFT: Note = {
  id: 'n1',
  encounter_id: 'e1',
  status: 'DRAFT',
  history: null,
  examination: null,
  assessment: null,
  plan: null,
  author: { id: 'doc', display_name: 'Test Doctor' },
  created_at: '2026-11-23T08:31:00Z',
  updated_at: '2026-11-23T08:31:00Z',
  signed_at: null,
  amends_note_id: null,
  amendment_reason: null,
  amended_by: [],
  version: 0,
}
const SIGNED: Note = {
  ...DRAFT,
  status: 'SIGNED',
  plan: 'Review in 2 weeks',
  signed_at: '2026-11-23T08:40:00Z',
  version: 2,
}

function fakes(user = DOCTOR, notes: () => Note[] = () => [], extra = {}) {
  return {
    'GET /api/v1/me': { body: user },
    'GET /api/v1/encounters/e1': { body: DETAIL },
    'GET /api/v1/encounters/e1/notes': () => ({ body: notes() }),
    'GET /api/v1/encounters/e1/diagnoses': { body: [] },
    ...extra,
  }
}

describe('clinical notes', () => {
  it('writes a draft and signs it after confirming, saving unsaved text first', async () => {
    let notes: Note[] = []
    const calls: { url: string; body: unknown }[] = []
    const record = (url: string) => (init: RequestInit) => {
      calls.push({ url, body: JSON.parse(String(init.body)) })
      return null
    }
    fakeBackend(
      fakes(DOCTOR, () => notes, {
        'POST /api/v1/encounters/e1/notes': (init: RequestInit) => {
          record('start')(init)
          notes = [DRAFT]
          return { status: 201, body: DRAFT }
        },
        'PATCH /api/v1/notes/n1': (init: RequestInit) => {
          record('save')(init)
          return { body: { ...DRAFT, history: 'Cough', version: 1 } }
        },
        'POST /api/v1/notes/n1/sign': (init: RequestInit) => {
          record('sign')(init)
          notes = [{ ...SIGNED, history: 'Cough', plan: null }]
          return { body: notes[0] }
        },
      }),
    )

    renderApp('/encounters/e1')
    await userEvent.click(await screen.findByRole('button', { name: 'Write note' }))
    const draft = await screen.findByRole('form', { name: 'Note draft' })
    await userEvent.type(within(draft).getByRole('textbox', { name: 'History' }), 'Cough')
    await userEvent.click(within(draft).getByRole('button', { name: 'Sign' }))
    const dialog = await screen.findByRole('dialog', { name: 'Sign this note?' })
    expect(dialog).toHaveTextContent('cannot be changed')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Sign note' }))

    await waitFor(() =>
      expect(calls).toEqual([
        { url: 'start', body: {} },
        { url: 'save', body: { version: 0, history: 'Cough', examination: null, assessment: null, plan: null } },
        { url: 'sign', body: { version: 1 } },
      ]),
    )
    expect(await screen.findByRole('article', { name: 'Note by Test Doctor' })).toHaveTextContent('Cough')
  })

  it('amends a signed note with a reason, starting from a copy', async () => {
    let sent: unknown = null
    fakeBackend(
      fakes(DOCTOR, () => [SIGNED], {
        'POST /api/v1/encounters/e1/notes': (init: RequestInit) => {
          sent = JSON.parse(String(init.body))
          return { status: 201, body: { ...DRAFT, id: 'n2', amends_note_id: 'n1', amendment_reason: 'Wrong interval' } }
        },
      }),
    )

    renderApp('/encounters/e1')
    const note = await screen.findByRole('article', { name: 'Note by Test Doctor' })
    await userEvent.click(within(note).getByRole('button', { name: 'Amend' }))
    const dialog = await screen.findByRole('dialog', { name: 'Amend this note?' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Wrong interval')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Start amendment' }))

    await waitFor(() =>
      expect(sent).toEqual({ amends_note_id: 'n1', amendment_reason: 'Wrong interval', plan: 'Review in 2 weeks' }),
    )
  })

  it('readers see signed notes but cannot write or amend', async () => {
    fakeBackend(fakes(NURSE, () => [SIGNED]))

    renderApp('/encounters/e1')
    const note = await screen.findByRole('article', { name: 'Note by Test Doctor' })
    expect(note).toHaveTextContent('Review in 2 weeks')
    expect(within(note).queryByRole('button', { name: 'Amend' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Write note' })).not.toBeInTheDocument()
    expect(screen.queryByRole('form', { name: 'Add diagnosis' })).not.toBeInTheDocument()
  })
})

describe('diagnoses', () => {
  it('adds a free-text diagnosis with its certainty', async () => {
    let sent: unknown = null
    const saved: Diagnosis = {
      id: 'g1',
      encounter_id: 'e1',
      description: 'Upper respiratory infection',
      certainty: 'CONFIRMED',
      code: null,
      code_system: null,
      status: 'RECORDED',
      recorded_by: { id: 'doc', display_name: 'Test Doctor' },
      recorded_at: '2026-11-23T08:45:00Z',
      error_reason: null,
      error_by: null,
      error_at: null,
    }
    fakeBackend(
      fakes(DOCTOR, () => [], {
        'POST /api/v1/encounters/e1/diagnoses': (init: RequestInit) => {
          sent = JSON.parse(String(init.body))
          return { status: 201, body: saved }
        },
      }),
    )

    renderApp('/encounters/e1')
    const form = await screen.findByRole('form', { name: 'Add diagnosis' })
    await userEvent.type(within(form).getByRole('textbox', { name: 'Diagnosis' }), 'Upper respiratory infection')
    await userEvent.click(within(form).getByText('Confirmed'))
    await userEvent.click(within(form).getByRole('button', { name: 'Add' }))

    await waitFor(() => expect(sent).toEqual({ description: 'Upper respiratory infection', certainty: 'CONFIRMED' }))
  })
})

describe('accessibility', () => {
  it('visit page with notes and diagnoses has no automated violations', async () => {
    fakeBackend(fakes(DOCTOR, () => [SIGNED, { ...DRAFT, id: 'n3' }]))
    const view = renderApp('/encounters/e1')
    await screen.findByRole('form', { name: 'Note draft' })
    const results = await axe.run(view.container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([])
  })
})
