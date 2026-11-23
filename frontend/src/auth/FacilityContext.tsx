import { useMemo, useState, type ReactNode } from 'react'
import type { Facility } from '../api/types'
import { FacilityCtx, type FacilityContextValue } from './facilityContextValue'

/**
 * The facility the user is working in (02-UI-UX: facility context in the shell). Choices come
 * from GET /api/v1/me, i.e. facilities where the user holds a permission. This is a display and
 * default-filter context only: the backend checks scope on every request.
 *
 * Kept in memory only (no browser storage).
 */
export function FacilityProvider({ facilities, children }: { facilities: Facility[]; children: ReactNode }) {
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const value = useMemo<FacilityContextValue>(() => {
    const current = facilities.find((f) => f.id === selectedId) ?? facilities[0] ?? null
    return {
      facilities,
      current,
      select: (id) => {
        if (facilities.some((f) => f.id === id)) setSelectedId(id)
      },
    }
  }, [facilities, selectedId])

  return <FacilityCtx.Provider value={value}>{children}</FacilityCtx.Provider>
}
