import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import type { Exemption, ExemptionReason, Refund } from '../../api/adjustments'
import type { Invoice } from '../../api/invoices'
import type { Receipt } from '../../api/payments'
import { FACILITY_A, fakeBackend, me, renderApp } from '../../test/render'

const CASHIER = me({
  id: 'cashier',
  permissions: [
    'BILLING_READ',
    'INVOICE_READ',
    'INVOICE_MANAGE',
    'PAYMENT_RECORD',
    'REFUND_REQUEST',
    'EXEMPTION_REQUEST',
  ],
  facilities: [FACILITY_A],
})
const MANAGER = me({
  id: 'manager',
  permissions: ['INVOICE_READ', 'REFUND_APPROVE', 'EXEMPTION_APPROVE'],
  facilities: [FACILITY_A],
})

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
const REASON: ExemptionReason = { id: 'r1', code: 'T-R', name: 'Test reason', status: 'ACTIVE', version: 0 }
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
const REFUND: Refund = {
  id: 'rf1',
  invoice_id: 'inv1',
  invoice_number: 'INV-000001',
  facility_id: FACILITY_A.id,
  payment_id: 'pay1',
  payment_receipt_number: 'RCT-000001',
  method: { id: 'm1', code: 'T-CASH', name: 'Test cash', is_cash: true },
  currency: 'USD',
  amount: 4,
  reason: 'Lab test not done',
  status: 'APPROVED',
  requested_by: USER,
  requested_at: '2026-11-23T10:00:00Z',
  decided_by: { id: 'manager', display_name: 'Test Manager' },
  decided_at: '2026-11-23T10:05:00Z',
  decision_note: 'Checked',
  paid_by: null,
  paid_at: null,
  receipt_number: null,
  balance_after: null,
  version: 1,
}
const EXEMPTION: Exemption = {
  id: 'x1',
  invoice_id: 'inv1',
  invoice_number: 'INV-000001',
  facility_id: FACILITY_A.id,
  reason: REASON,
  currency: 'USD',
  amount: 6,
  note: 'Cannot pay',
  status: 'REQUESTED',
  requested_by: USER,
  requested_at: '2026-11-23T10:00:00Z',
  decided_by: null,
  decided_at: null,
  decision_note: null,
  version: 0,
}

function pick(container: HTMLElement, label: RegExp) {
  const input = within(container)
    .getAllByLabelText(label)
    .find((el) => el.tagName === 'INPUT')
  if (!input) throw new Error(`select ${label} not found`)
  return input
}

function invoiceRoutes(adjustments: { refunds: Refund[]; exemptions: Exemption[] }) {
  return {
    'GET /api/v1/invoices/inv1': { body: INVOICE },
    'GET /api/v1/invoices/inv1/payments': { body: [] },
    'GET /api/v1/invoices/inv1/adjustments': { body: adjustments },
  }
}

describe('on the invoice', () => {
  it('requests an exemption with a reason, the typed amount and a note', async () => {
    let sent: unknown = null
    fakeBackend({
      'GET /api/v1/me': { body: CASHIER },
      ...invoiceRoutes({ refunds: [], exemptions: [] }),
      'GET /api/v1/exemption-reasons': { body: [REASON] },
      'POST /api/v1/invoices/inv1/exemptions': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { status: 201, body: EXEMPTION }
      },
    })

    renderApp('/billing/invoices/inv1')
    await userEvent.click(await screen.findByRole('button', { name: 'Request exemption' }))
    const form = await screen.findByRole('form', { name: 'Request exemption' })
    await userEvent.click(pick(form, /Reason/))
    await userEvent.click(await screen.findByRole('option', { name: 'Test reason' }))
    const amount = within(form).getByRole('textbox', { name: /Amount/ })
    await userEvent.type(amount, '6.005')
    await userEvent.type(within(form).getByRole('textbox', { name: /Note/ }), 'Cannot pay')
    expect(within(form).getByRole('button', { name: 'Request exemption' })).toBeDisabled()
    await userEvent.clear(amount)
    await userEvent.type(amount, '6')
    await userEvent.click(within(form).getByRole('button', { name: 'Request exemption' }))
    await waitFor(() => expect(sent).toEqual({ reason_id: 'r1', amount: '6', note: 'Cannot pay' }))
  })

  it('pays out an approved refund with an idempotency key', async () => {
    let sent: Record<string, unknown> | null = null
    fakeBackend({
      'GET /api/v1/me': { body: CASHIER },
      ...invoiceRoutes({ refunds: [REFUND], exemptions: [] }),
      'POST /api/v1/refunds/rf1/pay': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { body: { ...REFUND, status: 'PAID', receipt_number: 'RCT-000002' } }
      },
    })

    renderApp('/billing/invoices/inv1')
    const table = await screen.findByRole('table', { name: 'Refunds and exemptions' })
    expect(table).toHaveTextContent('Approved, not paid out')
    await userEvent.click(within(table).getByRole('button', { name: 'Pay out' }))
    const dialog = await screen.findByRole('dialog', { name: 'Pay out this refund?' })
    expect(dialog).toHaveTextContent('taken from the cash drawer')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Pay out' }))
    await waitFor(() => expect(sent).toMatchObject({ version: 1 }))
    expect(sent!.idempotency_key).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('shows a refund receipt', async () => {
    const receipt: Receipt = {
      format: 'PROVISIONAL',
      kind: 'REFUND',
      receipt_number: 'RCT-000002',
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
      received_at: '2026-11-23T11:00:00Z',
      patient: PATIENT,
      invoice_number: 'INV-000001',
      invoice_total: 10,
      currency: 'USD',
      amount: 4,
      method_name: 'Test cash',
      reference: 'Refund of RCT-000001',
      balance_after: 4,
      cashier_name: 'Test Cashier',
    }
    fakeBackend({ 'GET /api/v1/me': { body: CASHIER }, 'GET /api/v1/refunds/rf1/receipt': { body: receipt } })
    const view = renderApp('/billing/refund-receipts/rf1')
    expect(await screen.findByRole('heading', { name: 'Refund receipt RCT-000002' })).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Receipt' })).toHaveTextContent('Amount refunded4.00 USD')
    expect(
      (await axe.run(view.container, { rules: { 'color-contrast': { enabled: false } } })).violations.map((v) => v.id),
    ).toEqual([])
  })
})

describe('approvals', () => {
  it('a different person approves with a note; own requests cannot be decided', async () => {
    let sent: unknown = null
    fakeBackend({
      'GET /api/v1/me': { body: MANAGER },
      [`GET /api/v1/adjustments/pending?facility_id=${FACILITY_A.id}`]: {
        body: {
          refunds: [{ ...REFUND, status: 'REQUESTED', version: 0 }],
          exemptions: [{ ...EXEMPTION, requested_by: { id: 'manager', display_name: 'Test Manager' } }],
        },
      },
      'POST /api/v1/refunds/rf1/approve': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { body: REFUND }
      },
    })

    renderApp('/billing/adjustment-approvals')
    const table = await screen.findByRole('table', { name: 'Requests waiting for a decision' })
    expect(within(table).getAllByRole('button', { name: 'Approve' })).toHaveLength(1)
    expect(table).toHaveTextContent('Your request: another person decides.')
    await userEvent.click(within(table).getByRole('button', { name: 'Approve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Approve this request?' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Lab confirmed not done')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(sent).toEqual({ version: 0, note: 'Lab confirmed not done' }))
  })
})

describe('accessibility', () => {
  it('approvals, exemption reasons and the invoice adjustments have no automated violations', async () => {
    const rules = { rules: { 'color-contrast': { enabled: false } } }
    fakeBackend({
      'GET /api/v1/me': {
        body: me({
          permissions: [...CASHIER.permissions, ...MANAGER.permissions, 'EXEMPTION_REASON_MANAGE'],
          facilities: [FACILITY_A],
        }),
      },
      [`GET /api/v1/adjustments/pending?facility_id=${FACILITY_A.id}`]: {
        body: { refunds: [{ ...REFUND, status: 'REQUESTED' }], exemptions: [EXEMPTION] },
      },
      'GET /api/v1/exemption-reasons': { body: [REASON] },
      ...invoiceRoutes({ refunds: [REFUND], exemptions: [EXEMPTION] }),
    })

    for (const [path, table] of [
      ['/billing/adjustment-approvals', 'Requests waiting for a decision'],
      ['/admin/exemption-reasons', 'Exemption reasons'],
      ['/billing/invoices/inv1', 'Refunds and exemptions'],
    ]) {
      const view = renderApp(path)
      await screen.findByRole('table', { name: table })
      expect((await axe.run(view.container, rules)).violations.map((v) => v.id)).toEqual([])
      view.unmount()
    }
  })
})
