import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import type { SettingInfo, SettingVersion } from '../../api/settings'
import { FACILITY_A, fakeBackend, me, renderApp } from '../../test/render'

const LEAD = me({ id: 'lead', permissions: ['CONFIG_CLINICAL_MANAGE'], facilities: [FACILITY_A] })
const TRIAGE: SettingInfo = {
  key: 'TRIAGE',
  title: 'Triage urgency scale and alarm limits',
  description: 'Clinical rules.',
  scope: 'ORGANIZATION',
  permission: 'CONFIG_CLINICAL_MANAGE',
}
const PROPOSED: SettingVersion = {
  id: 'v1',
  key: 'TRIAGE',
  facility_id: null,
  version_number: 1,
  value: {
    urgency_levels: [{ code: 'T_FIRST', label: 'Test first', rank: 1 }],
    alarm_limits: { PULSE_BPM: { high: 150 } },
  },
  status: 'PROPOSED',
  reason: 'Agreed at meeting',
  proposed_by: { id: 'other', display_name: 'Other Lead' },
  proposed_at: '2026-11-23T08:00:00Z',
  decided_by: null,
  decided_at: null,
  decision_note: null,
  version: 0,
}

describe('settings', () => {
  it('proposes a triage scale entered by the lead, with a reason', async () => {
    let sent: Record<string, unknown> | null = null
    fakeBackend({
      'GET /api/v1/me': { body: LEAD },
      'GET /api/v1/settings': { body: [TRIAGE] },
      'GET /api/v1/settings/pending': { body: [] },
      'GET /api/v1/settings/TRIAGE/versions': { body: [] },
      'POST /api/v1/settings/TRIAGE/versions': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { status: 201, body: PROPOSED }
      },
    })

    renderApp('/admin/settings')
    await userEvent.click(await screen.findByRole('button', { name: `Open ${TRIAGE.title}` }))
    const form = await screen.findByRole('form', { name: `Propose a change to ${TRIAGE.title}` })
    expect(screen.getByText('In use: not configured')).toBeInTheDocument()
    await userEvent.click(within(form).getByRole('button', { name: 'Add level' }))
    await userEvent.type(within(form).getByRole('textbox', { name: 'Code' }), 't_first')
    await userEvent.type(within(form).getByRole('textbox', { name: 'Label' }), 'Test first')
    await userEvent.type(within(form).getByRole('textbox', { name: 'Pulse (bpm) high' }), '150')
    expect(within(form).getByRole('button', { name: 'Propose' })).toBeDisabled()
    await userEvent.type(within(form).getByRole('textbox', { name: /Reason/ }), 'Agreed at meeting')
    await userEvent.click(within(form).getByRole('button', { name: 'Propose' }))

    await waitFor(() =>
      expect(sent).toEqual({
        value: {
          urgency_levels: [{ code: 'T_FIRST', label: 'Test first', rank: 1 }],
          alarm_limits: { PULSE_BPM: { high: '150' } },
        },
        reason: 'Agreed at meeting',
      }),
    )
  }, 20_000)

  it('a different lead approves a pending change with a note; own proposals cannot be decided', async () => {
    let sent: unknown = null
    fakeBackend({
      'GET /api/v1/me': { body: LEAD },
      'GET /api/v1/settings': { body: [TRIAGE] },
      'GET /api/v1/settings/pending': {
        body: [PROPOSED, { ...PROPOSED, id: 'v2', key: 'TRIAGE', proposed_by: { id: 'lead', display_name: 'Me' } }],
      },
      'POST /api/v1/settings/versions/v1/approve': (init: RequestInit) => {
        sent = JSON.parse(String(init.body))
        return { body: { ...PROPOSED, status: 'ACTIVE' } }
      },
    })

    renderApp('/admin/settings')
    expect(await screen.findByText('Your proposal: another person decides.')).toBeInTheDocument()
    expect(screen.getAllByText(/1\. Test first \(T_FIRST\)/)).toHaveLength(2)
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Approve this change?' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Checked against policy')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(sent).toEqual({ version: 0, note: 'Checked against policy' }))
  }, 20_000)

  it('has no automated accessibility violations', async () => {
    fakeBackend({
      'GET /api/v1/me': { body: LEAD },
      'GET /api/v1/settings': { body: [TRIAGE] },
      'GET /api/v1/settings/pending': { body: [PROPOSED] },
      'GET /api/v1/settings/TRIAGE/versions': { body: [{ ...PROPOSED, status: 'REJECTED' }] },
    })
    const view = renderApp('/admin/settings')
    await userEvent.click(await screen.findByRole('button', { name: `Open ${TRIAGE.title}` }))
    await screen.findByRole('table', { name: `Versions of ${TRIAGE.title}` })
    const rules = { rules: { 'color-contrast': { enabled: false } } }
    expect((await axe.run(view.container, rules)).violations.map((v) => v.id)).toEqual([])
  })
})
