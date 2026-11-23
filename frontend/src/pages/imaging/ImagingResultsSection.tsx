import { Alert, Anchor, Badge, Button, Card, Group, Stack, Text, Title } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  acknowledgeCritical,
  getEncounterImaging,
  getPatientImaging,
  METHOD_LABEL,
  type CriticalResult,
  type ImagingResult,
  type Report,
} from '../../api/radiologyReports'
import { safeViewerHref } from '../../api/imaging'
import { Permission } from '../../api/types'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { QueryState } from '../../components/states'

/**
 * Imaging results on a visit or in the patient chart (task 6.4; docs/26): signed reports, critical
 * results with their acknowledgement, and viewer links; images without a report say "Not yet reported".
 */
export function ImagingResultsSection({ encounterId, patientId }: { encounterId?: string; patientId?: string }) {
  const me = useCurrentMe()
  const queryClient = useQueryClient()
  const [acking, setAcking] = useState<CriticalResult | null>(null)
  const key = ['imaging-results', encounterId ?? patientId]
  const results = useQuery({
    queryKey: key,
    queryFn: ({ signal }) =>
      encounterId ? getEncounterImaging(encounterId, signal) : getPatientImaging(patientId!, signal),
  })
  const ack = useMutation({
    mutationFn: ({ c, note }: { c: CriticalResult; note: string }) => acknowledgeCritical(c, note),
    onSuccess: () => {
      setAcking(null)
      void queryClient.invalidateQueries({ queryKey: key })
      void queryClient.invalidateQueries({ queryKey: ['critical-results'] })
    },
    // Someone may have acknowledged it first (409): reload so the dialog's message matches the list.
    onError: () => void queryClient.invalidateQueries({ queryKey: key }),
  })
  const canAck = hasPermission(me, Permission.CRITICAL_RESULT_ACKNOWLEDGE)

  return (
    <Card withBorder>
      <Title order={2} size="h4" mb="xs">
        Imaging results
      </Title>
      <QueryState query={results} loadingLabel="Loading imaging results…">
        {(list) =>
          list.length === 0 ? (
            <Text size="sm">No imaging orders.</Text>
          ) : (
            <Stack>
              {list.map((r) => (
                <Result key={r.order_id} result={r} canAck={canAck} onAck={(c) => (ack.reset(), setAcking(c))} />
              ))}
            </Stack>
          )
        }
      </QueryState>
      <ConfirmDialog
        opened={acking !== null}
        title="Acknowledge this critical result?"
        confirmLabel="Acknowledge"
        requireReason
        busy={ack.isPending}
        error={ack.error}
        onConfirm={(note) => acking && ack.mutate({ c: acking, note: note ?? '' })}
        onCancel={() => setAcking(null)}
      >
        Confirm you have read the report and say what was done (for example, patient recalled).
      </ConfirmDialog>
    </Card>
  )
}

function Result({
  result: r,
  canAck,
  onAck,
}: {
  result: ImagingResult
  canAck: boolean
  onAck: (c: CriticalResult) => void
}) {
  const crit = r.report?.critical_result ?? null
  return (
    <Stack gap={4} component="section" aria-label={`${r.item_name}, accession ${r.accession_number}`}>
      <Group gap="xs">
        <Text fw={600}>{r.item_name}</Text>
        <Text size="xs" ff="monospace">
          {r.accession_number}
        </Text>
        {r.report_state === 'NOT_YET_REPORTED' ? (
          <Badge color="gray" variant="light">
            Not yet reported
          </Badge>
        ) : (
          <Badge color="green" variant="light">
            {r.report_state === 'AMENDED' ? 'Reported (amended)' : 'Reported'}
          </Badge>
        )}
        {r.report?.critical && (
          <Badge color="red" variant="filled">
            Critical result
          </Badge>
        )}
      </Group>
      {r.studies.map(
        (s) =>
          safeViewerHref(s.viewer_url) && (
            <Anchor key={s.id} href={safeViewerHref(s.viewer_url)!} target="_blank" rel="noopener noreferrer" size="sm">
              Open images ({s.modalities.join(', ') || 'study'}, {s.instance_count ?? '?'} images)
              {r.report_state === 'NOT_YET_REPORTED' ? ' — not yet reported' : ''}
            </Anchor>
          ),
      )}
      {crit && (
        <Alert
          color="red"
          title={crit.acknowledged_at ? 'Critical result acknowledged' : 'Critical result: not yet acknowledged'}
        >
          <Text size="sm">
            Told to {crit.communicated_to} by {METHOD_LABEL[crit.communication_method].toLowerCase()} at{' '}
            {new Date(crit.communicated_at).toLocaleString()} ({crit.flagged_by.display_name}).
          </Text>
          {crit.acknowledged_at ? (
            <Text size="sm">
              Acknowledged by {crit.acknowledged_by?.display_name} at {new Date(crit.acknowledged_at).toLocaleString()}
              {crit.acknowledgement_note ? `: ${crit.acknowledgement_note}` : ''}
            </Text>
          ) : (
            canAck && (
              <Button size="xs" mt="xs" color="red" onClick={() => onAck(crit)}>
                Acknowledge
              </Button>
            )
          )}
        </Alert>
      )}
      {r.report && <ReportText report={r.report} />}
      {r.earlier_reports.length > 0 && (
        <details>
          <summary>
            <Text span size="sm">
              Earlier versions ({r.earlier_reports.length})
            </Text>
          </summary>
          {r.earlier_reports.map((e) => (
            <ReportText key={e.id} report={e} />
          ))}
        </details>
      )}
    </Stack>
  )
}

export function ReportText({ report }: { report: Report }) {
  return (
    <Stack gap={2} pl="sm" style={{ borderLeft: '3px solid var(--mantine-color-gray-4)' }}>
      <Text size="xs" c="dimmed">
        Signed by {report.author.display_name}
        {report.signed_at && ` at ${new Date(report.signed_at).toLocaleString()}`}
        {report.amendment_reason && ` · amendment: ${report.amendment_reason}`}
      </Text>
      {(
        [
          ['Technique', report.technique],
          ['Comparison', report.comparison],
          ['Findings', report.findings],
          ['Impression', report.impression],
        ] as const
      ).map(
        ([label, text]) =>
          text && (
            <Text key={label} size="sm" style={{ whiteSpace: 'pre-wrap' }}>
              <strong>{label}:</strong> {text}
            </Text>
          ),
      )}
    </Stack>
  )
}
