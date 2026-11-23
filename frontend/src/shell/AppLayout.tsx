import {
  ActionIcon,
  Anchor,
  AppShell,
  Avatar,
  Badge,
  Box,
  Breadcrumbs,
  Burger,
  Container,
  Group,
  Image,
  Menu,
  NavLink,
  ScrollArea,
  Select,
  Stack,
  Text,
  TextInput,
  ThemeIcon,
  Tooltip,
  UnstyledButton,
  useComputedColorScheme,
  useMantineColorScheme,
} from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { useQuery } from '@tanstack/react-query'
import { IconHeartbeat, IconLogout, IconMoon, IconSearch, IconSelector, IconSun } from '@tabler/icons-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { NavLink as RouterNavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { getBranding, logoUrl } from '../api/settings'
import { Permission, type Me } from '../api/types'
import { useFacilityContext } from '../auth/facilityContextValue'
import { useSignOut } from '../auth/session'
import { ErrorState } from '../components/states'
import { navTrail, visibleNavSections } from './navigation'
import { PageTrailContext } from './pageTrail'
import { setQuickSearch } from './quickSearch'

const environmentLabel: string | undefined =
  import.meta.env.VITE_ENVIRONMENT_LABEL || (import.meta.env.DEV ? 'Development · synthetic data only' : undefined)

/**
 * Persistent application shell (02-UI-UX), compact console layout: dark teal sidebar with the product
 * mark, role-scoped sections and the account at the bottom; a light header with the breadcrumb, quick
 * patient search, facility context and light/dark switch; page content in a centred column.
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

  const sections = visibleNavSections(me.permissions)
  const trail = navTrail(location.pathname)

  return (
    <AppShell
      layout="alt"
      header={{ height: 56 }}
      navbar={{ width: 240, breakpoint: 'sm', collapsed: { mobile: !navOpened } }}
      padding="md"
    >
      <Anchor href="#main" className="skip-link">
        Skip to main content
      </Anchor>

      <AppShell.Header className="gcare-header">
        <Group h="100%" px="md" justify="space-between" wrap="nowrap" gap="sm">
          <Group gap="sm" wrap="nowrap" miw={0}>
            <Burger
              opened={navOpened}
              onClick={nav.toggle}
              hiddenFrom="sm"
              size="sm"
              aria-label={navOpened ? 'Close navigation' : 'Open navigation'}
            />
            {trail && (
              <Breadcrumbs separatorMargin={6} visibleFrom="xs">
                <Text size="sm" c="dimmed">
                  {trail.section === 'Overview' ? 'GCare HMS' : trail.section}
                </Text>
                <Text size="sm" fw={600}>
                  {trail.label}
                </Text>
              </Breadcrumbs>
            )}
            {environmentLabel && (
              <Badge color="yellow" variant="filled" size="sm" visibleFrom="lg">
                {environmentLabel}
              </Badge>
            )}
          </Group>

          <Group gap="xs" wrap="nowrap">
            {me.permissions.includes(Permission.PATIENT_READ) && <QuickPatientSearch />}
            {facilities.length > 1 ? (
              <Select
                aria-label="Working facility"
                data={facilities.map((f) => ({ value: f.id, label: f.name }))}
                value={current?.id ?? null}
                onChange={(id) => id && select(id)}
                allowDeselect={false}
                size="sm"
                w={210}
                visibleFrom="sm"
              />
            ) : (
              <FacilityBrand fallback={current?.name ?? me.organization.name} />
            )}
            <ColorSchemeToggle />
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar className="gcare-navbar" aria-label="Main navigation" component="nav">
        <Box px="md" pt="md" pb="xs">
          <ProductMark organization={me.organization.name} />
        </Box>
        <ScrollArea type="auto" px="xs" py="xs" style={{ flex: 1 }}>
          <Stack gap="sm">
            {sections.map(({ section, items }) => (
              <div key={section}>
                {section !== 'Overview' && (
                  <Text className="gcare-nav-section gcare-navbar-muted" size="xs" fw={700} px="sm" mb={4}>
                    {section}
                  </Text>
                )}
                <Stack gap={2}>
                  {items.map((item) => (
                    <NavLink
                      key={item.to}
                      component={RouterNavLink}
                      to={item.to}
                      end={item.to === '/'}
                      label={item.label}
                      leftSection={<item.icon size={17} stroke={1.8} aria-hidden />}
                      className="gcare-nav-link"
                    />
                  ))}
                </Stack>
              </div>
            ))}
          </Stack>
        </ScrollArea>
        <Box className="gcare-navbar-account" px="sm" py="sm">
          <Menu position="top-start" withinPortal>
            <Menu.Target>
              <UnstyledButton aria-label={`Account: ${me.display_name}`} className="gcare-account" w="100%">
                <Group gap={10} wrap="nowrap">
                  <Avatar size={32} radius="xl" className="gcare-account-avatar" aria-hidden>
                    {initials(me.display_name)}
                  </Avatar>
                  <Stack gap={0} style={{ flex: 1, minWidth: 0 }}>
                    <Text size="sm" fw={600} lh={1.25} truncate>
                      {me.display_name}
                    </Text>
                    <Text size="xs" lh={1.3} truncate className="gcare-navbar-muted">
                      {me.username}
                    </Text>
                  </Stack>
                  <IconSelector size={16} aria-hidden />
                </Group>
              </UnstyledButton>
            </Menu.Target>
            <Menu.Dropdown>
              <Menu.Label>{me.organization.name}</Menu.Label>
              <Menu.Item
                leftSection={<IconLogout size={16} aria-hidden />}
                onClick={() => signOut.mutate()}
                disabled={signOut.isPending}
              >
                Sign out
              </Menu.Item>
            </Menu.Dropdown>
          </Menu>
        </Box>
      </AppShell.Navbar>

      <AppShell.Main id="main" className="gcare-main">
        <Container size={1280} px={{ base: 0, sm: 'md' }} py="sm">
          {signOut.isError && <ErrorState error={signOut.error} onRetry={() => signOut.mutate()} />}
          <PageTrailContext value={trail}>
            <Outlet />
          </PageTrailContext>
        </Container>
      </AppShell.Main>
    </AppShell>
  )
}

const initials = (name: string) =>
  name
    .split(/\s+/)
    .map((w) => w[0])
    .filter(Boolean)
    .slice(0, 2)
    .join('')
    .toUpperCase()

/**
 * Patient number search from anywhere. The number goes to the search page in memory only, never
 * into the URL or browser storage.
 */
function QuickPatientSearch() {
  const navigate = useNavigate()
  const [value, setValue] = useState('')
  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    const number = value.trim()
    if (!number) return
    setQuickSearch(number)
    setValue('')
    navigate('/patients', { state: { quickSearch: Date.now() } })
  }
  return (
    <form onSubmit={onSubmit} role="search" aria-label="Quick patient search">
      <TextInput
        aria-label="Quick search by patient number"
        placeholder="Patient number"
        leftSection={<IconSearch size={15} aria-hidden />}
        size="sm"
        className="gcare-search"
        value={value}
        onChange={(e) => setValue(e.currentTarget.value)}
        w={220}
        visibleFrom="md"
      />
    </form>
  )
}

/** Light/dark switch; the default follows the computer's setting. */
function ColorSchemeToggle() {
  const { setColorScheme } = useMantineColorScheme()
  const computed = useComputedColorScheme('light', { getInitialValueInEffect: true })
  const next = computed === 'dark' ? 'light' : 'dark'
  return (
    <Tooltip label={next === 'dark' ? 'Dark mode' : 'Light mode'}>
      <ActionIcon
        size={34}
        variant="subtle"
        color="gray"
        onClick={() => setColorScheme(next)}
        aria-label={next === 'dark' ? 'Switch to dark mode' : 'Switch to light mode'}
      >
        {next === 'dark' ? <IconMoon size={18} aria-hidden /> : <IconSun size={18} aria-hidden />}
      </ActionIcon>
    </Tooltip>
  )
}

/** Product mark at the top of the sidebar, with the organization underneath. */
function ProductMark({ organization }: { organization: string }) {
  return (
    <Group gap={10} wrap="nowrap">
      <ThemeIcon size={34} radius="md" className="gcare-mark" aria-hidden>
        <IconHeartbeat size={20} />
      </ThemeIcon>
      <Stack gap={3} miw={0}>
        <Text fw={700} fz={17} lh={1}>
          GCare HMS
        </Text>
        <Text className="gcare-navbar-muted gcare-nav-section" fz={10} fw={700} lh={1.2} truncate>
          {organization}
        </Text>
      </Stack>
    </Group>
  )
}

/** Approved facility branding (task 5.2): name and logo of the working facility; else the fallback name. */
function FacilityBrand({ fallback }: { fallback: string }) {
  const { current } = useFacilityContext()
  const branding = useQuery({
    queryKey: ['branding', current?.id],
    queryFn: ({ signal }) => getBranding(current!.id, signal),
    enabled: !!current,
    staleTime: 5 * 60_000,
    retry: false,
  })
  const b = branding.data?.configured ? branding.data : null
  return (
    <Group gap="xs" wrap="nowrap" visibleFrom="md">
      {b?.logo_id && <Image src={logoUrl(b.logo_id)} alt="" h={24} w="auto" fit="contain" />}
      <Text c="dimmed" size="sm" truncate maw={220} aria-label="Working facility">
        {b ? b.display_name : fallback}
      </Text>
    </Group>
  )
}
