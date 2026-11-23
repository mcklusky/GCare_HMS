import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it, vi } from 'vitest'
import { browser } from './auth/session'
import { FACILITY_A, FACILITY_B, fakeBackend, me, renderApp } from './test/render'

const UNAUTHENTICATED = { status: 401, body: { code: 'UNAUTHENTICATED', message: 'Authentication required' } }

async function expectNoAxeViolations(container: HTMLElement) {
  // Colour contrast cannot be computed in jsdom; it is covered by the theme review instead.
  const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
  expect(results.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([])
}

describe('shell', () => {
  it('groups the navigation in sections and switches between light and dark', async () => {
    fakeBackend({
      'GET /api/v1/me': {
        body: me({ permissions: ['FACILITY_READ', 'PATIENT_READ', 'ENCOUNTER_READ'], facilities: [FACILITY_A] }),
      },
    })
    const { container } = renderApp()
    await screen.findByRole('heading', { name: 'Welcome, Test Admin' })
    const nav = screen.getByRole('navigation', { name: 'Main navigation' })
    expect(within(nav).getByText('Patients & visits')).toBeInTheDocument()
    expect(within(nav).getByText('Administration')).toBeInTheDocument()
    expect(within(nav).queryByText('Billing')).not.toBeInTheDocument()
    // Shortcut cards on the home page follow the same permissions.
    expect(screen.getByRole('region', { name: 'Patients & visits' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Switch to dark mode' }))
    await waitFor(() => expect(document.documentElement).toHaveAttribute('data-mantine-color-scheme', 'dark'))
    await userEvent.click(screen.getByRole('button', { name: 'Switch to light mode' }))
    await waitFor(() => expect(document.documentElement).toHaveAttribute('data-mantine-color-scheme', 'light'))
    await expectNoAxeViolations(container)
  })
})

describe('sign-in', () => {
  it('shows the sign-in page when there is no session and starts the Keycloak flow', async () => {
    fakeBackend({ 'GET /api/v1/me': UNAUTHENTICATED })
    const assign = vi.spyOn(browser, 'assign').mockImplementation(() => {})

    renderApp()

    await userEvent.click(await screen.findByRole('button', { name: 'Sign in' }))
    expect(assign).toHaveBeenCalledWith('/oauth2/authorization/keycloak')
  })

  it('explains a refused sign-in', async () => {
    fakeBackend({ 'GET /api/v1/me': UNAUTHENTICATED })

    renderApp('/?login_error=account_not_enabled')

    expect(await screen.findByRole('alert')).toHaveTextContent(/not set up or is disabled in GCare/)
  })

  it('sign-in page has no accessibility violations', async () => {
    fakeBackend({ 'GET /api/v1/me': UNAUTHENTICATED })
    const { container } = renderApp()
    await screen.findByRole('button', { name: 'Sign in' })
    await expectNoAxeViolations(container)
  })

  it('shows an error with retry when the session check fails', async () => {
    let calls = 0
    fakeBackend({
      'GET /api/v1/me': () =>
        // fails the first attempt and the automatic retry
        ++calls <= 2
          ? {
              status: 500,
              body: { code: 'INTERNAL_ERROR', message: 'Unexpected error', correlation_id: 'corr-1234abcd' },
            }
          : { body: me() },
    })

    renderApp()

    const alert = await screen.findByRole('alert', {}, { timeout: 3000 })
    expect(alert).toHaveTextContent('corr-1234abcd')
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { name: 'Welcome, Test Admin' })).toBeInTheDocument()
  })
})

describe('application shell', () => {
  it('shows the user, organization, facility and permitted navigation', async () => {
    fakeBackend({ 'GET /api/v1/me': { body: me() } })

    renderApp()

    expect(await screen.findByRole('heading', { name: 'Welcome, Test Admin' })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Main navigation' })
    expect(within(nav).getByRole('link', { name: 'Home' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Facilities' })).toBeInTheDocument()
    expect(screen.getByText('Facility: Test Hospital A (synthetic)')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Your permissions' })).toHaveTextContent('FACILITY_READ')
    // Page eyebrow names the section; the month calendar marks exactly one day as today.
    expect(screen.getAllByText('GCare HMS').length).toBeGreaterThan(0)
    const calendar = screen.getByRole('table')
    expect(calendar.querySelectorAll('[aria-current="date"]')).toHaveLength(1)
    expect(screen.getByRole('link', { name: 'Skip to main content' })).toHaveAttribute('href', '#main')
  })

  it('is usable from the keyboard: the skip link comes first and main content is a landmark', async () => {
    fakeBackend({ 'GET /api/v1/me': { body: me() } })

    renderApp()

    await screen.findByRole('heading', { name: 'Welcome, Test Admin' })
    await userEvent.tab()
    expect(screen.getByRole('link', { name: 'Skip to main content' })).toHaveFocus()
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main')
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  it('hides navigation the user has no permission for, and guards the page', async () => {
    fakeBackend({ 'GET /api/v1/me': { body: me({ permissions: ['AUDIT_READ'], facilities: [] }) } })

    renderApp('/facilities')

    expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Main navigation' })
    expect(within(nav).queryByRole('link', { name: 'Facilities' })).not.toBeInTheDocument()
  })

  it('tells a user without roles that they have no access yet', async () => {
    fakeBackend({ 'GET /api/v1/me': { body: me({ permissions: [], facilities: [] }) } })

    renderApp()

    expect(await screen.findByText(/No roles are assigned to your account yet/)).toBeInTheDocument()
    expect(screen.getByText('Facility: No facility assigned')).toBeInTheDocument()
  })

  it('switches the working facility', async () => {
    fakeBackend({ 'GET /api/v1/me': { body: me({ facilities: [FACILITY_A, { ...FACILITY_B, status: 'ACTIVE' }] }) } })

    renderApp()

    await screen.findByRole('heading', { name: 'Welcome, Test Admin' })
    const picker = screen.getAllByLabelText('Working facility').find((el) => el.tagName === 'INPUT')
    if (!picker) throw new Error('facility picker input not found')
    await userEvent.click(picker)
    await userEvent.click(await screen.findByRole('option', { name: 'Test Clinic B (synthetic)' }))
    expect(screen.getByText('Facility: Test Clinic B (synthetic)')).toBeInTheDocument()
  })

  it('shows a not-found page for unknown paths', async () => {
    fakeBackend({ 'GET /api/v1/me': { body: me() } })

    renderApp('/no-such-page')

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })

  it('signs out of GCare and then Keycloak, sending the CSRF token', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-test-token; path=/'
    const fetchMock = fakeBackend({
      'GET /api/v1/me': { body: me() },
      'POST /api/v1/auth/logout': { body: { logout_url: 'http://keycloak.test.invalid/logout?id_token_hint=x' } },
    })
    const assign = vi.spyOn(browser, 'assign').mockImplementation(() => {})

    renderApp()

    await userEvent.click(await screen.findByRole('button', { name: 'Account: Test Admin' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Sign out' }))

    await waitFor(() => expect(assign).toHaveBeenCalledWith('http://keycloak.test.invalid/logout?id_token_hint=x'))
    const logoutCall = fetchMock.mock.calls.find(([url]) => url === '/api/v1/auth/logout')
    if (!logoutCall) throw new Error('logout was not called')
    const headers = logoutCall[1]?.headers as Record<string, string>
    expect(headers['X-XSRF-TOKEN']).toBe('csrf-test-token')
  })

  it('home page has no accessibility violations', async () => {
    fakeBackend({ 'GET /api/v1/me': { body: me() } })
    const { container } = renderApp()
    await screen.findByRole('heading', { name: 'Welcome, Test Admin' })
    await expectNoAxeViolations(container)
  })
})

describe('facilities', () => {
  it('lists facilities with text status and opens a facility', async () => {
    fakeBackend({
      'GET /api/v1/me': { body: me() },
      'GET /api/v1/facilities': { body: [FACILITY_A, FACILITY_B] },
      [`GET /api/v1/facilities/${FACILITY_A.id}/departments`]: {
        body: [{ id: 'd1', facility_id: FACILITY_A.id, code: 'OPD', name: 'Outpatient (synthetic)', status: 'ACTIVE' }],
      },
    })

    const { container } = renderApp('/facilities')

    const table = await screen.findByRole('table', { name: 'Facilities you can view' })
    expect(within(table).getByText('Active')).toBeInTheDocument()
    expect(within(table).getByText('Inactive')).toBeInTheDocument()
    await expectNoAxeViolations(container)

    await userEvent.click(within(table).getByRole('link', { name: FACILITY_A.name }))
    expect(await screen.findByRole('table', { name: 'Departments' })).toHaveTextContent('Outpatient (synthetic)')
    await expectNoAxeViolations(container)
  })

  it('shows an empty state', async () => {
    fakeBackend({ 'GET /api/v1/me': { body: me() }, 'GET /api/v1/facilities': { body: [] } })

    renderApp('/facilities')

    expect(await screen.findByText('No facilities')).toBeInTheDocument()
  })

  it('shows access denied when the API refuses', async () => {
    fakeBackend({
      'GET /api/v1/me': { body: me() },
      'GET /api/v1/facilities': { status: 403, body: { code: 'FORBIDDEN', message: 'Access denied' } },
    })

    renderApp('/facilities')

    expect(await screen.findByText('You do not have access to this')).toBeInTheDocument()
  })

  it('shows the error reference and retries', async () => {
    let calls = 0
    fakeBackend({
      'GET /api/v1/me': { body: me() },
      'GET /api/v1/facilities': () =>
        ++calls <= 2
          ? {
              status: 500,
              body: { code: 'INTERNAL_ERROR', message: 'Unexpected error', correlation_id: 'corr-5678efgh' },
            }
          : { body: [FACILITY_A] },
    })

    renderApp('/facilities')

    const alert = await screen.findByRole('alert', {}, { timeout: 3000 })
    expect(alert).toHaveTextContent('Reference for support: corr-5678efgh')
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('table', { name: 'Facilities you can view' })).toBeInTheDocument()
  })

  it('returns to sign-in with an explanation when the session ends mid-use', async () => {
    let meCalls = 0
    fakeBackend({
      'GET /api/v1/me': () => (++meCalls === 1 ? { body: me() } : UNAUTHENTICATED),
      'GET /api/v1/facilities': UNAUTHENTICATED,
    })

    renderApp('/facilities')

    expect(await screen.findByText('Session ended')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument()
  })
})
