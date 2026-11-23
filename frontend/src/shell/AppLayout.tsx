import {
  ActionIcon,
  Anchor,
  AppShell,
  Avatar,
  Box,
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
  IconChevronDown,
  IconHeartbeat,
  IconLogout,
  IconMoon,
  IconPlus,
  IconSearch,
  IconSun,
  IconUserPlus,
} from '@tabler/icons-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, NavLink as RouterNavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { getBranding, logoUrl } from '../api/settings'
import { Permission, type Me } from '../api/types'
import { useFacilityContext } from '../auth/facilityContextValue'
import { useSignOut } from '../auth/session'
import { ErrorState } from '../components/states'
import { CommandPalette } from './CommandPalette'
import { visibleNavItems, visibleNavSections, type NavItem } from './navigation'
import { setQuickSearch } from './quickSearch'

const environmentLabel: string | undefined =
  import.meta.env.VITE_ENVIRONMENT_LABEL || (import.meta.env.DEV ? 'Development · synthetic data only' : undefined)

/** Sidebar shortcuts, shown only when the role may open them (the API still enforces). */
const QUICK_ACTION_PATHS = ['/queue', '/billing/invoices', '/billing/shift', '/orders']

/**
 * Persistent application shell (02-UI-UX), "navy console" layout: dark navy sidebar with the product
 * mark, role-scoped sections, quick actions and the working-facility card; a light top bar with the
 * patient search (Ctrl/Cmd+K for the jump-to palette), environment marker, facility, light/dark switch
 * and the account menu.
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
  const items = visibleNavItems(me.permissions)
  const canSearchPatients = me.permissions.includes(Permission.PATIENT_READ)
  const canRegister = me.permissions.includes(Permission.PATIENT_REGISTER)
  const quick = QUICK_ACTION_PATHS.map((to) => items.find((i) => i.to === to)).filter((i): i is NavItem => !!i)
  const [paletteOpened, palette] = useDisclosure(false)
  const os = useOs()
  const isMac = os === 'macos' || os === 'ios'
  useHotkeys([['mod+K', palette.open]], [])

  return (
    <AppShell
      layout="alt"
      header={{ height: 60 }}
      navbar={{ width: 248, breakpoint: 'sm', collapsed: { mobile: !navOpened } }}
      padding="lg"
    >
      <CommandPalette
        opened={paletteOpened}
        onClose={palette.close}
        items={items}
        canSearchPatients={canSearchPatients}
      />
      <Anchor href="#main" className="skip-link">
        Skip to main content
      </Anchor>

      <AppShell.Header className="gcare-header">
        <Group h="100%" px="lg" justify="space-between" wrap="nowrap" gap="sm">
          <Group gap="sm" wrap="nowrap" miw={0} style={{ flex: 1 }}>
            <Burger
              opened={navOpened}
              onClick={nav.toggle}
              hiddenFrom="sm"
              size="sm"
              aria-label={navOpened ? 'Close navigation' : 'Open navigation'}
            />
            {canSearchPatients ? (
              <QuickPatientSearch shortcut={isMac ? '⌘ K' : 'Ctrl + K'} onPalette={palette.open} />
            ) : (
              <UnstyledButton
                onClick={palette.open}
                className="gcare-topsearch gcare-topsearch-button"
                aria-label="Jump to a page"
                aria-keyshortcuts="Control+K Meta+K"
                visibleFrom="sm"
              >
                <IconSearch size={15} aria-hidden />
                <span>Jump to a page…</span>
                <kbd className="gcare-kbd">{isMac ? '⌘ K' : 'Ctrl + K'}</kbd>
              </UnstyledButton>
            )}
            {environmentLabel && (
              <Box component="span" className="gcare-env" visibleFrom="lg">
                {environmentLabel}
              </Box>
            )}
          </Group>

          <Group gap="xs" wrap="nowrap">
            {facilities.length > 1 && (
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
            )}
            <ColorSchemeToggle />
            <Menu position="bottom-end" withinPortal>
              <Menu.Target>
                <UnstyledButton aria-label={`Account: ${me.display_name}`} className="gcare-user-chip">
                  <Avatar size={30} radius="xl" color="clinical" variant="filled" aria-hidden>
                    {initials(me.display_name)}
                  </Avatar>
                  <Stack gap={0} visibleFrom="sm" miw={0}>
                    <Text size="sm" fw={600} lh={1.2} truncate maw={160}>
                      {me.display_name}
                    </Text>
                    <Text size="xs" c="dimmed" lh={1.2} truncate maw={160}>
                      {me.username}
                    </Text>
                  </Stack>
                  <IconChevronDown size={14} aria-hidden style={{ color: 'var(--gcare-text-2)' }} />
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
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar className="gcare-navbar" aria-label="Main navigation" component="nav">
        <Box px="md" pt="md" pb="sm">
          <ProductMark />
        </Box>
        <ScrollArea type="auto" px="sm" py="xs" style={{ flex: 1 }}>
          <Stack gap="md">
            {sections.map(({ section, items: sectionItems }) => (
              <div key={section}>
                {section !== 'Overview' && (
                  <Text className="gcare-nav-section" px={10} mb={6}>
                    {section}
                  </Text>
                )}
                <Stack gap={2}>
                  {sectionItems.map((item) => (
                    <NavLink
                      key={item.to}
                      component={RouterNavLink}
                      to={item.to}
                      end={item.to === '/'}
                      label={item.label}
                      leftSection={<item.icon size={17} stroke={1.7} aria-hidden />}
                      className="gcare-nav-link"
                    />
                  ))}
                </Stack>
              </div>
            ))}

            {(canRegister || quick.length > 0) && (
              <div>
                <Text className="gcare-nav-section" px={10} mb={6}>
                  Quick actions
                </Text>
                <Stack gap={6}>
                  {canRegister && (
                    <UnstyledButton
                      component={Link}
                      to="/patients/new"
                      className="gcare-quick"
                      aria-label="Quick action: Register patient"
                    >
                      <IconUserPlus size={14} aria-hidden />
                      Register patient
                    </UnstyledButton>
                  )}
                  {quick.map((q) => (
                    <UnstyledButton
                      key={q.to}
                      component={Link}
                      to={q.to}
                      className="gcare-quick"
                      aria-label={`Quick action: ${q.label}`}
                    >
                      <IconPlus size={14} aria-hidden />
                      {q.label}
                    </UnstyledButton>
                  ))}
                </Stack>
              </div>
            )}
          </Stack>
        </ScrollArea>
        <Box p="sm">
          <FacilityCard fallback={me.organization.name} />
        </Box>
      </AppShell.Navbar>

      <AppShell.Main id="main" className="gcare-main">
        <Container size={1400} px={0} py={4}>
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
 * Wide top-bar search by patient number. The number goes to the search page in memory only, never
 * into the URL or browser storage. The shortcut hint opens the jump-to palette.
 */
function QuickPatientSearch({ shortcut, onPalette }: { shortcut: string; onPalette: () => void }) {
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
    <form onSubmit={onSubmit} role="search" aria-label="Quick patient search" className="gcare-topsearch-form">
      <TextInput
        aria-label="Quick search by patient number"
        placeholder="Search patient number, or jump to a page…"
        leftSection={<IconSearch size={15} aria-hidden />}
        rightSectionWidth={84}
        rightSection={
          <UnstyledButton
            onClick={onPalette}
            aria-label="Open the jump-to palette"
            aria-keyshortcuts="Control+K Meta+K"
          >
            <kbd className="gcare-kbd">{shortcut}</kbd>
          </UnstyledButton>
        }
        className="gcare-topsearch"
        value={value}
        onChange={(e) => setValue(e.currentTarget.value)}
        visibleFrom="sm"
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
        variant="default"
        className="gcare-icon-button"
        onClick={() => setColorScheme(next)}
        aria-label={next === 'dark' ? 'Switch to dark mode' : 'Switch to light mode'}
      >
        {next === 'dark' ? <IconMoon size={16} aria-hidden /> : <IconSun size={16} aria-hidden />}
      </ActionIcon>
    </Tooltip>
  )
}

/** Product mark at the top of the sidebar. */
function ProductMark() {
  return (
    <Group gap={10} wrap="nowrap">
      <ThemeIcon size={36} radius="md" className="gcare-mark" aria-hidden>
        <IconHeartbeat size={20} />
      </ThemeIcon>
      <Stack gap={2} miw={0}>
        <Text fw={700} size="md" lh={1.1} c="white">
          GCare HMS
        </Text>
        <Text size="xs" lh={1.2} className="gcare-navbar-muted" truncate>
          Hospital Management System
        </Text>
      </Stack>
    </Group>
  )
}

/**
 * Working-facility card at the foot of the sidebar: approved branding (task 5.2) when configured,
 * otherwise the facility or organization name.
 */
function FacilityCard({ fallback }: { fallback: string }) {
  const { current } = useFacilityContext()
  const branding = useQuery({
    queryKey: ['branding', current?.id],
    queryFn: ({ signal }) => getBranding(current!.id, signal),
    enabled: !!current,
    staleTime: 5 * 60_000,
    retry: false,
  })
  const b = branding.data?.configured ? branding.data : null
  const name = b ? b.display_name : (current?.name ?? fallback)
  return (
    <div className="gcare-facility-card">
      <Group gap={8} wrap="nowrap" align="center">
        {b?.logo_id && <Image src={logoUrl(b.logo_id)} alt="" h={28} w={28} fit="contain" radius={4} />}
        <Box miw={0} style={{ flex: 1 }}>
          <Text size="xs" className="gcare-navbar-muted">
            Working facility
          </Text>
          <Text size="sm" fw={650} c="white" lineClamp={2} lh={1.25} aria-label="Working facility">
            {name}
          </Text>
        </Box>
        {current && <span className="gcare-facility-status">Active</span>}
      </Group>
    </div>
  )
}
