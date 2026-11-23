import { Alert, Button, Center, Group, Loader, Stack, Text, ThemeIcon, Title } from '@mantine/core'
import { IconAlertTriangle, IconCalendar, IconClock, IconInbox, IconLock, IconRefresh } from '@tabler/icons-react'
import type { UseQueryResult } from '@tanstack/react-query'
import { useEffect, useState, type CSSProperties, type ReactNode } from 'react'
import { ApiRequestError } from '../api/client'
import { formatDate, formatTime } from './time'

/**
 * Standard list/page states (02-UI-UX: every list has loading, empty, error, permission-denied
 * and retry states). Each state has visible text, not only an icon or colour.
 */

export function LoadingState({ label = 'Loading…' }: { label?: string }) {
  return (
    <Center py="xl" role="status" aria-live="polite">
      <Group gap="sm">
        <Loader size="sm" aria-hidden />
        <Text>{label}</Text>
      </Group>
    </Center>
  )
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <Stack align="center" gap="xs" py="xl" role="status">
      <IconInbox size={32} aria-hidden />
      <Text fw={600}>{title}</Text>
      {children && <Text c="dimmed">{children}</Text>}
    </Stack>
  )
}

export function ForbiddenState({ children }: { children?: ReactNode }) {
  return (
    <Alert
      variant="light"
      color="orange"
      title="You do not have access to this"
      icon={<IconLock aria-hidden />}
      role="alert"
    >
      {children ??
        'Your account does not have permission for this page or action. If you need it, ask your administrator.'}
    </Alert>
  )
}

/** Error with a safe message, the correlation ID for support, and a retry button. */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const apiError = error instanceof ApiRequestError ? error : null
  if (apiError?.isForbidden) return <ForbiddenState />
  const message = apiError?.message ?? 'Something went wrong.'
  return (
    <Alert
      variant="light"
      color="red"
      title="This could not be loaded"
      icon={<IconAlertTriangle aria-hidden />}
      role="alert"
    >
      <Stack gap="xs">
        <Text>{message}</Text>
        {apiError?.correlationId && (
          <Text size="sm">
            Reference for support: <code>{apiError.correlationId}</code>
          </Text>
        )}
        {onRetry && (
          <Group>
            <Button variant="default" size="xs" leftSection={<IconRefresh size={14} aria-hidden />} onClick={onRetry}>
              Try again
            </Button>
          </Group>
        )}
      </Stack>
    </Alert>
  )
}

/**
 * Renders the right state for a query: loading, error (403 → access denied), empty, or data.
 */
export function QueryState<T>({
  query,
  loadingLabel,
  isEmpty,
  empty,
  children,
}: {
  query: UseQueryResult<T>
  loadingLabel?: string
  isEmpty?: (data: T) => boolean
  empty?: ReactNode
  children: (data: T) => ReactNode
}) {
  if (query.isPending) return <LoadingState label={loadingLabel} />
  if (query.isError) return <ErrorState error={query.error} onRetry={() => void query.refetch()} />
  if (isEmpty?.(query.data)) return <>{empty ?? <EmptyState title="Nothing to show" />}</>
  return <>{children(query.data)}</>
}

/** Page title. Receives focus after navigation so screen-reader users hear where they are. */
export function PageTitle({ children }: { children: ReactNode }) {
  return (
    <Title order={1} id="page-title" tabIndex={-1} mb="md">
      {children}
    </Title>
  )
}

/** Page title with an optional one-line description and actions on the right (wraps on small screens). */
export function PageHeader({
  title,
  description,
  actions,
  eyebrow,
  icon,
  meta,
}: {
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  /** Optional small label above the title (the breadcrumb in the header already names the section). */
  eyebrow?: ReactNode
  /** With an icon the header becomes a "hero" band: icon tile, soft accent glow and summary chips. */
  icon?: ReactNode
  /** Short facts shown as chips under the description (hero form only). */
  meta?: ReactNode[]
}) {
  const text = (
    <Stack gap={2} miw={0}>
      {eyebrow && (
        <Text size="xs" fw={600} c="dimmed">
          {eyebrow}
        </Text>
      )}
      <Title order={1} id="page-title" tabIndex={-1}>
        {title}
      </Title>
      {description && (
        <Text size="sm" c="dimmed" maw={820}>
          {description}
        </Text>
      )}
      {icon && meta && meta.length > 0 && (
        <Group gap={6} mt={8}>
          {meta.map((m, i) => (
            <span key={i} className="gcare-chip">
              {m}
            </span>
          ))}
        </Group>
      )}
    </Stack>
  )
  if (icon) {
    return (
      <div className="gcare-hero">
        <Group justify="space-between" align="flex-start" wrap="wrap" gap="md" pos="relative">
          <Group align="flex-start" gap="md" wrap="nowrap" miw={0}>
            <ThemeIcon size={44} radius="md" className="gcare-hero-icon" aria-hidden>
              {icon}
            </ThemeIcon>
            {text}
          </Group>
          {actions && <Group gap="xs">{actions}</Group>}
        </Group>
      </div>
    )
  }
  return (
    <Group justify="space-between" align="flex-end" wrap="wrap" gap="sm" mb={2}>
      {text}
      {actions && <Group gap="xs">{actions}</Group>}
    </Group>
  )
}

/**
 * KPI card: tinted icon tile and an uppercase label, the number underneath, an optional note.
 * `tone` (a Mantine colour) tints the icon only; the label always names the figure.
 */
export function StatCard({
  label,
  value,
  icon,
  tone = 'clinical',
  hint,
}: {
  label: string
  value: ReactNode
  icon?: ReactNode
  tone?: string
  hint?: ReactNode
}) {
  return (
    <div className="gcare-kpi-card">
      <Group gap={10} wrap="nowrap" align="center">
        {icon && (
          <ThemeIcon size={32} radius="md" variant="light" color={tone} aria-hidden>
            {icon}
          </ThemeIcon>
        )}
        <Text className="gcare-kpi-label" lineClamp={2}>
          {label}
        </Text>
      </Group>
      <Text className="gcare-stat-value" fz={24} fw={700} mt={10}>
        {value}
      </Text>
      {hint && (
        <Text size="xs" c="dimmed" mt={2}>
          {hint}
        </Text>
      )}
    </div>
  )
}

/**
 * Small coloured label for a type or status. Text stays readable on its tint (dark shade of the
 * colour in light mode, light shade in dark mode); `dot` adds a status dot.
 */
export function Pill({ color, dot, children }: { color: string; dot?: boolean; children: ReactNode }) {
  const style = {
    '--pill-bg-light': `var(--mantine-color-${color}-0)`,
    '--pill-fg-light': `color-mix(in srgb, var(--mantine-color-${color}-9), #000 35%)`,
    '--pill-bg-dark': `color-mix(in srgb, var(--mantine-color-${color}-6) 20%, transparent)`,
    '--pill-fg-dark': `var(--mantine-color-${color}-2)`,
  } as CSSProperties
  return (
    <span className="gcare-pill" data-dot={dot || undefined} style={style}>
      {children}
    </span>
  )
}

/** Today's date and the time in the facility's zone, for page headers; refreshes every minute. */
export function TodayStamp() {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 60_000)
    return () => clearInterval(t)
  }, [])
  return (
    <Stack gap={2} align="flex-end">
      <Group gap={6} wrap="nowrap">
        <IconCalendar size={14} aria-hidden style={{ color: 'var(--gcare-text-2)' }} />
        <Text size="sm" c="dimmed">
          {formatDate(now)}
        </Text>
      </Group>
      <Group gap={6} wrap="nowrap">
        <IconClock size={14} aria-hidden style={{ color: 'var(--gcare-text-2)' }} />
        <Text size="sm" c="dimmed">
          {formatTime(now, { hour: '2-digit', minute: '2-digit' })}
        </Text>
      </Group>
    </Stack>
  )
}
