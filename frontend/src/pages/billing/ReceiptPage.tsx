import { Alert, Anchor, Button, Card, Group, Stack, Table, Text, Title } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { formatMoney } from '../../api/billing'
import { displayName } from '../../api/patients'
import { getRefundReceipt } from '../../api/adjustments'
import { getReceipt } from '../../api/payments'
import { QueryState } from '../../components/states'

/**
 * Printable receipt (task 4.3). The layout is basic and PROVISIONAL until the finance owner
 * approves the receipt format (Phase 4 exit; docs/19).
 */
export function ReceiptPage({ kind = 'payment' }: { kind?: 'payment' | 'refund' }) {
  const { id = '' } = useParams()
  const receipt = useQuery({
    queryKey: ['receipt', kind, id],
    queryFn: ({ signal }) => (kind === 'refund' ? getRefundReceipt(id, signal) : getReceipt(id, signal)),
  })

  return (
    <Stack>
      <Anchor component={Link} to="/billing/invoices" size="sm">
        Back to invoices
      </Anchor>
      <QueryState query={receipt} loadingLabel="Loading receipt…">
        {(r) => (
          <Card withBorder maw={480}>
            {r.format === 'PROVISIONAL' && (
              <Alert color="yellow" mb="sm" title="Provisional format">
                This receipt layout has not been approved by the finance owner yet.
              </Alert>
            )}
            <Title order={1} size="h3">
              {r.kind === 'REFUND' ? 'Refund receipt' : 'Receipt'} {r.receipt_number}
            </Title>
            <Text>{r.facility_name}</Text>
            <Table aria-label="Receipt" mt="sm">
              <Table.Tbody>
                <Row label="Date">{new Date(r.received_at).toLocaleString()}</Row>
                <Row label="Patient">
                  {displayName(r.patient)} ({r.patient.patient_number})
                </Row>
                <Row label="Invoice">
                  {r.invoice_number} (total {formatMoney(r.invoice_total, r.currency)})
                </Row>
                <Row label={r.kind === 'REFUND' ? 'Amount refunded' : 'Amount paid'}>
                  <strong>{formatMoney(r.amount, r.currency)}</strong>
                </Row>
                <Row label="Method">
                  {r.method_name}
                  {r.reference && ` (ref. ${r.reference})`}
                </Row>
                <Row label="Balance due">{formatMoney(r.balance_after, r.currency)}</Row>
                <Row label={r.kind === 'REFUND' ? 'Paid out by' : 'Received by'}>{r.cashier_name}</Row>
              </Table.Tbody>
            </Table>
            <Group justify="flex-end" mt="md">
              <Button variant="default" onClick={() => window.print()}>
                Print
              </Button>
            </Group>
          </Card>
        )}
      </QueryState>
    </Stack>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Table.Tr>
      <Table.Th scope="row">{label}</Table.Th>
      <Table.Td>{children}</Table.Td>
    </Table.Tr>
  )
}
