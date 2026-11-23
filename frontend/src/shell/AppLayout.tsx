import {
  ActionIcon,
  Anchor,
  AppShell,
  Avatar,
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
import { useDisclosure, useHotkeys, useOs } from '@mantine/hooks'
import { useQuery } from '@tanstack/react-query'
import {
  IconCommand,
  IconHeartbeat,
  IconLogout,
  IconMoon,
  IconSearch,
  IconSelector,
  IconSun,
} from '@tabler/icons-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { NavLink as RouterNavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { getBranding, logoUrl } from '../api/settings'
import { Permission, type Me } from '../api/types'
import { useFacilityContext } from '../auth/facilityContextValue'
import { useSignOut } from '../auth/session'
import { ErrorState } from '../components/states'
import { CommandPalette } from './CommandPalette'
import { navTrail, visibleNavItems, visibleNavSections } from './navigation'
import { setQuickSearch } from './quickSearch'

const environmentLabel: string | undefined =
  import.meta.env.VITE_ENVIRONMENT_LABEL || (import.meta.env.DEV ? 'Development · synthetic data only' : undefined)

/**
 * Persistent application shell (02-UI-UX), "pro console" layout: light slim sidebar with the product
 * mark, a Ctrl/Cmd+K jump-to palette, role-scoped sections and the account at the bottom; a slim header
 * with the breadcrumb, environment marker, quick patient search, facility and light/dark switch.
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
  const canSearchPatients = me.permissions.includes(Permission.PATIENT_READ)
  const [paletteOpened, palette] = useDisclosure(false)
  const os = useOs()
  const isMac = os === 'macos' || os === 'ios'
  useHotkeys([['mod+K', palette.open]], [])

  return (
    <AppShell
      layout="alt"
      header={{ height: 52 }}
      navbar={{ width: 236, breakpoint: 'sm', collapsed: { mobile: !navOpened } }}
      padding="lg"
    >
      <CommandPalette
        opened={paletteOpened}
        onClose={palette.close}
        items={visibleNavItems(me.permissions)}
        canSearchPatients={canSearchPatients}
      />
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
              <Box component="span" className="gcare-env" visibleFrom="lg">
                {environmentLabel}
              </Box>
            )}
          </Group>

          <Group gap="xs" wrap="nowrap">
            {canSearchPatients && <QuickPatientSearch />}
            {facilities.length > 1 ? (
              <Select
                aria-label="Working facility"
                data={facilities.map((f) => ({ value: f.id, label: f.name }))}
                value={current?.id ?? null}
                onChange={(id) => id && select(id)}
                allowDeselect={false}
                size="xs"
                w={220}
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
        <Box px="sm" pt="sm">
          <Box px={6} py={6}>
            <ProductMark organization={me.organization.name} />
          </Box>
          <UnstyledButton
            onClick={palette.open}
            className="gcare-sidebar-button gcare-search-trigger"
            mt="xs"
            aria-label="Jump to a page or patient number"
            aria-keyshortcuts="Control+K Meta+K"
          >
            <IconSearch size={15} aria-hidden />
            <span>Jump to…</span>
            <Group gap={3} ml="auto" aria-hidden wrap="nowrap">
              <kbd className="gcare-kbd">{isMac ? <IconCommand size={10} /> : 'Ctrl'}</kbd>
              <kbd className="gcare-kbd">K</kbd>
            </Group>
          </UnstyledButton>
        </Box>
        <ScrollArea type="auto" px="sm" py="sm" style={{ flex: 1 }}>
          <Stack gap="md">
            {sections.map(({ section, items }) => (
              <div key={section}>
                {section !== 'Overview' && (
                  <Text className="gcare-nav-section" px={8} mb={4}>
                    {section}
                  </Text>
                )}
                <Stack gap={1}>
                  {items.map((item) => (
                    <NavLink
                      key={item.to}
                      component={RouterNavLink}
                      to={item.to}
                      end={item.to === '/'}
                      label={item.label}
                      leftSection={<item.icon size={16} stroke={1.7} aria-hidden />}
                      className="gcare-nav-link"
                    />
                  ))}
                </Stack>
              </div>
            ))}
          </Stack>
        </ScrollArea>
        <Box className="gcare-navbar-footer" px="sm" py={8}>
          <Menu position="top-start" withinPortal>
            <Menu.Target>
              <UnstyledButton aria-label={`Account: ${me.display_name}`} className="gcare-sidebar-button">
                <Group gap={10} wrap="nowrap" w="100%">
                  <Avatar size={28} radius="xl" color="clinical" variant="filled" aria-hidden>
                    {initials(me.display_name)}
                  </Avatar>
                  <Stack gap={0} style={{ flex: 1, minWidth: 0 }}>
                    <Text size="sm" fw={600} lh={1.25} truncate>
                      {me.display_name}
                    </Text>
                    <Text size="xs" lh={1.3} truncate c="dimmed">
                      {me.username}
                    </Text>
                  </Stack>
                  <IconSelector size={15} aria-hidden style={{ color: 'var(--gcare-text-2)' }} />
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
        <Container size={1320} px={0} py={4}>
          {signOut.isError && <ErrorState error={signOut.error} onRetry={() => signOut.mutate()} />}
          <Outlet />
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
        leftSection={<IconSearch size={14} aria-hidden />}
        size="xs"
        className="gcare-search"
        value={value}
        onChange={(e) => setValue(e.currentTarget.value)}
        w={200}
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
        size={30}
        variant="subtle"
        color="gray"
        onClick={() => setColorScheme(next)}
        aria-label={next === 'dark' ? 'Switch to dark mode' : 'Switch to light mode'}
      >
        {next === 'dark' ? <IconMoon size={16} aria-hidden /> : <IconSun size={16} aria-hidden />}
      </ActionIcon>
    </Tooltip>
  )
}

/** Product mark at the top of the sidebar, with the organization underneath. */
function ProductMark({ organization }: { organization: string }) {
  return (
    <Group gap={9} wrap="nowrap">
      <ThemeIcon size={28} radius="sm" className="gcare-mark" aria-hidden>
        <IconHeartbeat size={17} />
      </ThemeIcon>
      <Stack gap={1} miw={0}>
        <Text fw={700} size="sm" lh={1.1}>
          GCare HMS
        </Text>
        <Text size="xs" c="dimmed" lh={1.2} truncate>
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
      {b?.logo_id && <Image src={logoUrl(b.logo_id)} alt="" h={18} w="auto" fit="contain" radius={3} />}
      <Text c="dimmed" size="sm" truncate maw={220} aria-label="Working facility">
        {b ? b.display_name : fallback}
      </Text>
    </Group>
  )
}
