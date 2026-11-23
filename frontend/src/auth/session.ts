import { QueryCache, QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSyncExternalStore } from 'react'
import { ApiRequestError, apiFetch } from '../api/client'
import type { LogoutResponse, Me } from '../api/types'

/**
 * Session state (ADR-012). The backend owns the session; the frontend only asks "who am I?"
 * (GET /api/v1/me) and reacts when any request answers 401. Nothing about the session or the
 * user is stored in browser storage.
 */

export const ME_QUERY_KEY = ['me'] as const

/** Where the browser goes to sign in (Spring Security starts the Keycloak flow there). */
export const SIGN_IN_PATH = '/oauth2/authorization/keycloak'

// --- "your session ended" flag, set when a signed-in request suddenly gets 401 ---------------
let sessionEnded = false
const listeners = new Set<() => void>()

function setSessionEnded(value: boolean) {
  if (sessionEnded === value) return
  sessionEnded = value
  listeners.forEach((l) => l())
}

export function useSessionEnded(): boolean {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    () => sessionEnded,
  )
}

/**
 * Query client for the app. Any 401 from a data request means the session is gone (expired,
 * signed out elsewhere, account disabled): drop the cached user so the sign-in screen appears.
 */
export function createQueryClient(): QueryClient {
  const client: QueryClient = new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        if (
          error instanceof ApiRequestError &&
          error.isUnauthenticated &&
          query.queryKey[0] !== ME_QUERY_KEY[0]
        ) {
          setSessionEnded(true)
          client.removeQueries({ predicate: (q) => q.queryKey[0] !== ME_QUERY_KEY[0] })
          client.setQueryData(ME_QUERY_KEY, null)
        }
      },
    }),
    defaultOptions: {
      queries: {
        // Never retry authorization failures; retry other failures once.
        retry: (failureCount, error) =>
          !(error instanceof ApiRequestError && error.status >= 400 && error.status < 500) &&
          failureCount < 1,
        refetchOnWindowFocus: false,
      },
    },
  })
  return client
}

async function fetchMe(): Promise<Me | null> {
  try {
    const me = await apiFetch<Me>('/api/v1/me')
    setSessionEnded(false)
    return me
  } catch (error) {
    if (error instanceof ApiRequestError && error.isUnauthenticated) return null
    throw error
  }
}

/** The signed-in user, `null` when signed out. */
export function useMe() {
  return useQuery({ queryKey: ME_QUERY_KEY, queryFn: fetchMe, staleTime: 60_000 })
}

export function hasPermission(me: Me | null | undefined, permission: string): boolean {
  return !!me && me.permissions.includes(permission)
}

/** Full-page navigation; tests replace it. */
export const browser = {
  assign(url: string) {
    window.location.assign(url)
  },
}

export function signIn() {
  browser.assign(SIGN_IN_PATH)
}

/**
 * Signs out of GCare, then of Keycloak (the backend returns the provider's logout URL). Without
 * the second step, the next sign-in on this computer would not ask for a password.
 */
export function useSignOut() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiFetch<LogoutResponse>('/api/v1/auth/logout', { method: 'POST' }),
    onSuccess: (result) => {
      queryClient.clear()
      browser.assign(result.logout_url ?? '/')
    },
  })
}
