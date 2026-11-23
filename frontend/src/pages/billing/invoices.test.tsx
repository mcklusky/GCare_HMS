import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import type { BillableVisit, Invoice } from '../../api/invoices'
import { FACILITY_A, fakeBackend, me, renderApp } from '../../test/render'

const CASHIER = me({
  id: 'cashier',
  permissions: ['BILLING_READ', 'INVOICE_READ', 'INVOICE_MANAGE'],
  facilities: [FACILITY_A],
})
const VOIDER = me({ id: 'voider', permissions: ['INVOICE_READ', 'INVOICE_VOID'], facilities: [FACILITY_A] })

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
const USER = { id: 'cashier', display_name: 'Test Cashier' }
const VISIT: BillableVisit = {
  encounter_id: 'e1',
  facility_id: FACILITY_A.id,
  patient: PATIENT,
  department_name: 'Outpatients',
  status: 'IN_CONSULTATION',
  arrived_at: '2026-11-23T08:00:00Z',
}
const DRAFT: Invoice = {
  id: 'inv1',
  invoice_number: null,
  status: 'DRAFT',
  currency: 'USD',
  facility_id: FACILITY_A.id,
  encounter: { id: 'e1', status: 'IN_CONSULTATION', department_name: 'Outpatients', arrived_at: VISIT.arrived_at },
  patient: PATIENT,
  lines: [
    {
      id: 'l1',
      source: 'ORDER',
      order_id: 'o1',
      order_status: 'REQUESTED',
      service_id: 's1',
      service_code: 'T-LAB',
      service_name: 'Test lab (fixture)',
      quantity: 1,
      unit_price: 12.5,
      tax_rate_code: 'T-VAT',
      tax_rate_percent: 15,
      net_amount: 12.5,
      tax_amount: 1.88,
      line_total: 14.38,
      status: 'ACTIVE',
      reason: null,
      added_by: USER,
      added_at: '2026-11-23T08:05:00Z',
      removed_reason: null,
      removed_by: null,
      removed_at: null,
    },
  ],
  unbilled_orders: [{ order_id: 'o2', item_name: 'Test imaging', service_code: 'T-IMG', problem: 'NO_PRICE' }],
  subtotal: 12.5,
  tax_total: 1.88,
  total: 14.38,
  replaces_invoice_id: null,
  created_by: USER,
  created_at: '2026-11-23T08:05:00Z',
  issued_by: null,
  issued_at: null,
  void_reason: null,
  voided_by: null,
  voided_at: null,
  version: 3,
}
const ISSUED: Invoice = {
  ...DRAFT,
  status: 'ISSUED',
  invoice_number: 'INV-000001',
  unbilled_orders: [],
  issued_by: USER,
  issued_at: '2026-11-23T08:10:00Z',
  version: 4,
}

describe('invoices list', () => {
  it('starts an invoice for a visit in the chosen currency', async () => {
    let sent: unknown = null
    fakeBackend({
      'GET /api/v1/me': { body: CASHIER },
      [`GET /api/v1/invoices/billable-visits?facility_id=${FACILITY_A.id}`]: { body: [VISIT] },
      [`GET /api/v1/invoices?facility_id=${FACILITY_A.id}&status=DRAFT&status=ISSUED`]: { body: [] },
      'POST /api/v1/encounters/e1/invoices': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { status: 201, body: DRAFT }
      },
      'GET /api/v1/invoices/inv1': { body: DRAFT },
    })

    renderApp('/billing/invoices')
    const visits = await screen.findByRole('table', { name: 'Visits without an invoice' })
    await userEvent.click(within(visits).getByRole('button', { name: /Start invoice for TESTPERSON/ }))
    const form = await screen.findByRole('form', { name: 'Start invoice' })
    const start = within(form).getByRole('button', { name: 'Start invoice' })
    await userEvent.type(within(form).getByRole('textbox', { name: /Currency/ }), 'us')
    expect(start).toBeDisabled()
    await userEvent.type(within(form).getByRole('textbox', { name: /Currency/ }), 'd')
    await userEvent.click(start)

    await waitFor(() => expect(sent).toEqual({ currency: 'USD' }))
    expect(await screen.findByRole('heading', { name: 'Draft invoice' })).toBeInTheDocument()
  })
})

describe('invoice page', () => {
  it('shows server totals and unpriced orders, and issues with the version', async () => {
    let sent: unknown = null
    fakeBackend({
      'GET /api/v1/me': { body: CASHIER },
      'GET /api/v1/invoices/inv1': { body: DRAFT },
      'POST /api/v1/invoices/inv1/issue': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { body: ISSUED }
      },
    })

    renderApp('/billing/invoices/inv1')
    const charges = await screen.findByRole('table', { name: 'Charges' })
    expect(charges).toHaveTextContent('12.50 USD')
    expect(charges).toHaveTextContent('1.88 USD')
    expect(screen.getByRole('table', { name: 'Totals' })).toHaveTextContent('14.38 USD')
    expect(screen.getByText(/No approved price in this currency/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add new order charges' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Issue invoice' }))
    const dialog = await screen.findByRole('dialog', { name: 'Issue this invoice?' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Issue' }))
    await waitFor(() => expect(sent).toEqual({ version: 3 }))
    expect(await screen.findByRole('heading', { name: 'Invoice INV-000001' })).toBeInTheDocument()
    // Issued: no more editing, and a cashier cannot void.
    expect(screen.queryByRole('button', { name: 'Remove Test lab (fixture)' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Void invoice' })).not.toBeInTheDocument()
  })

  it('removing a charge needs a reason', async () => {
    let sent: unknown = null
    fakeBackend({
      'GET /api/v1/me': { body: CASHIER },
      'GET /api/v1/invoices/inv1': { body: DRAFT },
      'POST /api/v1/invoices/inv1/lines/l1/remove': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { body: DRAFT }
      },
    })

    renderApp('/billing/invoices/inv1')
    await userEvent.click(await screen.findByRole('button', { name: 'Remove Test lab (fixture)' }))
    const dialog = await screen.findByRole('dialog', { name: 'Remove this charge?' })
    expect(within(dialog).getByRole('button', { name: 'Remove' })).toBeDisabled()
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Order not done')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(sent).toEqual({ version: 3, reason: 'Order not done' }))
  })

  it('an issued invoice is voided with a reason by someone allowed to', async () => {
    let sent: unknown = null
    fakeBackend({
      'GET /api/v1/me': { body: VOIDER },
      'GET /api/v1/invoices/inv1': { body: ISSUED },
      'POST /api/v1/invoices/inv1/void': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return {
          body: { ...ISSUED, status: 'VOID', void_reason: 'Wrong patient', voided_by: USER, version: 5 },
        }
      },
    })

    renderApp('/billing/invoices/inv1')
    await userEvent.click(await screen.findByRole('button', { name: 'Void invoice' }))
    const dialog = await screen.findByRole('dialog', { name: 'Void this invoice?' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Wrong patient')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Void invoice' }))
    await waitFor(() => expect(sent).toEqual({ version: 4, reason: 'Wrong patient' }))
    expect(await screen.findByText('This invoice was voided')).toBeInTheDocument()
  })
})

describe('accessibility', () => {
  it('invoice pages have no automated violations', async () => {
    const rules = { rules: { 'color-contrast': { enabled: false } } }
    fakeBackend({
      'GET /api/v1/me': { body: CASHIER },
      [`GET /api/v1/invoices/billable-visits?facility_id=${FACILITY_A.id}`]: { body: [VISIT] },
      [`GET /api/v1/invoices?facility_id=${FACILITY_A.id}&status=DRAFT&status=ISSUED`]: {
        body: [
          {
            id: 'inv1',
            invoice_number: null,
            status: 'DRAFT',
            currency: 'USD',
            facility_id: FACILITY_A.id,
            encounter_id: 'e1',
            patient: PATIENT,
            total: 14.38,
            created_at: DRAFT.created_at,
            issued_at: null,
            version: 3,
          },
        ],
      },
      'GET /api/v1/invoices/inv1': { body: DRAFT },
    })

    const list = renderApp('/billing/invoices')
    await screen.findByRole('table', { name: 'Invoices' })
    await screen.findByRole('table', { name: 'Visits without an invoice' })
    expect((await axe.run(list.container, rules)).violations.map((v) => v.id)).toEqual([])
    list.unmount()

    const page = renderApp('/billing/invoices/inv1')
    await screen.findByRole('table', { name: 'Charges' })
    expect((await axe.run(page.container, rules)).violations.map((v) => v.id)).toEqual([])
  })
})
