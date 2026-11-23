import { MantineProvider } from '@mantine/core'
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { createQueryClient } from './auth/session'
import { routes } from './routes'
import { theme } from './theme'

/** Theme and data providers shared by the app and tests. */
export function AppProviders({
  queryClient,
  children,
  env,
}: {
  queryClient: QueryClient
  children: ReactNode
  /** 'test' turns off Mantine transitions and portals in unit tests. */
  env?: 'default' | 'test'
}) {
  return (
    <MantineProvider theme={theme} env={env} defaultColorScheme="auto">
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    </MantineProvider>
  )
}

export default function App() {
  const [queryClient] = useState(createQueryClient)
  const [router] = useState(() => createBrowserRouter(routes))
  return (
    <AppProviders queryClient={queryClient}>
      <RouterProvider router={router} />
    </AppProviders>
  )
}
