import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import type { Invoice } from '../../api/invoices'
import type { Payment, PaymentMethod, Receipt, Shift } from '../../api/payments'
import { FACILITY_A, fakeBackend, me, renderApp } from '../../test/render'

const CASHIER = me({
  id: 'cashier',
  permissions: ['BILLING_READ', 'INVOICE_READ', 'INVOICE_MANAGE', 'PAYMENT_RECORD'],
  facilities: [FACILITY_A],
})
const REVIEWER = me({ id: 'reviewer', permissions: ['INVOICE_READ', 'SHIFT_REVIEW'], facilities: [FACILITY_A] })

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
const CASH: PaymentMethod = { id: 'm1', code: 'T-CASH', name: 'Test cash', is_cash: true, status: 'ACTIVE', version: 0 }
const INVOICE: Invoice = {
  id: 'inv1',
  invoice_number: 'INV-000001',
  status: 'ISSUED',
  currency: 'USD',
  facility_id: FACILITY_A.id,
  encounter: { id: 'e1', status: 'CLOSED', department_name: 'Outpatients', arrived_at: '2026-11-23T08:00:00Z' },
  patient: PATIENT,
  lines: [],
  unbilled_orders: [],
  subtotal: 10,
  tax_total: 0,
  total: 10,
  paid: 0,
  refunded: 0,
  exempted: 0,
  balance: 10,
  replaces_invoice_id: null,
  created_by: USER,
  created_at: '2026-11-23T08:05:00Z',
  issued_by: USER,
  issued_at: '2026-11-23T08:10:00Z',
  void_reason: null,
  voided_by: null,
  voided_at: null,
  version: 2,
}
const PAYMENT: Payment = {
  id: 'pay1',
  invoice_id: 'inv1',
  shift_id: 'sh1',
  method: { id: 'm1', code: 'T-CASH', name: 'Test cash', is_cash: true },
  currency: 'USD',
  amount: 5,
  balance_after: 5,
  reference: null,
  receipt_number: 'RCT-000001',
  received_by: USER,
  received_at: '2026-11-23T09:00:00Z',
}
const SHIFT: Shift = {
  id: 'sh1',
  facility_id: FACILITY_A.id,
  cashier: USER,
  status: 'OPEN',
  opened_at: '2026-11-23T07:00:00Z',
  closed_at: null,
  close_note: null,
  reviewed_by: null,
  reviewed_at: null,
  review_note: null,
  totals: [{ method_code: 'T-CASH', method_name: 'Test cash', is_cash: true, currency: 'USD', amount: 5, payments: 1 }],
  refunds: [],
  expected_cash: [{ currency: 'USD', amount: 5 }],
  counts: [],
  version: 0,
}
const RECEIPT: Receipt = {
  format: 'PROVISIONAL',
  kind: 'PAYMENT',
  receipt_number: 'RCT-000001',
  facility_name: FACILITY_A.name,
  branding: {
    facility_id: FACILITY_A.id,
    display_name: 'Test Hospital A (synthetic)',
    address_lines: ['1 Test Road'],
    phone: null,
    registration_number: null,
    receipt_footer: 'Thank you (test)',
    logo_id: null,
    configured: true,
  },
  received_at: PAYMENT.received_at,
  patient: PATIENT,
  invoice_number: 'INV-000001',
  invoice_total: 10,
  currency: 'USD',
  amount: 5,
  method_name: 'Test cash',
  reference: null,
  balance_after: 5,
  cashier_name: 'Test Cashier',
}

function pick(container: HTMLElement, label: RegExp) {
  const input = within(container)
    .getAllByLabelText(label)
    .find((el) => el.tagName === 'INPUT')
  if (!input) throw new Error(`select ${label} not found`)
  return input
}

describe('payments', () => {
  it('records a part payment with an idempotency key and the typed decimal', async () => {
    const sent: Record<string, unknown>[] = []
    fakeBackend({
      'GET /api/v1/me': { body: CASHIER },
      'GET /api/v1/invoices/inv1': { body: INVOICE },
      'GET /api/v1/invoices/inv1/payments': { body: [] },
      'GET /api/v1/invoices/inv1/adjustments': { body: { refunds: [], exemptions: [] } },
      'GET /api/v1/payment-methods': { body: [CASH] },
      'POST /api/v1/invoices/inv1/payments': (init: RequestInit) => {
        sent.push(JSON.parse(String(init.body)))
        return { status: 201, body: PAYMENT }
      },
    })

    renderApp('/billing/invoices/inv1')
    await userEvent.click(await screen.findByRole('button', { name: 'Record payment' }))
    const form = await screen.findByRole('form', { name: 'Record payment' })
    await userEvent.click(pick(form, /Method/))
    await userEvent.click(await screen.findByRole('option', { name: 'Test cash' }))
    const amount = within(form).getByRole('textbox', { name: /Amount/ })
    await userEvent.type(amount, '5.005')
    expect(within(form).getByRole('button', { name: 'Record payment' })).toBeDisabled()
    await userEvent.clear(amount)
    await userEvent.type(amount, '5.00')
    await userEvent.click(within(form).getByRole('button', { name: 'Record payment' }))

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0]).toMatchObject({ method_id: 'm1', amount: '5.00' })
    expect(sent[0].idempotency_key).toMatch(/^[0-9a-f-]{36}$/)
    expect(await screen.findByText(/Receipt RCT-000001/)).toBeInTheDocument()
  })

  it('shows the provisional receipt', async () => {
    fakeBackend({
      'GET /api/v1/me': { body: CASHIER },
      'GET /api/v1/payments/pay1/receipt': { body: RECEIPT },
    })
    renderApp('/billing/receipts/pay1')
    const table = await screen.findByRole('table', { name: 'Receipt' })
    expect(table).toHaveTextContent('5.00 USD')
    expect(table).toHaveTextContent('INV-000001 (total 10.00 USD)')
    expect(screen.getByText('Provisional format')).toBeInTheDocument()
    expect(screen.getByText('1 Test Road')).toBeInTheDocument()
    expect(screen.getByText('Thank you (test)')).toBeInTheDocument()
  })
})

describe('shifts', () => {
  it('opens a shift, then closes it with a counted amount and a note', async () => {
    let shifts: Shift[] = []
    let opened: unknown = null
    let closed: unknown = null
    fakeBackend({
      'GET /api/v1/me': { body: CASHIER },
      'GET /api/v1/shifts/mine': () => ({ body: shifts }),
      'POST /api/v1/shifts': (init: RequestInit) => {
        opened = JSON.parse(String(init.body))
        shifts = [SHIFT]
        return { status: 201, body: SHIFT }
      },
      'POST /api/v1/shifts/sh1/close': (init: RequestInit) => {
        closed = JSON.parse(String(init.body))
        return { body: { ...SHIFT, status: 'CLOSED' } }
      },
    })

    renderApp('/billing/shift')
    await userEvent.click(await screen.findByRole('button', { name: /Open shift at/ }))
    await waitFor(() => expect(opened).toEqual({ facility_id: FACILITY_A.id }))
    const form = await screen.findByRole('form', { name: 'Close shift' })
    await userEvent.type(within(form).getByRole('textbox', { name: /Counted cash \(USD\)/ }), '4')
    await userEvent.type(within(form).getByRole('textbox', { name: /Note/ }), 'One coin short')
    await userEvent.click(within(form).getByRole('button', { name: 'Close shift' }))
    await waitFor(() =>
      expect(closed).toEqual({ version: 0, counts: [{ currency: 'USD', counted: '4' }], note: 'One coin short' }),
    )
  })

  it('a reviewer signs off other people’s closed shifts with a note', async () => {
    let sent: unknown = null
    const closed: Shift = {
      ...SHIFT,
      status: 'CLOSED',
      closed_at: '2026-11-23T17:00:00Z',
      close_note: 'One coin short',
      counts: [{ currency: 'USD', expected: 5, counted: 4, difference: -1 }],
      version: 1,
    }
    fakeBackend({
      'GET /api/v1/me': { body: REVIEWER },
      [`GET /api/v1/shifts/to-review?facility_id=${FACILITY_A.id}`]: { body: [closed] },
      'POST /api/v1/shifts/sh1/review': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { body: { ...closed, status: 'REVIEWED' } }
      },
    })

    renderApp('/billing/shift-reviews')
    const count = await screen.findByRole('table', { name: 'Cash count of Test Cashier' })
    expect(count).toHaveTextContent('-1.00 USD')
    await userEvent.click(screen.getByRole('button', { name: 'Sign off' }))
    const dialog = await screen.findByRole('dialog', { name: 'Sign off this shift?' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Checked drawer')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Sign off' }))
    await waitFor(() => expect(sent).toEqual({ version: 1, note: 'Checked drawer' }))
  })
})

describe('accessibility', () => {
  it('shift, receipt and payment method pages have no automated violations', async () => {
    const rules = { rules: { 'color-contrast': { enabled: false } } }
    fakeBackend({
      'GET /api/v1/me': {
        body: me({ permissions: [...CASHIER.permissions, 'PAYMENT_METHOD_MANAGE'], facilities: [FACILITY_A] }),
      },
      'GET /api/v1/shifts/mine': { body: [SHIFT] },
      'GET /api/v1/payments/pay1/receipt': { body: RECEIPT },
      'GET /api/v1/payment-methods': { body: [CASH] },
    })

    for (const [path, table] of [
      ['/billing/shift', 'Payments in this shift'],
      ['/billing/receipts/pay1', 'Receipt'],
      ['/admin/payment-methods', 'Payment methods'],
    ]) {
      const view = renderApp(path)
      await screen.findByRole('table', { name: table })
      expect((await axe.run(view.container, rules)).violations.map((v) => v.id)).toEqual([])
      view.unmount()
    }
  })
})
