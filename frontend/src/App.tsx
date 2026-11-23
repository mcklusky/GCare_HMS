import { useEffect, useState } from 'react'

type BackendStatus = 'checking' | 'up' | 'unavailable'

const statusText: Record<BackendStatus, string> = {
  checking: 'Checking backend…',
  up: 'Backend is up',
  unavailable: 'Backend is unavailable',
}

/**
 * Foundation placeholder (task 1.1). The real application shell, sign-in and role-filtered
 * navigation arrive in tasks 1.3–1.4. Status is shown as text, not colour alone (02-UI-UX).
 */
export default function App() {
  const [status, setStatus] = useState<BackendStatus>('checking')

  useEffect(() => {
    const controller = new AbortController()
    fetch('/actuator/health', { signal: controller.signal })
      .then(async (res) => {
        const body = res.ok ? await res.json() : null
        setStatus(body?.status === 'UP' ? 'up' : 'unavailable')
      })
      .catch((err: unknown) => {
        if (!(err instanceof DOMException && err.name === 'AbortError')) setStatus('unavailable')
      })
    return () => controller.abort()
  }, [])

  return (
    <main>
      <h1>GCare HMS</h1>
      <p>Foundation build. Synthetic data only.</p>
      <p role="status" aria-live="polite">
        {statusText[status]}
      </p>
    </main>
  )
}
