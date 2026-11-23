import { Alert, List } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { formTypeLabel, listDowntimeSources } from '../../api/downtime'

/**
 * Shown on a visit when parts of it were entered after downtime from paper forms. The records keep
 * the time they were entered; the time written on the paper is shown here (docs/22).
 */
export function DowntimeSourcesNotice({ encounterId }: { encounterId: string }) {
  const sources = useQuery({
    queryKey: ['downtime-sources', encounterId],
    queryFn: ({ signal }) => listDowntimeSources(encounterId, signal),
  })
  if (!sources.data || sources.data.length === 0) return null
  return (
    <Alert color="orange" title="Entered after downtime from paper forms">
      <List size="sm">
        {sources.data.map((s) => (
          <List.Item key={s.entry_id}>
            {formTypeLabel(s.form_type)} {s.form_label} (episode {s.episode_number}): paper time{' '}
            {new Date(s.occurred_at).toLocaleString()}, entered {new Date(s.reconciled_at).toLocaleString()}
          </List.Item>
        ))}
      </List>
    </Alert>
  )
}
