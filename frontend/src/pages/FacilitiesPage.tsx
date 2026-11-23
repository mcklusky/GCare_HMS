import { Anchor, Stack, Table, Text } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router'
import { apiFetch } from '../api/client'
import type { Department, Facility } from '../api/types'
import { EmptyState, PageHeader, QueryState } from '../components/states'
import { StatusBadge } from '../components/StatusBadge'

/** Facilities in the user's scope (GET /api/v1/facilities, needs FACILITY_READ). */
export function FacilitiesPage() {
  const facilities = useQuery({
    queryKey: ['facilities'],
    queryFn: ({ signal }) => apiFetch<Facility[]>('/api/v1/facilities', { signal }),
  })

  return (
    <Stack>
      <PageHeader title="Facilities" description="Facilities in your scope and their departments." />
      <QueryState
        query={facilities}
        loadingLabel="Loading facilities…"
        isEmpty={(list) => list.length === 0}
        empty={<EmptyState title="No facilities">No facilities are in your scope.</EmptyState>}
      >
        {(list) => (
          <Table striped highlightOnHover>
            <Table.Caption>Facilities you can view</Table.Caption>
            <Table.Thead>
              <Table.Tr>
                <Table.Th scope="col">Code</Table.Th>
                <Table.Th scope="col">Name</Table.Th>
                <Table.Th scope="col">Time zone</Table.Th>
                <Table.Th scope="col">Status</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {list.map((f) => (
                <Table.Tr key={f.id}>
                  <Table.Td>{f.code}</Table.Td>
                  <Table.Td>
                    <Anchor component={Link} to={`/facilities/${f.id}`}>
                      {f.name}
                    </Anchor>
                  </Table.Td>
                  <Table.Td>{f.timezone}</Table.Td>
                  <Table.Td>
                    <StatusBadge status={f.status} />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
    </Stack>
  )
}

/** Departments of one facility. Out-of-scope facilities answer 404 from the API. */
export function FacilityDepartmentsPage() {
  const { facilityId = '' } = useParams()
  const facilities = useQuery({
    queryKey: ['facilities'],
    queryFn: ({ signal }) => apiFetch<Facility[]>('/api/v1/facilities', { signal }),
  })
  const departments = useQuery({
    queryKey: ['facilities', facilityId, 'departments'],
    queryFn: ({ signal }) =>
      apiFetch<Department[]>(`/api/v1/facilities/${encodeURIComponent(facilityId)}/departments`, { signal }),
  })
  const facility = facilities.data?.find((f) => f.id === facilityId)

  return (
    <Stack>
      <Anchor component={Link} to="/facilities" size="sm">
        ← All facilities
      </Anchor>
      <PageHeader title={`${facility ? facility.name : 'Facility'}: departments`} />
      <QueryState
        query={departments}
        loadingLabel="Loading departments…"
        isEmpty={(list) => list.length === 0}
        empty={<EmptyState title="No departments">This facility has no departments yet.</EmptyState>}
      >
        {(list) => (
          <Table striped>
            <Table.Caption>Departments</Table.Caption>
            <Table.Thead>
              <Table.Tr>
                <Table.Th scope="col">Code</Table.Th>
                <Table.Th scope="col">Name</Table.Th>
                <Table.Th scope="col">Status</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {list.map((d) => (
                <Table.Tr key={d.id}>
                  <Table.Td>{d.code}</Table.Td>
                  <Table.Td>{d.name}</Table.Td>
                  <Table.Td>
                    <StatusBadge status={d.status} />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
      {departments.isError && departments.error && (
        <Text size="sm" c="dimmed">
          If you followed a link, the facility may be outside your access.
        </Text>
      )}
    </Stack>
  )
}
