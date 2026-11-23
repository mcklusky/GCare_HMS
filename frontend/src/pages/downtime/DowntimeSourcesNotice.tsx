import { Alert, List } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { formTypeLabel, listDowntimeSources } from '../../api/downtime'
import { formatDateTime } from '../../components/time'

/**
 * Shown on a visit when parts of it were entered after downtime from paper forms. The records keep
 * the time they were entered; the time written on the paper is shown here (docs/22).
 */
export function DowntimeSourcesNotice({ encounterId }: { encounterId: string }) {
  const sources = useQuery({
    queryKey: ['downtime-sources', encounterId],
    queryFn: ({ signal }) => listDowntimeSources(encounterId, signal),
  })
  if (sources.isError)
    return (
      <Alert color="red" title="Could not check for paper (downtime) records" role="alert">
        Parts of this visit may have been entered later from paper forms; their paper times could not be loaded. Reload
        the page before relying on recorded times.
      </Alert>
    )
  if (!sources.data || sources.data.length === 0) return null
  return (
    <Alert color="orange" title="Entered after downtime from paper forms">
      <List size="sm">
        {sources.data.map((s) => (
          <List.Item key={s.entry_id}>
            {formTypeLabel(s.form_type)} {s.form_label} (episode {s.episode_number}): paper time{' '}
            {formatDateTime(s.occurred_at)}, entered {formatDateTime(s.reconciled_at)}
          </List.Item>
        ))}
      </List>
    </Alert>
  )
}
