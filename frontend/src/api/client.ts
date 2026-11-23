/**
 * Thin fetch wrapper for the GCare API (docs/05-API.md).
 *
 * - Same-origin requests with the session cookie; the browser never sees a token (ADR-012).
 * - State-changing requests carry the CSRF token from the XSRF-TOKEN cookie.
 * - Errors arrive as {code, message, field_errors, correlation_id} and become ApiRequestError.
 */

export interface ApiErrorBody {
  code: string
  message: string
  field_errors?: Record<string, string[]>
  correlation_id?: string | null
}

export class ApiRequestError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: Record<string, string[]>
  readonly correlationId: string | null
  /** The full parsed error body (some errors carry extra data, e.g. duplicate candidates). */
  readonly body: unknown

  constructor(status: number, body: Partial<ApiErrorBody> | null, fallbackCorrelationId: string | null) {
    super(body?.message ?? `Request failed (${status})`)
    this.name = 'ApiRequestError'
    this.status = status
    this.code = body?.code ?? (status === 0 ? 'NETWORK_ERROR' : 'UNEXPECTED_ERROR')
    this.fieldErrors = body?.field_errors ?? {}
    this.correlationId = body?.correlation_id ?? fallbackCorrelationId
    this.body = body
  }

  get isUnauthenticated(): boolean {
    return this.status === 401
  }

  get isForbidden(): boolean {
    return this.status === 403
  }

  get isNotFound(): boolean {
    return this.status === 404
  }
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

export function readCookie(name: string): string | null {
  const prefix = `${name}=`
  for (const part of document.cookie.split(';')) {
    const trimmed = part.trim()
    if (trimmed.startsWith(prefix)) return decodeURIComponent(trimmed.slice(prefix.length))
  }
  return null
}

export interface RequestOptions {
  method?: string
  body?: unknown
  signal?: AbortSignal
}

export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase()
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (!SAFE_METHODS.has(method)) {
    const csrf = readCookie('XSRF-TOKEN')
    if (csrf) headers['X-XSRF-TOKEN'] = csrf
  }

  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      credentials: 'same-origin',
      signal: options.signal,
    })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    throw new ApiRequestError(0, { message: 'The server could not be reached. Check the connection and try again.' }, null)
  }

  if (!response.ok) {
    const correlationId = response.headers.get('X-Correlation-Id')
    let body: Partial<ApiErrorBody> | null = null
    try {
      body = (await response.json()) as Partial<ApiErrorBody>
    } catch {
      body = null
    }
    throw new ApiRequestError(response.status, body, correlationId)
  }

  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}
