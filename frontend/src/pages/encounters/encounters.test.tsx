import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import type { EncounterDetail, EncounterSummary, Observation } from '../../api/encounters'
import type { PatientDetail, PatientSummary } from '../../api/patients'
import { FACILITY_A, fakeBackend, me, renderApp } from '../../test/render'

const PATIENT: PatientSummary = {
  id: 'p1',
  patient_number: 'P1000001-6',
  status: 'ACTIVE',
  family_name: 'Testperson',
  given_names: 'Alpha',
  birth_date: '1985-03-02',
  birth_date_estimated: false,
  sex: 'FEMALE',
  details_incomplete: false,
}
const OPD = { id: 'd-opd', code: 'OPD', name: 'Outpatient Department' }
const RAD = { id: 'd-rad', code: 'RAD', name: 'Radiology' }
const ENCOUNTER: EncounterSummary = {
  id: 'e1',
  facility_id: FACILITY_A.id,
  department: OPD,
  patient: PATIENT,
  encounter_type: 'NEW_VISIT',
  status: 'WAITING',
  clinician: null,
  arrived_at: '2026-11-23T08:00:00Z',
  consultation_started_at: null,
  ended_at: null,
  triaged: true,
  urgency: null,
  flags: [],
  version: 0,
}
const DETAIL: EncounterDetail = {
  encounter: ENCOUNTER,
  events: [
    {
      id: 'ev1',
      event_type: 'OPENED',
      from_status: null,
      to_status: 'WAITING',
      department: OPD,
      reason: null,
      actor: { id: 'u1', display_name: 'Test Clerk' },
      occurred_at: '2026-11-23T08:00:00Z',
    },
  ],
}
const OBS: Observation = {
  id: 'o1',
  encounter_id: 'e1',
  temperature_c: 37.2,
  pulse_bpm: 88,
  respiratory_rate: null,
  systolic_mmhg: null,
  diastolic_mmhg: null,
  spo2_pct: null,
  weight_kg: null,
  height_cm: null,
  pain_score: null,
  urgency: null,
  allergy_note: null,
  notes: null,
  flags: [],
  status: 'RECORDED',
  recorded_by: { id: 'u2', display_name: 'Test Nurse' },
  recorded_at: '2026-11-23T08:10:00Z',
  error_reason: null,
  error_by: null,
  error_at: null,
}

/** Mantine Select: the label matches the input and its listbox; pick the input. */
function selectInput(container: HTMLElement, label: RegExp): HTMLElement {
  const input = within(container)
    .getAllByLabelText(label)
    .find((el) => el.tagName === 'INPUT')
  if (!input) throw new Error(`select ${label} not found`)
  return input
}

const CLINICIAN = me({
  permissions: [
    'PATIENT_READ',
    'ENCOUNTER_READ',
    'ENCOUNTER_MANAGE',
    'ENCOUNTER_REOPEN',
    'OBSERVATION_READ',
    'OBSERVATION_RECORD',
  ],
  facilities: [FACILITY_A],
})
const CLERK = me({
  permissions: ['PATIENT_READ', 'PATIENT_REGISTER', 'ENCOUNTER_READ', 'ENCOUNTER_OPEN'],
  facilities: [FACILITY_A],
})
const NO_SCALE = { body: { urgency_levels: [], alarm_limits_configured: false } }
const QUEUE_URL = `GET /api/v1/encounters?facility_id=${FACILITY_A.id}&size=100`
const DEPTS_URL = `GET /api/v1/encounter-departments?facility_id=${FACILITY_A.id}`

function encounterFakes(user = CLINICIAN, extra = {}) {
  return {
    'GET /api/v1/me': { body: user },
    'GET /api/v1/encounters/e1': { body: DETAIL },
    'GET /api/v1/encounters/e1/observations': { body: [OBS] },
    'GET /api/v1/triage/settings': NO_SCALE,
    [DEPTS_URL]: { body: [OPD, RAD] },
    ...extra,
  }
}

describe('queue', () => {
  it('lists open visits; a clinician calls the next patient in', async () => {
    let called: unknown = null
    fakeBackend({
      ...encounterFakes(),
      [QUEUE_URL]: { body: { items: [ENCOUNTER], page: 0, size: 100, total: 1 } },
      'POST /api/v1/encounters/e1/call': (init: RequestInit) => {
        called = JSON.parse(String(init.body))
        return { body: { ...DETAIL, encounter: { ...ENCOUNTER, status: 'IN_CONSULTATION', version: 1 } } }
      },
    })

    renderApp('/queue')
    const table = await screen.findByRole('table', { name: 'Queue, 1 open visits' })
    expect(within(table).getByRole('link', { name: 'TESTPERSON, Alpha' })).toHaveAttribute('href', '/encounters/e1')
    expect(within(table).getByRole('columnheader', { name: 'Triage' })).toBeInTheDocument()
    await userEvent.click(within(table).getByRole('button', { name: 'Call in TESTPERSON, Alpha' }))

    await waitFor(() => expect(called).toEqual({ version: 0 }))
    expect(await screen.findByRole('heading', { name: /New visit, Outpatient Department/ })).toBeInTheDocument()
  })

  it('front desk sees the queue without triage details or clinical actions', async () => {
    fakeBackend({
      'GET /api/v1/me': { body: CLERK },
      [DEPTS_URL]: { body: [OPD] },
      [QUEUE_URL]: { body: { items: [{ ...ENCOUNTER, triaged: null, flags: null }], page: 0, size: 100, total: 1 } },
    })

    renderApp('/queue')
    const table = await screen.findByRole('table', { name: /Queue/ })
    expect(within(table).queryByRole('columnheader', { name: 'Triage' })).not.toBeInTheDocument()
    expect(within(table).queryByRole('button', { name: /Call in/ })).not.toBeInTheDocument()
  })
})

describe('visit page', () => {
  it('transfer needs a department and a reason', async () => {
    let sent: unknown = null
    fakeBackend(
      encounterFakes(CLINICIAN, {
        'POST /api/v1/encounters/e1/transfer': (init: RequestInit) => {
          sent = JSON.parse(String(init.body))
          return { body: { ...DETAIL, encounter: { ...ENCOUNTER, department: RAD, version: 1 } } }
        },
      }),
    )

    renderApp('/encounters/e1')
    await userEvent.click(await screen.findByRole('button', { name: 'Transfer' }))
    const dialog = await screen.findByRole('dialog', { name: 'Transfer this visit?' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Needs imaging')
    expect(within(dialog).getByRole('button', { name: 'Transfer' })).toBeDisabled()
    await userEvent.click(selectInput(dialog, /To department/))
    await userEvent.click(await within(dialog).findByRole('option', { name: 'Radiology' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Transfer' }))

    await waitFor(() => expect(sent).toEqual({ version: 0, reason: 'Needs imaging', department_id: 'd-rad' }))
  })

  it('triage says no scale is configured and saves only the values entered', async () => {
    let sent: unknown = null
    fakeBackend(
      encounterFakes(CLINICIAN, {
        'POST /api/v1/encounters/e1/observations': (init: RequestInit) => {
          sent = JSON.parse(String(init.body))
          return { status: 201, body: OBS }
        },
      }),
    )

    renderApp('/encounters/e1')
    expect(await screen.findByText('No urgency scale configured')).toBeInTheDocument()
    const form = screen.getByRole('form', { name: 'Record observations' })
    expect(within(form).queryByRole('textbox', { name: 'Urgency' })).not.toBeInTheDocument()
    await userEvent.type(within(form).getByRole('textbox', { name: 'Pulse (/min)' }), '92')
    await userEvent.type(within(form).getByRole('textbox', { name: 'Triage notes' }), 'Cough')
    await userEvent.click(within(form).getByRole('button', { name: 'Save observations' }))

    await waitFor(() => expect(sent).toEqual({ pulse_bpm: 92, notes: 'Cough' }))
    const recorded = screen.getByRole('table', { name: 'Recorded observations' })
    expect(recorded).toHaveTextContent('Temperature 37.2 °C · Pulse 88 /min')
  })

  it('front desk sees the visit but not triage', async () => {
    fakeBackend(encounterFakes(CLERK))

    renderApp('/encounters/e1')
    expect(await screen.findByRole('region', { name: 'Patient identity' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel visit' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Call in' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Triage observations' })).not.toBeInTheDocument()
  })
})

describe('start visit', () => {
  it('opens a visit from the patient record', async () => {
    let sent: unknown = null
    const detail: PatientDetail = {
      ...PATIENT,
      phone: null,
      registration_facility_id: FACILITY_A.id,
      registration_source: 'WALK_IN',
      identifiers: [],
      created_at: '2026-11-23T08:00:00Z',
      version: 0,
      linked_to: null,
    }
    fakeBackend({
      ...encounterFakes(CLERK),
      'GET /api/v1/patients/p1': { body: detail },
      'GET /api/v1/patients/p1/history': { body: [] },
      'GET /api/v1/patients/p1/possible-duplicates': { body: [] },
      'GET /api/v1/patients/p1/links': { body: [] },
      'GET /api/v1/patients/p1/chart': {
        body: { patient_id: 'p1', visits: [], diagnoses: null, latest_observation: null, reported_allergies: null },
      },
      'POST /api/v1/encounters': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { status: 201, body: DETAIL }
      },
    })

    renderApp('/patients/p1')
    await userEvent.click(await screen.findByRole('button', { name: 'Start visit' }))
    const dialog = await screen.findByRole('dialog', { name: 'Start a visit' })
    await userEvent.click(selectInput(dialog, /Department/))
    await userEvent.click(await within(dialog).findByRole('option', { name: 'Outpatient Department' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Start visit' }))

    await waitFor(() =>
      expect(sent).toEqual({
        patient_id: 'p1',
        facility_id: FACILITY_A.id,
        department_id: 'd-opd',
        encounter_type: 'NEW_VISIT',
      }),
    )
  })
})

describe('accessibility', () => {
  it('queue and visit pages have no automated violations', async () => {
    fakeBackend({ ...encounterFakes(), [QUEUE_URL]: { body: { items: [ENCOUNTER], page: 0, size: 100, total: 1 } } })
    const rules = { rules: { 'color-contrast': { enabled: false } } }

    const queue = renderApp('/queue')
    await screen.findByRole('table', { name: /Queue/ })
    expect((await axe.run(queue.container, rules)).violations.map((v) => v.id)).toEqual([])
    queue.unmount()

    const visit = renderApp('/encounters/e1')
    await screen.findByRole('table', { name: 'Recorded observations' })
    expect((await axe.run(visit.container, rules)).violations.map((v) => v.id)).toEqual([])
  })
})
