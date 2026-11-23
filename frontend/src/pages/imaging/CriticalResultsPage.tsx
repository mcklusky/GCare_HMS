import { Anchor, Button, Card, Stack, Table, Text } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import {
  acknowledgeCritical,
  getOpenCriticalResults,
  CRITICAL_KIND_LABEL,
  METHOD_LABEL,
  type CriticalResult,
} from '../../api/radiologyReports'
import { Permission } from '../../api/types'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, PageTitle, QueryState } from '../../components/states'
import { formatDateTime } from '../../components/time'

/** Critical radiology results not yet acknowledged (task 6.4; docs/26). */
export function CriticalResultsPage() {
  const me = useCurrentMe()
  const queryClient = useQueryClient()
  const [acking, setAcking] = useState<CriticalResult | null>(null)
  const open = useQuery({ queryKey: ['critical-results'], queryFn: ({ signal }) => getOpenCriticalResults(signal) })
  const ack = useMutation({
    mutationFn: ({ c, note }: { c: CriticalResult; note: string }) => acknowledgeCritical(c, note),
    onSuccess: () => {
      setAcking(null)
      void queryClient.invalidateQueries({ queryKey: ['critical-results'] })
    },
    onError: () => void queryClient.invalidateQueries({ queryKey: ['critical-results'] }),
  })
  const canAck = hasPermission(me, Permission.CRITICAL_RESULT_ACKNOWLEDGE)

  return (
    <Stack>
      <PageTitle>Critical results</PageTitle>
      <Text size="sm" c="dimmed">
        Critical radiology results not yet acknowledged in the HMS, oldest first. The radiologist has already told
        someone directly; acknowledging records that the treating team has acted on it.
      </Text>
      <QueryState query={open} loadingLabel="Loading critical results…">
        {(list) =>
          list.length === 0 ? (
            <EmptyState title="No open critical results" />
          ) : (
            <Card withBorder>
              <Table aria-label="Critical results not yet acknowledged">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th scope="col">Flagged</Table.Th>
                    <Table.Th scope="col">Patient</Table.Th>
                    <Table.Th scope="col">Examination</Table.Th>
                    <Table.Th scope="col">Direct contact</Table.Th>
                    <Table.Th scope="col">Action</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {list.map((c) => (
                    <Table.Tr key={c.report_id}>
                      <Table.Td>
                        {formatDateTime(c.flagged_at)}
                        <Text size="xs">{c.flagged_by.display_name}</Text>
                      </Table.Td>
                      <Table.Td>
                        {[c.patient.family_name, c.patient.given_names].filter(Boolean).join(', ')}
                        <Text size="xs">{c.patient.patient_number}</Text>
                      </Table.Td>
                      <Table.Td>
                        {c.kind === 'CHANGED' && (
                          <Text size="xs" fw={600}>
                            {CRITICAL_KIND_LABEL.CHANGED}
                          </Text>
                        )}
                        {hasPermission(me, Permission.ENCOUNTER_READ) ? (
                          <Anchor component={Link} to={`/encounters/${c.encounter_id}`}>
                            {c.item_name}
                          </Anchor>
                        ) : (
                          c.item_name
                        )}
                        <Text size="xs" ff="monospace">
                          {c.accession_number}
                        </Text>
                      </Table.Td>
                      <Table.Td>
                        {c.communicated_to} · {METHOD_LABEL[c.communication_method]} ·{' '}
                        {formatDateTime(c.communicated_at)}
                      </Table.Td>
                      <Table.Td>
                        {canAck ? (
                          <Button size="xs" color="red" onClick={() => (ack.reset(), setAcking(c))}>
                            Acknowledge
                          </Button>
                        ) : (
                          'Waiting for the treating team'
                        )}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Card>
          )
        }
      </QueryState>
      <ConfirmDialog
        opened={acking !== null}
        title="Acknowledge this critical result?"
        confirmLabel="Acknowledge"
        requireReason
        busy={ack.isPending}
        error={ack.error}
        onConfirm={(note) => acking && ack.mutate({ c: acking, note: note ?? '' })}
        onCancel={() => setAcking(null)}
      >
        Confirm you have read the report and say what was done.
      </ConfirmDialog>
    </Stack>
  )
}
