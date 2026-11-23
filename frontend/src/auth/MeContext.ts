import { createContext, useContext } from 'react'
import type { Me } from '../api/types'

export const MeContext = createContext<Me | null>(null)

/** The signed-in user; only usable below SessionGate. */
export function useCurrentMe(): Me {
  const me = useContext(MeContext)
  if (!me) throw new Error('useCurrentMe must be used inside SessionGate')
  return me
}
