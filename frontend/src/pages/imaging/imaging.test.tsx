import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import { FACILITY_A, fakeBackend, me, renderApp } from '../../test/render'

const ADMIN = me({ permissions: ['IMAGING_ARCHIVE_ADMIN'], facilities: [FACILITY_A] })
const STATUS = {
  configured: true,
  reachable: true,
  latency_ms: 12,
  message: 'The archive answered',
  viewer_configured: true,
  checked_at: '2026-11-23T12:00:00Z',
}
const STUDY = {
  study_instance_uid: '1.2.3.4',
  accession_number: 'ACC-T-1',
  patient_id: 'P-000001',
  modalities: ['CR'],
  study_date: '2026-11-23',
  description: 'Synthetic chest',
  series_count: 1,
  instance_count: 2,
  viewer_url: 'https://viewer.test.invalid/?study=1.2.3.4',
}

describe('imaging archive', () => {
  it('shows the archive status and finds studies by accession number with a viewer link', async () => {
    fakeBackend({
      'GET /api/v1/me': { body: ADMIN },
      'GET /api/v1/imaging/archive/status': { body: STATUS },
      'GET /api/v1/imaging/archive/studies?accession_number=ACC-T-1': {
        body: [STUDY, { ...STUDY, study_instance_uid: '1.2.3.5' }],
      },
    })
    const view = renderApp('/admin/imaging-archive')
    expect(await screen.findByText('Answering')).toBeInTheDocument()
    const form = screen.getByRole('form', { name: 'Find studies by accession number' })
    await userEvent.type(within(form).getByRole('textbox', { name: 'Accession number' }), 'ACC-T-1')
    await userEvent.click(within(form).getByRole('button', { name: 'Find' }))
    const table = await screen.findByRole('table', { name: 'Studies in the archive' })
    expect(within(table).getAllByRole('link', { name: 'Open in viewer' })[0]).toHaveAttribute('href', STUDY.viewer_url)
    expect(screen.getByText('More than one study')).toBeInTheDocument()
    const rules = { rules: { 'color-contrast': { enabled: false } } }
    expect((await axe.run(view.container, rules)).violations.map((v) => v.id)).toEqual([])
  }, 20_000)

  it('says plainly when no archive is configured', async () => {
    fakeBackend({
      'GET /api/v1/me': { body: ADMIN },
      'GET /api/v1/imaging/archive/status': {
        body: { ...STATUS, configured: false, reachable: false, message: 'No imaging archive is configured' },
      },
    })
    renderApp('/admin/imaging-archive')
    expect(await screen.findByText('Not configured')).toBeInTheDocument()
  })
})
