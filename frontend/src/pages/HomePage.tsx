import {
  Badge,
  Card,
  Grid,
  Group,
  List,
  SimpleGrid,
  Stack,
  Text,
  ThemeIcon,
  Title,
  UnstyledButton,
} from '@mantine/core'
import { IconChartBar, IconChevronRight } from '@tabler/icons-react'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { getDashboard } from '../api/reports'
import { Permission, type Me } from '../api/types'
import { useFacilityContext } from '../auth/facilityContextValue'
import { PageHeader, StatCard } from '../components/states'
import { displayZone, formatDate } from '../components/time'
import { visibleNavSections } from '../shell/navigation'

const slug = (s: string) => s.toLowerCase().replace(/[^a-z]+/g, '-')

/**
 * Landing page after sign-in: today's numbers when you may see reports (aggregates only, docs/20),
 * shortcuts to the work your role allows, and a side column with the facility calendar and your
 * access. Shows only real data: no trends, charts or schedules until an API provides them.
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

  return (
    <Stack gap="lg">
      <PageHeader
        title={`Welcome, ${me.display_name}`}
        description={`${current ? current.name : 'No facility assigned'} · ${formatDate(today)}`}
      />

      <Grid gap="lg">
        <Grid.Col span={{ base: 12, lg: 8, xl: 9 }}>
          <Stack gap="lg">
            {mayReport && dashboard.data && dashboard.data.tiles.length > 0 && (
              <section aria-label="Today at this facility">
                <SimpleGrid cols={{ base: 1, xs: 2, xl: 4 }}>
                  {dashboard.data.tiles.map((t) => (
                    <StatCard
                      key={t.key}
                      label={t.label}
                      value={t.value === null ? '—' : `${t.value}${t.currency ? ` ${t.currency}` : ''}`}
                      icon={<IconChartBar size={20} />}
                      hint={`Today · ${current?.name ?? ''}`}
                    />
                  ))}
                </SimpleGrid>
              </section>
            )}

            {work.map(({ section, items }) => (
              <Card withBorder key={section} component="section" aria-labelledby={`home-${slug(section)}`}>
                <Title order={2} size="h4" mb="md" id={`home-${slug(section)}`}>
                  {section}
                </Title>
                <SimpleGrid cols={{ base: 1, xs: 2, md: 3 }} spacing="sm">
                  {items.map((item) => (
                    <UnstyledButton
                      key={item.to}
                      component={Link}
                      to={item.to}
                      aria-label={item.label}
                      className="gcare-shortcut"
                    >
                      <Group wrap="nowrap" gap="sm" justify="space-between">
                        <Group wrap="nowrap" gap="sm">
                          <ThemeIcon size={38} radius="md" variant="light" aria-hidden>
                            <item.icon size={20} stroke={1.7} />
                          </ThemeIcon>
                          <Text fw={600} size="sm">
                            {item.label}
                          </Text>
                        </Group>
                        <IconChevronRight size={16} aria-hidden className="gcare-shortcut-arrow" />
                      </Group>
                    </UnstyledButton>
                  ))}
                </SimpleGrid>
              </Card>
            ))}
          </Stack>
        </Grid.Col>

        <Grid.Col span={{ base: 12, lg: 4, xl: 3 }}>
          <Stack gap="lg">
            <Card withBorder component="section" aria-labelledby="home-calendar">
              <MonthCalendar now={today} />
            </Card>
            <Card withBorder component="section" aria-labelledby="home-context">
              <Title order={2} size="h4" mb="xs" id="home-context">
                Working context
              </Title>
              <Text size="sm">Organization: {me.organization.name}</Text>
              <Text size="sm">Facility: {current ? current.name : 'No facility assigned'}</Text>
            </Card>
            <Card withBorder component="section" aria-labelledby="home-access">
              <Title order={2} size="h4" mb="xs" id="home-access">
                Your access
              </Title>
              {me.permissions.length === 0 ? (
                <Text size="sm">No roles are assigned to your account yet. Ask your administrator for access.</Text>
              ) : (
                <List size="sm" aria-label="Your permissions" listStyleType="none" spacing={4}>
                  {me.permissions.map((p) => (
                    <List.Item key={p}>
                      <Badge variant="light" color="gray" tt="none">
                        {p}
                      </Badge>
                    </List.Item>
                  ))}
                </List>
              )}
            </Card>
          </Stack>
        </Grid.Col>
      </Grid>
    </Stack>
  )
}

const WEEKDAYS = [
  ['Sun', 'Sunday'],
  ['Mon', 'Monday'],
  ['Tue', 'Tuesday'],
  ['Wed', 'Wednesday'],
  ['Thu', 'Thursday'],
  ['Fri', 'Friday'],
  ['Sat', 'Saturday'],
] as const

/** This month in the facility's time zone, today marked. Read-only: no events are invented. */
function MonthCalendar({ now }: { now: number }) {
  // "YYYY-MM-DD" of today in the facility zone.
  const [y, m, d] = new Intl.DateTimeFormat('en-CA', { timeZone: displayZone() }).format(now).split('-').map(Number)
  const first = new Date(Date.UTC(y, m - 1, 1))
  const daysInMonth = new Date(Date.UTC(y, m, 0)).getUTCDate()
  const daysInPrev = new Date(Date.UTC(y, m - 1, 0)).getUTCDate()
  const lead = first.getUTCDay()
  const cells: { day: number; outside: boolean }[] = []
  for (let i = lead - 1; i >= 0; i--) cells.push({ day: daysInPrev - i, outside: true })
  for (let i = 1; i <= daysInMonth; i++) cells.push({ day: i, outside: false })
  for (let i = 1; cells.length % 7 !== 0; i++) cells.push({ day: i, outside: true })
  const weeks = Array.from({ length: cells.length / 7 }, (_, w) => cells.slice(w * 7, w * 7 + 7))
  const title = new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(first)

  return (
    <>
      <Title order={2} size="h4" mb="sm" id="home-calendar">
        {title}
      </Title>
      <table className="gcare-calendar" aria-labelledby="home-calendar">
        <thead>
          <tr>
            {WEEKDAYS.map(([short, long]) => (
              <th key={short} scope="col">
                <abbr title={long}>{short}</abbr>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {weeks.map((week, w) => (
            <tr key={w}>
              {week.map((c, i) => (
                <td
                  key={i}
                  data-outside={c.outside || undefined}
                  aria-current={!c.outside && c.day === d ? 'date' : undefined}
                >
                  <span>{c.day}</span>
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </>
  )
}
