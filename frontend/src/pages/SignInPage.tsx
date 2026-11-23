import { Alert, Button, Center, Paper, Stack, Text, Title } from '@mantine/core'
import { IconAlertTriangle, IconClockHour4, IconLogin } from '@tabler/icons-react'
import { useSearchParams } from 'react-router'
import { signIn } from '../auth/session'

/** Messages for the backend's `login_error` values (identity module, SignInHandlers). */
const LOGIN_ERRORS: Record<string, string> = {
  account_not_enabled:
    'You signed in, but your account is not set up or is disabled in GCare. Ask your administrator to enable it.',
  sign_in_failed: 'Sign-in did not complete. Please try again.',
}

/** Shown whenever there is no signed-in session. Sign-in itself happens on the Keycloak page. */
export function SignInPage({ sessionEnded = false }: { sessionEnded?: boolean }) {
  const [params] = useSearchParams()
  const loginError = params.get('login_error')
  const errorMessage = loginError ? (LOGIN_ERRORS[loginError] ?? LOGIN_ERRORS.sign_in_failed) : null

  return (
    <Center mih="100vh" p="md" component="main" id="main">
      <Paper withBorder shadow="sm" p="xl" maw={420} w="100%">
        <Stack>
          <Title order={1} size="h2" id="page-title" tabIndex={-1}>
            GCare HMS
          </Title>
          {errorMessage && (
            <Alert color="red" variant="light" icon={<IconAlertTriangle aria-hidden />} role="alert" title="Not signed in">
              {errorMessage}
            </Alert>
          )}
          {!errorMessage && sessionEnded && (
            <Alert color="blue" variant="light" icon={<IconClockHour4 aria-hidden />} role="status" title="Session ended">
              Your session ended, for example after a period of inactivity. Sign in again to continue. Unsaved
              changes were not kept.
            </Alert>
          )}
          <Text>Sign in with your hospital account to continue.</Text>
          <Button leftSection={<IconLogin size={18} aria-hidden />} onClick={signIn} size="md">
            Sign in
          </Button>
          <Text size="sm" c="dimmed">
            Use only your own account. Access is recorded.
          </Text>
        </Stack>
      </Paper>
    </Center>
  )
}
