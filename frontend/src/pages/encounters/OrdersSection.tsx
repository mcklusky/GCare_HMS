import {
  Badge,
  Button,
  Card,
  Group,
  SegmentedControl,
  Select,
  Stack,
  Table,
  Text,
  Textarea,
  Title,
} from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  CATEGORY_LABEL,
  ORDER_STATUS_LABEL,
  PRIORITY_LABEL,
  listCatalog,
  listVisitOrders,
  orderAction,
  placeOrder,
  type Category,
  type Order,
  type Priority,
} from '../../api/orders'
import { Permission } from '../../api/types'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, QueryState } from '../../components/states'
import { formatDateTime } from '../../components/time'

/** Orders and referrals of a visit with their status (FR-009). */
export function OrdersSection({ encounterId, open }: { encounterId: string; open: boolean }) {
  const me = useCurrentMe()
  const queryClient = useQueryClient()
  const canOrder = hasPermission(me, Permission.ORDER_CREATE)
  const [cancelling, setCancelling] = useState<Order | null>(null)
  const orders = useQuery({
    queryKey: ['visit-orders', encounterId],
    queryFn: ({ signal }) => listVisitOrders(encounterId, signal),
  })
  const cancel = useMutation({
    mutationFn: ({ order, reason }: { order: Order; reason: string }) => orderAction(order, 'cancel', reason),
    onSuccess: () => {
      setCancelling(null)
      void queryClient.invalidateQueries({ queryKey: ['visit-orders', encounterId] })
    },
  })

  return (
    <Card withBorder>
      <Title order={2} size="h4" mb="xs">
        Orders and referrals
      </Title>
      <Stack>
        {canOrder && open && <OrderForm encounterId={encounterId} />}
        {cancel.isError && <ErrorState error={cancel.error} />}
        <QueryState
          query={orders}
          loadingLabel="Loading orders…"
          isEmpty={(rows) => rows.length === 0}
          empty={<EmptyState title="No orders yet" />}
        >
          {(rows) => (
            <Table aria-label="Orders of this visit">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th scope="col">Item</Table.Th>
                  <Table.Th scope="col">Priority</Table.Th>
                  <Table.Th scope="col">Status</Table.Th>
                  <Table.Th scope="col">Requested</Table.Th>
                  {canOrder && <Table.Th scope="col">Action</Table.Th>}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((o) => (
                  <Table.Tr key={o.id}>
                    <Table.Td>
                      {o.item.name}
                      <Text size="xs">
                        {CATEGORY_LABEL[o.item.category]}
                        {o.performing_department && ` · ${o.performing_department.name}`}
                      </Text>
                      {o.indication && <Text size="xs">Indication: {o.indication}</Text>}
                    </Table.Td>
                    <Table.Td>
                      <Badge variant="light" color={o.priority === 'URGENT' ? 'red' : 'gray'}>
                        {PRIORITY_LABEL[o.priority]}
                      </Badge>
                    </Table.Td>
                    <Table.Td>
                      {ORDER_STATUS_LABEL[o.status]}
                      {o.cancel_reason && <Text size="xs">Reason: {o.cancel_reason}</Text>}
                    </Table.Td>
                    <Table.Td>
                      {formatDateTime(o.requested_at)}
                      <Text size="xs">{o.requested_by.display_name}</Text>
                    </Table.Td>
                    {canOrder && (
                      <Table.Td>
                        {(o.status === 'REQUESTED' || o.status === 'IN_PROGRESS') && (
                          <Button size="xs" variant="default" onClick={() => setCancelling(o)}>
                            Cancel
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
      <ConfirmDialog
        opened={cancelling !== null}
        title="Cancel this order?"
        confirmLabel="Cancel order"
        danger
        requireReason
        busy={cancel.isPending}
        onConfirm={(reason) => cancelling && cancel.mutate({ order: cancelling, reason: reason ?? '' })}
        onCancel={() => setCancelling(null)}
      >
        The performing department will see that it was cancelled, and why.
      </ConfirmDialog>
    </Card>
  )
}

function OrderForm({ encounterId }: { encounterId: string }) {
  const queryClient = useQueryClient()
  const [itemId, setItemId] = useState<string | null>(null)
  const [priority, setPriority] = useState<Priority>('ROUTINE')
  const [indication, setIndication] = useState('')
  const catalog = useQuery({ queryKey: ['order-catalog'], queryFn: ({ signal }) => listCatalog(false, signal) })
  const place = useMutation({
    mutationFn: () =>
      placeOrder(encounterId, {
        catalog_item_id: itemId!,
        priority,
        ...(indication.trim() ? { indication: indication.trim() } : {}),
      }),
    onSuccess: () => {
      setItemId(null)
      setPriority('ROUTINE')
      setIndication('')
      void queryClient.invalidateQueries({ queryKey: ['visit-orders', encounterId] })
    },
  })

  const groups = (['LAB', 'IMAGING', 'PROCEDURE', 'REFERRAL'] as Category[])
    .map((c) => ({
      group: CATEGORY_LABEL[c],
      items: (catalog.data ?? []).filter((i) => i.category === c).map((i) => ({ value: i.id, label: i.name })),
    }))
    .filter((g) => g.items.length > 0)

  if (catalog.data && catalog.data.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        Nothing can be ordered yet: the order catalog is empty. An administrator adds items under Order catalog.
      </Text>
    )
  }

  return (
    <form
      aria-label="New order"
      onSubmit={(e) => {
        e.preventDefault()
        place.mutate()
      }}
    >
      <Stack gap="sm">
        <Group align="end">
          <Select
            label="Order or referral"
            placeholder="Search the catalog"
            searchable
            data={groups}
            value={itemId}
            onChange={setItemId}
            style={{ flex: 1 }}
          />
          <SegmentedControl
            aria-label="Priority"
            value={priority}
            onChange={(v) => setPriority(v as Priority)}
            data={[
              { value: 'ROUTINE', label: 'Routine' },
              { value: 'URGENT', label: 'Urgent' },
            ]}
          />
        </Group>
        <Textarea
          label="Indication"
          description="Why it is needed. Shown to the performing department."
          value={indication}
          onChange={(e) => setIndication(e.currentTarget.value)}
          maxLength={1000}
          autosize
        />
        {place.isError && <ErrorState error={place.error} />}
        <Group>
          <Button type="submit" disabled={!itemId} loading={place.isPending}>
            Place order
          </Button>
        </Group>
      </Stack>
    </form>
  )
}
