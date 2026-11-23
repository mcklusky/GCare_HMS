import { Anchor, Badge, Card, Stack, Table, Text, Title } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { getWorkspace } from '../../api/chart'
import { displayName } from '../../api/patients'
import { EmptyState, PageHeader, QueryState } from '../../components/states'
import { formatDateTime, formatTime } from '../../components/time'

/** Clinician workspace (task 3.4): patients with me now and my unsigned drafts. */
export function WorkspacePage() {
  const workspace = useQuery({
    queryKey: ['workspace'],
    queryFn: ({ signal }) => getWorkspace(signal),
    refetchInterval: 30_000,
  })

  return (
    <Stack>
      <PageHeader title="My workspace" description="Patients with you now and the work waiting for you." />
      <QueryState query={workspace} loadingLabel="Loading your workspace…">
        {(w) => (
          <Stack>
            <Card withBorder>
              <Title order={2} size="h4" mb="xs">
                With me now
              </Title>
              {w.in_consultation.length === 0 ? (
                <EmptyState title="No patient with you">
                  Call the next patient from the{' '}
                  <Anchor component={Link} to="/queue">
                    queue
                  </Anchor>
                  .
                </EmptyState>
              ) : (
                <Table aria-label="Patients with me now">
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th scope="col">Patient</Table.Th>
                      <Table.Th scope="col">Department</Table.Th>
                      <Table.Th scope="col">Since</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {w.in_consultation.map((e) => (
                      <Table.Tr key={e.id}>
                        <Table.Td>
                          <Anchor component={Link} to={`/encounters/${e.id}`}>
                            {displayName(e.patient)}
                          </Anchor>
                          <Text size="xs">{e.patient.patient_number}</Text>
                        </Table.Td>
                        <Table.Td>{e.department.name}</Table.Td>
                        <Table.Td>{e.consultation_started_at ? formatTime(e.consultation_started_at) : '—'}</Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              )}
            </Card>
            <Card withBorder>
              <Title order={2} size="h4" mb="xs">
                My unsigned notes
              </Title>
              {w.drafts.length === 0 ? (
                <Text size="sm">No unsigned drafts.</Text>
              ) : (
                <Table aria-label="My unsigned notes">
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th scope="col">Patient</Table.Th>
                      <Table.Th scope="col">Kind</Table.Th>
                      <Table.Th scope="col">Last saved</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {w.drafts.map((d) => (
                      <Table.Tr key={d.note_id}>
                        <Table.Td>
                          <Anchor component={Link} to={`/encounters/${d.encounter_id}`}>
                            {displayName(d.patient)}
                          </Anchor>
                          <Text size="xs">{d.patient.patient_number}</Text>
                        </Table.Td>
                        <Table.Td>
                          <Badge variant="light">{d.amendment ? 'Amendment draft' : 'Note draft'}</Badge>
                        </Table.Td>
                        <Table.Td>{formatDateTime(d.updated_at)}</Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              )}
            </Card>
          </Stack>
        )}
      </QueryState>
    </Stack>
  )
}
