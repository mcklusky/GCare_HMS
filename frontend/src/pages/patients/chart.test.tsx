import { screen, within } from '@testing-library/react'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import type { Chart, Workspace } from '../../api/chart'
import type { EncounterSummary, Observation } from '../../api/encounters'
import type { Order } from '../../api/orders'
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
const DETAIL: PatientDetail = {
  ...PATIENT,
  phone: null,
  registration_facility_id: FACILITY_A.id,
  registration_source: 'WALK_IN',
  identifiers: [],
  created_at: '2026-11-01T08:00:00Z',
  version: 0,
  linked_to: null,
}
const VISIT: EncounterSummary = {
  id: 'e1',
  facility_id: FACILITY_A.id,
  department: { id: 'd', code: 'OPD', name: 'Outpatient Department' },
  patient: PATIENT,
  encounter_type: 'NEW_VISIT',
  status: 'CLOSED',
  clinician: { id: 'doc', display_name: 'Test Doctor' },
  arrived_at: '2026-11-20T08:00:00Z',
  consultation_started_at: '2026-11-20T08:30:00Z',
  ended_at: '2026-11-20T09:00:00Z',
  triaged: true,
  urgency: null,
  flags: [],
  version: 3,
}
const OBS: Observation = {
  id: 'o1',
  encounter_id: 'e1',
  temperature_c: null,
  pulse_bpm: 80,
  respiratory_rate: null,
  systolic_mmhg: null,
  diastolic_mmhg: null,
  spo2_pct: null,
  weight_kg: null,
  height_cm: null,
  pain_score: null,
  urgency: null,
  allergy_note: 'Reports a rash with a test drug',
  notes: null,
  flags: [],
  status: 'RECORDED',
  recorded_by: { id: 'n', display_name: 'Test Nurse' },
  recorded_at: '2026-11-20T08:10:00Z',
  error_reason: null,
  error_by: null,
  error_at: null,
}
const FULL: Chart = {
  patient_id: 'p1',
  visits: [VISIT],
  diagnoses: [
    {
      id: 'g1',
      encounter_id: 'e1',
      description: 'Test diagnosis one',
      certainty: 'CONFIRMED',
      code: null,
      code_system: null,
      status: 'RECORDED',
      recorded_by: { id: 'doc', display_name: 'Test Doctor' },
      recorded_at: '2026-11-20T08:50:00Z',
      error_reason: null,
      error_by: null,
      error_at: null,
    },
  ],
  latest_observation: OBS,
  reported_allergies: [
    {
      note: 'Reports a rash with a test drug',
      encounter_id: 'e1',
      recorded_by: OBS.recorded_by,
      recorded_at: OBS.recorded_at,
    },
  ],
}
const ORDER: Order = {
  id: 'or1',
  encounter_id: 'e1',
  facility_id: FACILITY_A.id,
  patient: PATIENT,
  item: { id: 'i1', code: 'T-1', name: 'Test item (fixture)', category: 'LAB' },
  performing_department: null,
  priority: 'ROUTINE',
  status: 'COMPLETED',
  indication: null,
  requested_by: { id: 'doc', display_name: 'Test Doctor' },
  requested_at: '2026-11-20T08:55:00Z',
  updated_at: '2026-11-20T10:00:00Z',
  cancel_reason: null,
  events: [],
  version: 2,
}

const CLINICIAN = me({
  permissions: [
    'PATIENT_READ',
    'ENCOUNTER_READ',
    'ENCOUNTER_MANAGE',
    'OBSERVATION_READ',
    'CLINICAL_NOTE_READ',
    'CLINICAL_NOTE_WRITE',
    'ORDER_READ',
  ],
  facilities: [FACILITY_A],
})
const CLERK = me({ permissions: ['PATIENT_READ', 'ENCOUNTER_READ', 'ENCOUNTER_OPEN'], facilities: [FACILITY_A] })

function patientFakes(user: ReturnType<typeof me>, chart: Chart) {
  return {
    'GET /api/v1/me': { body: user },
    'GET /api/v1/patients/p1': { body: DETAIL },
    'GET /api/v1/patients/p1/history': { body: [] },
    'GET /api/v1/patients/p1/possible-duplicates': { body: [] },
    'GET /api/v1/patients/p1/links': { body: [] },
    'GET /api/v1/patients/p1/chart': { body: chart },
    'GET /api/v1/patients/p1/orders': { body: [ORDER] },
  }
}

describe('patient chart', () => {
  it('shows reported allergies, latest triage, diagnoses, visits and orders to a clinician', async () => {
    fakeBackend(patientFakes(CLINICIAN, FULL))

    renderApp('/patients/p1')
    expect(await screen.findByText('Allergies reported at recent triage')).toBeInTheDocument()
    expect(screen.getByText(/Reports a rash with a test drug/)).toBeInTheDocument()
    expect(screen.getByText(/not verified/)).toBeInTheDocument()
    expect(screen.getByText(/Pulse 80 \/min/)).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Diagnoses across visits' })).toHaveTextContent('Test diagnosis one')
    const visits = screen.getByRole('table', { name: 'Visits' })
    expect(within(visits).getByText('New visit, Outpatient Department')).toBeInTheDocument()
    expect(await screen.findByRole('table', { name: 'Orders across visits' })).toHaveTextContent('Test item (fixture)')
  })

  it('shows the front desk visits only', async () => {
    fakeBackend(
      patientFakes(CLERK, {
        ...FULL,
        visits: [{ ...VISIT, triaged: null, flags: null }],
        diagnoses: null,
        latest_observation: null,
        reported_allergies: null,
      }),
    )

    renderApp('/patients/p1')
    expect(await screen.findByRole('table', { name: 'Visits' })).toBeInTheDocument()
    expect(screen.queryByText('Latest triage')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Diagnoses' })).not.toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Orders across visits' })).not.toBeInTheDocument()
  })
})

describe('workspace', () => {
  it('lists patients with me and my unsigned drafts', async () => {
    const workspace: Workspace = {
      in_consultation: [{ ...VISIT, status: 'IN_CONSULTATION', ended_at: null }],
      drafts: [
        { note_id: 'n1', encounter_id: 'e1', patient: PATIENT, amendment: true, updated_at: '2026-11-23T09:00:00Z' },
      ],
    }
    fakeBackend({ 'GET /api/v1/me': { body: CLINICIAN }, 'GET /api/v1/workspace': { body: workspace } })

    renderApp('/workspace')
    const withMe = await screen.findByRole('table', { name: 'Patients with me now' })
    expect(within(withMe).getByRole('link', { name: 'TESTPERSON, Alpha' })).toHaveAttribute('href', '/encounters/e1')
    expect(screen.getByRole('table', { name: 'My unsigned notes' })).toHaveTextContent('Amendment draft')
    const nav = screen.getByRole('navigation', { name: 'Main navigation' })
    expect(within(nav).getByRole('link', { name: 'My workspace' })).toBeInTheDocument()
  })
})

describe('accessibility', () => {
  it('chart and workspace have no automated violations', async () => {
    const rules = { rules: { 'color-contrast': { enabled: false } } }
    fakeBackend({
      ...patientFakes(CLINICIAN, FULL),
      'GET /api/v1/workspace': { body: { in_consultation: [], drafts: [] } },
    })

    const chart = renderApp('/patients/p1')
    await screen.findByRole('table', { name: 'Orders across visits' })
    expect((await axe.run(chart.container, rules)).violations.map((v) => v.id)).toEqual([])
    chart.unmount()

    const workspace = renderApp('/workspace')
    await screen.findByText('No patient with you')
    expect((await axe.run(workspace.container, rules)).violations.map((v) => v.id)).toEqual([])
  })
})
