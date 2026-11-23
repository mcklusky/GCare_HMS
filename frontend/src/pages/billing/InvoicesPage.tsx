import {
  ActionIcon,
  Anchor,
  Badge,
  Box,
  Button,
  Card,
  Group,
  Modal,
  Pagination,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  Title,
  Tooltip,
} from '@mantine/core'
import {
  IconCircleCheck,
  IconClockHour4,
  IconCoins,
  IconEye,
  IconFileInvoice,
  IconFilePencil,
  IconSearch,
} from '@tabler/icons-react'
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
  type InvoiceSummary,
} from '../../api/invoices'
import { displayName } from '../../api/patients'
import { Permission } from '../../api/types'
import { useFacilityContext } from '../../auth/facilityContextValue'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { EmptyState, ErrorState, PageHeader, Pill, QueryState, StatCard, TodayStamp } from '../../components/states'
import { formatDate, formatTime } from '../../components/time'

const FILTERS: Record<string, InvoiceStatus[]> = { open: ['DRAFT', 'ISSUED'], void: ['VOID'] }
const STATUS_COLOR: Record<InvoiceStatus, string> = { DRAFT: 'gray', ISSUED: 'green', VOID: 'red' }
const PAGE_SIZE = 10

const matches = (q: string, ...fields: (string | null | undefined)[]) =>
  !q || fields.some((f) => f?.toLowerCase().includes(q.toLowerCase()))

/** Date on one line, time underneath (facility time zone). */
function When({ iso }: { iso: string }) {
  return (
    <>
      <Text size="sm" fw={600}>
        {formatDate(iso)}
      </Text>
      <Text size="xs" c="dimmed">
        {formatTime(iso, { hour: '2-digit', minute: '2-digit' })}
      </Text>
    </>
  )
}

function PatientCell({ patient }: { patient: BillableVisit['patient'] }) {
  return (
    <>
      <Text size="sm" fw={500}>
        {displayName(patient)}
      </Text>
      <Text size="xs" c="dimmed" className="gcare-ref" fw={500}>
        {patient.patient_number}
      </Text>
    </>
  )
}

/** "Showing a–b of n" with page buttons; hidden when everything fits on one page. */
function Pager({
  total,
  pages,
  page,
  onChange,
  noun,
}: {
  total: number
  pages: number
  page: number
  onChange: (p: number) => void
  noun: string
}) {
  if (pages <= 1) return null
  const from = (page - 1) * PAGE_SIZE + 1
  const to = Math.min(page * PAGE_SIZE, total)
  return (
    <Group justify="space-between" px="md" py="sm" className="gcare-pager">
      <Text size="xs" c="dimmed">
        Showing {from}–{to} of {total} {noun}
      </Text>
      <Pagination
        total={pages}
        value={page}
        onChange={onChange}
        size="sm"
        getControlProps={(control) => ({
          'aria-label': control === 'previous' ? 'Previous page' : control === 'next' ? 'Next page' : undefined,
        })}
      />
    </Group>
  )
}

/** Invoices (task 4.2): visits waiting for an invoice, and the facility's invoices. */
export function InvoicesPage() {
  const me = useCurrentMe()
  const canManage = hasPermission(me, Permission.INVOICE_MANAGE)
  const { current: facility } = useFacilityContext()
  const [filter, setFilter] = useState('open')
  const [starting, setStarting] = useState<BillableVisit | null>(null)
  const [visitQuery, setVisitQuery] = useState('')
  const [invoiceQuery, setInvoiceQuery] = useState('')
  const [status, setStatus] = useState<string>('ALL')
  const [page, setPage] = useState(1)
  const [visitPage, setVisitPage] = useState(1)

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

  const open = filter === 'open' ? (invoices.data ?? []) : null
  const count = (st: InvoiceStatus) => (open ? String(open.filter((i) => i.status === st).length) : '–')
  const currencies = open ? new Set(open.map((i) => i.currency)).size : null

  const changeTab = (v: string | null) => {
    if (!v) return
    setFilter(v)
    setStatus('ALL')
    setPage(1)
  }
  const shown = (rows: InvoiceSummary[]) =>
    rows.filter(
      (i) =>
        (status === 'ALL' || i.status === status) &&
        matches(invoiceQuery, i.invoice_number, displayName(i.patient), i.patient.patient_number),
    )

  return (
    <Stack gap="lg">
      <PageHeader
        title="Invoices"
        description={<>Billing at {facility.name}: visits waiting for an invoice, drafts and issued invoices.</>}
        actions={<TodayStamp />}
      />

      <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" component="section" aria-label="Billing at a glance">
        {canManage && (
          <StatCard
            label="Waiting for an invoice"
            tone="orange"
            icon={<IconClockHour4 size={18} />}
            value={visits.data ? visits.data.length : '–'}
            hint="Visits without an invoice"
          />
        )}
        <StatCard
          label="Draft invoices"
          tone="violet"
          icon={<IconFilePencil size={18} />}
          value={count('DRAFT')}
          hint="Charges can still change"
        />
        <StatCard
          label="Issued invoices"
          tone="green"
          icon={<IconCircleCheck size={18} />}
          value={count('ISSUED')}
          hint="Final; payable"
        />
        <StatCard
          label="Currencies in use"
          tone="blue"
          icon={<IconCoins size={18} />}
          value={currencies ?? '–'}
          hint="One currency per invoice, never converted"
        />
      </SimpleGrid>

      {canManage && (
        <Card withBorder padding={0} className="gcare-flush" component="section" aria-labelledby="visits-title">
          <Group justify="space-between" px="md" py="sm" className="gcare-card-head" wrap="wrap">
            <Box>
              <Title order={2} id="visits-title">
                Visits without an invoice
              </Title>
              <Text size="xs" c="dimmed">
                Start an invoice; the visit's order charges are added automatically.
              </Text>
            </Box>
            <TextInput
              aria-label="Filter visits by patient"
              placeholder="Filter by patient name or number"
              leftSection={<IconSearch size={14} aria-hidden />}
              size="xs"
              w={260}
              value={visitQuery}
              onChange={(e) => {
                setVisitQuery(e.currentTarget.value)
                setVisitPage(1)
              }}
            />
          </Group>
          <QueryState
            query={visits}
            loadingLabel="Loading visits…"
            isEmpty={(rows) => rows.length === 0}
            empty={
              <Text size="sm" c="dimmed" p="md">
                No recent visits are waiting for an invoice.
              </Text>
            }
          >
            {(rows) => {
              const all = rows.filter((v) => matches(visitQuery, displayName(v.patient), v.patient.patient_number))
              const pages = Math.max(1, Math.ceil(all.length / PAGE_SIZE))
              const current = Math.min(visitPage, pages)
              const list = all.slice((current - 1) * PAGE_SIZE, current * PAGE_SIZE)
              return (
                <>
                  <Table.ScrollContainer minWidth={640}>
                    <Table aria-label="Visits without an invoice">
                      <Table.Thead>
                        <Table.Tr>
                          <Table.Th scope="col">Arrived</Table.Th>
                          <Table.Th scope="col">Patient</Table.Th>
                          <Table.Th scope="col">Department</Table.Th>
                          <Table.Th scope="col" ta="right">
                            Action
                          </Table.Th>
                        </Table.Tr>
                      </Table.Thead>
                      <Table.Tbody>
                        {list.length === 0 && (
                          <Table.Tr>
                            <Table.Td colSpan={4}>
                              <Text size="sm" c="dimmed">
                                No visits match the filter.
                              </Text>
                            </Table.Td>
                          </Table.Tr>
                        )}
                        {list.map((v) => (
                          <Table.Tr key={v.encounter_id}>
                            <Table.Td>
                              <When iso={v.arrived_at} />
                            </Table.Td>
                            <Table.Td>
                              <PatientCell patient={v.patient} />
                            </Table.Td>
                            <Table.Td>
                              <Pill color="blue">{v.department_name}</Pill>
                            </Table.Td>
                            <Table.Td ta="right">
                              <Button
                                size="xs"
                                variant="light"
                                leftSection={<IconFileInvoice size={14} aria-hidden />}
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
                  </Table.ScrollContainer>
                  <Pager total={all.length} pages={pages} page={current} onChange={setVisitPage} noun="visits" />
                </>
              )
            }}
          </QueryState>
        </Card>
      )}

      <Card withBorder padding={0} className="gcare-flush" component="section" aria-labelledby="invoices-title">
        <Tabs value={filter} onChange={changeTab} className="gcare-tabs">
          <Tabs.List aria-label="Which invoices">
            <Tabs.Tab value="open">
              Draft and issued
              {open && (
                <Badge size="xs" variant="light" ml={6}>
                  {open.length}
                </Badge>
              )}
            </Tabs.Tab>
            <Tabs.Tab value="void">Void</Tabs.Tab>
          </Tabs.List>
        </Tabs>
        <div className="gcare-filterbar">
          <TextInput
            label="Search"
            aria-label="Search invoices"
            placeholder="Invoice number or patient"
            leftSection={<IconSearch size={14} aria-hidden />}
            size="xs"
            w={280}
            value={invoiceQuery}
            onChange={(e) => {
              setInvoiceQuery(e.currentTarget.value)
              setPage(1)
            }}
          />
          {filter === 'open' && (
            <Select
              label="Status"
              size="xs"
              w={160}
              allowDeselect={false}
              value={status}
              onChange={(v) => {
                setStatus(v ?? 'ALL')
                setPage(1)
              }}
              data={[
                { value: 'ALL', label: 'All statuses' },
                { value: 'DRAFT', label: INVOICE_STATUS_LABEL.DRAFT },
                { value: 'ISSUED', label: INVOICE_STATUS_LABEL.ISSUED },
              ]}
            />
          )}
          <Button
            size="xs"
            variant="default"
            onClick={() => {
              setInvoiceQuery('')
              setStatus('ALL')
              setPage(1)
            }}
          >
            Reset
          </Button>
        </div>
        <Group justify="space-between" px="md" pt="sm" pb={6}>
          <Title order={2} id="invoices-title">
            {filter === 'open' ? 'Draft and issued invoices' : 'Void invoices'}
          </Title>
        </Group>
        <QueryState
          query={invoices}
          loadingLabel="Loading invoices…"
          isEmpty={(rows) => rows.length === 0}
          empty={
            <Text size="sm" c="dimmed" px="md" pb="md">
              No invoices.
            </Text>
          }
        >
          {(rows) => {
            const list = shown(rows)
            const pages = Math.max(1, Math.ceil(list.length / PAGE_SIZE))
            const current = Math.min(page, pages)
            const slice = list.slice((current - 1) * PAGE_SIZE, current * PAGE_SIZE)
            return (
              <>
                <Text size="xs" c="dimmed" px="md" pb={6}>
                  {list.length === 0
                    ? 'No invoices match the filters.'
                    : `Showing ${(current - 1) * PAGE_SIZE + 1}–${(current - 1) * PAGE_SIZE + slice.length} of ${list.length}`}
                </Text>
                <Table.ScrollContainer minWidth={720}>
                  <Table aria-label="Invoices">
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th scope="col">Started</Table.Th>
                        <Table.Th scope="col">Invoice</Table.Th>
                        <Table.Th scope="col">Patient</Table.Th>
                        <Table.Th scope="col">Status</Table.Th>
                        <Table.Th scope="col" ta="right">
                          Total
                        </Table.Th>
                        <Table.Th scope="col" ta="right">
                          Actions
                        </Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {slice.map((i) => (
                        <Table.Tr key={i.id}>
                          <Table.Td>
                            <When iso={i.created_at} />
                          </Table.Td>
                          <Table.Td>
                            <Anchor component={Link} to={`/billing/invoices/${i.id}`} className="gcare-ref">
                              {i.invoice_number ?? 'Draft'}
                            </Anchor>
                          </Table.Td>
                          <Table.Td>
                            <PatientCell patient={i.patient} />
                          </Table.Td>
                          <Table.Td>
                            <Pill color={STATUS_COLOR[i.status]} dot>
                              {INVOICE_STATUS_LABEL[i.status]}
                            </Pill>
                          </Table.Td>
                          <Table.Td ta="right" fw={650} className="gcare-stat-value">
                            {formatMoney(i.total, i.currency)}
                          </Table.Td>
                          <Table.Td ta="right">
                            <Tooltip label="Open invoice">
                              <ActionIcon
                                component={Link}
                                to={`/billing/invoices/${i.id}`}
                                variant="subtle"
                                color="gray"
                                aria-label={`Open invoice ${i.invoice_number ?? 'draft'} for ${displayName(i.patient)}`}
                              >
                                <IconEye size={16} aria-hidden />
                              </ActionIcon>
                            </Tooltip>
                          </Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </Table.ScrollContainer>
                <Pager total={list.length} pages={pages} page={current} onChange={setPage} noun="invoices" />
              </>
            )
          }}
        </QueryState>
      </Card>

      <div className="gcare-banner" role="note">
        <IconCircleCheck size={18} aria-hidden style={{ flexShrink: 0, marginTop: 1 }} />
        <span>
          An issued invoice never changes: to correct it, void it with a reason and start a new one. Payments, refunds
          and exemptions keep their own records and need a second person where the billing policy says so.
        </span>
      </div>

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
