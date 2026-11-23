import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { vi } from 'vitest'
import type { Facility, Me } from '../api/types'
import { AppProviders } from '../App'
import { createQueryClient } from '../auth/session'
import { routes } from '../routes'

/** A fake backend response. */
export interface FakeResponse {
  status?: number
  body?: unknown
  headers?: Record<string, string>
}

export type FakeRoutes = Record<string, FakeResponse | ((init: RequestInit) => FakeResponse)>

/**
 * Replaces fetch with a table of "METHOD /path" → response. Unknown requests fail the test with
 * a 599 so missing fakes are obvious. Returns the mock to inspect calls.
 */
export function fakeBackend(table: FakeRoutes) {
  const mock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = typeof input === 'string' ? input : input.toString()
    const key = `${(init.method ?? 'GET').toUpperCase()} ${url}`
    const entry = table[key]
    if (!entry) return new Response(JSON.stringify({ code: 'NO_FAKE', message: key }), { status: 599 })
    const res = typeof entry === 'function' ? entry(init) : entry
    return new Response(res.body === undefined ? null : JSON.stringify(res.body), {
      status: res.status ?? 200,
      headers: { 'Content-Type': 'application/json', ...res.headers },
    })
  })
  vi.stubGlobal('fetch', mock)
  return mock
}

/** Renders the real routes at `path` with fresh providers. */
export function renderApp(path = '/') {
  const queryClient = createQueryClient()
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const view = render(
    <AppProviders queryClient={queryClient} env="test">
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return { ...view, router, queryClient }
}

// --- synthetic data -------------------------------------------------------------------------

export const FACILITY_A: Facility = {
  id: '00000000-0000-4000-8000-00000000a001',
  code: 'TEST-A',
  name: 'Test Hospital A (synthetic)',
  timezone: 'Africa/Monrovia',
  status: 'ACTIVE',
}

export const FACILITY_B: Facility = {
  id: '00000000-0000-4000-8000-00000000b001',
  code: 'TEST-B',
  name: 'Test Clinic B (synthetic)',
  timezone: 'Africa/Monrovia',
  status: 'INACTIVE',
}

export function me(overrides: Partial<Me> = {}): Me {
  return {
    id: '00000000-0000-4000-8000-000000000999',
    username: 'test.admin',
    display_name: 'Test Admin',
    organization: { id: '00000000-0000-4000-8000-000000000001', code: 'TEST', name: 'Test Health Org (synthetic)' },
    permissions: ['FACILITY_READ', 'USER_READ'],
    facilities: [FACILITY_A],
    ...overrides,
  }
}
