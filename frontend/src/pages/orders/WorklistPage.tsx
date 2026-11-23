import { Badge, Button, Chip, Group, Stack, Table, Text } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { displayName } from '../../api/patients'
import {
  CATEGORY_LABEL,
  ORDER_STATUS_LABEL,
  PRIORITY_LABEL,
  listWorklist,
  orderAction,
  type Order,
} from '../../api/orders'
import { Permission } from '../../api/types'
import { useFacilityContext } from '../../auth/facilityContextValue'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, PageTitle, QueryState } from '../../components/states'
import { formatDateTime } from '../../components/time'

/** Performing departments' worklist (FR-009): urgent first, then oldest request. */
export function WorklistPage() {
  const me = useCurrentMe()
  const queryClient = useQueryClient()
  const { current: facility } = useFacilityContext()
  const canProcess = hasPermission(me, Permission.ORDER_PROCESS)
  const [department, setDepartment] = useState<string>('ALL')
  const [cancelling, setCancelling] = useState<Order | null>(null)
  const worklist = useQuery({
    queryKey: ['worklist', facility?.id],
    queryFn: ({ signal }) => listWorklist(facility!.id, signal),
    enabled: !!facility,
    refetchInterval: 30_000,
  })
  const act = useMutation({
    mutationFn: ({
      order,
      action,
      reason,
    }: {
      order: Order
      action: 'start' | 'complete' | 'cancel'
      reason?: string
    }) => orderAction(order, action, reason),
    onSuccess: () => {
      setCancelling(null)
      void queryClient.invalidateQueries({ queryKey: ['worklist'] })
    },
  })

  if (!facility) return <EmptyState title="No facility">You have no facility to work in.</EmptyState>

  const departments = new Map<string, string>()
  for (const o of worklist.data?.items ?? []) {
    if (o.performing_department) departments.set(o.performing_department.id, o.performing_department.name)
  }

  return (
    <Stack>
      <PageTitle>Worklist</PageTitle>
      <Text size="sm" c="dimmed">
        Open orders at {facility.name}. Urgent first. Updates every 30 seconds.
      </Text>
      {departments.size > 1 && (
        <Chip.Group value={department} onChange={(v) => setDepartment(v as string)}>
          <Group gap="xs" role="group" aria-label="Department">
            <Chip value="ALL">All departments</Chip>
            {[...departments].map(([id, name]) => (
              <Chip key={id} value={id}>
                {name}
              </Chip>
            ))}
          </Group>
        </Chip.Group>
      )}
      {act.isError && <ErrorState error={act.error} />}
      <QueryState
        query={worklist}
        loadingLabel="Loading worklist…"
        isEmpty={(page) => page.items.length === 0}
        empty={<EmptyState title="Nothing to do">No open orders here.</EmptyState>}
      >
        {(page) => {
          const rows = page.items.filter((o) => department === 'ALL' || o.performing_department?.id === department)
          return (
            <Table striped aria-label={`Worklist, ${rows.length} open orders`}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th scope="col">Priority</Table.Th>
                  <Table.Th scope="col">Patient</Table.Th>
                  <Table.Th scope="col">Order</Table.Th>
                  <Table.Th scope="col">Requested</Table.Th>
                  <Table.Th scope="col">Status</Table.Th>
                  {canProcess && <Table.Th scope="col">Action</Table.Th>}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((o) => (
                  <Table.Tr key={o.id}>
                    <Table.Td>
                      <Badge variant="light" color={o.priority === 'URGENT' ? 'red' : 'gray'}>
                        {PRIORITY_LABEL[o.priority]}
                      </Badge>
                    </Table.Td>
                    <Table.Td>
                      {displayName(o.patient)}
                      <Text size="xs">{o.patient.patient_number}</Text>
                    </Table.Td>
                    <Table.Td>
                      {o.item.name}
                      <Text size="xs">
                        {CATEGORY_LABEL[o.item.category]}
                        {o.performing_department && ` · ${o.performing_department.name}`}
                      </Text>
                      {o.indication && <Text size="xs">Indication: {o.indication}</Text>}
                    </Table.Td>
                    <Table.Td>
                      {formatDateTime(o.requested_at)}
                      <Text size="xs">{o.requested_by.display_name}</Text>
                    </Table.Td>
                    <Table.Td>{ORDER_STATUS_LABEL[o.status]}</Table.Td>
                    {canProcess && (
                      <Table.Td>
                        <Group gap="xs">
                          {o.status === 'REQUESTED' && (
                            <Button size="xs" onClick={() => act.mutate({ order: o, action: 'start' })}>
                              Start
                            </Button>
                          )}
                          {o.status === 'IN_PROGRESS' && (
                            <Button size="xs" onClick={() => act.mutate({ order: o, action: 'complete' })}>
                              Complete
                            </Button>
                          )}
                          <Button size="xs" variant="default" onClick={() => setCancelling(o)}>
                            Cancel
                          </Button>
                        </Group>
                      </Table.Td>
                    )}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )
        }}
      </QueryState>
      <ConfirmDialog
        opened={cancelling !== null}
        title="Cancel this order?"
        confirmLabel="Cancel order"
        danger
        requireReason
        busy={act.isPending}
        onConfirm={(reason) => cancelling && act.mutate({ order: cancelling, action: 'cancel', reason: reason ?? '' })}
        onCancel={() => setCancelling(null)}
      >
        The ordering clinician will see that it was cancelled, and why.
      </ConfirmDialog>
    </Stack>
  )
}
