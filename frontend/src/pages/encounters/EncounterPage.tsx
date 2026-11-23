import { Anchor, Badge, Button, Card, Group, Select, Stack, Table, Text, Title } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import {
  EVENT_LABEL,
  STATUS_LABEL,
  TYPE_LABEL,
  encounterAction,
  getEncounter,
  listDepartments,
  type EncounterAction,
  type EncounterSummary,
} from '../../api/encounters'
import { Permission } from '../../api/types'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { PatientBanner } from '../../components/PatientBadges'
import { ErrorState, PageTitle, QueryState } from '../../components/states'
import { TriageSection } from './TriageSection'

const DIALOG: Record<
  Exclude<EncounterAction, 'call'>,
  { title: string; label: string; text: string; reason: boolean }
> = {
  transfer: {
    title: 'Transfer this visit?',
    label: 'Transfer',
    text: 'The patient joins the queue of the chosen department.',
    reason: true,
  },
  close: {
    title: 'Close this visit?',
    label: 'Close visit',
    text: 'The visit ends now. It can be reopened with a reason.',
    reason: false,
  },
  cancel: {
    title: 'Cancel this visit?',
    label: 'Cancel visit',
    text: 'Use when the patient left before being seen or the visit was opened in error.',
    reason: true,
  },
  reopen: {
    title: 'Reopen this visit?',
    label: 'Reopen',
    text: 'You become the clinician for the reopened visit.',
    reason: true,
  },
}

/** One visit: patient identity, status, actions, history and triage (FR-006, FR-007). */
export function EncounterPage() {
  const { encounterId = '' } = useParams()
  const encounter = useQuery({
    queryKey: ['encounter', encounterId],
    queryFn: ({ signal }) => getEncounter(encounterId, signal),
  })
  const me = useCurrentMe()

  return (
    <Stack>
      <Anchor component={Link} to="/queue" size="sm">
        ← Queue
      </Anchor>
      <QueryState query={encounter} loadingLabel="Loading visit…">
        {({ encounter: e, events }) => (
          <Stack>
            <PageTitle>
              {TYPE_LABEL[e.encounter_type]}, {e.department.name}
            </PageTitle>
            <PatientBanner patient={e.patient} />
            <Group>
              <Badge size="lg" variant="light">
                {STATUS_LABEL[e.status]}
              </Badge>
              <Text size="sm">
                Arrived {new Date(e.arrived_at).toLocaleString()}
                {e.clinician && ` · Clinician: ${e.clinician.display_name}`}
              </Text>
            </Group>
            <Actions encounter={e} />
            <Card withBorder>
              <Title order={2} size="h4" mb="xs">
                Visit history
              </Title>
              <Table aria-label="Visit history">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th scope="col">When</Table.Th>
                    <Table.Th scope="col">What</Table.Th>
                    <Table.Th scope="col">Department</Table.Th>
                    <Table.Th scope="col">By</Table.Th>
                    <Table.Th scope="col">Reason</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {events.map((ev) => (
                    <Table.Tr key={ev.id}>
                      <Table.Td>{new Date(ev.occurred_at).toLocaleString()}</Table.Td>
                      <Table.Td>{EVENT_LABEL[ev.event_type]}</Table.Td>
                      <Table.Td>{ev.department.name}</Table.Td>
                      <Table.Td>{ev.actor.display_name}</Table.Td>
                      <Table.Td>{ev.reason ?? ''}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Card>
            {hasPermission(me, Permission.OBSERVATION_READ) && (
              <TriageSection encounterId={e.id} open={e.status === 'WAITING' || e.status === 'IN_CONSULTATION'} />
            )}
          </Stack>
        )}
      </QueryState>
    </Stack>
  )
}

function Actions({ encounter: e }: { encounter: EncounterSummary }) {
  const me = useCurrentMe()
  const queryClient = useQueryClient()
  const [dialog, setDialog] = useState<Exclude<EncounterAction, 'call'> | null>(null)
  const [department, setDepartment] = useState<string | null>(null)
  const departments = useQuery({
    queryKey: ['encounter-departments', e.facility_id],
    queryFn: ({ signal }) => listDepartments(e.facility_id, signal),
    enabled: dialog === 'transfer',
  })
  const act = useMutation({
    mutationFn: ({ action, reason }: { action: EncounterAction; reason?: string }) =>
      encounterAction(e, action, {
        reason,
        department_id: action === 'transfer' ? (department ?? undefined) : undefined,
      }),
    onSuccess: (d) => {
      setDialog(null)
      setDepartment(null)
      queryClient.setQueryData(['encounter', e.id], d)
      void queryClient.invalidateQueries({ queryKey: ['encounters'] })
    },
  })

  const manage = hasPermission(me, Permission.ENCOUNTER_MANAGE)
  const open = e.status === 'WAITING' || e.status === 'IN_CONSULTATION'
  const buttons: { action: EncounterAction; label: string; show: boolean }[] = [
    { action: 'call', label: 'Call in', show: manage && e.status === 'WAITING' },
    { action: 'transfer', label: 'Transfer', show: manage && open },
    { action: 'close', label: 'Close visit', show: manage && e.status === 'IN_CONSULTATION' },
    {
      action: 'cancel',
      label: 'Cancel visit',
      show: hasPermission(me, Permission.ENCOUNTER_OPEN) && e.status === 'WAITING',
    },
    {
      action: 'reopen',
      label: 'Reopen',
      show: hasPermission(me, Permission.ENCOUNTER_REOPEN) && e.status === 'CLOSED',
    },
  ]
  const d = dialog ? DIALOG[dialog] : null

  return (
    <>
      {act.isError && <ErrorState error={act.error} />}
      <Group>
        {buttons
          .filter((b) => b.show)
          .map((b) => (
            <Button
              key={b.action}
              variant={b.action === 'call' ? 'filled' : 'default'}
              loading={act.isPending && b.action === 'call'}
              onClick={() => (b.action === 'call' ? act.mutate({ action: 'call' }) : setDialog(b.action))}
            >
              {b.label}
            </Button>
          ))}
      </Group>
      <ConfirmDialog
        opened={d !== null}
        title={d?.title ?? ''}
        confirmLabel={d?.label ?? ''}
        requireReason={d?.reason ?? false}
        danger={dialog === 'cancel'}
        busy={act.isPending}
        confirmDisabled={dialog === 'transfer' && !department}
        onConfirm={(reason) => dialog && act.mutate({ action: dialog, reason: reason ?? undefined })}
        onCancel={() => {
          setDialog(null)
          setDepartment(null)
        }}
      >
        <Stack gap="sm">
          <Text size="sm">{d?.text}</Text>
          {dialog === 'transfer' && (
            <Select
              label="To department"
              required
              data={(departments.data ?? [])
                .filter((x) => x.id !== e.department.id)
                .map((x) => ({ value: x.id, label: x.name }))}
              value={department}
              onChange={setDepartment}
              comboboxProps={{ withinPortal: false }}
            />
          )}
        </Stack>
      </ConfirmDialog>
    </>
  )
}
