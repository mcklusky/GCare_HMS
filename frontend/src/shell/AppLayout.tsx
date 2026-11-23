import { Anchor, AppShell, Badge, Burger, Group, Menu, NavLink, Select, Text, UnstyledButton } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { IconChevronDown, IconLogout, IconUserCircle } from '@tabler/icons-react'
import { useEffect, useRef } from 'react'
import { NavLink as RouterNavLink, Outlet, useLocation } from 'react-router'
import type { Me } from '../api/types'
import { useFacilityContext } from '../auth/facilityContextValue'
import { useSignOut } from '../auth/session'
import { ErrorState } from '../components/states'
import { visibleNavItems } from './navigation'

const environmentLabel: string | undefined =
  import.meta.env.VITE_ENVIRONMENT_LABEL || (import.meta.env.DEV ? 'Development · synthetic data only' : undefined)

/**
 * Persistent application shell (02-UI-UX): product + facility identity, role-scoped navigation,
 * facility context, account and sign-out.
 */
export function AppLayout({ me }: { me: Me }) {
  const [navOpened, nav] = useDisclosure(false)
  const closeNav = nav.close
  const location = useLocation()
  const signOut = useSignOut()
  const { facilities, current, select } = useFacilityContext()
  const firstRender = useRef(true)

  // Move focus to the new page's title after navigation (screen readers announce it).
  useEffect(() => {
    closeNav()
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    document.getElementById('page-title')?.focus()
  }, [location.pathname, closeNav])

  const items = visibleNavItems(me.permissions)

  return (
    <AppShell
      header={{ height: 60 }}
      navbar={{ width: 240, breakpoint: 'sm', collapsed: { mobile: !navOpened } }}
      padding="md"
    >
      <Anchor href="#main" className="skip-link">
        Skip to main content
      </Anchor>

      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <Burger
              opened={navOpened}
              onClick={nav.toggle}
              hiddenFrom="sm"
              size="sm"
              aria-label={navOpened ? 'Close navigation' : 'Open navigation'}
            />
            <Text fw={700} size="lg">
              GCare HMS
            </Text>
            <Text c="dimmed" visibleFrom="md" truncate>
              {me.organization.name}
            </Text>
            {environmentLabel && (
              <Badge color="yellow" variant="filled" visibleFrom="sm">
                {environmentLabel}
              </Badge>
            )}
          </Group>

          <Group gap="sm" wrap="nowrap">
            {facilities.length > 1 ? (
              <Select
                aria-label="Working facility"
                data={facilities.map((f) => ({ value: f.id, label: f.name }))}
                value={current?.id ?? null}
                onChange={(id) => id && select(id)}
                allowDeselect={false}
                w={220}
                visibleFrom="sm"
              />
            ) : (
              current && (
                <Text size="sm" visibleFrom="sm" aria-label="Working facility">
                  {current.name}
                </Text>
              )
            )}

            <Menu position="bottom-end" withinPortal>
              <Menu.Target>
                <UnstyledButton aria-label={`Account: ${me.display_name}`}>
                  <Group gap={6} wrap="nowrap">
                    <IconUserCircle size={22} aria-hidden />
                    <Text size="sm" visibleFrom="sm">
                      {me.display_name}
                    </Text>
                    <IconChevronDown size={14} aria-hidden />
                  </Group>
                </UnstyledButton>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Label>{me.username}</Menu.Label>
                <Menu.Item
                  leftSection={<IconLogout size={16} aria-hidden />}
                  onClick={() => signOut.mutate()}
                  disabled={signOut.isPending}
                >
                  Sign out
                </Menu.Item>
              </Menu.Dropdown>
            </Menu>
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="xs" aria-label="Main navigation" component="nav">
        {items.map((item) => (
          <NavLink
            key={item.to}
            component={RouterNavLink}
            to={item.to}
            end={item.to === '/'}
            label={item.label}
            leftSection={<item.icon size={18} aria-hidden />}
          />
        ))}
      </AppShell.Navbar>

      <AppShell.Main id="main">
        {signOut.isError && <ErrorState error={signOut.error} onRetry={() => signOut.mutate()} />}
        <Outlet />
      </AppShell.Main>
    </AppShell>
  )
}
