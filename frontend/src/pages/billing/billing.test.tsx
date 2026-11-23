import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import { formatMoney, validAmount, type BillableService, type Price } from '../../api/billing'
import { FACILITY_A, fakeBackend, me, renderApp } from '../../test/render'

const OFFICER = me({
  id: 'officer',
  permissions: ['BILLING_READ', 'BILLING_CATALOG_MANAGE', 'PRICE_PROPOSE'],
  facilities: [FACILITY_A],
})
const MANAGER = me({
  id: 'manager',
  permissions: ['BILLING_READ', 'PRICE_PROPOSE', 'PRICE_APPROVE'],
  facilities: [FACILITY_A],
})

const SERVICE: BillableService = {
  id: 's1',
  code: 'T-SVC',
  name: 'Test service (fixture)',
  order_catalog_item_id: null,
  status: 'ACTIVE',
  current_prices: [],
  version: 0,
}
const PROPOSED: Price = {
  id: 'pr1',
  service_id: 's1',
  service_code: 'T-SVC',
  service_name: 'Test service (fixture)',
  currency: 'USD',
  amount: 12.5,
  tax_rate_code: null,
  effective_from: '2026-12-01',
  status: 'PROPOSED',
  reason: 'Fixture price',
  proposed_by: { id: 'officer', display_name: 'Test Officer' },
  proposed_at: '2026-11-23T08:00:00Z',
  decided_by: null,
  decided_at: null,
  decision_note: null,
  version: 0,
}

describe('money display', () => {
  it('pads to the currency decimals without arithmetic', () => {
    expect(formatMoney(12.5, 'USD')).toBe('12.50 USD')
    expect(formatMoney('0.1', 'LRD')).toBe('0.10 LRD')
    expect(formatMoney(500, 'JPY')).toBe('500 JPY')
    expect(formatMoney('1.234', 'BHD')).toBe('1.234 BHD')
    expect(validAmount('12.50', 'USD')).toBe(true)
    expect(validAmount('12.505', 'USD')).toBe(false)
    expect(validAmount('1.5', 'JPY')).toBe(false)
    expect(validAmount('1', 'NOPE')).toBe(false)
  })
})

describe('tariffs', () => {
  it('starts empty and lets finance add a service', async () => {
    let sent: unknown = null
    let services: BillableService[] = []
    fakeBackend({
      'GET /api/v1/me': { body: OFFICER },
      'GET /api/v1/billing/services': () => ({ body: services }),
      'GET /api/v1/billing/tax-rates': { body: [] },
      'POST /api/v1/billing/services': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        services = [SERVICE]
        return { status: 201, body: SERVICE }
      },
    })

    renderApp('/billing/tariffs')
    expect(await screen.findByText('No billable services yet')).toBeInTheDocument()
    expect(screen.getByText('No tax rates')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Add service' }))
    const dialog = await screen.findByRole('dialog', { name: 'Add billable service' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Code/ }), 't-svc')
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'Test service (fixture)')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(sent).toEqual({ code: 'T-SVC', name: 'Test service (fixture)' }))
    expect(await screen.findByRole('table', { name: 'Billable services' })).toHaveTextContent('No approved price')
  })

  it('proposes a price with the typed decimal string, never a float', async () => {
    let sent: Record<string, unknown> | null = null
    fakeBackend({
      'GET /api/v1/me': { body: OFFICER },
      'GET /api/v1/billing/services': { body: [SERVICE] },
      'GET /api/v1/billing/tax-rates': { body: [] },
      'GET /api/v1/billing/services/s1/prices': { body: [] },
      'POST /api/v1/billing/services/s1/prices': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { status: 201, body: PROPOSED }
      },
    })

    renderApp('/billing/tariffs')
    await userEvent.click(await screen.findByRole('button', { name: 'Prices of Test service (fixture)' }))
    const form = await screen.findByRole('form', { name: 'Propose a price' })
    await userEvent.type(within(form).getByRole('textbox', { name: /Currency/ }), 'usd')
    await userEvent.type(within(form).getByRole('textbox', { name: /Amount/ }), '12.505')
    await userEvent.type(within(form).getByRole('textbox', { name: /Reason/ }), 'Fixture price')
    expect(within(form).getByRole('button', { name: 'Propose price' })).toBeDisabled()
    await userEvent.clear(within(form).getByRole('textbox', { name: /Amount/ }))
    await userEvent.type(within(form).getByRole('textbox', { name: /Amount/ }), '12.50')
    await userEvent.click(within(form).getByRole('button', { name: 'Propose price' }))

    await waitFor(() => expect(sent).toMatchObject({ currency: 'USD', amount: '12.50', reason: 'Fixture price' }))
    expect(sent!.effective_from).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })
})

describe('price approvals', () => {
  it('a different person approves with a note; own proposals cannot be decided', async () => {
    let sent: unknown = null
    fakeBackend({
      'GET /api/v1/me': { body: MANAGER },
      'GET /api/v1/billing/approvals': {
        body: {
          prices: [PROPOSED, { ...PROPOSED, id: 'pr2', proposed_by: { id: 'manager', display_name: 'Test Manager' } }],
          tax_rates: [],
        },
      },
      'POST /api/v1/billing/prices/pr1/approve': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { body: { ...PROPOSED, status: 'APPROVED' } }
      },
    })

    renderApp('/billing/approvals')
    const table = await screen.findByRole('table', { name: 'Proposals waiting for approval' })
    expect(table).toHaveTextContent('12.50 USD')
    expect(within(table).getAllByRole('button', { name: 'Approve' })).toHaveLength(1)
    expect(table).toHaveTextContent('Your proposal: another person decides.')
    await userEvent.click(within(table).getByRole('button', { name: 'Approve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Approve this value?' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Matches signed tariff')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Approve' }))

    await waitFor(() => expect(sent).toEqual({ version: 0, note: 'Matches signed tariff' }))
  })
})

describe('accessibility', () => {
  it('tariffs and approvals pages have no automated violations', async () => {
    const rules = { rules: { 'color-contrast': { enabled: false } } }
    fakeBackend({
      'GET /api/v1/me': {
        body: me({ permissions: [...OFFICER.permissions, 'PRICE_APPROVE'], facilities: [FACILITY_A] }),
      },
      'GET /api/v1/billing/services': { body: [{ ...SERVICE, current_prices: [{ ...PROPOSED, status: 'APPROVED' }] }] },
      'GET /api/v1/billing/tax-rates': { body: [] },
      'GET /api/v1/billing/approvals': { body: { prices: [PROPOSED], tax_rates: [] } },
    })

    const tariffs = renderApp('/billing/tariffs')
    await screen.findByRole('table', { name: 'Billable services' })
    expect((await axe.run(tariffs.container, rules)).violations.map((v) => v.id)).toEqual([])
    tariffs.unmount()

    const approvals = renderApp('/billing/approvals')
    await screen.findByRole('table', { name: 'Proposals waiting for approval' })
    expect((await axe.run(approvals.container, rules)).violations.map((v) => v.id)).toEqual([])
  })
})
