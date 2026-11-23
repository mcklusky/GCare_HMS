import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from './App'

function mockFetch(impl: () => Promise<Response>) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(impl)
}

describe('App', () => {
  it('shows the product name and a checking state first', () => {
    mockFetch(() => new Promise(() => {}))
    render(<App />)
    expect(screen.getByRole('heading', { name: 'GCare HMS' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Checking backend')
  })

  it('reports the backend as up when health is UP', async () => {
    mockFetch(() => Promise.resolve(Response.json({ status: 'UP' })))
    render(<App />)
    expect(await screen.findByText('Backend is up')).toBeInTheDocument()
  })

  it('reports the backend as unavailable on a network error', async () => {
    mockFetch(() => Promise.reject(new TypeError('Failed to fetch')))
    render(<App />)
    expect(await screen.findByText('Backend is unavailable')).toBeInTheDocument()
  })

  it('reports the backend as unavailable on an error response', async () => {
    mockFetch(() => Promise.resolve(new Response(null, { status: 503 })))
    render(<App />)
    expect(await screen.findByText('Backend is unavailable')).toBeInTheDocument()
  })
})
