import { Alert, Anchor, Button, Card, Group, Modal, Select, Stack, Table, Text, TextInput, Title } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { formatMoney, validAmount } from '../../api/billing'
import type { Invoice } from '../../api/invoices'
import { listPaymentMethods, listPayments, recordPayment, type Payment } from '../../api/payments'
import { Permission } from '../../api/types'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { ErrorState, QueryState } from '../../components/states'
import { formatDateTime } from '../../components/time'

/** Payments on an issued invoice (task 4.3): part payments up to the balance, each with a receipt. */
export function PaymentsSection({ invoice }: { invoice: Invoice }) {
  const me = useCurrentMe()
  const canRecord = hasPermission(me, Permission.PAYMENT_RECORD)
  const [paying, setPaying] = useState(false)
  const [last, setLast] = useState<Payment | null>(null)
  const payments = useQuery({
    queryKey: ['payments', invoice.id],
    queryFn: ({ signal }) => listPayments(invoice.id, signal),
  })
  const money = (amount: Payment['amount']) => formatMoney(amount, invoice.currency)
  const due = invoice.balance !== null && Number(invoice.balance) > 0

  return (
    <Card withBorder>
      <Group justify="space-between" mb="xs">
        <Title order={2} size="h4">
          Payments
        </Title>
        {canRecord && due && (
          <Button size="xs" onClick={() => setPaying(true)}>
            Record payment
          </Button>
        )}
      </Group>
      <Text size="sm" mb="xs">
        Paid {money(invoice.paid)}
        {invoice.balance !== null && (
          <>
            {' '}
            · Balance due <strong>{money(invoice.balance)}</strong>
          </>
        )}
      </Text>
      {last && (
        <Alert color="green" mb="xs" title="Payment recorded">
          Receipt {last.receipt_number}.{' '}
          <Anchor component={Link} to={`/billing/receipts/${last.id}`}>
            Open receipt
          </Anchor>
        </Alert>
      )}
      <QueryState
        query={payments}
        loadingLabel="Loading payments…"
        isEmpty={(rows) => rows.length === 0}
        empty={<Text size="sm">No payments yet.</Text>}
      >
        {(rows) => (
          <Table aria-label="Payments">
            <Table.Thead>
              <Table.Tr>
                <Table.Th scope="col">Receipt</Table.Th>
                <Table.Th scope="col">Method</Table.Th>
                <Table.Th scope="col">Amount</Table.Th>
                <Table.Th scope="col">Balance after</Table.Th>
                <Table.Th scope="col">Received</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((p) => (
                <Table.Tr key={p.id}>
                  <Table.Td>
                    <Anchor component={Link} to={`/billing/receipts/${p.id}`}>
                      {p.receipt_number}
                    </Anchor>
                  </Table.Td>
                  <Table.Td>
                    {p.method.name}
                    {p.reference && <Text size="xs">Ref. {p.reference}</Text>}
                  </Table.Td>
                  <Table.Td>{money(p.amount)}</Table.Td>
                  <Table.Td>{money(p.balance_after)}</Table.Td>
                  <Table.Td>
                    {formatDateTime(p.received_at)}
                    <Text size="xs">{p.received_by.display_name}</Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
      {paying && (
        <PaymentDialog
          invoice={invoice}
          onClose={() => setPaying(false)}
          onRecorded={(p) => {
            setLast(p)
            setPaying(false)
          }}
        />
      )}
    </Card>
  )
}

function PaymentDialog({
  invoice,
  onClose,
  onRecorded,
}: {
  invoice: Invoice
  onClose: () => void
  onRecorded: (p: Payment) => void
}) {
  const queryClient = useQueryClient()
  // One key per dialog: a retry after a network error re-sends it, so the payment is never doubled.
  const [key] = useState(() => crypto.randomUUID())
  const [methodId, setMethodId] = useState<string | null>(null)
  const [amount, setAmount] = useState('')
  const [reference, setReference] = useState('')
  const methods = useQuery({ queryKey: ['payment-methods'], queryFn: ({ signal }) => listPaymentMethods(signal) })
  const record = useMutation({
    mutationFn: () =>
      recordPayment(invoice.id, {
        idempotency_key: key,
        method_id: methodId!,
        amount,
        reference: reference.trim() || undefined,
      }),
    onSuccess: (p) => {
      void queryClient.invalidateQueries({ queryKey: ['invoice', invoice.id] })
      void queryClient.invalidateQueries({ queryKey: ['payments', invoice.id] })
      onRecorded(p)
    },
  })
  const valid = methodId !== null && validAmount(amount, invoice.currency) && Number(amount) > 0

  return (
    <Modal opened onClose={onClose} title="Record payment">
      <form
        aria-label="Record payment"
        onSubmit={(e) => {
          e.preventDefault()
          if (valid) record.mutate()
        }}
      >
        <Stack>
          <Text size="sm">
            Balance due <strong>{formatMoney(invoice.balance ?? 0, invoice.currency)}</strong>. Part payments are
            allowed; more than the balance is refused (give change outside the system). You need an open shift.
          </Text>
          <QueryState
            query={methods}
            loadingLabel="Loading methods…"
            isEmpty={(rows) => !rows.some((m) => m.status === 'ACTIVE')}
            empty={<Text size="sm">No payment methods yet. Ask an administrator to add them.</Text>}
          >
            {(rows) => (
              <Select
                label="Method"
                required
                value={methodId}
                onChange={setMethodId}
                data={rows.filter((m) => m.status === 'ACTIVE').map((m) => ({ value: m.id, label: m.name }))}
              />
            )}
          </QueryState>
          <TextInput
            label={`Amount (${invoice.currency})`}
            required
            inputMode="decimal"
            value={amount}
            onChange={(e) => setAmount(e.currentTarget.value.trim())}
          />
          <TextInput
            label="Reference"
            description="Optional, e.g. a transaction number"
            maxLength={100}
            value={reference}
            onChange={(e) => setReference(e.currentTarget.value)}
          />
          {record.isError && (
            <>
              <ErrorState error={record.error} />
              <Anchor component={Link} to="/billing/shift" size="sm">
                Go to my shift
              </Anchor>
            </>
          )}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={!valid} loading={record.isPending}>
              Record payment
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  )
}
