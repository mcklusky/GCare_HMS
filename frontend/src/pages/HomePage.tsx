import { Card, List, SimpleGrid, Stack, Text } from '@mantine/core'
import type { Me } from '../api/types'
import { useFacilityContext } from '../auth/facilityContextValue'
import { PageTitle } from '../components/states'

/** Landing page after sign-in. Role dashboards and work queues replace this as features arrive. */
export function HomePage({ me }: { me: Me }) {
  const { current } = useFacilityContext()

  return (
    <Stack>
      <PageTitle>Welcome, {me.display_name}</PageTitle>
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <Card withBorder>
          <Text fw={600} mb="xs">
            Working context
          </Text>
          <Text>Organization: {me.organization.name}</Text>
          <Text>Facility: {current ? current.name : 'No facility assigned'}</Text>
        </Card>
        <Card withBorder>
          <Text fw={600} mb="xs">
            Your access
          </Text>
          {me.permissions.length === 0 ? (
            <Text>No roles are assigned to your account yet. Ask your administrator for access.</Text>
          ) : (
            <List size="sm" aria-label="Your permissions">
              {me.permissions.map((p) => (
                <List.Item key={p}>{p}</List.Item>
              ))}
            </List>
          )}
        </Card>
      </SimpleGrid>
    </Stack>
  )
}
