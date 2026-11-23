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
  primaryShade: { light: 7, dark: 7 },
  defaultRadius: 'md',
  focusRing: 'always',
  cursorType: 'pointer',
  fontFamily,
  fontFamilyMonospace: "'Cascadia Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",
  headings: {
    fontFamily,
    fontWeight: '650',
    sizes: {
      h1: { fontSize: '1.625rem', lineHeight: '1.2' },
      h2: { fontSize: '1.25rem', lineHeight: '1.3' },
      h3: { fontSize: '1.125rem', lineHeight: '1.35' },
    },
  },
  shadows: {
    xs: '0 1px 2px rgba(15, 23, 42, 0.06)',
    sm: '0 1px 3px rgba(15, 23, 42, 0.08), 0 1px 2px rgba(15, 23, 42, 0.04)',
    md: '0 4px 12px rgba(15, 23, 42, 0.08)',
  },
  components: {
    Card: Card.extend({ defaultProps: { radius: 'lg', padding: 'md', shadow: 'xs' } }),
    Paper: Paper.extend({ defaultProps: { radius: 'lg' } }),
    Button: Button.extend({ defaultProps: { radius: 'md' } }),
    ActionIcon: ActionIcon.extend({ defaultProps: { radius: 'md', variant: 'subtle' } }),
    Badge: Badge.extend({ defaultProps: { radius: 'sm', variant: 'light' } }),
    Input: Input.extend({ defaultProps: { radius: 'md' } }),
    Modal: Modal.extend({ defaultProps: { radius: 'lg', overlayProps: { backgroundOpacity: 0.4, blur: 2 } } }),
    NavLink: NavLink.extend({ defaultProps: { variant: 'subtle' } }),
    Table: Table.extend({ defaultProps: { highlightOnHover: true, verticalSpacing: 'xs', horizontalSpacing: 'sm' } }),
  },
})
