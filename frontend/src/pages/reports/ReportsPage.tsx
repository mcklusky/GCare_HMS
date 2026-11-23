import { Anchor, Button, Card, Group, Select, SimpleGrid, Stack, Table, Text, TextInput, Title } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { formatMoney, todayUtc } from '../../api/billing'
import { exportUrl, getDashboard, listReports, runReport, type ReportView } from '../../api/reports'
import { useFacilityContext } from '../../auth/facilityContextValue'
import { EmptyState, PageHeader, QueryState } from '../../components/states'
import { formatDateTime } from '../../components/time'

/**
 * Dashboard and operational reports (task 5.1). Aggregates for the current facility only; days are
 * the facility's local days. CSV downloads (if permitted) contain the same numbers and are audited.
 */
export function ReportsPage() {
  const { current: facility } = useFacilityContext()
  const [slug, setSlug] = useState<string | null>(null)
  const [from, setFrom] = useState(todayUtc())
  const [to, setTo] = useState(todayUtc())
  const [run, setRun] = useState<{ slug: string; from: string; to: string } | null>(null)

  const dashboard = useQuery({
    queryKey: ['dashboard', facility?.id],
    queryFn: ({ signal }) => getDashboard(facility!.id, signal),
    enabled: !!facility,
    refetchInterval: 60_000,
  })
  const reports = useQuery({ queryKey: ['reports'], queryFn: ({ signal }) => listReports(signal) })
  const report = useQuery({
    queryKey: ['report', facility?.id, run],
    queryFn: ({ signal }) => runReport(run!.slug, facility!.id, run!.from, run!.to, signal),
    enabled: !!facility && run !== null,
  })

  if (!facility) return <EmptyState title="No facility">You have no facility to report on.</EmptyState>
  const validRange = /^\d{4}-\d{2}-\d{2}$/.test(from) && /^\d{4}-\d{2}-\d{2}$/.test(to) && from <= to
  const exportable = reports.data?.find((r) => r.slug === run?.slug)?.exportable ?? false

  return (
    <Stack>
      <PageHeader
        title="Reports"
        description="Aggregated numbers only, counted in facility-local days. Every export is recorded in the audit trail."
      />
      <QueryState query={dashboard} loadingLabel="Loading today's numbers…">
        {(d) => (
          <Card withBorder>
            <Title order={2} size="h4" mb="xs">
              Today at {d.facility_name} ({d.day})
            </Title>
            <SimpleGrid cols={{ base: 2, sm: 3, md: 5 }}>
              {d.tiles.map((t) => (
                <Stack key={t.key} gap={0}>
                  <Text size="xs" c="dimmed">
                    {t.label}
                  </Text>
                  <Text size="xl" fw={700}>
                    {t.currency ? formatMoney(t.value ?? 0, t.currency) : String(t.value ?? 0)}
                  </Text>
                </Stack>
              ))}
            </SimpleGrid>
          </Card>
        )}
      </QueryState>

      <Card withBorder>
        <form
          aria-label="Run a report"
          onSubmit={(e) => {
            e.preventDefault()
            if (slug && validRange) setRun({ slug, from, to })
          }}
        >
          <Group align="flex-end">
            <QueryState query={reports} loadingLabel="Loading reports…">
              {(rows) => (
                <Select
                  label="Report"
                  required
                  value={slug}
                  onChange={setSlug}
                  data={rows.map((r) => ({ value: r.slug, label: r.title }))}
                />
              )}
            </QueryState>
            <TextInput
              label="From"
              type="date"
              required
              value={from}
              onChange={(e) => setFrom(e.currentTarget.value)}
            />
            <TextInput label="To" type="date" required value={to} onChange={(e) => setTo(e.currentTarget.value)} />
            <Button type="submit" disabled={!slug || !validRange}>
              Run report
            </Button>
          </Group>
        </form>
      </Card>

      {run && (
        <QueryState query={report} loadingLabel="Running report…">
          {(r) => <ReportTable report={r} exportable={exportable} />}
        </QueryState>
      )}
    </Stack>
  )
}

function ReportTable({ report, exportable }: { report: ReportView; exportable: boolean }) {
  const show = (row: ReportView['rows'][number], key: string, type: string) => {
    const value = row[key]
    if (value === null || value === undefined) return '—'
    if (type === 'DECIMAL' && typeof row.currency === 'string') return formatMoney(value, row.currency)
    return String(value)
  }
  return (
    <Card withBorder>
      <Group justify="space-between" mb="xs">
        <Title order={2} size="h4">
          {report.title}
        </Title>
        {exportable && (
          <Anchor href={exportUrl(report.report, report.facility_id, report.from, report.to)} download>
            Download CSV
          </Anchor>
        )}
      </Group>
      <Text size="xs" c="dimmed" mb="xs">
        {report.facility_name}, {report.from} to {report.to} (days in {report.timezone}). Generated{' '}
        {formatDateTime(report.generated_at)} by {report.generated_by.display_name}.
      </Text>
      {report.rows.length === 0 ? (
        <Text size="sm">No data in this period.</Text>
      ) : (
        <Table.ScrollContainer minWidth={600}>
          <Table aria-label={report.title} striped>
            <Table.Thead>
              <Table.Tr>
                {report.columns.map((c) => (
                  <Table.Th scope="col" key={c.key}>
                    {c.label}
                  </Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {report.rows.map((row, i) => (
                <Table.Tr key={i}>
                  {report.columns.map((c) => (
                    <Table.Td key={c.key}>{show(row, c.key, c.type)}</Table.Td>
                  ))}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {report.notes.map((n) => (
        <Text key={n} size="xs" c="dimmed" mt={4}>
          {n}
        </Text>
      ))}
    </Card>
  )
}
