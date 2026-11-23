import {
  Alert,
  Badge,
  Button,
  Card,
  Group,
  NumberInput,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Textarea,
  Title,
} from '@mantine/core'
import { IconInfoCircle } from '@tabler/icons-react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  MEASURES,
  getTriageSettings,
  listObservations,
  markEnteredInError,
  recordObservations,
  type MeasureKey,
  type Observation,
  type ObservationInput,
} from '../../api/encounters'
import { Permission } from '../../api/types'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, QueryState } from '../../components/states'
import { formatDateTime } from '../../components/time'

/**
 * Triage observations (FR-007). Units are fixed. No clinical thresholds live in the UI: urgency
 * levels and flags come only from the backend configuration, which a clinical lead must supply.
 */
export function TriageSection({ encounterId, open }: { encounterId: string; open: boolean }) {
  const me = useCurrentMe()
  const canRecord = hasPermission(me, Permission.OBSERVATION_RECORD)
  const settings = useQuery({ queryKey: ['triage-settings'], queryFn: ({ signal }) => getTriageSettings(signal) })
  const observations = useQuery({
    queryKey: ['observations', encounterId],
    queryFn: ({ signal }) => listObservations(encounterId, signal),
  })

  return (
    <Card withBorder>
      <Title order={2} size="h4" mb="xs">
        Triage observations
      </Title>
      <Stack>
        {settings.data && settings.data.urgency_levels.length === 0 && (
          <Alert color="gray" variant="light" icon={<IconInfoCircle aria-hidden />} title="No urgency scale configured">
            Urgency levels and alarm limits have not been set by a clinical lead yet. Values are recorded but not graded
            or flagged. Follow your local triage procedure.
          </Alert>
        )}
        {settings.data && settings.data.urgency_levels.length > 0 && !settings.data.alarm_limits_configured && (
          <Alert color="gray" variant="light" icon={<IconInfoCircle aria-hidden />}>
            Alarm limits are not configured: values are not flagged automatically.
          </Alert>
        )}
        {canRecord && open && settings.data && (
          <ObservationForm encounterId={encounterId} levels={settings.data.urgency_levels} />
        )}
        <QueryState
          query={observations}
          loadingLabel="Loading observations…"
          isEmpty={(rows) => rows.length === 0}
          empty={<EmptyState title="No observations yet" />}
        >
          {(rows) => <ObservationTable rows={rows} canRecord={canRecord} encounterId={encounterId} />}
        </QueryState>
      </Stack>
    </Card>
  )
}

function ObservationForm({ encounterId, levels }: { encounterId: string; levels: { code: string; label: string }[] }) {
  const queryClient = useQueryClient()
  const [values, setValues] = useState<Partial<Record<MeasureKey, number>>>({})
  const [urgency, setUrgency] = useState<string | null>(null)
  const [allergy, setAllergy] = useState('')
  const [notes, setNotes] = useState('')
  const record = useMutation({
    mutationFn: () => {
      const body: ObservationInput = { ...values }
      if (urgency) body.urgency = urgency
      if (allergy.trim()) body.allergy_note = allergy.trim()
      if (notes.trim()) body.notes = notes.trim()
      return recordObservations(encounterId, body)
    },
    onSuccess: () => {
      setValues({})
      setUrgency(null)
      setAllergy('')
      setNotes('')
      void queryClient.invalidateQueries({ queryKey: ['observations', encounterId] })
      void queryClient.invalidateQueries({ queryKey: ['encounters'] })
      void queryClient.invalidateQueries({ queryKey: ['encounter', encounterId] })
    },
  })
  const empty = Object.keys(values).length === 0 && !urgency && !allergy.trim() && !notes.trim()

  return (
    <form
      aria-label="Record observations"
      onSubmit={(ev) => {
        ev.preventDefault()
        record.mutate()
      }}
    >
      <Stack>
        <SimpleGrid cols={{ base: 1, sm: 3 }}>
          {MEASURES.map((m) => (
            <NumberInput
              key={m.key}
              label={`${m.label} (${m.unit})`}
              step={m.step}
              decimalScale={m.step < 1 ? 1 : 0}
              allowNegative={false}
              value={values[m.key] ?? ''}
              onChange={(v) =>
                setValues((prev) => {
                  const next = { ...prev }
                  if (typeof v === 'number') next[m.key] = v
                  else delete next[m.key]
                  return next
                })
              }
            />
          ))}
        </SimpleGrid>
        {levels.length > 0 && (
          <Select
            label="Urgency"
            clearable
            data={levels.map((l) => ({ value: l.code, label: l.label }))}
            value={urgency}
            onChange={setUrgency}
            w={280}
          />
        )}
        <Textarea
          label="Allergies reported"
          value={allergy}
          onChange={(e) => setAllergy(e.currentTarget.value)}
          maxLength={500}
          autosize
        />
        <Textarea
          label="Triage notes"
          value={notes}
          onChange={(e) => setNotes(e.currentTarget.value)}
          maxLength={1000}
          autosize
        />
        {record.isError && <ErrorState error={record.error} />}
        <Group>
          <Button type="submit" disabled={empty} loading={record.isPending}>
            Save observations
          </Button>
        </Group>
      </Stack>
    </form>
  )
}

function ObservationTable({
  rows,
  canRecord,
  encounterId,
}: {
  rows: Observation[]
  canRecord: boolean
  encounterId: string
}) {
  const queryClient = useQueryClient()
  const [marking, setMarking] = useState<Observation | null>(null)
  const mark = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => markEnteredInError(id, reason),
    onSuccess: () => {
      setMarking(null)
      void queryClient.invalidateQueries({ queryKey: ['observations', encounterId] })
      void queryClient.invalidateQueries({ queryKey: ['encounters'] })
    },
  })

  return (
    <>
      {mark.isError && <ErrorState error={mark.error} />}
      <Table aria-label="Recorded observations">
        <Table.Thead>
          <Table.Tr>
            <Table.Th scope="col">Recorded</Table.Th>
            <Table.Th scope="col">Values</Table.Th>
            <Table.Th scope="col">Flags</Table.Th>
            <Table.Th scope="col">Status</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((o) => {
            const error = o.status === 'ENTERED_IN_ERROR'
            return (
              <Table.Tr key={o.id}>
                <Table.Td>
                  {formatDateTime(o.recorded_at)}
                  <Text size="xs">{o.recorded_by.display_name}</Text>
                </Table.Td>
                <Table.Td td={error ? 'line-through' : undefined}>
                  {MEASURES.filter((m) => o[m.key] !== null)
                    .map((m) => `${m.label} ${o[m.key]} ${m.unit}`)
                    .join(' · ') || '—'}
                  {o.urgency && <Text size="sm">Urgency: {o.urgency}</Text>}
                  {o.allergy_note && <Text size="sm">Allergies: {o.allergy_note}</Text>}
                  {o.notes && <Text size="sm">Notes: {o.notes}</Text>}
                </Table.Td>
                <Table.Td>
                  {o.flags.map((f) => (
                    <Badge key={f.measure} color="red" variant="light">
                      {f.measure.toLowerCase().replace('_', ' ')} {f.direction.toLowerCase()}
                    </Badge>
                  ))}
                </Table.Td>
                <Table.Td>
                  {error ? (
                    <Text size="sm">
                      Entered in error: {o.error_reason} ({o.error_by?.display_name})
                    </Text>
                  ) : (
                    canRecord && (
                      <Button size="xs" variant="default" onClick={() => setMarking(o)}>
                        Mark entered in error
                      </Button>
                    )
                  )}
                </Table.Td>
              </Table.Tr>
            )
          })}
        </Table.Tbody>
      </Table>
      <ConfirmDialog
        opened={marking !== null}
        title="Mark as entered in error?"
        confirmLabel="Mark entered in error"
        danger
        requireReason
        busy={mark.isPending}
        onConfirm={(reason) => marking && mark.mutate({ id: marking.id, reason: reason ?? '' })}
        onCancel={() => setMarking(null)}
      >
        The values stay visible, crossed out, with your reason. Record the correct values as a new set.
      </ConfirmDialog>
    </>
  )
}
