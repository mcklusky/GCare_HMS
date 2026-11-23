import { Anchor, Badge, Card, Stack, Table, Text } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { getReportingList } from '../../api/radiologyReports'
import { EmptyState, PageTitle, QueryState } from '../../components/states'

/** Examined imaging orders waiting for a report (task 6.4; docs/26). */
export function ReportingPage() {
  const list = useQuery({ queryKey: ['radiology-reporting'], queryFn: ({ signal }) => getReportingList(signal) })
  return (
    <Stack>
      <PageTitle>Reporting</PageTitle>
      <Text size="sm" c="dimmed">
        Examinations recorded as done, without a signed report or with a draft open. Urgent ones first.
      </Text>
      <QueryState query={list} loadingLabel="Loading examinations…">
        {(items) =>
          items.length === 0 ? (
            <EmptyState title="Nothing to report" />
          ) : (
            <Card withBorder>
              <Table aria-label="Examinations to report">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th scope="col">Accession</Table.Th>
                    <Table.Th scope="col">Patient</Table.Th>
                    <Table.Th scope="col">Examination</Table.Th>
                    <Table.Th scope="col">Done</Table.Th>
                    <Table.Th scope="col">Report</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {items.map((i) => (
                    <Table.Tr key={i.order_id}>
                      <Table.Td>
                        <Anchor component={Link} to={`/radiology/orders/${i.order_id}`} ff="monospace">
                          {i.accession_number}
                        </Anchor>
                      </Table.Td>
                      <Table.Td>
                        {[i.patient.family_name, i.patient.given_names].filter(Boolean).join(', ')}
                        <Text size="xs">{i.patient.patient_number}</Text>
                      </Table.Td>
                      <Table.Td>
                        {i.item_name}
                        {i.priority === 'URGENT' && (
                          <Badge ml="xs" color="red" variant="light">
                            Urgent
                          </Badge>
                        )}
                        {i.indication && <Text size="xs">{i.indication}</Text>}
                      </Table.Td>
                      <Table.Td>{new Date(i.examined_at).toLocaleString()}</Table.Td>
                      <Table.Td>
                        {i.draft_id
                          ? `Draft by ${i.draft_author?.display_name}`
                          : i.signed_report_id
                            ? 'Signed'
                            : `Not started (${i.studies.length} linked ${i.studies.length === 1 ? 'study' : 'studies'})`}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Card>
          )
        }
      </QueryState>
    </Stack>
  )
}
