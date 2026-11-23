import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import type { DuplicateCandidate, DuplicateFlag, PatientDetail, PatientSummary } from '../../api/patients'
import { FACILITY_A, fakeBackend, me, renderApp } from '../../test/render'

const CLERK = me({
  display_name: 'Test Clerk',
  permissions: ['PATIENT_READ', 'PATIENT_REGISTER', 'PATIENT_UPDATE'],
  facilities: [FACILITY_A],
})

const SUMMARY: PatientSummary = {
  id: '00000000-0000-4000-8000-00000000c001',
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
  ...SUMMARY,
  phone: '0000000001',
  registration_facility_id: FACILITY_A.id,
  registration_source: 'WALK_IN',
  identifiers: [],
  created_at: '2026-11-23T08:00:00Z',
  version: 0,
}

const LIKELY: DuplicateCandidate = { patient: SUMMARY, score: 95, band: 'LIKELY', evidence: ['PHONE', 'BIRTH_DATE'] }

function patientPageFakes(extra = {}) {
  return {
    'GET /api/v1/me': { body: CLERK },
    [`GET /api/v1/patients/${SUMMARY.id}`]: { body: DETAIL },
    [`GET /api/v1/patients/${SUMMARY.id}/history`]: { body: [] },
    [`GET /api/v1/patients/${SUMMARY.id}/possible-duplicates`]: { body: [] },
    ...extra,
  }
}

describe('patient search', () => {
  it('explains when a search is too broad', async () => {
    fakeBackend({
      'GET /api/v1/me': { body: CLERK },
      'GET /api/v1/patients?name=Testperson&size=25': {
        status: 400,
        body: { code: 'SEARCH_TOO_BROAD', message: 'Search with a patient number, … or a name together with a birth year.' },
      },
    })

    renderApp('/patients')
    await userEvent.type(await screen.findByRole('textbox', { name: 'Name' }), 'Testperson')
    await userEvent.click(screen.getByRole('button', { name: 'Search' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Add one more detail')
  })

  it('lists matches with number, name and age, and links to the record', async () => {
    fakeBackend({
      'GET /api/v1/me': { body: CLERK },
      'GET /api/v1/patients?number=10000016&size=25': { body: { items: [SUMMARY], page: 0, size: 25, total: 1 } },
    })

    renderApp('/patients')
    await userEvent.type(await screen.findByRole('textbox', { name: 'Patient number' }), '10000016')
    await userEvent.click(screen.getByRole('button', { name: 'Search' }))

    const table = await screen.findByRole('table', { name: /1 matching patient/ })
    expect(within(table).getByRole('link', { name: 'TESTPERSON, Alpha' })).toHaveAttribute('href', `/patients/${SUMMARY.id}`)
    expect(within(table).getByText('P1000001-6')).toBeInTheDocument()
  })
})

describe('registration with duplicate check', () => {
  it('shows likely duplicates, then needs a reason to register anyway', async () => {
    const posts: unknown[] = []
    fakeBackend({
      'GET /api/v1/me': { body: CLERK },
      'POST /api/v1/patients': (init) => {
        const body = JSON.parse(String(init.body))
        posts.push(body)
        return body.duplicate_review
          ? { status: 201, body: { ...DETAIL, id: 'new-id', patient_number: 'P1000004-0' } }
          : { status: 409, body: { code: 'DUPLICATE_CANDIDATES', message: 'Possible duplicates', candidates: [LIKELY] } }
      },
      'GET /api/v1/patients/new-id': { body: { ...DETAIL, id: 'new-id', patient_number: 'P1000004-0' } },
      'GET /api/v1/patients/new-id/history': { body: [] },
      'GET /api/v1/patients/new-id/possible-duplicates': { body: [] },
    })

    renderApp('/patients/new')
    await userEvent.type(await screen.findByRole('textbox', { name: 'Family name' }), 'Testperson')
    await userEvent.click(screen.getByRole('button', { name: 'Check and register' }))

    const panel = await screen.findByRole('region', { name: 'This patient may already be registered' })
    expect(within(panel).getByText('Likely duplicate')).toBeInTheDocument()
    expect(within(panel).getByText(/Same phone number, Same birth date/)).toBeInTheDocument()
    expect(within(panel).getByRole('link', { name: /This is the patient/ })).toHaveAttribute('href', `/patients/${SUMMARY.id}`)

    await userEvent.click(within(panel).getByRole('button', { name: /None of these is the patient/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Register a new record?' })
    const confirm = within(dialog).getByRole('button', { name: 'Register new record' })
    expect(confirm).toBeDisabled()
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'ID card checked: different person')
    await userEvent.click(confirm)

    expect(await screen.findByText(/Write it on the patient’s card/)).toHaveTextContent('P1000004-0')
    expect(posts[1]).toMatchObject({
      family_name: 'Testperson',
      duplicate_review: { reviewed_candidate_ids: [SUMMARY.id], reason: 'ID card checked: different person' },
    })
  })

  it('changing a detail after the check hides the old candidates', async () => {
    fakeBackend({
      'GET /api/v1/me': { body: CLERK },
      'POST /api/v1/patients': {
        status: 409,
        body: { code: 'DUPLICATE_CANDIDATES', message: 'Possible duplicates', candidates: [{ ...LIKELY, band: 'POSSIBLE' }] },
      },
    })

    renderApp('/patients/new')
    await userEvent.type(await screen.findByRole('textbox', { name: 'Family name' }), 'Testperson')
    await userEvent.click(screen.getByRole('button', { name: 'Check and register' }))
    await screen.findByRole('region', { name: 'This patient may already be registered' })

    await userEvent.type(screen.getByRole('textbox', { name: 'Given names' }), 'X')

    expect(screen.queryByRole('region', { name: 'This patient may already be registered' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Check and register' })).toBeInTheDocument()
  })
})

describe('patient record', () => {
  it('shows the identity banner and history', async () => {
    fakeBackend(
      patientPageFakes({
        [`GET /api/v1/patients/${SUMMARY.id}/history`]: {
          body: [
            {
              id: 'h1',
              patient_version: 1,
              changes: { family_name: { from: 'Testpersn', to: 'Testperson' } },
              reason: 'Spelling from ID card',
              changed_by: 'u1',
              changed_at: '2026-11-23T09:00:00Z',
            },
          ],
        },
      }),
    )

    renderApp(`/patients/${SUMMARY.id}`)

    const banner = await screen.findByRole('region', { name: 'Patient identity' })
    expect(banner).toHaveTextContent('P1000001-6')
    expect(banner).toHaveTextContent('Sex: Female')
    expect(await screen.findByText('Family name: Testpersn → Testperson')).toBeInTheDocument()
  })

  it('sends only changed fields with version and reason, and warns when someone else changed it', async () => {
    const bodies: unknown[] = []
    fakeBackend(
      patientPageFakes({
        [`PATCH /api/v1/patients/${SUMMARY.id}`]: (init: RequestInit) => {
          bodies.push(JSON.parse(String(init.body)))
          return { status: 409, body: { code: 'CONFLICT', message: 'Someone else changed this patient' } }
        },
      }),
    )

    renderApp(`/patients/${SUMMARY.id}`)
    await userEvent.click(await screen.findByRole('button', { name: 'Correct details' }))
    const dialog = await screen.findByRole('dialog', { name: 'Correct patient details' })
    const save = within(dialog).getByRole('button', { name: 'Save correction' })
    expect(save).toBeDisabled()

    const phone = within(dialog).getByRole('textbox', { name: 'Phone' })
    await userEvent.clear(phone)
    await userEvent.type(phone, '0770000999')
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Reason for the correction/ }), 'New number')
    await userEvent.click(save)

    expect(await within(dialog).findByText('Changed by someone else')).toBeInTheDocument()
    expect(bodies[0]).toEqual({ version: 0, reason: 'New number', changes: { phone: '0770000999' } })
  })

  it('hides correction from users without the permission', async () => {
    fakeBackend({ ...patientPageFakes(), 'GET /api/v1/me': { body: { ...CLERK, permissions: ['PATIENT_READ'] } } })

    renderApp(`/patients/${SUMMARY.id}`)

    await screen.findByRole('region', { name: 'Patient identity' })
    expect(screen.queryByRole('button', { name: 'Correct details' })).not.toBeInTheDocument()
  })
})

describe('duplicate review', () => {
  const FLAG: DuplicateFlag = {
    id: 'f1',
    status: 'OPEN',
    origin: 'REGISTERED_DESPITE_MATCH',
    score: 95,
    reason: 'Clerk says different person',
    patient: SUMMARY,
    candidate: { ...SUMMARY, id: 'other', patient_number: 'P1000002-4', given_names: 'Bravo' },
    created_by: 'u1',
    created_at: '2026-11-23T09:00:00Z',
    version: 0,
  }

  it('lists items and records a decision with a note', async () => {
    let decided: unknown = null
    let open = true
    fakeBackend({
      'GET /api/v1/me': { body: me({ permissions: ['PATIENT_READ', 'PATIENT_DUPLICATE_REVIEW'] }) },
      'GET /api/v1/patient-duplicate-flags?status=OPEN&size=50': () => ({
        body: { items: open ? [FLAG] : [], page: 0, size: 50, total: open ? 1 : 0 },
      }),
      'POST /api/v1/patient-duplicate-flags/f1/not-duplicate': (init) => {
        decided = JSON.parse(String(init.body))
        open = false
        return { body: { ...FLAG, status: 'NOT_DUPLICATE', version: 1 } }
      },
    })

    renderApp('/patients/duplicates')
    const item = await screen.findByRole('article', { name: 'Review item P1000001-6' })
    expect(item).toHaveTextContent('Registered despite a likely duplicate')
    expect(item).toHaveTextContent('P1000002-4')

    await userEvent.click(within(item).getByRole('button', { name: 'Different people' }))
    const dialog = await screen.findByRole('dialog', { name: 'Mark as different people?' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Both ID cards checked')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Mark as different people' }))

    await waitFor(() => expect(decided).toEqual({ version: 0, note: 'Both ID cards checked' }))
    expect(await screen.findByText('Nothing to review')).toBeInTheDocument()
  })

  it('is not in the menu for clerks', async () => {
    fakeBackend({ 'GET /api/v1/me': { body: CLERK } })

    renderApp()

    const nav = await screen.findByRole('navigation', { name: 'Main navigation' })
    expect(within(nav).getByRole('link', { name: 'Patients' })).toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: 'Duplicate review' })).not.toBeInTheDocument()
  })
})

describe('accessibility', () => {
  async function noViolations(container: HTMLElement) {
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([])
  }

  it('registration, search and patient pages have no automated violations', async () => {
    fakeBackend(patientPageFakes())

    const register = renderApp('/patients/new')
    await screen.findByRole('button', { name: 'Check and register' })
    await noViolations(register.container)
    register.unmount()

    const search = renderApp('/patients')
    await screen.findByRole('button', { name: 'Search' })
    await noViolations(search.container)
    search.unmount()

    const record = renderApp(`/patients/${SUMMARY.id}`)
    await screen.findByRole('region', { name: 'Patient identity' })
    await screen.findByText(/No corrections/)
    await noViolations(record.container)
  })
})
