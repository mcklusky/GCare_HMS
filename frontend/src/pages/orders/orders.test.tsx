import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import type { EncounterDetail } from '../../api/encounters'
import type { CatalogItem, Order } from '../../api/orders'
import { FACILITY_A, fakeBackend, me, renderApp } from '../../test/render'

const PATIENT = {
  id: 'p1',
  patient_number: 'P1000001-6',
  status: 'ACTIVE' as const,
  family_name: 'Testperson',
  given_names: 'Alpha',
  birth_date: '1985-03-02',
  birth_date_estimated: false,
  sex: 'FEMALE' as const,
  details_incomplete: false,
}
const LAB = { id: 'd-lab', code: 'LAB', name: 'Laboratory' }
const ITEM: CatalogItem = {
  id: 'i1',
  code: 'TEST-LAB-1',
  name: 'Test lab item (fixture)',
  category: 'LAB',
  performing_department: LAB,
  facility_id: FACILITY_A.id,
  status: 'ACTIVE',
  version: 0,
}
const DETAIL: EncounterDetail = {
  encounter: {
    id: 'e1',
    facility_id: FACILITY_A.id,
    department: { id: 'd-opd', code: 'OPD', name: 'Outpatient Department' },
    patient: PATIENT,
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
const ORDER: Order = {
  id: 'o1',
  encounter_id: 'e1',
  facility_id: FACILITY_A.id,
  patient: PATIENT,
  item: { id: 'i1', code: 'TEST-LAB-1', name: 'Test lab item (fixture)', category: 'LAB' },
  performing_department: LAB,
  priority: 'URGENT',
  status: 'REQUESTED',
  indication: 'Fever',
  requested_by: { id: 'doc', display_name: 'Test Doctor' },
  requested_at: '2026-11-23T08:40:00Z',
  updated_at: '2026-11-23T08:40:00Z',
  cancel_reason: null,
  events: [],
  version: 0,
}

const DOCTOR = me({ permissions: ['ENCOUNTER_READ', 'ORDER_READ', 'ORDER_CREATE'], facilities: [FACILITY_A] })
const LAB_STAFF = me({ permissions: ['PATIENT_READ', 'ORDER_READ', 'ORDER_PROCESS'], facilities: [FACILITY_A] })
const ADMIN = me({ permissions: ['FACILITY_READ', 'ORDER_CATALOG_MANAGE'], facilities: [FACILITY_A] })
const WORKLIST = `GET /api/v1/orders?facility_id=${FACILITY_A.id}&size=100`

function pick(container: HTMLElement, label: RegExp) {
  const input = within(container)
    .getAllByLabelText(label)
    .find((el) => el.tagName === 'INPUT')
  if (!input) throw new Error(`select ${label} not found`)
  return input
}

describe('orders on a visit', () => {
  it('places an urgent order from the catalog with an indication', async () => {
    let sent: unknown = null
    fakeBackend({
      'GET /api/v1/me': { body: DOCTOR },
      'GET /api/v1/encounters/e1': { body: DETAIL },
      'GET /api/v1/encounters/e1/orders': { body: [] },
      'GET /api/v1/order-catalog': { body: [ITEM] },
      'POST /api/v1/encounters/e1/orders': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { status: 201, body: ORDER }
      },
    })

    renderApp('/encounters/e1')
    const form = await screen.findByRole('form', { name: 'New order' })
    await userEvent.click(pick(form, /Order or referral/))
    await userEvent.click(await screen.findByRole('option', { name: 'Test lab item (fixture)' }))
    await userEvent.click(within(form).getByText('Urgent'))
    await userEvent.type(within(form).getByRole('textbox', { name: /Indication/ }), 'Fever')
    await userEvent.click(within(form).getByRole('button', { name: 'Place order' }))

    await waitFor(() => expect(sent).toEqual({ catalog_item_id: 'i1', priority: 'URGENT', indication: 'Fever' }))
  })

  it('explains that nothing can be ordered while the catalog is empty', async () => {
    fakeBackend({
      'GET /api/v1/me': { body: DOCTOR },
      'GET /api/v1/encounters/e1': { body: DETAIL },
      'GET /api/v1/encounters/e1/orders': { body: [] },
      'GET /api/v1/order-catalog': { body: [] },
    })

    renderApp('/encounters/e1')
    expect(await screen.findByText(/the order catalog is empty/)).toBeInTheDocument()
  })
})

describe('worklist', () => {
  it('department staff start an order; cancelling needs a reason', async () => {
    const calls: unknown[] = []
    fakeBackend({
      'GET /api/v1/me': { body: LAB_STAFF },
      [WORKLIST]: { body: { items: [ORDER], page: 0, size: 100, total: 1 } },
      'POST /api/v1/orders/o1/start': (init: RequestInit) => {
        calls.push(JSON.parse(String(init.body)))
        return { body: { ...ORDER, status: 'IN_PROGRESS', version: 1 } }
      },
      'POST /api/v1/orders/o1/cancel': (init: RequestInit) => {
        calls.push(JSON.parse(String(init.body)))
        return { body: { ...ORDER, status: 'CANCELLED', version: 1 } }
      },
    })

    renderApp('/orders')
    const table = await screen.findByRole('table', { name: /Worklist/ })
    expect(table).toHaveTextContent('Indication: Fever')
    await userEvent.click(within(table).getByRole('button', { name: 'Start' }))
    await userEvent.click(within(table).getByRole('button', { name: 'Cancel' }))
    const dialog = await screen.findByRole('dialog', { name: 'Cancel this order?' })
    expect(within(dialog).getByRole('button', { name: 'Cancel order' })).toBeDisabled()
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Sample not received')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel order' }))

    await waitFor(() => expect(calls).toEqual([{ version: 0 }, { version: 0, reason: 'Sample not received' }]))
  })

  it('is in the menu for department staff only', async () => {
    fakeBackend({ 'GET /api/v1/me': { body: LAB_STAFF } })
    renderApp()
    const nav = await screen.findByRole('navigation', { name: 'Main navigation' })
    expect(within(nav).getByRole('link', { name: 'Worklist' })).toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: 'Order catalog' })).not.toBeInTheDocument()
  })
})

describe('order catalog', () => {
  it('starts empty and lets an administrator add an item', async () => {
    let sent: unknown = null
    let items: CatalogItem[] = []
    fakeBackend({
      'GET /api/v1/me': { body: ADMIN },
      'GET /api/v1/order-catalog?include_inactive=true': () => ({ body: items }),
      [`GET /api/v1/facilities/${FACILITY_A.id}/departments`]: {
        body: [{ id: 'd-lab', facility_id: FACILITY_A.id, code: 'LAB', name: 'Laboratory', status: 'ACTIVE' }],
      },
      'POST /api/v1/order-catalog': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        items = [ITEM]
        return { status: 201, body: ITEM }
      },
    })

    renderApp('/admin/order-catalog')
    expect(await screen.findByText('The catalog is empty')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Add item' }))
    const dialog = await screen.findByRole('dialog', { name: 'Add catalog item' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Code/ }), 'test-lab-1')
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'Test lab item (fixture)')
    await userEvent.click(pick(dialog, /^Type/))
    await userEvent.click(await within(dialog).findByRole('option', { name: 'Laboratory' }))
    await userEvent.click(pick(dialog, /Performed by/))
    await userEvent.click(await within(dialog).findByRole('option', { name: 'Laboratory' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(sent).toEqual({
        code: 'TEST-LAB-1',
        name: 'Test lab item (fixture)',
        category: 'LAB',
        performing_department_id: 'd-lab',
      }),
    )
    expect(await screen.findByRole('table', { name: 'Order catalog' })).toHaveTextContent('TEST-LAB-1')
  })
})

describe('accessibility', () => {
  it('worklist and catalog pages have no automated violations', async () => {
    const rules = { rules: { 'color-contrast': { enabled: false } } }
    fakeBackend({
      'GET /api/v1/me': {
        body: me({ permissions: [...LAB_STAFF.permissions, ...ADMIN.permissions], facilities: [FACILITY_A] }),
      },
      [WORKLIST]: { body: { items: [ORDER], page: 0, size: 100, total: 1 } },
      'GET /api/v1/order-catalog?include_inactive=true': { body: [ITEM] },
    })

    const worklist = renderApp('/orders')
    await screen.findByRole('table', { name: /Worklist/ })
    expect((await axe.run(worklist.container, rules)).violations.map((v) => v.id)).toEqual([])
    worklist.unmount()

    const catalog = renderApp('/admin/order-catalog')
    await screen.findByRole('table', { name: 'Order catalog' })
    expect((await axe.run(catalog.container, rules)).violations.map((v) => v.id)).toEqual([])
  })
})
