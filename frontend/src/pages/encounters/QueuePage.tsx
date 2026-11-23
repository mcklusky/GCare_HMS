import { Anchor, Badge, Button, Group, Select, Stack, Table, Text } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import {
  encounterAction,
  flagLabel,
  listDepartments,
  listEncounters,
  STATUS_LABEL,
  type EncounterSummary,
} from '../../api/encounters'
import { displayName } from '../../api/patients'
import { Permission } from '../../api/types'
import { useFacilityContext } from '../../auth/facilityContextValue'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { EmptyState, ErrorState, PageHeader, QueryState } from '../../components/states'
import { formatTime } from '../../components/time'

/**
 * Department queue (task 3.1): open visits at the current facility. Ordered by the backend:
 * configured urgency first (none until a clinical lead sets the scale), then arrival.
 */
export function QueuePage() {
  const me = useCurrentMe()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { current: facility } = useFacilityContext()
  const [departmentId, setDepartmentId] = useState<string | null>(null)
  const clinical = hasPermission(me, Permission.OBSERVATION_READ)
  const canCall = hasPermission(me, Permission.ENCOUNTER_MANAGE)

  const departments = useQuery({
    queryKey: ['encounter-departments', facility?.id],
    queryFn: ({ signal }) => listDepartments(facility!.id, signal),
    enabled: !!facility,
  })
  const queue = useQuery({
    queryKey: ['encounters', 'queue', facility?.id, departmentId],
    queryFn: ({ signal }) =>
      listEncounters({ facilityId: facility!.id, departmentId: departmentId ?? undefined }, signal),
    enabled: !!facility,
    refetchInterval: 30_000,
  })
  const call = useMutation({
    mutationFn: (e: EncounterSummary) => encounterAction(e, 'call'),
    onSuccess: (d) => {
      void queryClient.invalidateQueries({ queryKey: ['encounters'] })
      void navigate(`/encounters/${d.encounter.id}`)
    },
  })

  if (!facility) return <EmptyState title="No facility">You have no facility to work in.</EmptyState>

  return (
    <Stack>
      <PageHeader
        title="Queue"
        description="Open visits at this facility: configured urgency first, then arrival time."
      />
      <Group align="end">
        <Select
          label="Department"
          placeholder="All departments"
          clearable
          data={(departments.data ?? []).map((d) => ({ value: d.id, label: d.name }))}
          value={departmentId}
          onChange={setDepartmentId}
          w={280}
        />
        <Text size="sm" c="dimmed">
          {facility.name}. Updates every 30 seconds.
        </Text>
      </Group>
      {call.isError && <ErrorState error={call.error} />}
      <QueryState
        query={queue}
        loadingLabel="Loading queue…"
        isEmpty={(page) => page.items.length === 0}
        empty={<EmptyState title="Nobody waiting">No open visits here.</EmptyState>}
      >
        {(page) => (
          <Table striped aria-label={`Queue, ${page.total} open visits`}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th scope="col">Arrived</Table.Th>
                <Table.Th scope="col">Patient</Table.Th>
                <Table.Th scope="col">Department</Table.Th>
                <Table.Th scope="col">Status</Table.Th>
                {clinical && <Table.Th scope="col">Triage</Table.Th>}
                <Table.Th scope="col">Clinician</Table.Th>
                {canCall && <Table.Th scope="col">Action</Table.Th>}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {page.items.map((e) => (
                <Table.Tr key={e.id}>
                  <Table.Td>{formatTime(e.arrived_at, { hour: '2-digit', minute: '2-digit' })}</Table.Td>
                  <Table.Td>
                    <Anchor component={Link} to={`/encounters/${e.id}`}>
                      {displayName(e.patient)}
                    </Anchor>
                    <Text size="xs">{e.patient.patient_number}</Text>
                  </Table.Td>
                  <Table.Td>{e.department.name}</Table.Td>
                  <Table.Td>{STATUS_LABEL[e.status]}</Table.Td>
                  {clinical && (
                    <Table.Td>
                      <TriageCell encounter={e} />
                    </Table.Td>
                  )}
                  <Table.Td>{e.clinician?.display_name ?? '—'}</Table.Td>
                  {canCall && (
                    <Table.Td>
                      {e.status === 'WAITING' && (
                        <Button
                          size="xs"
                          loading={call.isPending && call.variables?.id === e.id}
                          onClick={() => call.mutate(e)}
                          aria-label={`Call in ${displayName(e.patient)}`}
                        >
                          Call in
                        </Button>
                      )}
                    </Table.Td>
                  )}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
    </Stack>
  )
}

function TriageCell({ encounter: e }: { encounter: EncounterSummary }) {
  if (!e.triaged) return <Text size="sm">Not yet</Text>
  return (
    <Group gap={4}>
      <Text size="sm">Done</Text>
      {e.urgency && <Badge variant="light">{e.urgency}</Badge>}
      {(e.flags ?? []).map((f) => (
        <Badge key={f} color="red" variant="light">
          {flagLabel(f)}
        </Badge>
      ))}
    </Group>
  )
}
