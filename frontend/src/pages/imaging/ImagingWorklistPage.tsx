import { Anchor, Badge, Button, Card, Checkbox, Group, Stack, Table, Text } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { safeViewerHref } from '../../api/imaging'
import { Permission } from '../../api/types'
import { checkArchive, getWorklist, recordStep, STEP_LABEL, type WorklistItem } from '../../api/imagingWork'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, PageTitle, QueryState } from '../../components/states'
import { formatDateTime } from '../../components/time'

const STEP_COLOR = { ORDERED: 'gray', ARRIVED: 'blue', EXAMINED: 'orange', IMAGES_AVAILABLE: 'green', CANCELLED: 'red' }

/** Radiographer's imaging worklist (task 6.3; docs/25). */
export function ImagingWorklistPage() {
  const me = useCurrentMe()
  const queryClient = useQueryClient()
  const [finished, setFinished] = useState(false)
  const [confirm, setConfirm] = useState<{ item: WorklistItem; step: 'arrived' | 'examined' } | null>(null)
  const worklist = useQuery({
    queryKey: ['imaging-worklist', finished],
    queryFn: ({ signal }) => getWorklist(finished, signal),
  })
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['imaging-worklist'] })
  const step = useMutation({
    mutationFn: ({ item, step }: { item: WorklistItem; step: 'arrived' | 'examined' }) => recordStep(item, step),
    onSuccess: () => {
      setConfirm(null)
      refresh()
    },
    onError: refresh,
  })
  const check = useMutation({ mutationFn: checkArchive, onSuccess: refresh })
  const canLink = hasPermission(me, Permission.IMAGING_LINK)

  return (
    <Stack>
      <PageTitle>Imaging worklist</PageTitle>
      <Text size="sm" c="dimmed">
        Enter the accession number at the scanner. A study links by itself only when its accession number and patient ID
        both match the order; anything else goes to{' '}
        <Anchor component={Link} to="/imaging/studies">
          studies to review
        </Anchor>
        .
      </Text>
      <Checkbox
        label="Show finished and cancelled orders"
        checked={finished}
        onChange={(e) => setFinished(e.currentTarget.checked)}
      />
      {check.isError && <ErrorState error={check.error} />}
      <QueryState query={worklist} loadingLabel="Loading imaging orders…">
        {(items) =>
          items.length === 0 ? (
            <EmptyState title="No imaging orders" />
          ) : (
            <Card withBorder>
              <Table aria-label="Imaging orders">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th scope="col">Accession</Table.Th>
                    <Table.Th scope="col">Patient</Table.Th>
                    <Table.Th scope="col">Examination</Table.Th>
                    <Table.Th scope="col">Ordered</Table.Th>
                    <Table.Th scope="col">Step</Table.Th>
                    <Table.Th scope="col">Images</Table.Th>
                    <Table.Th scope="col">Actions</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {items.map((i) => (
                    <Table.Tr key={i.order_id}>
                      <Table.Td>
                        <Text ff="monospace" fw={600}>
                          {i.accession_number}
                        </Text>
                      </Table.Td>
                      <Table.Td>
                        {[i.patient.family_name, i.patient.given_names].filter(Boolean).join(', ')}
                        <Text size="xs">
                          {i.patient.patient_number} · {i.patient.birth_date ?? 'no birth date'}
                        </Text>
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
                      <Table.Td>{formatDateTime(i.requested_at)}</Table.Td>
                      <Table.Td>
                        <Badge variant="light" color={STEP_COLOR[i.step]}>
                          {STEP_LABEL[i.step]}
                        </Badge>
                      </Table.Td>
                      <Table.Td>
                        {i.studies.length === 0
                          ? '—'
                          : i.studies.map((s) =>
                              safeViewerHref(s.viewer_url) ? (
                                <Anchor
                                  key={s.id}
                                  href={safeViewerHref(s.viewer_url)!}
                                  target="_blank"
                                  rel="noopener noreferrer"
                                  display="block"
                                >
                                  Open {s.modalities.join(', ') || 'study'} ({s.instance_count ?? '?'} images)
                                </Anchor>
                              ) : (
                                <Text key={s.id} size="sm">
                                  {s.modalities.join(', ') || 'Study'} ({s.instance_count ?? '?'} images)
                                </Text>
                              ),
                            )}
                      </Table.Td>
                      <Table.Td>
                        <Group gap="xs">
                          {i.step === 'ORDERED' && (
                            <Button size="xs" onClick={() => (step.reset(), setConfirm({ item: i, step: 'arrived' }))}>
                              Patient arrived
                            </Button>
                          )}
                          {i.step === 'ARRIVED' && (
                            <Button size="xs" onClick={() => (step.reset(), setConfirm({ item: i, step: 'examined' }))}>
                              Examination done
                            </Button>
                          )}
                          {canLink && i.step !== 'CANCELLED' && (
                            <Button
                              size="xs"
                              variant="default"
                              loading={check.isPending && check.variables?.order_id === i.order_id}
                              onClick={() => check.mutate(i)}
                            >
                              Check archive now
                            </Button>
                          )}
                        </Group>
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
        opened={confirm !== null}
        title={confirm?.step === 'arrived' ? 'Record that the patient arrived?' : 'Record the examination as done?'}
        confirmLabel="Record"
        busy={step.isPending}
        error={step.error}
        onConfirm={() => confirm && step.mutate(confirm)}
        onCancel={() => setConfirm(null)}
      >
        {confirm && (
          <>
            {confirm.item.item_name} for {confirm.item.patient.patient_number}, accession{' '}
            <strong>{confirm.item.accession_number}</strong>. Check the patient's identity first.
          </>
        )}
      </ConfirmDialog>
    </Stack>
  )
}
