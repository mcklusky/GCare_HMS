import { createContext, useContext } from 'react'
import type { NavSection } from './navigation'

/** The current page's place in the navigation, provided by the shell; null outside the shell (e.g. tests). */
export const PageTrailContext = createContext<{ section: NavSection; label: string } | null>(null)

export const usePageTrail = () => useContext(PageTrailContext)
