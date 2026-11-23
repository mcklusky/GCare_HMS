import { Badge, Button, Card, Group, Modal, Stack, Table, Text, TextInput, Textarea, Title } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  DECISION_LABEL,
  createService,
  formatMoney,
  listPrices,
  listServices,
  listTaxRates,
  proposePrice,
  proposeTaxRate,
  todayUtc,
  validAmount,
  type BillableService,
} from '../../api/billing'
import { Permission } from '../../api/types'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { EmptyState, ErrorState, PageHeader, QueryState } from '../../components/states'

const CURRENCY = /^[A-Z]{3}$/

/**
 * Tariffs (task 4.1): billable services, their dated prices and tax rates. Starts empty; prices
 * are proposed by finance and approved by a different person (Price approvals page).
 */
export function TariffsPage() {
  const me = useCurrentMe()
  const canManage = hasPermission(me, Permission.BILLING_CATALOG_MANAGE)
  const canPropose = hasPermission(me, Permission.PRICE_PROPOSE)
  const [adding, setAdding] = useState(false)
  const [pricing, setPricing] = useState<BillableService | null>(null)
  const services = useQuery({ queryKey: ['billing-services'], queryFn: ({ signal }) => listServices(signal) })

  return (
    <Stack>
      <PageHeader
        title="Tariffs"
        description="Enter only prices approved by your finance owner. A price change is a new price from a later date; invoices keep the price they were made with. Amounts are never converted between currencies."
        actions={canManage && <Button onClick={() => setAdding(true)}>Add service</Button>}
      />
      <QueryState
        query={services}
        loadingLabel="Loading services…"
        isEmpty={(rows) => rows.length === 0}
        empty={<EmptyState title="No billable services yet">Add the services your hospital charges for.</EmptyState>}
      >
        {(rows) => (
          <Table aria-label="Billable services">
            <Table.Thead>
              <Table.Tr>
                <Table.Th scope="col">Code</Table.Th>
                <Table.Th scope="col">Service</Table.Th>
                <Table.Th scope="col">Price today</Table.Th>
                <Table.Th scope="col">Status</Table.Th>
                <Table.Th scope="col">Action</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((s) => (
                <Table.Tr key={s.id}>
                  <Table.Td>{s.code}</Table.Td>
                  <Table.Td>{s.name}</Table.Td>
                  <Table.Td>
                    {s.current_prices.length === 0
                      ? 'No approved price'
                      : s.current_prices.map((p) => formatMoney(p.amount, p.currency)).join(' · ')}
                  </Table.Td>
                  <Table.Td>{s.status === 'ACTIVE' ? 'Active' : 'Inactive'}</Table.Td>
                  <Table.Td>
                    <Button
                      size="xs"
                      variant="default"
                      onClick={() => setPricing(s)}
                      aria-label={`Prices of ${s.name}`}
                    >
                      Prices
                    </Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
      <TaxRates canPropose={canPropose} />
      {adding && <AddService onClose={() => setAdding(false)} />}
      {pricing && <PricesDialog service={pricing} canPropose={canPropose} onClose={() => setPricing(null)} />}
    </Stack>
  )
}

function AddService({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient()
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const save = useMutation({
    mutationFn: () => createService({ code: code.trim(), name: name.trim() }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['billing-services'] })
      onClose()
    },
  })
  return (
    <Modal opened onClose={onClose} title="Add billable service" centered>
      <Stack>
        <TextInput
          label="Code"
          required
          description="Capital letters, digits, - or _. Cannot be changed later."
          value={code}
          onChange={(e) => setCode(e.currentTarget.value.toUpperCase())}
          data-autofocus
        />
        <TextInput label="Name" required value={name} onChange={(e) => setName(e.currentTarget.value)} />
        {save.isError && <ErrorState error={save.error} />}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={!code.trim() || !name.trim()} loading={save.isPending} onClick={() => save.mutate()}>
            Save
          </Button>
        </Group>
      </Stack>
    </Modal>
  )
}

function PricesDialog({
  service,
  canPropose,
  onClose,
}: {
  service: BillableService
  canPropose: boolean
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const [currency, setCurrency] = useState('')
  const [amount, setAmount] = useState('')
  const [from, setFrom] = useState(todayUtc())
  const [tax, setTax] = useState('')
  const [reason, setReason] = useState('')
  const prices = useQuery({
    queryKey: ['billing-prices', service.id],
    queryFn: ({ signal }) => listPrices(service.id, signal),
  })
  const propose = useMutation({
    mutationFn: () =>
      proposePrice(service.id, {
        currency,
        amount,
        effective_from: from,
        reason: reason.trim(),
        ...(tax.trim() ? { tax_rate_code: tax.trim() } : {}),
      }),
    onSuccess: () => {
      setAmount('')
      setReason('')
      void queryClient.invalidateQueries({ queryKey: ['billing-prices', service.id] })
    },
  })
  const valid =
    CURRENCY.test(currency) && validAmount(amount, currency) && /^\d{4}-\d{2}-\d{2}$/.test(from) && reason.trim()

  return (
    <Modal opened onClose={onClose} title={`Prices: ${service.name}`} centered size="xl">
      <Stack>
        <QueryState
          query={prices}
          loadingLabel="Loading prices…"
          isEmpty={(rows) => rows.length === 0}
          empty={<EmptyState title="No prices yet" />}
        >
          {(rows) => (
            <Table aria-label="Price history">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th scope="col">Price</Table.Th>
                  <Table.Th scope="col">From</Table.Th>
                  <Table.Th scope="col">Tax</Table.Th>
                  <Table.Th scope="col">Status</Table.Th>
                  <Table.Th scope="col">Proposed / decided</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((p) => (
                  <Table.Tr key={p.id}>
                    <Table.Td>{formatMoney(p.amount, p.currency)}</Table.Td>
                    <Table.Td>{p.effective_from}</Table.Td>
                    <Table.Td>{p.tax_rate_code ?? '—'}</Table.Td>
                    <Table.Td>
                      <Badge
                        variant="light"
                        color={p.status === 'APPROVED' ? 'teal' : p.status === 'REJECTED' ? 'gray' : 'yellow'}
                      >
                        {DECISION_LABEL[p.status]}
                      </Badge>
                    </Table.Td>
                    <Table.Td>
                      <Text size="xs">
                        {p.proposed_by.display_name}: {p.reason}
                      </Text>
                      {p.decided_by && (
                        <Text size="xs">
                          {p.decided_by.display_name}: {p.decision_note}
                        </Text>
                      )}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </QueryState>
        {canPropose && (
          <form
            aria-label="Propose a price"
            onSubmit={(e) => {
              e.preventDefault()
              propose.mutate()
            }}
          >
            <Stack gap="sm">
              <Title order={3} size="h5">
                Propose a price
              </Title>
              <Group align="end">
                <TextInput
                  label="Currency"
                  description="ISO code, e.g. USD"
                  required
                  maxLength={3}
                  w={110}
                  value={currency}
                  onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())}
                />
                <TextInput
                  label="Amount"
                  required
                  inputMode="decimal"
                  w={160}
                  value={amount}
                  onChange={(e) => setAmount(e.currentTarget.value.trim())}
                />
                <TextInput
                  label="Effective from"
                  type="date"
                  required
                  w={170}
                  min={todayUtc()}
                  value={from}
                  onChange={(e) => setFrom(e.currentTarget.value)}
                />
                <TextInput
                  label="Tax rate code"
                  w={150}
                  value={tax}
                  onChange={(e) => setTax(e.currentTarget.value.toUpperCase())}
                />
              </Group>
              <Textarea
                label="Reason"
                required
                value={reason}
                onChange={(e) => setReason(e.currentTarget.value)}
                maxLength={500}
                autosize
              />
              {propose.isError && <ErrorState error={propose.error} />}
              <Group>
                <Button type="submit" disabled={!valid} loading={propose.isPending}>
                  Propose price
                </Button>
                <Text size="xs" c="dimmed">
                  Takes effect only after another person approves it.
                </Text>
              </Group>
            </Stack>
          </form>
        )}
      </Stack>
    </Modal>
  )
}

function TaxRates({ canPropose }: { canPropose: boolean }) {
  const queryClient = useQueryClient()
  const [proposing, setProposing] = useState(false)
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [rate, setRate] = useState('')
  const [from, setFrom] = useState(todayUtc())
  const [reason, setReason] = useState('')
  const rates = useQuery({ queryKey: ['tax-rates'], queryFn: ({ signal }) => listTaxRates(signal) })
  const propose = useMutation({
    mutationFn: () =>
      proposeTaxRate({
        code: code.trim(),
        name: name.trim(),
        rate_percent: rate,
        effective_from: from,
        reason: reason.trim(),
      }),
    onSuccess: () => {
      setProposing(false)
      void queryClient.invalidateQueries({ queryKey: ['tax-rates'] })
    },
  })

  return (
    <Card withBorder>
      <Group justify="space-between" mb="xs">
        <Title order={2} size="h4">
          Tax rates
        </Title>
        {canPropose && (
          <Button size="xs" variant="default" onClick={() => setProposing(true)}>
            Propose tax rate
          </Button>
        )}
      </Group>
      <QueryState
        query={rates}
        loadingLabel="Loading tax rates…"
        isEmpty={(rows) => rows.length === 0}
        empty={
          <EmptyState title="No tax rates">
            None configured. Add a rate only after finance confirms the legal position (open decision OD-11).
          </EmptyState>
        }
      >
        {(rows) => (
          <Table aria-label="Tax rates">
            <Table.Thead>
              <Table.Tr>
                <Table.Th scope="col">Code</Table.Th>
                <Table.Th scope="col">Name</Table.Th>
                <Table.Th scope="col">Rate</Table.Th>
                <Table.Th scope="col">From</Table.Th>
                <Table.Th scope="col">Status</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((r) => (
                <Table.Tr key={r.id}>
                  <Table.Td>{r.code}</Table.Td>
                  <Table.Td>{r.name}</Table.Td>
                  <Table.Td>{String(r.rate_percent)} %</Table.Td>
                  <Table.Td>{r.effective_from}</Table.Td>
                  <Table.Td>{DECISION_LABEL[r.status]}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
      <Modal opened={proposing} onClose={() => setProposing(false)} title="Propose tax rate" centered>
        <Stack>
          <TextInput
            label="Code"
            required
            value={code}
            onChange={(e) => setCode(e.currentTarget.value.toUpperCase())}
            data-autofocus
          />
          <TextInput label="Name" required value={name} onChange={(e) => setName(e.currentTarget.value)} />
          <TextInput
            label="Rate (%)"
            required
            inputMode="decimal"
            value={rate}
            onChange={(e) => setRate(e.currentTarget.value.trim())}
          />
          <TextInput
            label="Effective from"
            type="date"
            required
            min={todayUtc()}
            value={from}
            onChange={(e) => setFrom(e.currentTarget.value)}
          />
          <Textarea
            label="Reason"
            required
            value={reason}
            onChange={(e) => setReason(e.currentTarget.value)}
            autosize
          />
          {propose.isError && <ErrorState error={propose.error} />}
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setProposing(false)}>
              Cancel
            </Button>
            <Button
              disabled={!code.trim() || !name.trim() || !/^\d{1,3}(\.\d{1,4})?$/.test(rate) || !reason.trim()}
              loading={propose.isPending}
              onClick={() => propose.mutate()}
            >
              Propose
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Card>
  )
}
