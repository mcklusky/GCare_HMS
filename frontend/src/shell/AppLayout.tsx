import {
  ActionIcon,
  Anchor,
  AppShell,
  Avatar,
  Badge,
  Box,
  Burger,
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
import { IconChevronDown, IconHeartbeat, IconLogout, IconMoon, IconSearch, IconSun } from '@tabler/icons-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { NavLink as RouterNavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { getBranding, logoUrl } from '../api/settings'
import { Permission, type Me } from '../api/types'
import { useFacilityContext } from '../auth/facilityContextValue'
import { useSignOut } from '../auth/session'
import { ErrorState } from '../components/states'
import { visibleNavSections } from './navigation'
import { setQuickSearch } from './quickSearch'

const environmentLabel: string | undefined =
  import.meta.env.VITE_ENVIRONMENT_LABEL || (import.meta.env.DEV ? 'Development · synthetic data only' : undefined)

/**
 * Persistent application shell (02-UI-UX): product + facility identity, role-scoped navigation in
 * sections, quick patient search, facility context, light/dark switch, account and sign-out.
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
  const initials = me.display_name
    .split(/\s+/)
    .map((w) => w[0])
    .filter(Boolean)
    .slice(0, 2)
    .join('')
    .toUpperCase()

  return (
    <AppShell
      layout="alt"
      header={{ height: 72 }}
      navbar={{ width: 260, breakpoint: 'sm', collapsed: { mobile: !navOpened } }}
      padding="lg"
    >
      <Anchor href="#main" className="skip-link">
        Skip to main content
      </Anchor>

      <AppShell.Header className="gcare-header">
        <Group h="100%" px="md" justify="space-between" wrap="nowrap" gap="md">
          <Group gap="sm" wrap="nowrap">
            <Burger
              opened={navOpened}
              onClick={nav.toggle}
              hiddenFrom="sm"
              size="sm"
              aria-label={navOpened ? 'Close navigation' : 'Open navigation'}
            />
            <Box hiddenFrom="sm">
              <ProductMark />
            </Box>
            <FacilityBrand fallback={me.organization.name} />
            {environmentLabel && (
              <Badge color="yellow" variant="filled" visibleFrom="lg">
                {environmentLabel}
              </Badge>
            )}
          </Group>

          <Group gap="sm" wrap="nowrap">
            {me.permissions.includes(Permission.PATIENT_READ) && <QuickPatientSearch />}
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
                <Text size="sm" c="dimmed" visibleFrom="md" aria-label="Working facility">
                  {current.name}
                </Text>
              )
            )}
            <ColorSchemeToggle />
            <Menu position="bottom-end" withinPortal>
              <Menu.Target>
                <UnstyledButton aria-label={`Account: ${me.display_name}`}>
                  <Group gap={10} wrap="nowrap">
                    <Avatar size={38} radius="xl" color="clinical" variant="filled" aria-hidden>
                      {initials}
                    </Avatar>
                    <Stack gap={0} visibleFrom="sm">
                      <Text size="sm" fw={600} lh={1.2}>
                        {me.display_name}
                      </Text>
                      <Text size="xs" c="dimmed" lh={1.3}>
                        {me.username}
                      </Text>
                    </Stack>
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

      <AppShell.Navbar className="gcare-navbar" aria-label="Main navigation" component="nav">
        <Box px="lg" pt="lg" pb="sm" visibleFrom="sm">
          <ProductMark />
        </Box>
        <ScrollArea type="auto" px="md" py="sm">
          <Stack gap="md">
            {sections.map(({ section, items }) => (
              <div key={section}>
                {section !== 'Overview' && (
                  <Text className="gcare-nav-section" size="xs" fw={700} c="dimmed" px="sm" mb={4}>
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
                      leftSection={<item.icon size={19} stroke={1.7} aria-hidden />}
                      className="gcare-nav-link"
                    />
                  ))}
                </Stack>
              </div>
            ))}
          </Stack>
        </ScrollArea>
      </AppShell.Navbar>

      <AppShell.Main id="main" className="gcare-main">
        {signOut.isError && <ErrorState error={signOut.error} onRetry={() => signOut.mutate()} />}
        <Outlet />
      </AppShell.Main>
    </AppShell>
  )
}

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
        placeholder="Search patient number"
        rightSection={<IconSearch size={16} aria-hidden />}
        radius="xl"
        className="gcare-search"
        value={value}
        onChange={(e) => setValue(e.currentTarget.value)}
        w={260}
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
        size={38}
        radius="xl"
        variant="default"
        onClick={() => setColorScheme(next)}
        aria-label={next === 'dark' ? 'Switch to dark mode' : 'Switch to light mode'}
      >
        {next === 'dark' ? <IconMoon size={18} aria-hidden /> : <IconSun size={18} aria-hidden />}
      </ActionIcon>
    </Tooltip>
  )
}

/** Product name and mark: top of the sidebar on wide screens, in the header on phones. */
function ProductMark() {
  return (
    <Group gap={10} wrap="nowrap">
      <ThemeIcon size={36} radius="md" variant="filled" aria-hidden>
        <IconHeartbeat size={21} />
      </ThemeIcon>
      <Text fw={700} fz={20} lh={1} className="gcare-product">
        GCare HMS
      </Text>
    </Group>
  )
}

/** Approved facility branding (task 5.2): name and logo of the working facility; else the organization name. */
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
      {b?.logo_id && <Image src={logoUrl(b.logo_id)} alt="" h={28} w="auto" fit="contain" />}
      <Text c="dimmed" size="sm" truncate>
        {b ? b.display_name : fallback}
      </Text>
    </Group>
  )
}
