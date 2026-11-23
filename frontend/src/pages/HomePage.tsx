import { Badge, Card, Group, List, SimpleGrid, Stack, Text, ThemeIcon, Title, UnstyledButton } from '@mantine/core'
import { IconChartBar } from '@tabler/icons-react'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { getDashboard } from '../api/reports'
import { Permission, type Me } from '../api/types'
import { useFacilityContext } from '../auth/facilityContextValue'
import { PageHeader, StatCard } from '../components/states'
import { formatDate } from '../components/time'
import { visibleNavSections } from '../shell/navigation'

/**
 * Landing page after sign-in: who and where you are, today's numbers when you may see reports
 * (aggregates only, docs/20), and shortcuts to the work your role allows.
 */
const slug = (s: string) => s.toLowerCase().replace(/[^a-z]+/g, '-')

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

      {mayReport && dashboard.data && dashboard.data.tiles.length > 0 && (
        <section aria-label="Today at this facility">
          <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }}>
            {dashboard.data.tiles.map((t) => (
              <StatCard
                key={t.key}
                label={t.label}
                value={t.value === null ? '—' : `${t.value}${t.currency ? ` ${t.currency}` : ''}`}
                icon={<IconChartBar size={20} />}
              />
            ))}
          </SimpleGrid>
        </section>
      )}

      {work.map(({ section, items }) => (
        <section key={section} aria-labelledby={`home-${slug(section)}`}>
          <Title order={2} size="h4" mb="sm" id={`home-${slug(section)}`}>
            {section}
          </Title>
          <SimpleGrid cols={{ base: 1, xs: 2, md: 3, xl: 4 }}>
            {items.map((item) => (
              <UnstyledButton key={item.to} component={Link} to={item.to} aria-label={item.label}>
                <Card withBorder h="100%">
                  <Group wrap="nowrap" gap="sm">
                    <ThemeIcon size={38} radius="md" variant="light" aria-hidden>
                      <item.icon size={20} stroke={1.7} />
                    </ThemeIcon>
                    <Text fw={600}>{item.label}</Text>
                  </Group>
                </Card>
              </UnstyledButton>
            ))}
          </SimpleGrid>
        </section>
      ))}

      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <Card withBorder>
          <Title order={2} size="h4" mb="xs">
            Working context
          </Title>
          <Text>Organization: {me.organization.name}</Text>
          <Text>Facility: {current ? current.name : 'No facility assigned'}</Text>
        </Card>
        <Card withBorder>
          <Title order={2} size="h4" mb="xs">
            Your access
          </Title>
          {me.permissions.length === 0 ? (
            <Text>No roles are assigned to your account yet. Ask your administrator for access.</Text>
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
      </SimpleGrid>
    </Stack>
  )
}
