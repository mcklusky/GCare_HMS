import { Badge, Box, Card, Grid, Group, SimpleGrid, Stack, Text, ThemeIcon, Title } from '@mantine/core'
import {
  IconActivity,
  IconChevronRight,
  IconClipboardList,
  IconClock,
  IconStethoscope,
  IconUserPlus,
  IconUsers,
} from '@tabler/icons-react'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { getDashboard } from '../api/reports'
import { Permission, type Me } from '../api/types'
import { useFacilityContext } from '../auth/facilityContextValue'
import { PageHeader, StatCard, TodayStamp } from '../components/states'
import { displayZone, formatDate } from '../components/time'
import { visibleNavSections } from '../shell/navigation'

/** Icon and colour per dashboard tile, by position; colour only decorates, the label names the figure. */
const TILE_LOOKS = [
  { tone: 'blue', icon: IconUsers },
  { tone: 'orange', icon: IconClock },
  { tone: 'violet', icon: IconStethoscope },
  { tone: 'teal', icon: IconClipboardList },
  { tone: 'pink', icon: IconUserPlus },
  { tone: 'cyan', icon: IconActivity },
]

/**
 * Landing page after sign-in, dense console layout: today's numbers in one KPI strip (report users
 * only; aggregates, docs/20), quick actions grouped by section, and the working context and access
 * on the side. Real data only: no invented trends, charts or schedules.
 */
export function HomePage({ me }: { me: Me }) {
  const { current } = useFacilityContext()
  const [today] = useState(() => Date.now())
  const mayReport =
    me.permissions.includes(Permission.REPORT_OPERATIONS_READ) ||
    me.permissions.includes(Permission.REPORT_REVENUE_READ)
  const dashboard = useQuery({
    queryKey: ['dashboard', current?.id],
    queryFn: ({ signal }) => getDashboard(current!.id, signal),
    enabled: mayReport && !!current,
    retry: false,
  })
  const work = visibleNavSections(me.permissions).filter((g) => g.section !== 'Overview')
  const zone = displayZone() ?? Intl.DateTimeFormat().resolvedOptions().timeZone

  return (
    <Stack gap="lg">
      <PageHeader
        title={`Welcome, ${me.display_name}`}
        description={`${current ? current.name : 'No facility assigned'} · ${formatDate(today)}`}
        actions={<TodayStamp />}
      />

      {mayReport && dashboard.data && dashboard.data.tiles.length > 0 && (
        <section aria-label="Today at this facility">
          <SimpleGrid cols={{ base: 1, xs: 2, md: 3, xl: 5 }} spacing="md">
            {dashboard.data.tiles.map((t, i) => {
              const look = TILE_LOOKS[i % TILE_LOOKS.length]
              return (
                <StatCard
                  key={t.key}
                  label={t.label}
                  tone={look.tone}
                  icon={<look.icon size={18} />}
                  value={
                    t.value === null ? (
                      '—'
                    ) : (
                      <>
                        {t.value}
                        {t.currency && (
                          <Text span size="sm" c="dimmed" fw={600} ml={4}>
                            {t.currency}
                          </Text>
                        )}
                      </>
                    )
                  }
                  hint="Today at this facility"
                />
              )
            })}
          </SimpleGrid>
        </section>
      )}

      <Grid gap="lg">
        <Grid.Col span={{ base: 12, lg: 8 }}>
          <Card withBorder padding={0} component="section" aria-labelledby="home-actions">
            <Group justify="space-between" px="md" py="sm" className="gcare-card-head">
              <div>
                <Title order={2} id="home-actions">
                  Quick actions
                </Title>
                <Text size="xs" c="dimmed">
                  Everything your role can open. Press Ctrl+K to jump from anywhere.
                </Text>
              </div>
            </Group>
            {work.length === 0 ? (
              <Text size="sm" c="dimmed" p="md">
                No work areas are assigned to your account yet.
              </Text>
            ) : (
              work.map(({ section, items }) => (
                <Box key={section} component="section" aria-label={section}>
                  <Text className="gcare-nav-section" px="md" pt="sm" pb={4}>
                    {section}
                  </Text>
                  <SimpleGrid cols={{ base: 1, md: 2 }} spacing={0} px={4} pb={6}>
                    {items.map((item) => (
                      <Link key={item.to} to={item.to} className="gcare-row" style={{ borderTop: 0 }}>
                        <ThemeIcon size={28} radius="sm" variant="light" aria-hidden>
                          <item.icon size={16} stroke={1.7} />
                        </ThemeIcon>
                        <Text size="sm" fw={500}>
                          {item.label}
                        </Text>
                        <IconChevronRight size={15} aria-hidden className="gcare-row-arrow" />
                      </Link>
                    ))}
                  </SimpleGrid>
                </Box>
              ))
            )}
          </Card>
        </Grid.Col>

        <Grid.Col span={{ base: 12, lg: 4 }}>
          <Stack gap="lg">
            <Card withBorder component="section" aria-labelledby="home-context">
              <Title order={2} id="home-context" mb="sm">
                Working context
              </Title>
              <dl className="gcare-dl">
                <dt>Organization</dt>
                <dd>{me.organization.name}</dd>
                <dt>Facility</dt>
                <dd>{current ? current.name : 'No facility assigned'}</dd>
                <dt>Time zone</dt>
                <dd>{zone}</dd>
                <dt>Signed in as</dt>
                <dd>{me.username}</dd>
              </dl>
            </Card>
            <Card withBorder component="section" aria-labelledby="home-access">
              <Group justify="space-between" mb="sm">
                <Title order={2} id="home-access">
                  Your access
                </Title>
                <Text size="xs" c="dimmed">
                  {me.permissions.length} {me.permissions.length === 1 ? 'permission' : 'permissions'}
                </Text>
              </Group>
              {me.permissions.length === 0 ? (
                <Text size="sm">No roles are assigned to your account yet. Ask your administrator for access.</Text>
              ) : (
                <Group
                  component="ul"
                  gap={4}
                  aria-label="Your permissions"
                  style={{ listStyle: 'none', margin: 0, padding: 0 }}
                >
                  {me.permissions.map((p) => (
                    <li key={p}>
                      <Badge variant="default" radius="sm" fw={500} ff="monospace" size="sm">
                        {p}
                      </Badge>
                    </li>
                  ))}
                </Group>
              )}
            </Card>
          </Stack>
        </Grid.Col>
      </Grid>
    </Stack>
  )
}
