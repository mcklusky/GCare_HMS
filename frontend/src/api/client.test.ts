import { describe, expect, it, vi } from 'vitest'
import { ApiRequestError, apiFetch } from './client'

function respond(status: number, body: unknown, headers: Record<string, string> = {}) {
  return vi.fn(async () => new Response(body === null ? null : JSON.stringify(body), { status, headers }))
}

describe('apiFetch', () => {
  it('sends the CSRF token on state-changing requests only', async () => {
    document.cookie = 'XSRF-TOKEN=abc%20123; path=/'
    const fetchMock = respond(200, {})
    vi.stubGlobal('fetch', fetchMock)

    await apiFetch('/api/v1/x')
    await apiFetch('/api/v1/x', { method: 'POST', body: { a: 1 } })

    const [, getInit] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    const [, postInit] = fetchMock.mock.calls[1] as unknown as [string, RequestInit]
    expect((getInit.headers as Record<string, string>)['X-XSRF-TOKEN']).toBeUndefined()
    expect((postInit.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('abc 123')
    expect(postInit.credentials).toBe('same-origin')
    expect(postInit.body).toBe('{"a":1}')
  })

  it('turns the standard error body into ApiRequestError', async () => {
    vi.stubGlobal(
      'fetch',
      respond(422, {
        code: 'VALIDATION_FAILED',
        message: 'Check the highlighted fields',
        field_errors: { name: ['must not be blank'] },
        correlation_id: 'corr-0001',
      }),
    )

    const error = await apiFetch('/api/v1/x').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiRequestError)
    expect(error).toMatchObject({
      status: 422,
      code: 'VALIDATION_FAILED',
      message: 'Check the highlighted fields',
      fieldErrors: { name: ['must not be blank'] },
      correlationId: 'corr-0001',
    })
  })

  it('falls back to the correlation header when the body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>', { status: 502, headers: { 'X-Correlation-Id': 'corr-hdr' } })))

    const error = (await apiFetch('/api/v1/x').catch((e: unknown) => e)) as ApiRequestError

    expect(error.status).toBe(502)
    expect(error.code).toBe('UNEXPECTED_ERROR')
    expect(error.correlationId).toBe('corr-hdr')
  })

  it('reports an unreachable server as a network error', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))))

    const error = (await apiFetch('/api/v1/x').catch((e: unknown) => e)) as ApiRequestError

    expect(error.code).toBe('NETWORK_ERROR')
    expect(error.message).toMatch(/could not be reached/)
  })
})
