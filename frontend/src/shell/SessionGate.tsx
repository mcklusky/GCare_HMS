import { Center, Container, Stack } from '@mantine/core'
import type { ReactNode } from 'react'
import { useRouteError } from 'react-router'
import { FacilityProvider } from '../auth/FacilityContext'
import { MeContext, useCurrentMe } from '../auth/MeContext'
import { hasPermission, useMe, useSessionEnded } from '../auth/session'
import { ErrorState, ForbiddenState, LoadingState, PageTitle } from '../components/states'
import { HomePage } from '../pages/HomePage'
import { SignInPage } from '../pages/SignInPage'
import { AppLayout } from './AppLayout'

/** Shows sign-in, a loading or error state, or the app shell for the signed-in user. */
export function SessionGate() {
  const me = useMe()
  const sessionEnded = useSessionEnded()

  if (me.isPending) {
    return (
      <Center mih="100vh">
        <LoadingState label="Checking your session…" />
      </Center>
    )
  }
  if (me.isError) {
    return (
      <Container size="sm" py="xl" component="main" id="main">
        <ErrorState error={me.error} onRetry={() => void me.refetch()} />
      </Container>
    )
  }
  if (!me.data) return <SignInPage sessionEnded={sessionEnded} />

  return (
    <MeContext.Provider value={me.data}>
      <FacilityProvider facilities={me.data.facilities}>
        <AppLayout me={me.data} />
      </FacilityProvider>
    </MeContext.Provider>
  )
}

/**
 * Hides a page from users without the permission. Usability only: the API enforces the same
 * permission and facility scope on every request.
 */
export function RequirePermission({ permission, children }: { permission: string; children: ReactNode }) {
  const me = useCurrentMe()
  if (hasPermission(me, permission)) return <>{children}</>
  return (
    <Stack>
      <PageTitle>Access denied</PageTitle>
      <ForbiddenState />
    </Stack>
  )
}

export function HomeRoute() {
  return <HomePage me={useCurrentMe()} />
}

export function RouteError() {
  const error = useRouteError()
  return (
    <Container size="sm" py="xl" component="main" id="main">
      <ErrorState error={error} onRetry={() => window.location.reload()} />
    </Container>
  )
}
