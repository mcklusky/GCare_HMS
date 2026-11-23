import {
  ActionIcon,
  Badge,
  Button,
  Card,
  createTheme,
  Input,
  Modal,
  NavLink,
  Paper,
  Table,
  type MantineColorsTuple,
} from '@mantine/core'

/**
 * GCare design tokens on top of Mantine (ADR-013): the "calm clinical" look (2026-11-23). A deep
 * teal accent on a soft mint-grey page with white cards, rounded corners and quiet shadows, in light and dark.
 * Keep text/background contrast at WCAG 2.2 AA: filled teal uses shade 7 #256a6c (white text 6.2:1).
 * No web fonts are downloaded (hospital networks may be offline; no third-party requests): Inter is
 * used when installed, otherwise the system font. Customer branding (01-PRD) extends this through
 * configuration, not forks.
 */
const clinical: MantineColorsTuple = [
  '#eef7f7',
  '#d9eeee',
  '#b4dcdd',
  '#88c5c7',
  '#5fadb0',
  '#3f9497',
  '#2f7d80',
  '#256a6c',
  '#1d5557',
  '#163f41',
]

const fontFamily = "Inter, 'Segoe UI Variable', 'Segoe UI', system-ui, -apple-system, Roboto, sans-serif"

export const theme = createTheme({
  colors: { clinical },
  primaryColor: 'clinical',
  primaryShade: { light: 7, dark: 6 },
  defaultRadius: 'md',
  focusRing: 'auto',
  cursorType: 'pointer',
  fontFamily,
  fontFamilyMonospace: "'Cascadia Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",
  // High-density console scale (round 5): 14 px body, 13 px secondary text, tight spacing.
  fontSizes: { xs: '0.75rem', sm: '0.8125rem', md: '0.875rem', lg: '1rem', xl: '1.125rem' },
  lineHeights: { xs: '1.4', sm: '1.45', md: '1.5', lg: '1.5', xl: '1.45' },
  spacing: { xs: '0.375rem', sm: '0.625rem', md: '0.875rem', lg: '1.25rem', xl: '1.75rem' },
  radius: { xs: '4px', sm: '6px', md: '8px', lg: '10px', xl: '14px' },
  headings: {
    fontFamily,
    fontWeight: '650',
    sizes: {
      h1: { fontSize: '1.375rem', lineHeight: '1.25' },
      h2: { fontSize: '1rem', lineHeight: '1.35' },
      h3: { fontSize: '0.9375rem', lineHeight: '1.4' },
      h4: { fontSize: '0.875rem', lineHeight: '1.4' },
      h5: { fontSize: '0.8125rem', lineHeight: '1.4' },
    },
  },
  shadows: {
    xs: '0 1px 2px rgba(15, 23, 42, 0.04)',
    sm: '0 1px 2px rgba(15, 23, 42, 0.06), 0 1px 3px rgba(15, 23, 42, 0.04)',
    md: '0 8px 24px rgba(15, 23, 42, 0.08), 0 2px 6px rgba(15, 23, 42, 0.04)',
    lg: '0 16px 48px rgba(15, 23, 42, 0.14)',
  },
  components: {
    Card: Card.extend({ defaultProps: { radius: 'lg', padding: 'md', shadow: 'xs' } }),
    Paper: Paper.extend({ defaultProps: { radius: 'lg' } }),
    Button: Button.extend({ defaultProps: { radius: 'md', size: 'sm' } }),
    ActionIcon: ActionIcon.extend({ defaultProps: { radius: 'md', variant: 'subtle' } }),
    Badge: Badge.extend({
      defaultProps: { radius: 'sm', variant: 'light', size: 'sm' },
      styles: { root: { textTransform: 'none', fontWeight: 600 } },
    }),
    Input: Input.extend({ defaultProps: { radius: 'md' } }),
    Modal: Modal.extend({ defaultProps: { radius: 'lg', overlayProps: { backgroundOpacity: 0.35, blur: 3 } } }),
    NavLink: NavLink.extend({ defaultProps: { variant: 'subtle' } }),
    Table: Table.extend({
      defaultProps: { highlightOnHover: true, verticalSpacing: 7, horizontalSpacing: 'sm', fz: 'sm' },
    }),
  },
})
