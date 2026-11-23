import { Anchor, Button, Card, Group, Stack, Table, Text } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { decideAdjustment, listPendingAdjustments } from '../../api/adjustments'
import { formatMoney } from '../../api/billing'
import type { UserRef } from '../../api/encounters'
import { useFacilityContext } from '../../auth/facilityContextValue'
import { useCurrentMe } from '../../auth/MeContext'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, PageHeader, QueryState } from '../../components/states'

interface Pending {
  kind: 'refunds' | 'exemptions'
  id: string
  version: number
  invoiceId: string
  invoiceNumber: string
  what: string
  amount: string
  detail: string
  requester: UserRef
}

/** Second-person decisions on refunds and exemptions (task 4.4). You cannot decide your own. */
export function AdjustmentApprovalsPage() {
  const me = useCurrentMe()
  const { current: facility } = useFacilityContext()
  const queryClient = useQueryClient()
  const [deciding, setDeciding] = useState<{ item: Pending; decision: 'approve' | 'reject' } | null>(null)
  const pending = useQuery({
    queryKey: ['adjustments-pending', facility?.id],
    queryFn: ({ signal }) => listPendingAdjustments(facility!.id, signal),
    enabled: !!facility,
  })
  const act = useMutation({
    mutationFn: ({ item, decision, note }: { item: Pending; decision: 'approve' | 'reject'; note: string }) =>
      decideAdjustment(item.kind, item, decision, note),
    onSuccess: () => {
      setDeciding(null)
      void queryClient.invalidateQueries({ queryKey: ['adjustments-pending'] })
    },
  })

  if (!facility) return <EmptyState title="No facility">You have no facility to work in.</EmptyState>

  return (
    <Stack>
      <PageHeader
        title="Refund and exemption approvals"
        description={
          <>
            Requests at {facility.name}. Check the invoice and the reason before deciding. Approved refunds are then
            paid out by a cashier; approved exemptions reduce the balance due at once.
          </>
        }
      />
      {act.isError && <ErrorState error={act.error} />}
      <QueryState query={pending} loadingLabel="Loading requests…">
        {(a) => {
          const items: Pending[] = [
            ...a.refunds.map((r) => ({
              kind: 'refunds' as const,
              id: r.id,
              version: r.version,
              invoiceId: r.invoice_id,
              invoiceNumber: r.invoice_number,
              what: `Refund of ${r.payment_receipt_number} (${r.method.name})`,
              amount: formatMoney(r.amount, r.currency),
              detail: r.reason,
              requester: r.requested_by,
            })),
            ...a.exemptions.map((x) => ({
              kind: 'exemptions' as const,
              id: x.id,
              version: x.version,
              invoiceId: x.invoice_id,
              invoiceNumber: x.invoice_number,
              what: `Exemption: ${x.reason.name}`,
              amount: formatMoney(x.amount, x.currency),
              detail: x.note,
              requester: x.requested_by,
            })),
          ]
          if (items.length === 0) return <EmptyState title="Nothing to decide" />
          return (
            <Card withBorder>
              <Table aria-label="Requests waiting for a decision">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th scope="col">Invoice</Table.Th>
                    <Table.Th scope="col">Request</Table.Th>
                    <Table.Th scope="col">Amount</Table.Th>
                    <Table.Th scope="col">Requested by</Table.Th>
                    <Table.Th scope="col">Decision</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {items.map((i) => (
                    <Table.Tr key={i.id}>
                      <Table.Td>
                        <Anchor component={Link} to={`/billing/invoices/${i.invoiceId}`}>
                          {i.invoiceNumber}
                        </Anchor>
                      </Table.Td>
                      <Table.Td>
                        {i.what}
                        <Text size="xs">{i.detail}</Text>
                      </Table.Td>
                      <Table.Td>{i.amount}</Table.Td>
                      <Table.Td>{i.requester.display_name}</Table.Td>
                      <Table.Td>
                        {i.requester.id === me.id ? (
                          <Text size="xs">Your request: another person decides.</Text>
                        ) : (
                          <Group gap="xs">
                            <Button size="xs" onClick={() => setDeciding({ item: i, decision: 'approve' })}>
                              Approve
                            </Button>
                            <Button
                              size="xs"
                              variant="default"
                              onClick={() => setDeciding({ item: i, decision: 'reject' })}
                            >
                              Reject
                            </Button>
                          </Group>
                        )}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Card>
          )
        }}
      </QueryState>
      <ConfirmDialog
        opened={deciding !== null}
        title={deciding?.decision === 'approve' ? 'Approve this request?' : 'Reject this request?'}
        confirmLabel={deciding?.decision === 'approve' ? 'Approve' : 'Reject'}
        danger={deciding?.decision === 'reject'}
        requireReason
        busy={act.isPending}
        onConfirm={(note) => deciding && act.mutate({ ...deciding, note: note ?? '' })}
        onCancel={() => setDeciding(null)}
      >
        {deciding && (
          <>
            <strong>{deciding.item.what}</strong>: {deciding.item.amount} on {deciding.item.invoiceNumber}.
          </>
        )}
      </ConfirmDialog>
    </Stack>
  )
}
