import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import type { Dashboard, ReportView } from '../../api/reports'
import { FACILITY_A, fakeBackend, me, renderApp } from '../../test/render'

const MANAGER = me({
  id: 'manager',
  permissions: ['REPORT_OPERATIONS_READ', 'REPORT_EXPORT'],
  facilities: [FACILITY_A],
})
const FINANCE = me({ id: 'finance', permissions: ['REPORT_REVENUE_READ'], facilities: [FACILITY_A] })

const DASHBOARD: Dashboard = {
  facility_id: FACILITY_A.id,
  facility_name: FACILITY_A.name,
  day: '2026-11-23',
  generated_at: '2026-11-23T09:00:00Z',
  tiles: [
    { key: 'arrivals_today', label: 'Arrivals today', value: 12, currency: null },
    { key: 'waiting_now', label: 'Waiting now', value: 3, currency: null },
  ],
}
const VISITS: ReportView = {
  report: 'visits',
  title: 'Visits and waiting',
  facility_id: FACILITY_A.id,
  facility_name: FACILITY_A.name,
  timezone: 'Africa/Monrovia',
  from: '2026-11-01',
  to: '2026-11-23',
  generated_at: '2026-11-23T09:00:00Z',
  generated_by: { id: 'manager', display_name: 'Test Manager' },
  columns: [
    { key: 'day', label: 'Day', type: 'DATE' },
    { key: 'department', label: 'Department', type: 'TEXT' },
    { key: 'arrivals', label: 'Arrivals', type: 'COUNT' },
    { key: 'avg_wait_minutes', label: 'Average wait (min)', type: 'MINUTES' },
  ],
  rows: [{ day: '2026-11-23', department: 'Outpatients', arrivals: 12, avg_wait_minutes: null }],
  notes: ['Wait = arrival to call-in as recorded.'],
}
const DASH = `GET /api/v1/reports/dashboard?facility_id=${FACILITY_A.id}`

describe('reports', () => {
  it('shows today’s tiles, runs a report for a period and offers the audited CSV', async () => {
    let asked: string | null = null
    fakeBackend({
      'GET /api/v1/me': { body: MANAGER },
      [DASH]: { body: DASHBOARD },
      'GET /api/v1/reports': { body: [{ slug: 'visits', title: 'Visits and waiting', exportable: true }] },
      [`GET /api/v1/reports/visits?facility_id=${FACILITY_A.id}&from=2026-11-01&to=2026-11-23`]: () => {
        asked = 'visits'
        return { body: VISITS }
      },
    })

    renderApp('/reports')
    expect(await screen.findByText('Arrivals today')).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()
    const form = screen.getByRole('form', { name: 'Run a report' })
    const select = within(form)
      .getAllByLabelText(/Report/)
      .find((el) => el.tagName === 'INPUT')!
    await userEvent.click(select)
    await userEvent.click(await screen.findByRole('option', { name: 'Visits and waiting' }))
    const fromInput = within(form).getByLabelText(/From/)
    await userEvent.clear(fromInput)
    await userEvent.type(fromInput, '2026-11-01')
    const toInput = within(form).getByLabelText(/^To/)
    await userEvent.clear(toInput)
    await userEvent.type(toInput, '2026-11-23')
    await userEvent.click(within(form).getByRole('button', { name: 'Run report' }))

    const table = await screen.findByRole('table', { name: 'Visits and waiting' })
    await waitFor(() => expect(asked).toBe('visits'))
    expect(table).toHaveTextContent('Outpatients')
    expect(table).toHaveTextContent('—')
    expect(screen.getByRole('link', { name: 'Download CSV' })).toHaveAttribute(
      'href',
      `/api/v1/reports/visits/export?facility_id=${FACILITY_A.id}&from=2026-11-01&to=2026-11-23`,
    )
  })

  it('shows revenue tiles in their own currency and no CSV link without the export permission', async () => {
    fakeBackend({
      'GET /api/v1/me': { body: FINANCE },
      [DASH]: {
        body: {
          ...DASHBOARD,
          tiles: [{ key: 'net_collected_USD', label: 'Net collected today', value: 4.5, currency: 'USD' }],
        },
      },
      'GET /api/v1/reports': { body: [{ slug: 'revenue', title: 'Revenue and receipts', exportable: false }] },
    })
    renderApp('/reports')
    expect(await screen.findByText('4.50 USD')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Download CSV' })).not.toBeInTheDocument()
  })

  it('is hidden from users without report permissions', async () => {
    fakeBackend({ 'GET /api/v1/me': { body: me({ permissions: ['PATIENT_READ'], facilities: [FACILITY_A] }) } })
    renderApp('/reports')
    expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument()
  })

  it('has no automated accessibility violations', async () => {
    fakeBackend({
      'GET /api/v1/me': { body: MANAGER },
      [DASH]: { body: DASHBOARD },
      'GET /api/v1/reports': { body: [{ slug: 'visits', title: 'Visits and waiting', exportable: true }] },
    })
    const view = renderApp('/reports')
    await screen.findByText('Arrivals today')
    const rules = { rules: { 'color-contrast': { enabled: false } } }
    expect((await axe.run(view.container, rules)).violations.map((v) => v.id)).toEqual([])
  })
})
