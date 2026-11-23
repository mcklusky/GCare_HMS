import {
  Anchor,
  Button,
  Card,
  Group,
  Modal,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import {
  REQUEST_STATUS_LABEL,
  listAdjustments,
  listReasons,
  payOutRefund,
  requestExemption,
  requestRefund,
  type Refund,
} from '../../api/adjustments'
import { formatMoney, validAmount } from '../../api/billing'
import type { Invoice } from '../../api/invoices'
import { listPayments } from '../../api/payments'
import { Permission } from '../../api/types'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { ErrorState, QueryState } from '../../components/states'

/**
 * Refunds and exemptions on an issued invoice (task 4.4). Each is requested here and approved by
 * a different person on the approvals page; approved refunds are paid out from an open shift.
 */
export function AdjustmentsSection({ invoice }: { invoice: Invoice }) {
  const me = useCurrentMe()
  const queryClient = useQueryClient()
  const canRefund = hasPermission(me, Permission.REFUND_REQUEST)
  const canExempt = hasPermission(me, Permission.EXEMPTION_REQUEST)
  const [dialog, setDialog] = useState<'refund' | 'exemption' | null>(null)
  const [payingOut, setPayingOut] = useState<{ refund: Refund; key: string } | null>(null)
  const adjustments = useQuery({
    queryKey: ['adjustments', invoice.id],
    queryFn: ({ signal }) => listAdjustments(invoice.id, signal),
  })
  const payOut = useMutation({
    mutationFn: ({ refund, key }: { refund: Refund; key: string }) => payOutRefund(refund, key),
    onSuccess: () => {
      setPayingOut(null)
      void queryClient.invalidateQueries({ queryKey: ['adjustments', invoice.id] })
      void queryClient.invalidateQueries({ queryKey: ['invoice', invoice.id] })
    },
  })
  const money = (amount: Refund['amount']) => formatMoney(amount, invoice.currency)
  const due = invoice.balance !== null && Number(invoice.balance) > 0
  const paid = Number(invoice.paid) > 0

  return (
    <Card withBorder>
      <Group justify="space-between" mb="xs">
        <Title order={2} size="h4">
          Refunds and exemptions
        </Title>
        <Group gap="xs">
          {canRefund && paid && (
            <Button size="xs" variant="default" onClick={() => setDialog('refund')}>
              Request refund
            </Button>
          )}
          {canExempt && due && (
            <Button size="xs" variant="default" onClick={() => setDialog('exemption')}>
              Request exemption
            </Button>
          )}
        </Group>
      </Group>
      <Text size="sm" mb="xs">
        Exempted {money(invoice.exempted)} · Refunded {money(invoice.refunded)}
      </Text>
      {payOut.isError && <ErrorState error={payOut.error} />}
      <QueryState query={adjustments} loadingLabel="Loading refunds and exemptions…">
        {(a) =>
          a.refunds.length + a.exemptions.length === 0 ? (
            <Text size="sm">None.</Text>
          ) : (
            <Table aria-label="Refunds and exemptions">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th scope="col">What</Table.Th>
                  <Table.Th scope="col">Amount</Table.Th>
                  <Table.Th scope="col">Status</Table.Th>
                  <Table.Th scope="col">Requested by</Table.Th>
                  <Table.Th scope="col">Action</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {a.refunds.map((r) => (
                  <Table.Tr key={r.id}>
                    <Table.Td>
                      Refund of {r.payment_receipt_number} ({r.method.name})<Text size="xs">{r.reason}</Text>
                    </Table.Td>
                    <Table.Td>{money(r.amount)}</Table.Td>
                    <Table.Td>
                      {REQUEST_STATUS_LABEL[r.status]}
                      {r.decision_note && <Text size="xs">{r.decision_note}</Text>}
                    </Table.Td>
                    <Table.Td>{r.requested_by.display_name}</Table.Td>
                    <Table.Td>
                      {r.status === 'APPROVED' && canRefund && (
                        <Button size="xs" onClick={() => setPayingOut({ refund: r, key: crypto.randomUUID() })}>
                          Pay out
                        </Button>
                      )}
                      {r.status === 'PAID' && r.receipt_number && (
                        <Anchor component={Link} to={`/billing/refund-receipts/${r.id}`}>
                          {r.receipt_number}
                        </Anchor>
                      )}
                    </Table.Td>
                  </Table.Tr>
                ))}
                {a.exemptions.map((x) => (
                  <Table.Tr key={x.id}>
                    <Table.Td>
                      Exemption: {x.reason.name}
                      <Text size="xs">{x.note}</Text>
                    </Table.Td>
                    <Table.Td>{money(x.amount)}</Table.Td>
                    <Table.Td>
                      {REQUEST_STATUS_LABEL[x.status]}
                      {x.decision_note && <Text size="xs">{x.decision_note}</Text>}
                    </Table.Td>
                    <Table.Td>{x.requested_by.display_name}</Table.Td>
                    <Table.Td />
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )
        }
      </QueryState>
      <ConfirmDialog
        opened={payingOut !== null}
        title="Pay out this refund?"
        confirmLabel="Pay out"
        busy={payOut.isPending}
        onConfirm={() => payingOut && payOut.mutate(payingOut)}
        onCancel={() => setPayingOut(null)}
      >
        Give <strong>{payingOut && money(payingOut.refund.amount)}</strong> back by {payingOut?.refund.method.name}. It
        is recorded in your open shift
        {payingOut?.refund.method.is_cash ? ' and taken from the cash drawer' : ''}.
      </ConfirmDialog>
      {dialog === 'refund' && <RefundDialog invoice={invoice} onClose={() => setDialog(null)} />}
      {dialog === 'exemption' && <ExemptionDialog invoice={invoice} onClose={() => setDialog(null)} />}
    </Card>
  )
}

function RefundDialog({ invoice, onClose }: { invoice: Invoice; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [paymentId, setPaymentId] = useState<string | null>(null)
  const [amount, setAmount] = useState('')
  const [reason, setReason] = useState('')
  const payments = useQuery({
    queryKey: ['payments', invoice.id],
    queryFn: ({ signal }) => listPayments(invoice.id, signal),
  })
  const submit = useMutation({
    mutationFn: () => requestRefund(paymentId!, amount, reason.trim()),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['adjustments', invoice.id] })
      onClose()
    },
  })
  const valid =
    paymentId !== null && validAmount(amount, invoice.currency) && Number(amount) > 0 && reason.trim().length > 0

  return (
    <Modal opened onClose={onClose} title="Request refund">
      <form
        aria-label="Request refund"
        onSubmit={(e) => {
          e.preventDefault()
          if (valid) submit.mutate()
        }}
      >
        <Stack>
          <Text size="sm">
            A different person must approve the refund before it is paid out. The original payment stays on record.
          </Text>
          <QueryState query={payments} loadingLabel="Loading payments…">
            {(rows) => (
              <Select
                label="Payment"
                required
                value={paymentId}
                onChange={setPaymentId}
                data={rows.map((p) => ({
                  value: p.id,
                  label: `${p.receipt_number}: ${formatMoney(p.amount, p.currency)} (${p.method.name})`,
                }))}
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
          <Textarea label="Reason" required value={reason} onChange={(e) => setReason(e.currentTarget.value)} />
          {submit.isError && <ErrorState error={submit.error} />}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={!valid} loading={submit.isPending}>
              Request refund
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  )
}

function ExemptionDialog({ invoice, onClose }: { invoice: Invoice; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [reasonId, setReasonId] = useState<string | null>(null)
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')
  const reasons = useQuery({ queryKey: ['exemption-reasons'], queryFn: ({ signal }) => listReasons(signal) })
  const submit = useMutation({
    mutationFn: () => requestExemption(invoice.id, reasonId!, amount, note.trim()),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['adjustments', invoice.id] })
      onClose()
    },
  })
  const valid =
    reasonId !== null && validAmount(amount, invoice.currency) && Number(amount) > 0 && note.trim().length > 0

  return (
    <Modal opened onClose={onClose} title="Request exemption">
      <form
        aria-label="Request exemption"
        onSubmit={(e) => {
          e.preventDefault()
          if (valid) submit.mutate()
        }}
      >
        <Stack>
          <Text size="sm">
            Waive up to the balance due ({formatMoney(invoice.balance ?? 0, invoice.currency)}). A different person must
            approve it.
          </Text>
          <QueryState
            query={reasons}
            loadingLabel="Loading reasons…"
            isEmpty={(rows) => !rows.some((r) => r.status === 'ACTIVE')}
            empty={<Text size="sm">No exemption reasons yet. Ask an administrator to add them.</Text>}
          >
            {(rows) => (
              <Select
                label="Reason"
                required
                value={reasonId}
                onChange={setReasonId}
                data={rows.filter((r) => r.status === 'ACTIVE').map((r) => ({ value: r.id, label: r.name }))}
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
          <Textarea label="Note" required value={note} onChange={(e) => setNote(e.currentTarget.value)} />
          {submit.isError && <ErrorState error={submit.error} />}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={!valid} loading={submit.isPending}>
              Request exemption
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  )
}
