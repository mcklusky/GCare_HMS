import { Badge, Button, Card, Group, SegmentedControl, Stack, Table, Text, TextInput, Title } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  CERTAINTY_LABEL,
  diagnosisInError,
  listDiagnoses,
  recordDiagnosis,
  type Certainty,
  type Diagnosis,
} from '../../api/clinical'
import { Permission } from '../../api/types'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, QueryState } from '../../components/states'

/** Visit diagnoses: free text for now; codes come once a coding system is chosen (OD-09). */
export function DiagnosesSection({ encounterId, open }: { encounterId: string; open: boolean }) {
  const me = useCurrentMe()
  const queryClient = useQueryClient()
  const canWrite = hasPermission(me, Permission.CLINICAL_NOTE_WRITE)
  const [description, setDescription] = useState('')
  const [certainty, setCertainty] = useState<Certainty>('PROVISIONAL')
  const [marking, setMarking] = useState<Diagnosis | null>(null)
  const diagnoses = useQuery({
    queryKey: ['diagnoses', encounterId],
    queryFn: ({ signal }) => listDiagnoses(encounterId, signal),
  })
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['diagnoses', encounterId] })
  const add = useMutation({
    mutationFn: () => recordDiagnosis(encounterId, description.trim(), certainty),
    onSuccess: () => {
      setDescription('')
      setCertainty('PROVISIONAL')
      refresh()
    },
  })
  const mark = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => diagnosisInError(id, reason),
    onSuccess: () => {
      setMarking(null)
      refresh()
    },
  })

  return (
    <Card withBorder>
      <Title order={2} size="h4" mb="xs">
        Diagnoses
      </Title>
      <Stack>
        {canWrite && open && (
          <form
            aria-label="Add diagnosis"
            onSubmit={(e) => {
              e.preventDefault()
              add.mutate()
            }}
          >
            <Group align="end">
              <TextInput
                label="Diagnosis"
                description="Free text. Codes will be added once a coding system is chosen."
                value={description}
                onChange={(e) => setDescription(e.currentTarget.value)}
                maxLength={500}
                style={{ flex: 1 }}
              />
              <SegmentedControl
                aria-label="Certainty"
                value={certainty}
                onChange={(v) => setCertainty(v as Certainty)}
                data={[
                  { value: 'PROVISIONAL', label: 'Provisional' },
                  { value: 'CONFIRMED', label: 'Confirmed' },
                ]}
              />
              <Button type="submit" disabled={!description.trim()} loading={add.isPending}>
                Add
              </Button>
            </Group>
          </form>
        )}
        {(add.error ?? mark.error) && <ErrorState error={add.error ?? mark.error} />}
        <QueryState
          query={diagnoses}
          loadingLabel="Loading diagnoses…"
          isEmpty={(rows) => rows.length === 0}
          empty={<EmptyState title="No diagnoses recorded" />}
        >
          {(rows) => (
            <Table aria-label="Recorded diagnoses">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th scope="col">Diagnosis</Table.Th>
                  <Table.Th scope="col">Certainty</Table.Th>
                  <Table.Th scope="col">Recorded</Table.Th>
                  <Table.Th scope="col">Status</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((d) => {
                  const error = d.status === 'ENTERED_IN_ERROR'
                  return (
                    <Table.Tr key={d.id}>
                      <Table.Td td={error ? 'line-through' : undefined}>{d.description}</Table.Td>
                      <Table.Td>
                        <Badge variant="light" color={d.certainty === 'CONFIRMED' ? 'blue' : 'gray'}>
                          {CERTAINTY_LABEL[d.certainty]}
                        </Badge>
                      </Table.Td>
                      <Table.Td>
                        {new Date(d.recorded_at).toLocaleString()}
                        <Text size="xs">{d.recorded_by.display_name}</Text>
                      </Table.Td>
                      <Table.Td>
                        {error ? (
                          <Text size="sm">
                            Entered in error: {d.error_reason} ({d.error_by?.display_name})
                          </Text>
                        ) : (
                          canWrite && (
                            <Button size="xs" variant="default" onClick={() => setMarking(d)}>
                              Mark entered in error
                            </Button>
                          )
                        )}
                      </Table.Td>
                    </Table.Tr>
                  )
                })}
              </Table.Tbody>
            </Table>
          )}
        </QueryState>
      </Stack>
      <ConfirmDialog
        opened={marking !== null}
        title="Mark diagnosis as entered in error?"
        confirmLabel="Mark entered in error"
        danger
        requireReason
        busy={mark.isPending}
        onConfirm={(reason) => marking && mark.mutate({ id: marking.id, reason: reason ?? '' })}
        onCancel={() => setMarking(null)}
      >
        The diagnosis stays visible, crossed out, with your reason.
      </ConfirmDialog>
    </Card>
  )
}
