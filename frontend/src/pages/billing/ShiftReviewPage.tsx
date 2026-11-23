import { Button, Card, Group, Stack, Table, Text, Title } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { formatMoney } from '../../api/billing'
import { reviewShift, shiftsToReview, type Shift } from '../../api/payments'
import { useFacilityContext } from '../../auth/facilityContextValue'
import { useCurrentMe } from '../../auth/MeContext'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, PageTitle, QueryState } from '../../components/states'
import { ShiftTotals } from './ShiftPage'

/** Second-person sign-off of closed cashier shifts (task 4.3). You cannot sign off your own. */
export function ShiftReviewPage() {
  const me = useCurrentMe()
  const { current: facility } = useFacilityContext()
  const queryClient = useQueryClient()
  const [reviewing, setReviewing] = useState<Shift | null>(null)
  const shifts = useQuery({
    queryKey: ['shifts-to-review', facility?.id],
    queryFn: ({ signal }) => shiftsToReview(facility!.id, signal),
    enabled: !!facility,
  })
  const review = useMutation({
    mutationFn: ({ shift, note }: { shift: Shift; note: string }) => reviewShift(shift, note),
    onSuccess: () => {
      setReviewing(null)
      void queryClient.invalidateQueries({ queryKey: ['shifts-to-review'] })
    },
  })

  if (!facility) return <EmptyState title="No facility">You have no facility to work in.</EmptyState>

  return (
    <Stack>
      <PageTitle>Shift sign-off</PageTitle>
      <Text size="sm" c="dimmed">
        Closed cashier shifts at {facility.name}. Check the cash count and the cashier's note before signing off.
      </Text>
      {review.isError && <ErrorState error={review.error} />}
      <QueryState
        query={shifts}
        loadingLabel="Loading shifts…"
        isEmpty={(rows) => rows.length === 0}
        empty={<EmptyState title="Nothing to sign off" />}
      >
        {(rows) => (
          <Stack>
            {rows.map((s) => (
              <Card withBorder key={s.id}>
                <Group justify="space-between" mb="xs">
                  <Title order={2} size="h4">
                    {s.cashier.display_name}: {new Date(s.opened_at).toLocaleString()} to{' '}
                    {s.closed_at && new Date(s.closed_at).toLocaleString()}
                  </Title>
                  {s.cashier.id === me.id ? (
                    <Text size="xs">Your shift: another person signs it off.</Text>
                  ) : (
                    <Button size="xs" onClick={() => setReviewing(s)}>
                      Sign off
                    </Button>
                  )}
                </Group>
                <ShiftTotals shift={s} />
                {s.counts.length > 0 && (
                  <Table aria-label={`Cash count of ${s.cashier.display_name}`} mt="sm">
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th scope="col">Currency</Table.Th>
                        <Table.Th scope="col">Expected</Table.Th>
                        <Table.Th scope="col">Counted</Table.Th>
                        <Table.Th scope="col">Difference</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {s.counts.map((c) => (
                        <Table.Tr key={c.currency}>
                          <Table.Td>{c.currency}</Table.Td>
                          <Table.Td>{formatMoney(c.expected, c.currency)}</Table.Td>
                          <Table.Td>{formatMoney(c.counted, c.currency)}</Table.Td>
                          <Table.Td>
                            {Number(c.difference) === 0 ? 'None' : formatMoney(c.difference, c.currency)}
                          </Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                )}
                {s.close_note && <Text size="sm">Cashier's note: {s.close_note}</Text>}
              </Card>
            ))}
          </Stack>
        )}
      </QueryState>
      <ConfirmDialog
        opened={reviewing !== null}
        title="Sign off this shift?"
        confirmLabel="Sign off"
        requireReason
        busy={review.isPending}
        onConfirm={(note) => reviewing && review.mutate({ shift: reviewing, note: note ?? '' })}
        onCancel={() => setReviewing(null)}
      >
        Confirm you checked the cash count of <strong>{reviewing?.cashier.display_name}</strong>. Add a note.
      </ConfirmDialog>
    </Stack>
  )
}
