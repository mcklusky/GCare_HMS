import {
  Alert,
  Anchor,
  Badge,
  Button,
  Card,
  Checkbox,
  Group,
  NumberInput,
  Radio,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import {
  closeEpisode,
  decideEntry,
  endEpisode,
  ENTRY_STATUS_LABEL,
  EPISODE_STATUS_LABEL,
  FORM_TYPES,
  formTypeLabel,
  getCandidates,
  getEntry,
  getEpisode,
  getFormOptions,
  keyEntry,
  listEntries,
  listVisitInvoices,
  localToIso,
  proposeEntry,
  type Entry,
  type EntryDecision,
  type EntryRequest,
  type Episode,
  type FormType,
  type ProposeRequest,
} from '../../api/downtime'
import { listEncounters } from '../../api/encounters'
import { Permission } from '../../api/types'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, PageTitle, QueryState } from '../../components/states'
import { formatDateTime, zoneNote } from '../../components/time'

const TRIAGE_FIELDS: [string, string][] = [
  ['temperature_c', 'Temperature (°C)'],
  ['pulse_bpm', 'Pulse (bpm)'],
  ['respiratory_rate', 'Respiratory rate (/min)'],
  ['systolic_mmhg', 'Systolic BP (mmHg)'],
  ['diastolic_mmhg', 'Diastolic BP (mmHg)'],
  ['spo2_pct', 'SpO₂ (%)'],
  ['weight_kg', 'Weight (kg)'],
  ['height_cm', 'Height (cm)'],
  ['pain_score', 'Pain (0–10)'],
  ['urgency', 'Urgency code'],
  ['allergy_note', 'Allergies'],
  ['notes', 'Notes'],
]
const TEXT_TRIAGE = new Set(['urgency', 'allergy_note', 'notes'])

/** One downtime episode: key paper forms and reconcile them (task 5.3; docs/22). */
export function EpisodePage() {
  const { id = '' } = useParams()
  const me = useCurrentMe()
  const [selected, setSelected] = useState<string | null>(null)
  const episode = useQuery({ queryKey: ['downtime-episode', id], queryFn: ({ signal }) => getEpisode(id, signal) })
  const entries = useQuery({ queryKey: ['downtime-entries', id], queryFn: ({ signal }) => listEntries(id, signal) })
  const canRecord = hasPermission(me, Permission.DOWNTIME_RECORD)

  return (
    <Stack>
      <Anchor component={Link} to="/downtime" size="sm">
        ← Downtime
      </Anchor>
      <QueryState query={episode} loadingLabel="Loading episode…">
        {(e) => (
          <Stack>
            <PageTitle>Downtime episode {e.episode_number}</PageTitle>
            <Group>
              <Badge size="lg" variant="light">
                {EPISODE_STATUS_LABEL[e.status]}
              </Badge>
              <Text size="sm">
                {formatDateTime(e.started_at)} – {e.ended_at ? formatDateTime(e.ended_at) : 'ongoing'} ·{' '}
                {e.affected_areas} · {e.reason}
              </Text>
            </Group>
            {e.closing_note && <Text size="sm">Closed: {e.closing_note}</Text>}
            {hasPermission(me, Permission.DOWNTIME_MANAGE) && <EpisodeActions episode={e} />}
            {canRecord && e.status !== 'CLOSED' && <KeyForm episode={e} />}
          </Stack>
        )}
      </QueryState>
      <QueryState query={entries} loadingLabel="Loading keyed forms…">
        {(list) =>
          list.length === 0 ? (
            <EmptyState title="No paper forms keyed yet" />
          ) : (
            <Card withBorder>
              <Title order={2} size="h4" mb="xs">
                Reconciliation queue
              </Title>
              <Table aria-label="Keyed paper forms">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th scope="col">Form</Table.Th>
                    <Table.Th scope="col">Type</Table.Th>
                    <Table.Th scope="col">Patient form</Table.Th>
                    <Table.Th scope="col">Paper time</Table.Th>
                    <Table.Th scope="col">Status</Table.Th>
                    <Table.Th scope="col">Keyed by</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {list.map((x) => (
                    <Table.Tr key={x.id}>
                      <Table.Td>
                        <Anchor component="button" type="button" onClick={() => setSelected(x.id)}>
                          {x.form_label}
                        </Anchor>
                      </Table.Td>
                      <Table.Td>{formTypeLabel(x.form_type)}</Table.Td>
                      <Table.Td>{x.registration_form_label ?? '—'}</Table.Td>
                      <Table.Td>{formatDateTime(x.occurred_at)}</Table.Td>
                      <Table.Td>{ENTRY_STATUS_LABEL[x.status]}</Table.Td>
                      <Table.Td>{x.entered_by.display_name}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Card>
          )
        }
      </QueryState>
      {selected && canRecord && <EntryPanel key={selected} id={selected} entries={entries.data ?? []} />}
    </Stack>
  )
}

function EpisodeActions({ episode }: { episode: Episode }) {
  const queryClient = useQueryClient()
  const [end, setEnd] = useState('')
  const [closing, setClosing] = useState(false)
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['downtime-episode', episode.id] })
    void queryClient.invalidateQueries({ queryKey: ['downtime-episodes'] })
  }
  const ending = useMutation({ mutationFn: () => endEpisode(episode, localToIso(end)), onSuccess: refresh })
  const close = useMutation({
    mutationFn: (note: string) => closeEpisode(episode, note),
    onSuccess: () => {
      setClosing(false)
      refresh()
    },
    onError: refresh,
  })
  if (episode.status === 'CLOSED') return null
  const open = episode.counts.PENDING + episode.counts.PROPOSED
  return (
    <Card withBorder>
      {episode.status === 'ACTIVE' ? (
        <Group align="end">
          <TextInput
            type="datetime-local"
            description={zoneNote()}
            label="System back at"
            value={end}
            onChange={(e) => setEnd(e.currentTarget.value)}
          />
          <Button onClick={() => ending.mutate()} disabled={!end} loading={ending.isPending}>
            Record end of downtime
          </Button>
        </Group>
      ) : (
        <Group>
          <Text size="sm">
            {open === 0
              ? 'Every keyed form is reconciled or voided.'
              : `${open} form(s) still wait for reconciliation.`}
          </Text>
          <Button variant="default" disabled={open > 0} onClick={() => (close.reset(), setClosing(true))}>
            Close episode
          </Button>
        </Group>
      )}
      {ending.isError && <ErrorState error={ending.error} />}
      <ConfirmDialog
        opened={closing}
        title="Close this downtime episode?"
        confirmLabel="Close episode"
        requireReason
        busy={close.isPending}
        error={close.error}
        onConfirm={(note) => close.mutate(note ?? '')}
        onCancel={() => setClosing(false)}
      >
        Confirm that all paper forms are accounted for and critical results and open tasks were followed up.
      </ConfirmDialog>
    </Card>
  )
}

// --- keying ------------------------------------------------------------------------------------

function KeyForm({ episode }: { episode: Episode }) {
  const queryClient = useQueryClient()
  const options = useQuery({
    queryKey: ['downtime-options', episode.facility_id],
    queryFn: ({ signal }) => getFormOptions(episode.facility_id, signal),
  })
  const [type, setType] = useState<FormType>('REGISTRATION')
  const [number, setNumber] = useState<number | string>('')
  const [registration, setRegistration] = useState<number | string>('')
  const [at, setAt] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [keyed, setKeyed] = useState<string | null>(null)
  const set = (k: string) => (v: string | null) => setFields((f) => ({ ...f, [k]: v ?? '' }))
  const text = (k: string) => (e: { currentTarget: { value: string } }) => set(k)(e.currentTarget.value)

  const save = useMutation({
    mutationFn: () => keyEntry(episode.id, request()),
    onSuccess: (x) => {
      setKeyed(x.form_label)
      setNumber('')
      setFields({})
      void queryClient.invalidateQueries({ queryKey: ['downtime-entries', episode.id] })
      void queryClient.invalidateQueries({ queryKey: ['downtime-episode', episode.id] })
    },
  })

  const opt = (k: string) => (fields[k]?.trim() ? fields[k].trim() : null)
  function request(): EntryRequest {
    const base: EntryRequest = {
      form_type: type,
      form_number: Number(number),
      occurred_at: localToIso(at),
      registration_form_number: type === 'REGISTRATION' ? undefined : Number(registration),
    }
    switch (type) {
      case 'REGISTRATION':
        return {
          ...base,
          registration: {
            family_name: opt('family_name'),
            given_names: opt('given_names'),
            birth_date: opt('birth_date'),
            birth_date_estimated: fields.birth_date_estimated === 'true',
            sex: (opt('sex') ?? 'UNKNOWN') as never,
            phone: opt('phone'),
            written_patient_number: opt('written_patient_number'),
            registration_source: (opt('registration_source') ?? 'WALK_IN') as never,
            department_id: fields.department_id,
            encounter_type: (opt('encounter_type') ?? 'NEW_VISIT') as never,
          },
        }
      case 'TRIAGE':
        return {
          ...base,
          triage: Object.fromEntries(
            TRIAGE_FIELDS.map(([k]) => [k, opt(k)]).filter(([, v]) => v !== null) as [string, string][],
          ),
        }
      case 'ORDER':
        return {
          ...base,
          order: {
            catalog_item_id: fields.catalog_item_id,
            priority: (opt('priority') ?? 'ROUTINE') as never,
            indication: opt('indication'),
            ordered_by_name: fields.ordered_by_name?.trim() ?? '',
          },
        }
      case 'PAYMENT':
        return {
          ...base,
          payment: {
            method_id: fields.method_id,
            amount: fields.amount?.trim() ?? '',
            currency: (fields.currency ?? '').trim().toUpperCase(),
          },
        }
    }
  }

  const required: Record<FormType, string[]> = {
    REGISTRATION: ['department_id'],
    TRIAGE: [],
    ORDER: ['catalog_item_id', 'ordered_by_name'],
    PAYMENT: ['method_id', 'amount', 'currency'],
  }
  const ready =
    Number(number) > 0 &&
    !!at &&
    (type === 'REGISTRATION' || Number(registration) > 0) &&
    required[type].every((k) => fields[k]?.trim())

  const submit = (ev: FormEvent) => {
    ev.preventDefault()
    save.mutate()
  }
  const o = options.data
  return (
    <Card withBorder component="form" aria-label="Key a paper form" onSubmit={submit}>
      <Title order={2} size="h4" mb="xs">
        Key a paper form
      </Title>
      <Text size="sm" mb="sm">
        Type exactly what is written. Keying the same form twice is safe; a different keying of a form number already
        keyed is refused.
      </Text>
      <Stack gap="sm">
        <Group grow align="end">
          <Select
            label="Form"
            data={FORM_TYPES}
            value={type}
            allowDeselect={false}
            onChange={(v) => {
              if (v) setType(v as FormType)
              setFields({})
            }}
          />
          <NumberInput label="Form number" required min={1} value={number} onChange={setNumber} />
          {type !== 'REGISTRATION' && (
            <NumberInput
              label="Registration form number"
              required
              min={1}
              value={registration}
              onChange={setRegistration}
            />
          )}
          <TextInput
            type="datetime-local"
            description={zoneNote()}
            label="Time on the paper"
            required
            value={at}
            onChange={(e) => setAt(e.currentTarget.value)}
          />
        </Group>
        {type === 'REGISTRATION' && (
          <>
            <Group grow>
              <TextInput label="Family name" value={fields.family_name ?? ''} onChange={text('family_name')} />
              <TextInput label="Given names" value={fields.given_names ?? ''} onChange={text('given_names')} />
            </Group>
            <Group grow align="end">
              <TextInput
                type="date"
                label="Date of birth"
                value={fields.birth_date ?? ''}
                onChange={text('birth_date')}
              />
              <Checkbox
                label="Estimated"
                checked={fields.birth_date_estimated === 'true'}
                onChange={(e) => set('birth_date_estimated')(String(e.currentTarget.checked))}
              />
              <Select
                label="Sex"
                data={['FEMALE', 'MALE', 'OTHER', 'UNKNOWN']}
                value={fields.sex ?? null}
                onChange={set('sex')}
              />
            </Group>
            <Group grow>
              <TextInput label="Phone" value={fields.phone ?? ''} onChange={text('phone')} />
              <TextInput
                label="Existing patient number"
                value={fields.written_patient_number ?? ''}
                onChange={text('written_patient_number')}
              />
            </Group>
            <Group grow>
              <Select
                label="Arrived"
                data={[
                  { value: 'WALK_IN', label: 'Walk-in' },
                  { value: 'REFERRAL', label: 'Referral' },
                  { value: 'EMERGENCY', label: 'Emergency' },
                  { value: 'OTHER', label: 'Other' },
                ]}
                value={fields.registration_source ?? 'WALK_IN'}
                onChange={set('registration_source')}
              />
              <Select
                label="Department"
                required
                data={(o?.departments ?? []).map((d) => ({ value: d.id, label: d.name }))}
                value={fields.department_id ?? null}
                onChange={set('department_id')}
              />
              <Select
                label="Visit type"
                data={[
                  { value: 'NEW_VISIT', label: 'New visit' },
                  { value: 'FOLLOW_UP', label: 'Follow-up' },
                ]}
                value={fields.encounter_type ?? 'NEW_VISIT'}
                onChange={set('encounter_type')}
              />
            </Group>
          </>
        )}
        {type === 'TRIAGE' && (
          <Group grow wrap="wrap">
            {TRIAGE_FIELDS.map(([k, label]) => (
              <TextInput
                key={k}
                label={label}
                inputMode={TEXT_TRIAGE.has(k) ? undefined : 'decimal'}
                value={fields[k] ?? ''}
                onChange={text(k)}
                miw={160}
              />
            ))}
          </Group>
        )}
        {type === 'ORDER' && (
          <Group grow>
            <Select
              label="Item ordered"
              required
              searchable
              data={(o?.catalog_items ?? []).map((i) => ({ value: i.id, label: `${i.name} (${i.code})` }))}
              value={fields.catalog_item_id ?? null}
              onChange={set('catalog_item_id')}
            />
            <Select
              label="Priority"
              data={[
                { value: 'ROUTINE', label: 'Routine' },
                { value: 'URGENT', label: 'Urgent' },
              ]}
              value={fields.priority ?? 'ROUTINE'}
              onChange={set('priority')}
            />
            <TextInput label="Indication" value={fields.indication ?? ''} onChange={text('indication')} />
            <TextInput
              label="Ordered by (as written)"
              required
              value={fields.ordered_by_name ?? ''}
              onChange={text('ordered_by_name')}
            />
          </Group>
        )}
        {type === 'PAYMENT' && (
          <Group grow>
            <Select
              label="Method"
              required
              data={(o?.payment_methods ?? []).map((m) => ({ value: m.id, label: m.name }))}
              value={fields.method_id ?? null}
              onChange={set('method_id')}
            />
            <TextInput
              label="Amount"
              required
              inputMode="decimal"
              value={fields.amount ?? ''}
              onChange={text('amount')}
            />
            <TextInput
              label="Currency (ISO code)"
              required
              maxLength={3}
              value={fields.currency ?? ''}
              onChange={text('currency')}
            />
          </Group>
        )}
        {save.isError && <ErrorState error={save.error} />}
        {keyed && !save.isError && (
          <Alert color="green" title="Keyed">
            {keyed} is in the queue.
          </Alert>
        )}
        <Group>
          <Button type="submit" disabled={!ready} loading={save.isPending}>
            Key form
          </Button>
        </Group>
      </Stack>
    </Card>
  )
}

// --- one entry ---------------------------------------------------------------------------------

function EntryPanel({ id, entries }: { id: string; entries: Entry[] }) {
  const me = useCurrentMe()
  const queryClient = useQueryClient()
  const [deciding, setDeciding] = useState<EntryDecision | null>(null)
  const entry = useQuery({ queryKey: ['downtime-entry', id], queryFn: ({ signal }) => getEntry(id, signal) })
  const refresh = (x: Entry) => {
    void queryClient.invalidateQueries({ queryKey: ['downtime-entry', id] })
    void queryClient.invalidateQueries({ queryKey: ['downtime-entries', x.episode_id] })
    void queryClient.invalidateQueries({ queryKey: ['downtime-episode', x.episode_id] })
  }
  const decide = useMutation({
    mutationFn: ({ x, d, note }: { x: Entry; d: EntryDecision; note: string }) => decideEntry(x, d, note),
    onSuccess: (x) => {
      setDeciding(null)
      refresh(x)
    },
    onError: (_e, v) => refresh(v.x),
  })
  const TITLES: Record<EntryDecision, string> = {
    approve: 'Approve this proposal?',
    'send-back': 'Send this proposal back?',
    reconcile: 'Record these observations on the visit?',
    void: 'Void this keyed form?',
  }

  return (
    <QueryState query={entry} loadingLabel="Loading form…">
      {(x) => (
        <Card withBorder>
          <Stack gap="sm">
            <Title order={2} size="h4">
              {x.form_label} · {formTypeLabel(x.form_type)} · {ENTRY_STATUS_LABEL[x.status]}
            </Title>
            <Text size="sm">
              Paper time {formatDateTime(x.occurred_at)}
              {x.registration_form_label && ` · patient's registration form ${x.registration_form_label}`}
            </Text>
            {x.data === null && (
              <Text size="sm" c="dimmed">
                The keyed contents are shown only to staff who may see this kind of record.
              </Text>
            )}
            <Table aria-label={`Keyed values of ${x.form_label}`}>
              <Table.Tbody>
                {Object.entries(x.data ?? {})
                  .filter(([, v]) => v !== null && v !== '' && v !== false)
                  .map(([k, v]) => (
                    <Table.Tr key={k}>
                      <Table.Th scope="row">{k.replaceAll('_', ' ')}</Table.Th>
                      <Table.Td>{String(v)}</Table.Td>
                    </Table.Tr>
                  ))}
              </Table.Tbody>
            </Table>
            {x.status === 'PROPOSED' && x.proposal && (
              <Text size="sm">
                Proposed by {x.proposed_by?.display_name}: {describeProposal(x)} — “{x.proposal_note}”
              </Text>
            )}
            {x.status === 'RECONCILED' && (
              <Group gap="md">
                {x.patient_id && hasPermission(me, Permission.PATIENT_READ) && (
                  <Anchor component={Link} to={`/patients/${x.patient_id}`} size="sm">
                    Patient
                  </Anchor>
                )}
                {x.encounter_id && hasPermission(me, Permission.ENCOUNTER_READ) && (
                  <Anchor component={Link} to={`/encounters/${x.encounter_id}`} size="sm">
                    Visit
                  </Anchor>
                )}
              </Group>
            )}
            {x.status === 'PENDING' && !x.needs_second_person && (
              <Group>
                <Button onClick={() => setDeciding('reconcile')}>Reconcile</Button>
                <Button variant="default" color="red" onClick={() => setDeciding('void')}>
                  Void
                </Button>
              </Group>
            )}
            {x.status === 'PENDING' && x.needs_second_person && (
              <ProposeForm key={`${x.id}-${x.version}`} entry={x} entries={entries} onDone={() => refresh(x)} />
            )}
            {x.status === 'PROPOSED' && (
              <Group>
                {x.proposed_by?.id === me.id ? (
                  <Text size="sm">Your proposal: another person approves.</Text>
                ) : (
                  <Button onClick={() => setDeciding('approve')}>Approve</Button>
                )}
                <Button variant="default" onClick={() => setDeciding('send-back')}>
                  Send back
                </Button>
              </Group>
            )}
            <Title order={3} size="h5">
              History
            </Title>
            <Table aria-label={`History of ${x.form_label}`}>
              <Table.Tbody>
                {(x.events ?? []).map((ev, i) => (
                  <Table.Tr key={i}>
                    <Table.Td>{formatDateTime(ev.occurred_at)}</Table.Td>
                    <Table.Td>{ev.event_type.replaceAll('_', ' ').toLowerCase()}</Table.Td>
                    <Table.Td>{ev.by.display_name}</Table.Td>
                    <Table.Td>{ev.note ?? ''}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Stack>
          <ConfirmDialog
            opened={deciding !== null}
            title={deciding ? TITLES[deciding] : ''}
            confirmLabel={deciding === 'send-back' ? 'Send back' : deciding === 'void' ? 'Void' : 'Confirm'}
            danger={deciding === 'void'}
            requireReason
            busy={decide.isPending}
            error={decide.error}
            onConfirm={(note) => deciding && decide.mutate({ x, d: deciding, note: note ?? '' })}
            onCancel={() => setDeciding(null)}
          >
            {deciding === 'approve'
              ? 'This creates the records through the normal screens’ rules. Check the proposal against the paper form.'
              : `${x.form_label}: add a note.`}
          </ConfirmDialog>
        </Card>
      )}
    </QueryState>
  )
}

/** What the approver checks against the paper form: the chosen patient, visit and invoice by name, not by id. */
function describeProposal(x: Entry): string {
  const p = x.proposal ?? {}
  const label = (k: string) => (typeof p[k] === 'string' ? (p[k] as string) : null)
  if (p.action === 'VOID') return 'void this form'
  if (x.form_type === 'REGISTRATION')
    return p.patient_id
      ? `match to existing patient ${label('patient_label') ?? '(details not available)'}` +
          (p.encounter_id
            ? ` and their open visit ${label('encounter_label') ?? ''}`.trimEnd()
            : ' and open a new visit')
      : 'register a new patient and open a visit'
  if (x.form_type === 'PAYMENT')
    return `record the payment on invoice ${label('invoice_label') ?? '(details not available)'}`
  return 'create the order on the patient’s visit'
}

function ProposeForm({ entry, entries, onDone }: { entry: Entry; entries: Entry[]; onDone: () => void }) {
  const me = useCurrentMe()
  const [note, setNote] = useState('')
  const [choice, setChoice] = useState<string>('')
  const [reviewed, setReviewed] = useState(false)
  const [reason, setReason] = useState('')
  const [visit, setVisit] = useState<string | null>(null)
  const [invoice, setInvoice] = useState<string | null>(null)
  const isReg = entry.form_type === 'REGISTRATION'
  const canMatch = hasPermission(me, Permission.PATIENT_READ)
  const candidates = useQuery({
    queryKey: ['downtime-candidates', entry.id],
    queryFn: ({ signal }) => getCandidates(entry.id, signal),
    enabled: isReg && canMatch,
  })
  const openVisits = useQuery({
    queryKey: ['downtime-open-visits', choice],
    queryFn: ({ signal }) =>
      listEncounters(
        { facilityId: entry.facility_id, patientId: choice, statuses: ['WAITING', 'IN_CONSULTATION'] },
        signal,
      ),
    enabled: isReg && !!choice && choice !== 'new' && hasPermission(me, Permission.ENCOUNTER_READ),
  })
  const registration = entries.find(
    (e) =>
      e.form_type === 'REGISTRATION' && e.form_number === entry.registration_form_number && e.status === 'RECONCILED',
  )
  const invoices = useQuery({
    queryKey: ['downtime-invoices', registration?.encounter_id],
    queryFn: ({ signal }) => listVisitInvoices(registration!.encounter_id!, signal),
    enabled: entry.form_type === 'PAYMENT' && !!registration?.encounter_id,
  })
  const propose = useMutation({
    mutationFn: (body: ProposeRequest) => proposeEntry(entry, body),
    onSuccess: onDone,
  })

  const c = candidates.data
  const list = c
    ? [...(c.written_number_match ? [c.written_number_match] : []), ...c.candidates.map((d) => d.patient)]
    : []
  const unique = list.filter((p, i) => list.findIndex((q) => q.id === p.id) === i)
  const likely = (c?.candidates ?? []).some((d) => d.band === 'LIKELY')
  const apply = (): ProposeRequest => {
    const body: ProposeRequest = { action: 'APPLY', note: note.trim() }
    if (isReg && choice === 'new') {
      body.reviewed_candidate_ids = (c?.candidates ?? []).map((d) => d.patient.id)
      if (reason.trim()) body.duplicate_reason = reason.trim()
    } else if (isReg) {
      body.patient_id = choice
      if (visit) body.encounter_id = visit
    }
    if (entry.form_type === 'PAYMENT' && invoice) body.invoice_id = invoice
    return body
  }
  const applyReady =
    !!note.trim() &&
    (!isReg ||
      (choice !== '' &&
        (choice !== 'new' || (c?.candidates.length ?? 0) === 0 || (reviewed && (!likely || !!reason.trim()))))) &&
    (entry.form_type !== 'PAYMENT' || !!invoice)

  return (
    <Stack gap="sm" component="section" aria-label={`Propose reconciliation of ${entry.form_label}`}>
      <Text size="sm" fw={600}>
        Propose how to reconcile this form. Another person approves it.
      </Text>
      {isReg && !canMatch && <Text size="sm">Matching patients needs patient access (registration staff).</Text>}
      {isReg && canMatch && (
        <QueryState query={candidates} loadingLabel="Looking for existing records…">
          {() => (
            <Radio.Group
              label="Patient"
              value={choice}
              onChange={(v) => {
                setChoice(v)
                setVisit(null)
              }}
            >
              <Stack gap={4} mt={4}>
                {unique.map((p) => (
                  <Radio
                    key={p.id}
                    value={p.id}
                    label={`${p.patient_number} · ${[p.family_name, p.given_names].filter(Boolean).join(', ')} · ${p.birth_date ?? 'no birth date'}${c?.written_number_match?.id === p.id ? ' (number written on the form)' : ''}`}
                  />
                ))}
                <Radio value="new" label="None of these: register a new patient" />
              </Stack>
            </Radio.Group>
          )}
        </QueryState>
      )}
      {isReg && choice === 'new' && (c?.candidates.length ?? 0) > 0 && (
        <>
          <Checkbox
            label={`I reviewed all ${c?.candidates.length} possible existing records above`}
            checked={reviewed}
            onChange={(e) => setReviewed(e.currentTarget.checked)}
          />
          {likely && (
            <TextInput
              label="Why this is a different person"
              required
              maxLength={500}
              value={reason}
              onChange={(e) => setReason(e.currentTarget.value)}
            />
          )}
        </>
      )}
      {isReg && choice !== '' && choice !== 'new' && (openVisits.data?.items.length ?? 0) > 0 && (
        <Select
          label="Visit"
          data={[
            ...(openVisits.data?.items ?? []).map((v) => ({
              value: v.id,
              label: `Use open visit: ${v.department.name}, arrived ${formatDateTime(v.arrived_at)}`,
            })),
          ]}
          placeholder="Open a new visit"
          clearable
          value={visit}
          onChange={setVisit}
        />
      )}
      {entry.form_type === 'PAYMENT' &&
        (!registration ? (
          <Text size="sm">Reconcile registration form {entry.registration_form_label} first.</Text>
        ) : (
          <QueryState query={invoices} loadingLabel="Loading invoices…">
            {(list) => {
              const issued = list.filter((i) => i.status === 'ISSUED')
              return issued.length === 0 ? (
                <Text size="sm">The visit has no issued invoice yet. A cashier issues it first.</Text>
              ) : (
                <Select
                  label="Invoice"
                  required
                  data={issued.map((i) => ({ value: i.id, label: `${i.invoice_number} (${i.currency})` }))}
                  value={invoice}
                  onChange={setInvoice}
                />
              )
            }}
          </QueryState>
        ))}
      <TextInput
        label="Note for the approver"
        required
        maxLength={500}
        value={note}
        onChange={(e) => setNote(e.currentTarget.value)}
      />
      {propose.isError && <ErrorState error={propose.error} />}
      <Group>
        <Button onClick={() => propose.mutate(apply())} disabled={!applyReady} loading={propose.isPending}>
          Propose
        </Button>
        <Button
          variant="default"
          color="red"
          disabled={!note.trim()}
          onClick={() => propose.mutate({ action: 'VOID', note: note.trim() })}
        >
          Propose to void
        </Button>
      </Group>
    </Stack>
  )
}
