import {
  Anchor,
  Badge,
  Button,
  Card,
  Group,
  NumberInput,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import {
  declareEpisode,
  EPISODE_STATUS_LABEL,
  FORM_TYPES,
  formTypeLabel,
  issueForms,
  listBatches,
  listEpisodes,
  localToIso,
  type FormType,
} from '../../api/downtime'
import { Permission } from '../../api/types'
import { useFacilityContext } from '../../auth/facilityContextValue'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { EmptyState, ErrorState, PageTitle, QueryState } from '../../components/states'
import { formatDateTime, zoneNote } from '../../components/time'

/** Downtime episodes and numbered paper forms (task 5.3; docs/22). */
export function DowntimePage() {
  const me = useCurrentMe()
  const { current: facility } = useFacilityContext()
  const canManage = hasPermission(me, Permission.DOWNTIME_MANAGE)
  const episodes = useQuery({ queryKey: ['downtime-episodes'], queryFn: ({ signal }) => listEpisodes(signal) })

  return (
    <Stack>
      <PageTitle>Downtime</PageTitle>
      <Text size="sm" c="dimmed">
        When the system is down, staff use the numbered paper forms. Afterwards each form is keyed into the episode's
        queue and reconciled: nothing becomes a patient, visit, order or payment until it is reconciled. Registrations,
        orders and payments need a second person.
      </Text>
      <QueryState query={episodes} loadingLabel="Loading downtime episodes…">
        {(list) => {
          const here = list.filter((e) => !facility || e.facility_id === facility.id)
          if (here.length === 0) return <EmptyState title="No downtime episodes" />
          return (
            <Card withBorder>
              <Table aria-label="Downtime episodes">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th scope="col">Episode</Table.Th>
                    <Table.Th scope="col">Period</Table.Th>
                    <Table.Th scope="col">Affected</Table.Th>
                    <Table.Th scope="col">Status</Table.Th>
                    <Table.Th scope="col">Forms</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {here.map((e) => (
                    <Table.Tr key={e.id}>
                      <Table.Td>
                        <Anchor component={Link} to={`/downtime/episodes/${e.id}`}>
                          Episode {e.episode_number}
                        </Anchor>
                      </Table.Td>
                      <Table.Td>
                        {formatDateTime(e.started_at)} – {e.ended_at ? formatDateTime(e.ended_at) : 'ongoing'}
                      </Table.Td>
                      <Table.Td>{e.affected_areas}</Table.Td>
                      <Table.Td>
                        <Badge
                          variant="light"
                          color={e.status === 'ACTIVE' ? 'red' : e.status === 'ENDED' ? 'orange' : 'gray'}
                        >
                          {EPISODE_STATUS_LABEL[e.status]}
                        </Badge>
                      </Table.Td>
                      <Table.Td>
                        {e.counts.PENDING + e.counts.PROPOSED} open · {e.counts.RECONCILED} reconciled ·{' '}
                        {e.counts.VOIDED} voided
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Card>
          )
        }}
      </QueryState>
      {canManage && facility && (
        <>
          <DeclareForm facilityId={facility.id} />
          <PrintForms facilityId={facility.id} />
        </>
      )}
    </Stack>
  )
}

function DeclareForm({ facilityId }: { facilityId: string }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [start, setStart] = useState('')
  const [end, setEnd] = useState('')
  const [areas, setAreas] = useState('')
  const [reason, setReason] = useState('')
  const declare = useMutation({
    mutationFn: () =>
      declareEpisode({
        facility_id: facilityId,
        started_at: localToIso(start),
        ended_at: end ? localToIso(end) : undefined,
        affected_areas: areas.trim(),
        reason: reason.trim(),
      }),
    onSuccess: (e) => {
      void queryClient.invalidateQueries({ queryKey: ['downtime-episodes'] })
      void navigate(`/downtime/episodes/${e.id}`)
    },
  })
  const submit = (ev: FormEvent) => {
    ev.preventDefault()
    declare.mutate()
  }
  return (
    <Card withBorder component="form" aria-label="Declare a downtime episode" onSubmit={submit}>
      <Title order={2} size="h4" mb="xs">
        Declare a downtime episode
      </Title>
      <Text size="sm" mb="sm">
        Usually recorded after recovery. Leave the end empty if the outage is still going on.
      </Text>
      <Stack gap="sm">
        <Group grow>
          <TextInput
            type="datetime-local"
            description={zoneNote()}
            label="Started"
            required
            value={start}
            onChange={(e) => setStart(e.currentTarget.value)}
          />
          <TextInput
            type="datetime-local"
            description={zoneNote()}
            label="Ended"
            value={end}
            onChange={(e) => setEnd(e.currentTarget.value)}
          />
        </Group>
        <TextInput
          label="Affected areas"
          required
          maxLength={500}
          value={areas}
          onChange={(e) => setAreas(e.currentTarget.value)}
        />
        <TextInput
          label="Reason"
          required
          maxLength={500}
          value={reason}
          onChange={(e) => setReason(e.currentTarget.value)}
        />
        {declare.isError && <ErrorState error={declare.error} />}
        <Group>
          <Button type="submit" loading={declare.isPending} disabled={!start || !areas.trim() || !reason.trim()}>
            Declare
          </Button>
        </Group>
      </Stack>
    </Card>
  )
}

function PrintForms({ facilityId }: { facilityId: string }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [type, setType] = useState<FormType>('REGISTRATION')
  const [count, setCount] = useState<number | string>(20)
  const batches = useQuery({
    queryKey: ['downtime-batches', facilityId],
    queryFn: ({ signal }) => listBatches(facilityId, signal),
  })
  const issue = useMutation({
    mutationFn: () => issueForms(facilityId, type, Number(count)),
    onSuccess: (b) => {
      void queryClient.invalidateQueries({ queryKey: ['downtime-batches'] })
      void navigate(`/downtime/forms/${b.id}/print`)
    },
  })
  const n = Number(count)
  return (
    <Card withBorder>
      <Title order={2} size="h4" mb="xs">
        Numbered paper forms
      </Title>
      <Text size="sm" mb="sm">
        Print forms before they are needed and keep them with the downtime kit. Every form has its own number; numbers
        are never reused.
      </Text>
      <Group align="end">
        <Select
          label="Form"
          data={FORM_TYPES}
          value={type}
          allowDeselect={false}
          onChange={(v) => v && setType(v as FormType)}
        />
        <NumberInput label="How many" min={1} max={500} value={count} onChange={setCount} w={140} />
        <Button onClick={() => issue.mutate()} loading={issue.isPending} disabled={!(n >= 1 && n <= 500)}>
          Issue and print
        </Button>
      </Group>
      {issue.isError && <ErrorState error={issue.error} />}
      <QueryState query={batches} loadingLabel="Loading issued forms…">
        {(list) =>
          list.length === 0 ? (
            <Text size="sm" mt="sm">
              No forms issued yet.
            </Text>
          ) : (
            <Table aria-label="Issued forms" mt="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th scope="col">Form</Table.Th>
                  <Table.Th scope="col">Numbers</Table.Th>
                  <Table.Th scope="col">Issued</Table.Th>
                  <Table.Th scope="col">Print</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {list.map((b) => (
                  <Table.Tr key={b.id}>
                    <Table.Td>{formTypeLabel(b.form_type)}</Table.Td>
                    <Table.Td>
                      {b.first_label} – {b.last_label}
                    </Table.Td>
                    <Table.Td>
                      {formatDateTime(b.issued_at)} by {b.issued_by.display_name}
                    </Table.Td>
                    <Table.Td>
                      <Anchor component={Link} to={`/downtime/forms/${b.id}/print`}>
                        Print again
                      </Anchor>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )
        }
      </QueryState>
    </Card>
  )
}
