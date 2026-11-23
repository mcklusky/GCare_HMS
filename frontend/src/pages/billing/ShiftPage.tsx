import { Alert, Badge, Button, Card, Group, Stack, Table, Text, TextInput, Textarea, Title } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { currencyDecimals, formatMoney, validAmount } from '../../api/billing'
import { SHIFT_STATUS_LABEL, closeShift, myShifts, openShift, type Shift } from '../../api/payments'
import { useFacilityContext } from '../../auth/facilityContextValue'
import { EmptyState, ErrorState, PageTitle, QueryState } from '../../components/states'

/** The cashier's shift (task 4.3): open, take payments, close with a cash count per currency. */
export function ShiftPage() {
  const { current: facility } = useFacilityContext()
  const queryClient = useQueryClient()
  const shifts = useQuery({ queryKey: ['my-shifts'], queryFn: ({ signal }) => myShifts(signal) })
  const open = useMutation({
    mutationFn: () => openShift(facility!.id),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['my-shifts'] }),
  })

  if (!facility) return <EmptyState title="No facility">You have no facility to work in.</EmptyState>

  return (
    <Stack>
      <PageTitle>My shift</PageTitle>
      <Text size="sm" c="dimmed">
        Payments are recorded in your open shift. At the end, count the cash in each currency; a different person signs
        the shift off.
      </Text>
      {open.isError && <ErrorState error={open.error} />}
      <QueryState query={shifts} loadingLabel="Loading shifts…">
        {(rows) => {
          const current = rows.find((s) => s.status === 'OPEN')
          const past = rows.filter((s) => s.status !== 'OPEN')
          return (
            <>
              {current ? (
                <OpenShift shift={current} />
              ) : (
                <Card withBorder>
                  <Text mb="sm">You have no open shift.</Text>
                  <Button loading={open.isPending} onClick={() => open.mutate()}>
                    Open shift at {facility.name}
                  </Button>
                </Card>
              )}
              {past.length > 0 && (
                <Card withBorder>
                  <Title order={2} size="h4" mb="xs">
                    Earlier shifts
                  </Title>
                  <Table aria-label="Earlier shifts">
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th scope="col">Opened</Table.Th>
                        <Table.Th scope="col">Closed</Table.Th>
                        <Table.Th scope="col">Status</Table.Th>
                        <Table.Th scope="col">Cash difference</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {past.map((s) => (
                        <Table.Tr key={s.id}>
                          <Table.Td>{new Date(s.opened_at).toLocaleString()}</Table.Td>
                          <Table.Td>{s.closed_at && new Date(s.closed_at).toLocaleString()}</Table.Td>
                          <Table.Td>{SHIFT_STATUS_LABEL[s.status]}</Table.Td>
                          <Table.Td>
                            {s.counts.length === 0
                              ? 'No cash'
                              : s.counts.map((c) => formatMoney(c.difference, c.currency)).join(', ')}
                          </Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </Card>
              )}
            </>
          )
        }}
      </QueryState>
    </Stack>
  )
}

export function ShiftTotals({ shift }: { shift: Shift }) {
  if (shift.totals.length === 0) return <Text size="sm">No payments in this shift.</Text>
  return (
    <Table aria-label="Payments in this shift">
      <Table.Thead>
        <Table.Tr>
          <Table.Th scope="col">Method</Table.Th>
          <Table.Th scope="col">Payments</Table.Th>
          <Table.Th scope="col">Amount</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {shift.totals.map((t) => (
          <Table.Tr key={`${t.method_code}-${t.currency}`}>
            <Table.Td>
              {t.method_name}{' '}
              {t.is_cash && (
                <Badge size="xs" variant="light">
                  Cash
                </Badge>
              )}
            </Table.Td>
            <Table.Td>{t.payments}</Table.Td>
            <Table.Td>{formatMoney(t.amount, t.currency)}</Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  )
}

function OpenShift({ shift }: { shift: Shift }) {
  const queryClient = useQueryClient()
  const [counted, setCounted] = useState<Record<string, string>>({})
  const [extra, setExtra] = useState('')
  const [note, setNote] = useState('')
  const currencies = [...shift.expected_cash.map((e) => e.currency), ...(extra ? [extra] : [])]
  const close = useMutation({
    mutationFn: () =>
      closeShift(
        shift,
        currencies.map((c) => ({ currency: c, counted: counted[c] ?? '' })),
        note,
      ),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['my-shifts'] }),
  })
  const valid =
    currencies.every((c) => validAmount(counted[c] ?? '', c)) &&
    (extra === '' || (currencyDecimals(extra) !== null && /^[A-Z]{3}$/.test(extra)))

  return (
    <Card withBorder>
      <Group justify="space-between" mb="xs">
        <Title order={2} size="h4">
          Open shift
        </Title>
        <Badge variant="light" color="green">
          Open since {new Date(shift.opened_at).toLocaleString()}
        </Badge>
      </Group>
      <ShiftTotals shift={shift} />
      <form
        aria-label="Close shift"
        onSubmit={(e) => {
          e.preventDefault()
          if (valid) close.mutate()
        }}
      >
        <Stack mt="md">
          <Title order={3} size="h5">
            Close shift
          </Title>
          <Text size="sm">
            Count the cash in the drawer for each currency. Any difference from the cash payments above needs a note.
          </Text>
          {shift.expected_cash.map((e) => (
            <TextInput
              key={e.currency}
              label={`Counted cash (${e.currency})`}
              required
              inputMode="decimal"
              value={counted[e.currency] ?? ''}
              onChange={(ev) => setCounted({ ...counted, [e.currency]: ev.currentTarget.value.trim() })}
            />
          ))}
          {shift.expected_cash.length === 0 && (
            <TextInput
              label="Other cash currency"
              description="Only if there is cash in the drawer without a recorded payment"
              maxLength={3}
              value={extra}
              onChange={(ev) => setExtra(ev.currentTarget.value.toUpperCase())}
            />
          )}
          {extra && (
            <TextInput
              label={`Counted cash (${extra})`}
              required
              inputMode="decimal"
              value={counted[extra] ?? ''}
              onChange={(ev) => setCounted({ ...counted, [extra]: ev.currentTarget.value.trim() })}
            />
          )}
          <Textarea
            label="Note"
            description="Required if counted and expected differ"
            value={note}
            onChange={(e) => setNote(e.currentTarget.value)}
          />
          {close.isError && <ErrorState error={close.error} />}
          {close.isSuccess && <Alert color="green">Shift closed.</Alert>}
          <Group justify="flex-end">
            <Button type="submit" disabled={!valid} loading={close.isPending}>
              Close shift
            </Button>
          </Group>
        </Stack>
      </form>
    </Card>
  )
}
