import {
  Alert,
  Anchor,
  Badge,
  Button,
  Card,
  Group,
  List,
  Modal,
  NumberInput,
  Select,
  Stack,
  Table,
  Text,
  Textarea,
  Title,
} from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { formatMoney, listServices } from '../../api/billing'
import {
  INVOICE_STATUS_LABEL,
  UNBILLED_LABEL,
  addInvoiceLine,
  addOrderCharges,
  getInvoice,
  issueInvoice,
  removeInvoiceLine,
  voidInvoice,
  type Invoice,
  type InvoiceLine,
} from '../../api/invoices'
import { Permission } from '../../api/types'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { PatientBanner } from '../../components/PatientBadges'
import { ErrorState, PageTitle, QueryState } from '../../components/states'
import { PaymentsSection } from './PaymentsSection'

type Pending = { kind: 'issue' } | { kind: 'discard' } | { kind: 'void' } | { kind: 'remove'; line: InvoiceLine }

/** One invoice (task 4.2): its charges, totals and the draft → issued → void steps. */
export function InvoicePage() {
  const { invoiceId = '' } = useParams()
  const invoice = useQuery({
    queryKey: ['invoice', invoiceId],
    queryFn: ({ signal }) => getInvoice(invoiceId, signal),
  })

  return (
    <Stack>
      <Anchor component={Link} to="/billing/invoices" size="sm">
        Back to invoices
      </Anchor>
      <QueryState query={invoice} loadingLabel="Loading invoice…">
        {(i) => <InvoiceDetail invoice={i} />}
      </QueryState>
    </Stack>
  )
}

function InvoiceDetail({ invoice }: { invoice: Invoice }) {
  const me = useCurrentMe()
  const queryClient = useQueryClient()
  const canManage = hasPermission(me, Permission.INVOICE_MANAGE)
  const canVoid = hasPermission(me, Permission.INVOICE_VOID)
  const draft = invoice.status === 'DRAFT'
  const [pending, setPending] = useState<Pending | null>(null)
  const [adding, setAdding] = useState(false)
  const money = (amount: InvoiceLine['line_total']) => formatMoney(amount, invoice.currency)

  const act = useMutation({
    mutationFn: ({ p, reason }: { p: Pending; reason: string }) => {
      switch (p.kind) {
        case 'issue':
          return issueInvoice(invoice)
        case 'remove':
          return removeInvoiceLine(invoice, p.line.id, reason)
        default:
          return voidInvoice(invoice, reason)
      }
    },
    onSuccess: (updated) => {
      setPending(null)
      queryClient.setQueryData(['invoice', invoice.id], updated)
      void queryClient.invalidateQueries({ queryKey: ['invoices'] })
      void queryClient.invalidateQueries({ queryKey: ['billable-visits'] })
    },
  })
  const sync = useMutation({
    mutationFn: () => addOrderCharges(invoice),
    onSuccess: (updated) => queryClient.setQueryData(['invoice', invoice.id], updated),
  })

  const ready = invoice.unbilled_orders.filter((o) => o.problem === null)

  return (
    <Stack>
      <Group justify="space-between">
        <PageTitle>{invoice.invoice_number ? `Invoice ${invoice.invoice_number}` : 'Draft invoice'}</PageTitle>
        <Badge size="lg" variant="light" color={invoice.status === 'ISSUED' ? 'green' : 'gray'}>
          {INVOICE_STATUS_LABEL[invoice.status]}
        </Badge>
      </Group>
      <PatientBanner patient={invoice.patient} />
      <Text size="sm">
        Visit: {invoice.encounter.department_name}, arrived {new Date(invoice.encounter.arrived_at).toLocaleString()}.
        Currency: <strong>{invoice.currency}</strong>.
        {invoice.issued_at &&
          ` Issued ${new Date(invoice.issued_at).toLocaleString()} by ${invoice.issued_by?.display_name ?? ''}.`}
      </Text>
      {invoice.replaces_invoice_id && (
        <Text size="sm">
          Replaces a{' '}
          <Anchor component={Link} to={`/billing/invoices/${invoice.replaces_invoice_id}`}>
            voided invoice
          </Anchor>
          .
        </Text>
      )}
      {invoice.status === 'VOID' && (
        <Alert color="gray" title={invoice.invoice_number ? 'This invoice was voided' : 'This draft was discarded'}>
          {invoice.voided_by?.display_name}: {invoice.void_reason}
        </Alert>
      )}
      {(act.isError || sync.isError) && <ErrorState error={act.error ?? sync.error} />}

      <Card withBorder>
        <Group justify="space-between" mb="xs">
          <Title order={2} size="h4">
            Charges
          </Title>
          {draft && canManage && (
            <Button size="xs" variant="default" onClick={() => setAdding(true)}>
              Add charge
            </Button>
          )}
        </Group>
        {invoice.lines.length === 0 ? (
          <Text size="sm">No charges yet.</Text>
        ) : (
          <Table aria-label="Charges">
            <Table.Thead>
              <Table.Tr>
                <Table.Th scope="col">Service</Table.Th>
                <Table.Th scope="col">Qty</Table.Th>
                <Table.Th scope="col">Unit price</Table.Th>
                <Table.Th scope="col">Tax</Table.Th>
                <Table.Th scope="col">Total</Table.Th>
                {draft && canManage && <Table.Th scope="col">Action</Table.Th>}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {invoice.lines.map((l) => {
                const removed = l.status === 'REMOVED'
                return (
                  <Table.Tr key={l.id} style={removed ? { textDecoration: 'line-through' } : undefined}>
                    <Table.Td>
                      {l.service_name} ({l.service_code})
                      <Text size="xs">{l.source === 'ORDER' ? 'From an order' : `Added: ${l.reason ?? ''}`}</Text>
                      {l.order_status === 'CANCELLED' && !removed && (
                        <Badge color="red" variant="light">
                          Order cancelled: remove this charge
                        </Badge>
                      )}
                      {removed && (
                        <Text size="xs" style={{ textDecoration: 'none' }}>
                          Removed by {l.removed_by?.display_name}: {l.removed_reason}
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td>{l.quantity}</Table.Td>
                    <Table.Td>{money(l.unit_price)}</Table.Td>
                    <Table.Td>
                      {money(l.tax_amount)}
                      {l.tax_rate_code && (
                        <Text size="xs">
                          {l.tax_rate_code} {String(l.tax_rate_percent)} %
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td>{money(l.line_total)}</Table.Td>
                    {draft && canManage && (
                      <Table.Td>
                        {!removed && (
                          <Button
                            size="xs"
                            variant="default"
                            aria-label={`Remove ${l.service_name}`}
                            onClick={() => setPending({ kind: 'remove', line: l })}
                          >
                            Remove
                          </Button>
                        )}
                      </Table.Td>
                    )}
                  </Table.Tr>
                )
              })}
            </Table.Tbody>
          </Table>
        )}
        <Table aria-label="Totals" mt="sm" w="auto" ml="auto">
          <Table.Tbody>
            <Table.Tr>
              <Table.Th scope="row">Subtotal</Table.Th>
              <Table.Td>{money(invoice.subtotal)}</Table.Td>
            </Table.Tr>
            <Table.Tr>
              <Table.Th scope="row">Tax</Table.Th>
              <Table.Td>{money(invoice.tax_total)}</Table.Td>
            </Table.Tr>
            <Table.Tr>
              <Table.Th scope="row">Total</Table.Th>
              <Table.Td>
                <strong>{money(invoice.total)}</strong>
              </Table.Td>
            </Table.Tr>
          </Table.Tbody>
        </Table>
      </Card>

      {invoice.invoice_number && <PaymentsSection invoice={invoice} />}

      {draft && invoice.unbilled_orders.length > 0 && (
        <Card withBorder>
          <Title order={2} size="h4" mb="xs">
            Orders not on this invoice
          </Title>
          <List size="sm">
            {invoice.unbilled_orders.map((o) => (
              <List.Item key={o.order_id}>
                {o.item_name} ({o.service_code}): {UNBILLED_LABEL[o.problem ?? 'READY']}
              </List.Item>
            ))}
          </List>
          {canManage && ready.length > 0 && (
            <Button mt="sm" size="xs" loading={sync.isPending} onClick={() => sync.mutate()}>
              Add new order charges
            </Button>
          )}
        </Card>
      )}

      <Group>
        {draft && canManage && (
          <>
            <Button onClick={() => setPending({ kind: 'issue' })}>Issue invoice</Button>
            <Button variant="default" onClick={() => setPending({ kind: 'discard' })}>
              Discard draft
            </Button>
          </>
        )}
        {invoice.status === 'ISSUED' && canVoid && Number(invoice.paid) === 0 && (
          <Button color="red" variant="light" onClick={() => setPending({ kind: 'void' })}>
            Void invoice
          </Button>
        )}
      </Group>

      <ConfirmDialog
        opened={pending !== null}
        title={TITLES[pending?.kind ?? 'issue']}
        confirmLabel={LABELS[pending?.kind ?? 'issue']}
        danger={pending?.kind === 'void' || pending?.kind === 'discard'}
        requireReason={pending?.kind !== 'issue'}
        busy={act.isPending}
        onConfirm={(reason) => pending && act.mutate({ p: pending, reason: reason ?? '' })}
        onCancel={() => setPending(null)}
      >
        {pending?.kind === 'issue' && (
          <>
            Total <strong>{money(invoice.total)}</strong>. Once issued, the invoice gets its number and cannot be
            changed; mistakes are corrected by voiding it.
          </>
        )}
        {pending?.kind === 'remove' && (
          <>
            Remove <strong>{pending.line.service_name}</strong>? The charge stays visible as removed. Say why.
          </>
        )}
        {pending?.kind === 'discard' && <>This draft will be closed without a number. Say why.</>}
        {pending?.kind === 'void' && (
          <>
            Invoice <strong>{invoice.invoice_number}</strong> will be marked void. It stays on record. Say why.
          </>
        )}
      </ConfirmDialog>
      <AddChargeDialog opened={adding} invoice={invoice} onClose={() => setAdding(false)} />
    </Stack>
  )
}

const TITLES: Record<Pending['kind'], string> = {
  issue: 'Issue this invoice?',
  remove: 'Remove this charge?',
  discard: 'Discard this draft?',
  void: 'Void this invoice?',
}
const LABELS: Record<Pending['kind'], string> = {
  issue: 'Issue',
  remove: 'Remove',
  discard: 'Discard',
  void: 'Void invoice',
}

function AddChargeDialog({ opened, invoice, onClose }: { opened: boolean; invoice: Invoice; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [serviceId, setServiceId] = useState<string | null>(null)
  const [quantity, setQuantity] = useState<number | string>(1)
  const [reason, setReason] = useState('')
  const services = useQuery({
    queryKey: ['billing-services'],
    queryFn: ({ signal }) => listServices(signal),
    enabled: opened,
  })
  const add = useMutation({
    mutationFn: () => addInvoiceLine(invoice, serviceId!, Number(quantity), reason.trim()),
    onSuccess: (updated) => {
      queryClient.setQueryData(['invoice', invoice.id], updated)
      close()
    },
  })
  const close = () => {
    setServiceId(null)
    setQuantity(1)
    setReason('')
    add.reset()
    onClose()
  }
  const qty = Number(quantity)
  const valid = serviceId !== null && Number.isInteger(qty) && qty >= 1 && qty <= 999 && reason.trim().length > 0

  return (
    <Modal opened={opened} onClose={close} title="Add a charge">
      <form
        aria-label="Add a charge"
        onSubmit={(e) => {
          e.preventDefault()
          if (valid) add.mutate()
        }}
      >
        <Stack>
          <QueryState query={services} loadingLabel="Loading services…">
            {(rows) => (
              <Select
                label="Service"
                required
                searchable
                value={serviceId}
                onChange={setServiceId}
                data={rows
                  .filter((s) => s.status === 'ACTIVE')
                  .map((s) => {
                    const price = s.current_prices.find((p) => p.currency === invoice.currency)
                    return {
                      value: s.id,
                      label: `${s.name} (${s.code}): ${
                        price ? formatMoney(price.amount, price.currency) : `no price in ${invoice.currency}`
                      }`,
                      disabled: !price,
                    }
                  })}
              />
            )}
          </QueryState>
          <NumberInput
            label="Quantity"
            required
            min={1}
            max={999}
            allowDecimal={false}
            value={quantity}
            onChange={setQuantity}
          />
          <Textarea label="Reason" required value={reason} onChange={(e) => setReason(e.currentTarget.value)} />
          {add.isError && <ErrorState error={add.error} />}
          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              Cancel
            </Button>
            <Button type="submit" disabled={!valid} loading={add.isPending}>
              Add charge
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  )
}
