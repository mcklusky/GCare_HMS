import {
  Anchor,
  Badge,
  Button,
  Card,
  Group,
  Modal,
  SegmentedControl,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { currencyDecimals, formatMoney } from '../../api/billing'
import {
  INVOICE_STATUS_LABEL,
  listBillableVisits,
  listInvoices,
  startInvoice,
  type BillableVisit,
  type InvoiceStatus,
} from '../../api/invoices'
import { displayName } from '../../api/patients'
import { Permission } from '../../api/types'
import { useFacilityContext } from '../../auth/facilityContextValue'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { EmptyState, ErrorState, PageTitle, QueryState } from '../../components/states'

const FILTERS: Record<string, InvoiceStatus[]> = { open: ['DRAFT', 'ISSUED'], void: ['VOID'] }

/** Invoices (task 4.2): visits waiting for an invoice, and the facility's invoices. */
export function InvoicesPage() {
  const me = useCurrentMe()
  const canManage = hasPermission(me, Permission.INVOICE_MANAGE)
  const { current: facility } = useFacilityContext()
  const [filter, setFilter] = useState('open')
  const [starting, setStarting] = useState<BillableVisit | null>(null)

  const visits = useQuery({
    queryKey: ['billable-visits', facility?.id],
    queryFn: ({ signal }) => listBillableVisits(facility!.id, signal),
    enabled: !!facility && canManage,
  })
  const invoices = useQuery({
    queryKey: ['invoices', facility?.id, filter],
    queryFn: ({ signal }) => listInvoices(facility!.id, FILTERS[filter], signal),
    enabled: !!facility,
  })

  if (!facility) return <EmptyState title="No facility">You have no facility to work in.</EmptyState>

  return (
    <Stack>
      <PageTitle>Invoices</PageTitle>
      <Text size="sm" c="dimmed">
        Invoices at {facility.name}. Each invoice uses one currency and the approved prices of the day each charge is
        added. An issued invoice cannot change; void it with a reason and start a new one.
      </Text>

      {canManage && (
        <Card withBorder>
          <Title order={2} size="h4" mb="xs">
            Visits without an invoice
          </Title>
          <QueryState
            query={visits}
            loadingLabel="Loading visits…"
            isEmpty={(rows) => rows.length === 0}
            empty={<Text size="sm">No visits from the last 7 days are waiting for an invoice.</Text>}
          >
            {(rows) => (
              <Table aria-label="Visits without an invoice">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th scope="col">Patient</Table.Th>
                    <Table.Th scope="col">Department</Table.Th>
                    <Table.Th scope="col">Arrived</Table.Th>
                    <Table.Th scope="col">Action</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {rows.map((v) => (
                    <Table.Tr key={v.encounter_id}>
                      <Table.Td>
                        {displayName(v.patient)}
                        <Text size="xs">{v.patient.patient_number}</Text>
                      </Table.Td>
                      <Table.Td>{v.department_name}</Table.Td>
                      <Table.Td>{new Date(v.arrived_at).toLocaleString()}</Table.Td>
                      <Table.Td>
                        <Button
                          size="xs"
                          onClick={() => setStarting(v)}
                          aria-label={`Start invoice for ${displayName(v.patient)}`}
                        >
                          Start invoice
                        </Button>
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            )}
          </QueryState>
        </Card>
      )}

      <Card withBorder>
        <Group justify="space-between" mb="xs">
          <Title order={2} size="h4">
            Invoices
          </Title>
          <SegmentedControl
            aria-label="Which invoices"
            value={filter}
            onChange={setFilter}
            data={[
              { value: 'open', label: 'Draft and issued' },
              { value: 'void', label: 'Void' },
            ]}
          />
        </Group>
        <QueryState
          query={invoices}
          loadingLabel="Loading invoices…"
          isEmpty={(rows) => rows.length === 0}
          empty={<Text size="sm">No invoices.</Text>}
        >
          {(rows) => (
            <Table aria-label="Invoices">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th scope="col">Invoice</Table.Th>
                  <Table.Th scope="col">Patient</Table.Th>
                  <Table.Th scope="col">Status</Table.Th>
                  <Table.Th scope="col">Total</Table.Th>
                  <Table.Th scope="col">Started</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((i) => (
                  <Table.Tr key={i.id}>
                    <Table.Td>
                      <Anchor component={Link} to={`/billing/invoices/${i.id}`}>
                        {i.invoice_number ?? 'Draft'}
                      </Anchor>
                    </Table.Td>
                    <Table.Td>
                      {displayName(i.patient)}
                      <Text size="xs">{i.patient.patient_number}</Text>
                    </Table.Td>
                    <Table.Td>
                      <Badge variant="light" color={i.status === 'ISSUED' ? 'green' : 'gray'}>
                        {INVOICE_STATUS_LABEL[i.status]}
                      </Badge>
                    </Table.Td>
                    <Table.Td>{formatMoney(i.total, i.currency)}</Table.Td>
                    <Table.Td>{new Date(i.created_at).toLocaleString()}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </QueryState>
      </Card>

      <StartInvoiceDialog visit={starting} onClose={() => setStarting(null)} />
    </Stack>
  )
}

function StartInvoiceDialog({ visit, onClose }: { visit: BillableVisit | null; onClose: () => void }) {
  const navigate = useNavigate()
  const [currency, setCurrency] = useState('')
  const valid = currencyDecimals(currency) !== null && /^[A-Z]{3}$/.test(currency)
  const start = useMutation({
    mutationFn: () => startInvoice(visit!.encounter_id, currency),
    onSuccess: (invoice) => {
      setCurrency('')
      onClose()
      void navigate(`/billing/invoices/${invoice.id}`)
    },
  })
  const close = () => {
    setCurrency('')
    start.reset()
    onClose()
  }

  return (
    <Modal opened={visit !== null} onClose={close} title="Start invoice">
      {visit && (
        <form
          aria-label="Start invoice"
          onSubmit={(e) => {
            e.preventDefault()
            if (valid) start.mutate()
          }}
        >
          <Stack>
            <Text size="sm">
              Invoice for <strong>{displayName(visit.patient)}</strong> ({visit.patient.patient_number}). The charges of
              the visit's orders are added automatically. Every charge needs an approved price in this currency; nothing
              is converted.
            </Text>
            <TextInput
              label="Currency"
              description="ISO code, e.g. USD or LRD"
              required
              maxLength={3}
              value={currency}
              onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())}
            />
            {start.isError && <ErrorState error={start.error} />}
            <Group justify="flex-end">
              <Button variant="default" onClick={close}>
                Cancel
              </Button>
              <Button type="submit" disabled={!valid} loading={start.isPending}>
                Start invoice
              </Button>
            </Group>
          </Stack>
        </form>
      )}
    </Modal>
  )
}
