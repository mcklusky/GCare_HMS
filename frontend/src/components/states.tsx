import { Alert, Button, Card, Center, Group, Loader, Stack, Text, ThemeIcon, Title } from '@mantine/core'
import { IconAlertTriangle, IconInbox, IconLock, IconRefresh } from '@tabler/icons-react'
import type { UseQueryResult } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { ApiRequestError } from '../api/client'

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
}: {
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
}) {
  return (
    <Group justify="space-between" align="flex-end" wrap="wrap" gap="sm" mb="xs">
      <Stack gap={4}>
        <Title order={1} id="page-title" tabIndex={-1}>
          {title}
        </Title>
        {description && (
          <Text size="sm" c="dimmed" maw={720}>
            {description}
          </Text>
        )}
      </Stack>
      {actions && <Group gap="sm">{actions}</Group>}
    </Group>
  )
}

/** A headline number with its label, e.g. on the home page. `tone` colours the icon only (never the only signal). */
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
    <Card withBorder>
      <Group justify="space-between" align="flex-start" wrap="nowrap">
        <Stack gap={4}>
          <Text size="sm" c="dimmed" fw={500}>
            {label}
          </Text>
          <Text className="gcare-stat-value" fz={28} fw={700}>
            {value}
          </Text>
          {hint && (
            <Text size="xs" c="dimmed">
              {hint}
            </Text>
          )}
        </Stack>
        {icon && (
          <ThemeIcon size={40} radius="md" variant="light" color={tone} aria-hidden>
            {icon}
          </ThemeIcon>
        )}
      </Group>
    </Card>
  )
}
