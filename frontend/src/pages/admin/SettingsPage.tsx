import {
  ActionIcon,
  Badge,
  Button,
  Card,
  FileInput,
  Group,
  Image,
  NumberInput,
  Stack,
  Table,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core'
import { IconTrash } from '@tabler/icons-react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  MEASURES,
  STATUS_LABEL,
  decideSetting,
  listPendingSettings,
  listSettings,
  listVersions,
  logoUrl,
  proposeSetting,
  uploadLogo,
  type SettingInfo,
  type SettingVersion,
  type TriageValue,
} from '../../api/settings'
import { useFacilityContext } from '../../auth/facilityContextValue'
import { useCurrentMe } from '../../auth/MeContext'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, PageHeader, QueryState } from '../../components/states'

type Json = Record<string, unknown>

/**
 * Settings (task 5.2): every change is a new version proposed by one person and approved by
 * another; earlier versions stay on record and can be proposed again. Nothing has a built-in value.
 */
export function SettingsPage() {
  const [selected, setSelected] = useState<SettingInfo | null>(null)
  const settings = useQuery({ queryKey: ['settings'], queryFn: ({ signal }) => listSettings(signal) })

  return (
    <Stack>
      <PageHeader
        title="Settings"
        description="A change takes effect only after a different person approves it. Every version is kept; to roll back, propose an earlier version again."
      />
      <PendingApprovals />
      <QueryState query={settings} loadingLabel="Loading settings…">
        {(rows) => (
          <Group align="stretch">
            {rows.map((s) => (
              <Card key={s.key} withBorder w={300}>
                <Title order={2} size="h5">
                  {s.title}
                </Title>
                <Text size="xs" c="dimmed" my="xs">
                  {s.description}
                </Text>
                <Button
                  size="xs"
                  variant={selected?.key === s.key ? 'filled' : 'default'}
                  onClick={() => setSelected(s)}
                  aria-label={`Open ${s.title}`}
                >
                  Open
                </Button>
              </Card>
            ))}
          </Group>
        )}
      </QueryState>
      {selected && <SettingPanel key={selected.key} setting={selected} />}
    </Stack>
  )
}

function PendingApprovals() {
  const me = useCurrentMe()
  const queryClient = useQueryClient()
  const [deciding, setDeciding] = useState<{ v: SettingVersion; decision: 'approve' | 'reject' } | null>(null)
  const pending = useQuery({ queryKey: ['settings-pending'], queryFn: ({ signal }) => listPendingSettings(signal) })
  const act = useMutation({
    mutationFn: ({ v, decision, note }: { v: SettingVersion; decision: 'approve' | 'reject'; note: string }) =>
      decideSetting(v, decision, note),
    onSuccess: () => {
      setDeciding(null)
      void queryClient.invalidateQueries({ queryKey: ['settings-pending'] })
      void queryClient.invalidateQueries({ queryKey: ['setting-versions'] })
      void queryClient.invalidateQueries({ queryKey: ['branding'] })
    },
  })
  if (!pending.data || pending.data.length === 0) return null
  return (
    <Card withBorder>
      <Title order={2} size="h4" mb="xs">
        Waiting for approval
      </Title>
      {act.isError && <ErrorState error={act.error} />}
      <Stack>
        {pending.data.map((v) => (
          <Card key={v.id} withBorder>
            <Group justify="space-between">
              <Text fw={600}>
                {v.key} version {v.version_number}
              </Text>
              {v.proposed_by.id === me.id ? (
                <Text size="xs">Your proposal: another person decides.</Text>
              ) : (
                <Group gap="xs">
                  <Button size="xs" onClick={() => setDeciding({ v, decision: 'approve' })}>
                    Approve
                  </Button>
                  <Button size="xs" variant="default" onClick={() => setDeciding({ v, decision: 'reject' })}>
                    Reject
                  </Button>
                </Group>
              )}
            </Group>
            <Text size="xs" c="dimmed">
              Proposed by {v.proposed_by.display_name}: {v.reason}
            </Text>
            <ValueSummary settingKey={v.key} value={v.value} />
          </Card>
        ))}
      </Stack>
      <ConfirmDialog
        opened={deciding !== null}
        title={deciding?.decision === 'approve' ? 'Approve this change?' : 'Reject this change?'}
        confirmLabel={deciding?.decision === 'approve' ? 'Approve' : 'Reject'}
        danger={deciding?.decision === 'reject'}
        requireReason
        busy={act.isPending}
        onConfirm={(note) => deciding && act.mutate({ ...deciding, note: note ?? '' })}
        onCancel={() => setDeciding(null)}
      >
        {deciding?.decision === 'approve'
          ? 'It takes effect at once and replaces the version in use.'
          : 'The proposal stays on record as rejected.'}
      </ConfirmDialog>
    </Card>
  )
}

function SettingPanel({ setting }: { setting: SettingInfo }) {
  const { current: facility } = useFacilityContext()
  const facilityId = setting.scope === 'FACILITY' ? (facility?.id ?? null) : null
  const versions = useQuery({
    queryKey: ['setting-versions', setting.key, facilityId],
    queryFn: ({ signal }) => listVersions(setting.key, facilityId, signal),
    enabled: setting.scope === 'ORGANIZATION' || !!facilityId,
  })
  const [draft, setDraft] = useState<unknown>(undefined)

  if (setting.scope === 'FACILITY' && !facility) return <EmptyState title="No facility" />
  return (
    <Card withBorder>
      <Title order={2} size="h4">
        {setting.title}
        {setting.scope === 'FACILITY' && facility ? ` (${facility.name})` : ''}
      </Title>
      <QueryState query={versions} loadingLabel="Loading versions…">
        {(rows) => {
          const active = rows.find((r) => r.status === 'ACTIVE')
          const pending = rows.find((r) => r.status === 'PROPOSED')
          const start = draft ?? active?.value ?? emptyValue(setting.key)
          return (
            <Stack mt="sm">
              <Text size="sm" fw={600}>
                In use: {active ? `version ${active.version_number}` : 'not configured'}
              </Text>
              {active && <ValueSummary settingKey={setting.key} value={active.value} />}
              {pending ? (
                <Text size="sm">
                  Version {pending.version_number} is waiting for approval; decide it before proposing another.
                </Text>
              ) : (
                <ProposeForm
                  key={JSON.stringify(start)}
                  setting={setting}
                  facilityId={facilityId}
                  initial={start}
                  onDone={() => setDraft(undefined)}
                />
              )}
              <Table aria-label={`Versions of ${setting.title}`}>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th scope="col">Version</Table.Th>
                    <Table.Th scope="col">Status</Table.Th>
                    <Table.Th scope="col">Proposed</Table.Th>
                    <Table.Th scope="col">Decided</Table.Th>
                    <Table.Th scope="col">Action</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {rows.map((v) => (
                    <Table.Tr key={v.id}>
                      <Table.Td>{v.version_number}</Table.Td>
                      <Table.Td>
                        <Badge variant="light" color={v.status === 'ACTIVE' ? 'green' : 'gray'}>
                          {STATUS_LABEL[v.status]}
                        </Badge>
                      </Table.Td>
                      <Table.Td>
                        {v.proposed_by.display_name}
                        <Text size="xs">{v.reason}</Text>
                      </Table.Td>
                      <Table.Td>
                        {v.decided_by?.display_name}
                        {v.decision_note && <Text size="xs">{v.decision_note}</Text>}
                      </Table.Td>
                      <Table.Td>
                        {(v.status === 'SUPERSEDED' || v.status === 'REJECTED') && !pending && (
                          <Button size="xs" variant="default" onClick={() => setDraft(v.value)}>
                            Propose again
                          </Button>
                        )}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Stack>
          )
        }}
      </QueryState>
    </Card>
  )
}

function emptyValue(key: string): unknown {
  if (key === 'TRIAGE') return { urgency_levels: [], alarm_limits: {} }
  if (key === 'BILLING_OPTIONS') return { billable_visit_days: 7 }
  return { display_name: '', address_lines: [] }
}

function ProposeForm({
  setting,
  facilityId,
  initial,
  onDone,
}: {
  setting: SettingInfo
  facilityId: string | null
  initial: unknown
  onDone: () => void
}) {
  const queryClient = useQueryClient()
  const [value, setValue] = useState<unknown>(initial)
  const [reason, setReason] = useState('')
  const propose = useMutation({
    mutationFn: () => proposeSetting(setting.key, facilityId, value, reason.trim()),
    onSuccess: () => {
      setReason('')
      onDone()
      void queryClient.invalidateQueries({ queryKey: ['setting-versions', setting.key] })
      void queryClient.invalidateQueries({ queryKey: ['settings-pending'] })
    },
  })
  return (
    <form
      aria-label={`Propose a change to ${setting.title}`}
      onSubmit={(e) => {
        e.preventDefault()
        if (reason.trim()) propose.mutate()
      }}
    >
      <Stack>
        <Title order={3} size="h5">
          Propose a change
        </Title>
        {setting.key === 'TRIAGE' && <TriageEditor value={value as TriageValue} onChange={setValue} />}
        {setting.key === 'FACILITY_BRANDING' && facilityId && (
          <BrandingEditor value={value as Json} facilityId={facilityId} onChange={setValue} />
        )}
        {setting.key === 'BILLING_OPTIONS' && (
          <NumberInput
            label="Days back for visits without an invoice"
            min={1}
            max={90}
            allowDecimal={false}
            value={(value as Json).billable_visit_days as number}
            onChange={(n) => setValue({ billable_visit_days: Number(n) })}
          />
        )}
        <Textarea label="Reason" required value={reason} onChange={(e) => setReason(e.currentTarget.value)} />
        {propose.isError && <ErrorState error={propose.error} />}
        <Group justify="flex-end">
          <Button type="submit" disabled={!reason.trim()} loading={propose.isPending}>
            Propose
          </Button>
        </Group>
      </Stack>
    </form>
  )
}

function TriageEditor({ value, onChange }: { value: TriageValue; onChange: (v: TriageValue) => void }) {
  const levels = value.urgency_levels ?? []
  const limits = value.alarm_limits ?? {}
  const setLevel = (i: number, patch: Partial<TriageValue['urgency_levels'][number]>) =>
    onChange({ ...value, urgency_levels: levels.map((l, j) => (j === i ? { ...l, ...patch } : l)) })
  const setLimit = (measure: string, side: 'low' | 'high', v: string) => {
    const next = { ...limits, [measure]: { ...limits[measure], [side]: v === '' ? undefined : v } }
    if (next[measure].low === undefined && next[measure].high === undefined) delete next[measure]
    onChange({ ...value, alarm_limits: next })
  }
  return (
    <Stack>
      <Text size="sm" fw={600}>
        Urgency levels (lower rank is seen first)
      </Text>
      {levels.length === 0 && <Text size="xs">No levels: urgency cannot be recorded.</Text>}
      {levels.map((l, i) => (
        <Group key={i} align="flex-end">
          <TextInput
            label="Code"
            value={l.code}
            onChange={(e) => setLevel(i, { code: e.currentTarget.value.toUpperCase() })}
          />
          <TextInput label="Label" value={l.label} onChange={(e) => setLevel(i, { label: e.currentTarget.value })} />
          <NumberInput
            label="Rank"
            min={1}
            max={99}
            allowDecimal={false}
            value={l.rank}
            onChange={(n) => setLevel(i, { rank: Number(n) })}
          />
          <ActionIcon
            variant="default"
            aria-label={`Remove level ${l.code || i + 1}`}
            onClick={() => onChange({ ...value, urgency_levels: levels.filter((_, j) => j !== i) })}
          >
            <IconTrash size={16} aria-hidden />
          </ActionIcon>
        </Group>
      ))}
      <Button
        size="xs"
        variant="default"
        w="fit-content"
        onClick={() =>
          onChange({ ...value, urgency_levels: [...levels, { code: '', label: '', rank: levels.length + 1 }] })
        }
      >
        Add level
      </Button>
      <Text size="sm" fw={600}>
        Alarm limits (leave empty for no alarm)
      </Text>
      <Table aria-label="Alarm limits">
        <Table.Thead>
          <Table.Tr>
            <Table.Th scope="col">Measure</Table.Th>
            <Table.Th scope="col">Low</Table.Th>
            <Table.Th scope="col">High</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {MEASURES.map(([key, label]) => (
            <Table.Tr key={key}>
              <Table.Td>{label}</Table.Td>
              <Table.Td>
                <TextInput
                  aria-label={`${label} low`}
                  inputMode="decimal"
                  value={String(limits[key]?.low ?? '')}
                  onChange={(e) => setLimit(key, 'low', e.currentTarget.value.trim())}
                />
              </Table.Td>
              <Table.Td>
                <TextInput
                  aria-label={`${label} high`}
                  inputMode="decimal"
                  value={String(limits[key]?.high ?? '')}
                  onChange={(e) => setLimit(key, 'high', e.currentTarget.value.trim())}
                />
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  )
}

function BrandingEditor({
  value,
  facilityId,
  onChange,
}: {
  value: Json
  facilityId: string
  onChange: (v: Json) => void
}) {
  const [uploadError, setUploadError] = useState<unknown>(null)
  const text = (k: string) => (typeof value[k] === 'string' ? (value[k] as string) : '')
  const set = (k: string, v: string) => onChange({ ...value, [k]: v })
  const lines = Array.isArray(value.address_lines) ? (value.address_lines as string[]) : []
  const upload = async (file: File | null) => {
    setUploadError(null)
    if (!file) return
    try {
      const data = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(String(reader.result).split(',')[1] ?? '')
        reader.onerror = () => reject(reader.error)
        reader.readAsDataURL(file)
      })
      const logo = await uploadLogo(facilityId, file.type, data)
      onChange({ ...value, logo_id: logo.id })
    } catch (err) {
      setUploadError(err)
    }
  }
  return (
    <Stack>
      <TextInput
        label="Display name"
        required
        maxLength={120}
        value={text('display_name')}
        onChange={(e) => set('display_name', e.currentTarget.value)}
      />
      <Textarea
        label="Address (one line each, at most 4)"
        value={lines.join('\n')}
        onChange={(e) =>
          onChange({
            ...value,
            address_lines: e.currentTarget.value
              .split('\n')
              .map((l) => l.trim())
              .filter(Boolean),
          })
        }
      />
      <TextInput
        label="Phone"
        maxLength={40}
        value={text('phone')}
        onChange={(e) => set('phone', e.currentTarget.value)}
      />
      <TextInput
        label="Registration or tax number"
        maxLength={60}
        value={text('registration_number')}
        onChange={(e) => set('registration_number', e.currentTarget.value)}
      />
      <Textarea
        label="Receipt footer"
        maxLength={300}
        value={text('receipt_footer')}
        onChange={(e) => set('receipt_footer', e.currentTarget.value)}
      />
      <FileInput
        label="Logo (PNG or JPEG, at most 256 KB)"
        accept="image/png,image/jpeg"
        onChange={(f) => void upload(f)}
      />
      {uploadError !== null && <ErrorState error={uploadError} />}
      {typeof value.logo_id === 'string' && (
        <Group>
          <Image src={logoUrl(value.logo_id)} alt="Logo preview" h={48} w="auto" fit="contain" />
          <Button size="xs" variant="default" onClick={() => onChange({ ...value, logo_id: undefined })}>
            Remove logo
          </Button>
        </Group>
      )}
    </Stack>
  )
}

/** Readable summary of a setting value. */
export function ValueSummary({ settingKey, value }: { settingKey: string; value: unknown }) {
  const v = (value ?? {}) as Json
  if (settingKey === 'TRIAGE') {
    const t = v as unknown as TriageValue
    const limits = Object.entries(t.alarm_limits ?? {})
    return (
      <Stack gap={2}>
        <Text size="xs">
          Levels:{' '}
          {(t.urgency_levels ?? []).length === 0
            ? 'none'
            : t.urgency_levels.map((l) => `${l.rank}. ${l.label} (${l.code})`).join(', ')}
        </Text>
        <Text size="xs">
          Alarm limits:{' '}
          {limits.length === 0 ? 'none' : limits.map(([m, l]) => `${m} ${l.low ?? '–'} to ${l.high ?? '–'}`).join('; ')}
        </Text>
      </Stack>
    )
  }
  if (settingKey === 'BILLING_OPTIONS') {
    return <Text size="xs">Visits without an invoice: last {String(v.billable_visit_days)} days</Text>
  }
  return (
    <Stack gap={2}>
      <Text size="xs">Name: {String(v.display_name ?? '')}</Text>
      {Array.isArray(v.address_lines) && v.address_lines.length > 0 && (
        <Text size="xs">Address: {(v.address_lines as string[]).join(', ')}</Text>
      )}
      {typeof v.phone === 'string' && <Text size="xs">Phone: {v.phone}</Text>}
      {typeof v.registration_number === 'string' && <Text size="xs">Registration: {v.registration_number}</Text>}
      {typeof v.receipt_footer === 'string' && <Text size="xs">Receipt footer: {v.receipt_footer}</Text>}
      {typeof v.logo_id === 'string' && (
        <Image src={logoUrl(v.logo_id)} alt="Proposed logo" h={32} w="auto" fit="contain" />
      )}
    </Stack>
  )
}
