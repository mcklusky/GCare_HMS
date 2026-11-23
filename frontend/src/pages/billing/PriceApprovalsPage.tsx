import { Button, Card, Group, Stack, Table, Text, Title } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { decide, formatMoney, listApprovals } from '../../api/billing'
import { useCurrentMe } from '../../auth/MeContext'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, PageTitle, QueryState } from '../../components/states'

interface Pending {
  kind: 'prices' | 'tax-rates'
  id: string
  version: number
  what: string
  value: string
  from: string
  reason: string
  proposer: { id: string; display_name: string }
}

/** Second-person approval of prices and tax rates (task 4.1). You cannot decide your own. */
export function PriceApprovalsPage() {
  const me = useCurrentMe()
  const queryClient = useQueryClient()
  const [deciding, setDeciding] = useState<{ item: Pending; decision: 'approve' | 'reject' } | null>(null)
  const approvals = useQuery({ queryKey: ['billing-approvals'], queryFn: ({ signal }) => listApprovals(signal) })
  const act = useMutation({
    mutationFn: ({ item, decision, note }: { item: Pending; decision: 'approve' | 'reject'; note: string }) =>
      decide(item.kind, item, decision, note),
    onSuccess: () => {
      setDeciding(null)
      void queryClient.invalidateQueries({ queryKey: ['billing-approvals'] })
    },
  })

  return (
    <Stack>
      <PageTitle>Price approvals</PageTitle>
      <Text size="sm" c="dimmed">
        Check each value against the approved tariff before approving. Approved prices and rates can never be changed;
        mistakes are corrected with a new price from a later date.
      </Text>
      {act.isError && <ErrorState error={act.error} />}
      <QueryState query={approvals} loadingLabel="Loading proposals…">
        {(a) => {
          const items: Pending[] = [
            ...a.prices.map((p) => ({
              kind: 'prices' as const,
              id: p.id,
              version: p.version,
              what: `${p.service_name} (${p.service_code})`,
              value: formatMoney(p.amount, p.currency) + (p.tax_rate_code ? ` + tax ${p.tax_rate_code}` : ''),
              from: p.effective_from,
              reason: p.reason,
              proposer: p.proposed_by,
            })),
            ...a.tax_rates.map((r) => ({
              kind: 'tax-rates' as const,
              id: r.id,
              version: r.version,
              what: `Tax rate ${r.name} (${r.code})`,
              value: `${String(r.rate_percent)} %`,
              from: r.effective_from,
              reason: r.reason,
              proposer: r.proposed_by,
            })),
          ]
          if (items.length === 0) return <EmptyState title="Nothing to approve" />
          return (
            <Card withBorder>
              <Title order={2} size="h4" mb="xs">
                Waiting for approval
              </Title>
              <Table aria-label="Proposals waiting for approval">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th scope="col">What</Table.Th>
                    <Table.Th scope="col">Value</Table.Th>
                    <Table.Th scope="col">From</Table.Th>
                    <Table.Th scope="col">Proposed by</Table.Th>
                    <Table.Th scope="col">Decision</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {items.map((i) => {
                    const own = i.proposer.id === me.id
                    return (
                      <Table.Tr key={i.id}>
                        <Table.Td>{i.what}</Table.Td>
                        <Table.Td>{i.value}</Table.Td>
                        <Table.Td>{i.from}</Table.Td>
                        <Table.Td>
                          {i.proposer.display_name}
                          <Text size="xs">{i.reason}</Text>
                        </Table.Td>
                        <Table.Td>
                          {own ? (
                            <Text size="xs">Your proposal: another person decides.</Text>
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
                    )
                  })}
                </Table.Tbody>
              </Table>
            </Card>
          )
        }}
      </QueryState>
      <ConfirmDialog
        opened={deciding !== null}
        title={deciding?.decision === 'approve' ? 'Approve this value?' : 'Reject this proposal?'}
        confirmLabel={deciding?.decision === 'approve' ? 'Approve' : 'Reject'}
        danger={deciding?.decision === 'reject'}
        requireReason
        busy={act.isPending}
        onConfirm={(note) => deciding && act.mutate({ ...deciding, note: note ?? '' })}
        onCancel={() => setDeciding(null)}
      >
        {deciding && (
          <>
            <strong>{deciding.item.what}</strong>: {deciding.item.value} from {deciding.item.from}.{' '}
            {deciding.decision === 'approve' ? 'Once approved it cannot be changed.' : 'Say why.'}
          </>
        )}
      </ConfirmDialog>
    </Stack>
  )
}
