import { createContext, useContext } from 'react'
import type { Facility } from '../api/types'

export interface FacilityContextValue {
  facilities: Facility[]
  current: Facility | null
  select: (facilityId: string) => void
}

export const FacilityCtx = createContext<FacilityContextValue | null>(null)

/** The facility the user is working in (see FacilityProvider). */
export function useFacilityContext(): FacilityContextValue {
  const value = useContext(FacilityCtx)
  if (!value) throw new Error('useFacilityContext must be used inside FacilityProvider')
  return value
}
