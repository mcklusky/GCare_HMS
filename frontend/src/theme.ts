import { createTheme } from '@mantine/core'

/**
 * GCare design tokens on top of Mantine (ADR-013). Keep text/background contrast at WCAG 2.2 AA.
 * Customer branding (01-PRD onboarding/branding) will extend this through configuration, not forks.
 */
export const theme = createTheme({
  primaryColor: 'blue',
  primaryShade: { light: 7, dark: 5 },
  defaultRadius: 'sm',
  focusRing: 'always',
  fontFamily: "system-ui, 'Segoe UI', Roboto, sans-serif",
  headings: { fontFamily: "system-ui, 'Segoe UI', Roboto, sans-serif" },
})
