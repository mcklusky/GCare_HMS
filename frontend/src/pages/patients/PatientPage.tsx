import {
  Alert,
  Anchor,
  Button,
  Card,
  Checkbox,
  Group,
  Modal,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core'
import { IconCircleCheck, IconEdit } from '@tabler/icons-react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { ApiRequestError } from '../../api/client'
import {
  EVIDENCE_LABEL,
  FIELD_LABEL,
  IDENTIFIER_LABEL,
  SEX_LABEL,
  SOURCE_LABEL,
  birthLabel,
  correctPatient,
  displayName,
  getHistory,
  getPatient,
  getPossibleDuplicates,
  reportDuplicate,
  type DuplicateCandidate,
  type PatientDetail,
  type Sex,
} from '../../api/patients'
import { Permission } from '../../api/types'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { BandBadge, PatientBanner } from '../../components/PatientBadges'
import { EmptyState, ErrorState, PageTitle, QueryState } from '../../components/states'
import { StartVisitDialog } from '../encounters/StartVisitDialog'
import { PatientChart } from './PatientChart'
import { LinkHistory, LinkedNotice } from './PatientLinks'

/** One patient's record (FR-003, FR-004). Viewing is audited by the backend. */
export function PatientPage() {
  const { patientId = '' } = useParams()
  const location = useLocation()
  const registeredNumber = (location.state as { registeredNumber?: string } | null)?.registeredNumber
  const me = useCurrentMe()
  const [correcting, setCorrecting] = useState(false)
  const [startingVisit, setStartingVisit] = useState(false)

  const patient = useQuery({
    queryKey: ['patient', patientId],
    queryFn: ({ signal }) => getPatient(patientId, signal),
  })

  return (
    <Stack>
      <Anchor component={Link} to="/patients" size="sm">
        ← Find a patient
      </Anchor>
      <QueryState query={patient} loadingLabel="Loading patient…">
        {(p) => (
          <Stack>
            <PageTitle>{displayName(p)}</PageTitle>
            {registeredNumber && (
              <Alert color="teal" variant="light" icon={<IconCircleCheck aria-hidden />} role="status" title="Registered">
                Patient number <strong>{registeredNumber}</strong>. Write it on the patient’s card.
              </Alert>
            )}
            <PatientBanner patient={p} />
            <LinkedNotice patient={p} />
            <Group>
              {hasPermission(me, Permission.PATIENT_UPDATE) && p.status === 'ACTIVE' && (
                <Button variant="default" leftSection={<IconEdit size={16} aria-hidden />} onClick={() => setCorrecting(true)}>
                  Correct details
                </Button>
              )}
              {hasPermission(me, Permission.ENCOUNTER_OPEN) && p.status === 'ACTIVE' && (
                <Button variant="default" onClick={() => setStartingVisit(true)}>
                  Start visit
                </Button>
              )}
            </Group>
            <Details patient={p} />
            {hasPermission(me, Permission.ENCOUNTER_READ) && <PatientChart patientId={p.id} />}
            <LinkHistory patientId={p.id} />
            <History patientId={p.id} />
            {p.status === 'ACTIVE' && <PossibleDuplicates patientId={p.id} />}
            {correcting && <CorrectionDialog patient={p} onClose={() => setCorrecting(false)} />}
            {startingVisit && <StartVisitDialog patientId={p.id} onClose={() => setStartingVisit(false)} />}
          </Stack>
        )}
      </QueryState>
    </Stack>
  )
}

function Details({ patient: p }: { patient: PatientDetail }) {
  return (
    <SimpleGrid cols={{ base: 1, md: 2 }}>
      <Card withBorder>
        <Title order={2} size="h4" mb="xs">
          Details
        </Title>
        <Text>Phone: {p.phone ?? 'Not recorded'}</Text>
        <Text>How the patient arrived: {SOURCE_LABEL[p.registration_source]}</Text>
        <Text>Registered: {new Date(p.created_at).toLocaleString()}</Text>
      </Card>
      <Card withBorder>
        <Title order={2} size="h4" mb="xs">
          ID documents
        </Title>
        {p.identifiers.length === 0 ? (
          <Text>None recorded.</Text>
        ) : (
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th scope="col">Type</Table.Th>
                <Table.Th scope="col">Number</Table.Th>
                <Table.Th scope="col">Checked</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {p.identifiers.map((i) => (
                <Table.Tr key={i.id}>
                  <Table.Td>{IDENTIFIER_LABEL[i.type]}</Table.Td>
                  <Table.Td>{i.value}</Table.Td>
                  <Table.Td>{i.verified ? 'Yes, document seen' : 'Not checked'}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Card>
    </SimpleGrid>
  )
}

function formatValue(field: string, value: unknown): string {
  if (value === null || value === undefined || value === '') return '(empty)'
  if (field === 'sex') return SEX_LABEL[value as Sex] ?? String(value)
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  return String(value)
}

function History({ patientId }: { patientId: string }) {
  const history = useQuery({
    queryKey: ['patient', patientId, 'history'],
    queryFn: ({ signal }) => getHistory(patientId, signal),
  })
  return (
    <Card withBorder component="section" aria-labelledby="history-title">
      <Title order={2} size="h4" mb="xs" id="history-title">
        Correction history
      </Title>
      <QueryState
        query={history}
        isEmpty={(list) => list.length === 0}
        empty={<Text>No corrections. These are the details as registered.</Text>}
      >
        {(list) => (
          <Stack gap="sm">
            {list.map((c) => (
              <Stack key={c.id} gap={2}>
                <Text size="sm" fw={600}>
                  {new Date(c.changed_at).toLocaleString()}. Reason: {c.reason}
                </Text>
                {Object.entries(c.changes).map(([field, change]) => (
                  <Text size="sm" key={field}>
                    {FIELD_LABEL[field] ?? field}: {formatValue(field, change.from)} → {formatValue(field, change.to)}
                  </Text>
                ))}
              </Stack>
            ))}
          </Stack>
        )}
      </QueryState>
    </Card>
  )
}

function PossibleDuplicates({ patientId }: { patientId: string }) {
  const queryClient = useQueryClient()
  const [reporting, setReporting] = useState<DuplicateCandidate | null>(null)
  const [reported, setReported] = useState<string[]>([])
  const duplicates = useQuery({
    queryKey: ['patient', patientId, 'duplicates'],
    queryFn: ({ signal }) => getPossibleDuplicates(patientId, signal),
  })
  const report = useMutation({
    mutationFn: ({ candidateId, reason }: { candidateId: string; reason: string }) =>
      reportDuplicate(patientId, candidateId, reason),
    onSuccess: (_, { candidateId }) => {
      setReported((r) => [...r, candidateId])
      setReporting(null)
      void queryClient.invalidateQueries({ queryKey: ['duplicate-flags'] })
    },
  })

  return (
    <Card withBorder component="section" aria-labelledby="duplicates-title">
      <Title order={2} size="h4" mb="xs" id="duplicates-title">
        Possible duplicate records
      </Title>
      <QueryState
        query={duplicates}
        isEmpty={(list) => list.length === 0}
        empty={<Text>No other record looks like this patient.</Text>}
      >
        {(list) => (
          <Stack gap="sm">
            {list.map((c) => (
              <Group key={c.patient.id} justify="space-between" align="flex-start">
                <Stack gap={2}>
                  <Group gap="xs">
                    <Anchor component={Link} to={`/patients/${c.patient.id}`}>
                      {displayName(c.patient)}
                    </Anchor>
                    <BandBadge band={c.band} />
                  </Group>
                  <Text size="sm">
                    {c.patient.patient_number} · Born {birthLabel(c.patient)} ·{' '}
                    {c.evidence.map((e) => EVIDENCE_LABEL[e]).join(', ')}
                  </Text>
                </Stack>
                {reported.includes(c.patient.id) ? (
                  <Text size="sm">Sent for review</Text>
                ) : (
                  <Button size="xs" variant="default" onClick={() => setReporting(c)}>
                    Report as same person
                  </Button>
                )}
              </Group>
            ))}
            {report.isError && <ErrorState error={report.error} />}
          </Stack>
        )}
      </QueryState>
      <ConfirmDialog
        opened={reporting !== null}
        title="Send for duplicate review?"
        confirmLabel="Send for review"
        requireReason
        busy={report.isPending}
        onConfirm={(reason) => reporting && report.mutate({ candidateId: reporting.patient.id, reason: reason ?? '' })}
        onCancel={() => setReporting(null)}
      >
        A supervisor will compare the two records. Nothing is changed until they decide.
      </ConfirmDialog>
    </Card>
  )
}

/** Correction form: only changed fields are sent, with the version that was shown and a reason. */
function CorrectionDialog({ patient: p, onClose }: { patient: PatientDetail; onClose: () => void }) {
  const queryClient = useQueryClient()
  const initial = {
    family_name: p.family_name ?? '',
    given_names: p.given_names ?? '',
    birth_date: p.birth_date ?? '',
    birth_date_estimated: p.birth_date_estimated,
    sex: p.sex,
    phone: p.phone ?? '',
  }
  const [values, setValues] = useState(initial)
  const [reason, setReason] = useState('')

  const changes: Record<string, unknown> = {}
  for (const key of Object.keys(initial) as (keyof typeof initial)[]) {
    if (values[key] !== initial[key]) changes[key] = values[key] === '' ? null : values[key]
  }
  const changed = Object.keys(changes).length > 0

  const save = useMutation({
    mutationFn: () => correctPatient(p.id, p.version, reason.trim(), changes),
    onSuccess: (updated) => {
      queryClient.setQueryData(['patient', p.id], updated)
      void queryClient.invalidateQueries({ queryKey: ['patient', p.id] })
      onClose()
    },
  })
  const stale = save.error instanceof ApiRequestError && save.error.status === 409

  return (
    <Modal opened onClose={onClose} title="Correct patient details" size="lg" trapFocus returnFocus>
      <Stack>
        <Text size="sm">The previous values, your name, the time and your reason are kept in the history.</Text>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <TextInput
            label="Family name"
            value={values.family_name}
            onChange={(e) => setValues({ ...values, family_name: e.currentTarget.value })}
            data-autofocus
          />
          <TextInput
            label="Given names"
            value={values.given_names}
            onChange={(e) => setValues({ ...values, given_names: e.currentTarget.value })}
          />
          <TextInput
            label="Date of birth"
            type="date"
            value={values.birth_date}
            onChange={(e) => setValues({ ...values, birth_date: e.currentTarget.value })}
          />
          <Checkbox
            mt={{ sm: 'xl' }}
            label="Date of birth is estimated"
            checked={values.birth_date_estimated}
            onChange={(e) => setValues({ ...values, birth_date_estimated: e.currentTarget.checked })}
          />
          <Select
            label="Sex"
            data={Object.entries(SEX_LABEL).map(([value, label]) => ({ value, label }))}
            value={values.sex}
            onChange={(v) => v && setValues({ ...values, sex: v as Sex })}
            allowDeselect={false}
          />
          <TextInput
            label="Phone"
            type="tel"
            value={values.phone}
            onChange={(e) => setValues({ ...values, phone: e.currentTarget.value })}
          />
        </SimpleGrid>
        <Textarea
          label="Reason for the correction"
          description="For example: spelling checked against the ID card."
          required
          autosize
          minRows={2}
          value={reason}
          onChange={(e) => setReason(e.currentTarget.value)}
        />
        {stale && (
          <Alert color="orange" role="alert" title="Changed by someone else">
            Someone else changed this patient while you were editing. Close this form, check the new details, and
            correct again if still needed.
          </Alert>
        )}
        {save.isError && !stale && <ErrorState error={save.error} />}
        {!changed && <EmptyState title="No detail changed yet" />}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={() => save.mutate()} disabled={!changed || reason.trim() === '' || stale} loading={save.isPending}>
            Save correction
          </Button>
        </Group>
      </Stack>
    </Modal>
  )
}
