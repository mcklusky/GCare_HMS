import {
  ActionIcon,
  Badge,
  Box,
  Button,
  Card,
  Divider,
  FileButton,
  Grid,
  Group,
  Image,
  NumberInput,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
  Textarea,
  ThemeIcon,
  Title,
  UnstyledButton,
} from '@mantine/core'
import {
  IconAdjustments,
  IconBuildingHospital,
  IconCheck,
  IconChevronRight,
  IconClock,
  IconPhoto,
  IconReceipt2,
  IconStethoscope,
  IconTrash,
  IconUpload,
} from '@tabler/icons-react'
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

const SETTING_ICON: Record<string, typeof IconAdjustments> = {
  FACILITY_BRANDING: IconBuildingHospital,
  TRIAGE: IconStethoscope,
  BILLING_OPTIONS: IconReceipt2,
}

/**
 * Settings (task 5.2): every change is a new version proposed by one person and approved by
 * another; earlier versions stay on record and can be proposed again. Nothing has a built-in value.
 * Layout: settings list on the left, the open setting on the right (edit, preview, history).
 */
export function SettingsPage() {
  const [selected, setSelected] = useState<SettingInfo | null>(null)
  const settings = useQuery({ queryKey: ['settings'], queryFn: ({ signal }) => listSettings(signal) })

  return (
    <Stack gap="lg">
      <PageHeader
        title="Settings"
        description="A change takes effect only after a different person approves it. Every version is kept; to roll back, propose an earlier version again."
      />
      <PendingApprovals />
      <Grid gap="lg" align="flex-start">
        <Grid.Col span={{ base: 12, md: 4, lg: 3 }}>
          <Card withBorder padding={0} component="nav" aria-label="Settings list">
            <Text className="gcare-nav-section" px="md" pt="sm" pb={6}>
              Settings
            </Text>
            <QueryState query={settings} loadingLabel="Loading settings…">
              {(rows) => (
                <Box pb={6}>
                  {rows.map((s) => {
                    const Icon = SETTING_ICON[s.key] ?? IconAdjustments
                    const active = selected?.key === s.key
                    return (
                      <UnstyledButton
                        key={s.key}
                        className="gcare-row gcare-setting-item"
                        data-active={active || undefined}
                        aria-current={active ? 'true' : undefined}
                        aria-label={`Open ${s.title}`}
                        onClick={() => setSelected(s)}
                        w="100%"
                      >
                        <ThemeIcon size={30} radius="sm" variant={active ? 'filled' : 'light'} aria-hidden>
                          <Icon size={16} stroke={1.7} />
                        </ThemeIcon>
                        <Box miw={0} style={{ flex: 1 }}>
                          <Text size="sm" fw={600} lh={1.3}>
                            {s.title}
                          </Text>
                          <Text size="xs" c="dimmed" lineClamp={2}>
                            {s.description}
                          </Text>
                        </Box>
                        <IconChevronRight size={15} aria-hidden className="gcare-row-arrow" />
                      </UnstyledButton>
                    )
                  })}
                </Box>
              )}
            </QueryState>
          </Card>
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 8, lg: 9 }}>
          {selected ? (
            <SettingPanel key={selected.key} setting={selected} />
          ) : (
            <Card withBorder>
              <EmptyState title="Choose a setting">
                Pick a setting on the left to see what is in use, propose a change or review its history.
              </EmptyState>
            </Card>
          )}
        </Grid.Col>
      </Grid>
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
    <Card withBorder padding={0} component="section" aria-labelledby="settings-pending">
      <Group px="md" py="sm" gap="xs" className="gcare-card-head">
        <IconClock size={16} aria-hidden style={{ color: 'var(--mantine-color-yellow-7)' }} />
        <Title order={2} id="settings-pending">
          Waiting for approval
        </Title>
        <Badge color="yellow">{pending.data.length}</Badge>
      </Group>
      {act.isError && (
        <Box p="sm">
          <ErrorState error={act.error} />
        </Box>
      )}
      {pending.data.map((v) => (
        <Group key={v.id} className="gcare-row" align="flex-start" wrap="nowrap" style={{ cursor: 'default' }}>
          <Box miw={0} style={{ flex: 1 }}>
            <Text size="sm" fw={600}>
              {v.key} · version {v.version_number}
            </Text>
            <Text size="xs" c="dimmed">
              Proposed by {v.proposed_by.display_name}: {v.reason}
            </Text>
            <Box mt={6}>
              <ValueSummary settingKey={v.key} value={v.value} />
            </Box>
          </Box>
          {v.proposed_by.id === me.id ? (
            <Text size="xs" c="dimmed" maw={200} ta="right">
              Your proposal: another person decides.
            </Text>
          ) : (
            <Group gap="xs" wrap="nowrap">
              <Button
                size="xs"
                leftSection={<IconCheck size={14} aria-hidden />}
                onClick={() => setDeciding({ v, decision: 'approve' })}
              >
                Approve
              </Button>
              <Button size="xs" variant="default" onClick={() => setDeciding({ v, decision: 'reject' })}>
                Reject
              </Button>
            </Group>
          )}
        </Group>
      ))}
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
    <Card withBorder padding={0} component="section" aria-labelledby="setting-title">
      <Group justify="space-between" px="md" py="sm" className="gcare-card-head" wrap="wrap" gap="xs">
        <Box miw={0}>
          <Title order={2} id="setting-title">
            {setting.title}
          </Title>
          <Text size="xs" c="dimmed">
            {setting.scope === 'FACILITY' && facility ? `For ${facility.name}` : 'For the whole organization'}
          </Text>
        </Box>
        <Badge variant="default">{setting.scope === 'FACILITY' ? 'Facility setting' : 'Organization setting'}</Badge>
      </Group>
      <QueryState query={versions} loadingLabel="Loading versions…">
        {(rows) => {
          const active = rows.find((r) => r.status === 'ACTIVE')
          const pending = rows.find((r) => r.status === 'PROPOSED')
          const start = draft ?? active?.value ?? emptyValue(setting.key)
          return (
            <Stack gap={0}>
              <Group px="md" py="sm" gap="sm" className="gcare-card-head" wrap="wrap">
                <Badge
                  color={active ? 'green' : 'gray'}
                  leftSection={active ? <IconCheck size={11} aria-hidden /> : null}
                >
                  {active ? 'Configured' : 'Not configured'}
                </Badge>
                <Text size="sm" fw={600}>
                  In use: {active ? `version ${active.version_number}` : 'not configured'}
                </Text>
                {active?.decided_by && (
                  <Text size="xs" c="dimmed">
                    approved by {active.decided_by.display_name}
                  </Text>
                )}
              </Group>
              <Box p="md">
                {pending ? (
                  <Stack gap="sm">
                    <Text size="sm">
                      Version {pending.version_number} is waiting for approval; decide it before proposing another.
                    </Text>
                    <Card withBorder bg="var(--gcare-subtle)">
                      <ValueSummary settingKey={setting.key} value={pending.value} />
                    </Card>
                  </Stack>
                ) : (
                  <ProposeForm
                    key={JSON.stringify(start)}
                    setting={setting}
                    facilityId={facilityId}
                    initial={start}
                    onDone={() => setDraft(undefined)}
                  />
                )}
              </Box>
              <Divider color="var(--gcare-border)" />
              <Box p="md">
                <Group justify="space-between" mb="xs">
                  <Title order={3}>Version history</Title>
                  <Text size="xs" c="dimmed">
                    {rows.length} {rows.length === 1 ? 'version' : 'versions'}
                  </Text>
                </Group>
                <Table.ScrollContainer minWidth={560}>
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
                      {rows.length === 0 && (
                        <Table.Tr>
                          <Table.Td colSpan={5}>
                            <Text size="sm" c="dimmed">
                              No versions yet.
                            </Text>
                          </Table.Td>
                        </Table.Tr>
                      )}
                      {rows.map((v) => (
                        <Table.Tr key={v.id}>
                          <Table.Td fw={600}>v{v.version_number}</Table.Td>
                          <Table.Td>
                            <Badge
                              color={v.status === 'ACTIVE' ? 'green' : v.status === 'PROPOSED' ? 'yellow' : 'gray'}
                            >
                              {STATUS_LABEL[v.status]}
                            </Badge>
                          </Table.Td>
                          <Table.Td>
                            {v.proposed_by.display_name}
                            <Text size="xs" c="dimmed">
                              {v.reason}
                            </Text>
                          </Table.Td>
                          <Table.Td>
                            {v.decided_by?.display_name ?? '—'}
                            {v.decision_note && (
                              <Text size="xs" c="dimmed">
                                {v.decision_note}
                              </Text>
                            )}
                          </Table.Td>
                          <Table.Td>
                            {(v.status === 'SUPERSEDED' || v.status === 'REJECTED') && !pending && (
                              <Button size="compact-xs" variant="default" onClick={() => setDraft(v.value)}>
                                Propose again
                              </Button>
                            )}
                          </Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </Table.ScrollContainer>
              </Box>
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
      <Stack gap="md">
        <Title order={3}>Propose a change</Title>
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
            maw={320}
            value={(value as Json).billable_visit_days as number}
            onChange={(n) => setValue({ billable_visit_days: Number(n) })}
          />
        )}
        <Box className="gcare-propose-bar">
          <Textarea
            label="Reason"
            description="Shown to the approver and kept with this version."
            required
            autosize
            minRows={1}
            maxRows={4}
            value={reason}
            onChange={(e) => setReason(e.currentTarget.value)}
            style={{ flex: 1 }}
          />
          <Button type="submit" disabled={!reason.trim()} loading={propose.isPending}>
            Propose
          </Button>
        </Box>
        {propose.isError && <ErrorState error={propose.error} />}
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
  const [uploading, setUploading] = useState(false)
  const text = (k: string) => (typeof value[k] === 'string' ? (value[k] as string) : '')
  const set = (k: string, v: string) => onChange({ ...value, [k]: v })
  const lines = Array.isArray(value.address_lines) ? (value.address_lines as string[]) : []
  const upload = async (file: File | null) => {
    setUploadError(null)
    if (!file) return
    setUploading(true)
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
    } finally {
      setUploading(false)
    }
  }
  const logoId = typeof value.logo_id === 'string' ? value.logo_id : null
  return (
    <Grid gap="lg">
      <Grid.Col span={{ base: 12, xl: 7 }}>
        <Stack gap="sm">
          <Group gap="sm" wrap="nowrap" align="center" className="gcare-logo-field">
            <Box className="gcare-logo-box">
              {logoId ? (
                <Image src={logoUrl(logoId)} alt="Logo preview" h={44} w={44} fit="contain" />
              ) : (
                <IconPhoto size={20} aria-hidden />
              )}
            </Box>
            <Box style={{ flex: 1 }} miw={0}>
              <Text size="sm" fw={500}>
                Logo
              </Text>
              <Text size="xs" c="dimmed">
                PNG or JPEG, at most 256 KB. Shown on receipts and in the header.
              </Text>
            </Box>
            <FileButton onChange={(f) => void upload(f)} accept="image/png,image/jpeg">
              {(props) => (
                <Button
                  {...props}
                  size="xs"
                  variant="default"
                  loading={uploading}
                  leftSection={<IconUpload size={14} aria-hidden />}
                >
                  {logoId ? 'Replace' : 'Upload'}
                </Button>
              )}
            </FileButton>
            {logoId && (
              <ActionIcon
                variant="default"
                size={30}
                aria-label="Remove logo"
                onClick={() => onChange({ ...value, logo_id: undefined })}
              >
                <IconTrash size={14} aria-hidden />
              </ActionIcon>
            )}
          </Group>
          {uploadError !== null && <ErrorState error={uploadError} />}
          <TextInput
            label="Display name"
            required
            maxLength={120}
            value={text('display_name')}
            onChange={(e) => set('display_name', e.currentTarget.value)}
          />
          <Textarea
            label="Address"
            description="One line each, at most 4."
            autosize
            minRows={2}
            maxRows={4}
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
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
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
          </SimpleGrid>
          <Textarea
            label="Receipt footer"
            description={`${text('receipt_footer').length}/300 characters`}
            maxLength={300}
            autosize
            minRows={2}
            maxRows={5}
            value={text('receipt_footer')}
            onChange={(e) => set('receipt_footer', e.currentTarget.value)}
          />
        </Stack>
      </Grid.Col>
      <Grid.Col span={{ base: 12, xl: 5 }}>
        <BrandingPreview value={value} />
      </Grid.Col>
    </Grid>
  )
}

/** Live preview of how the branding appears at the top and bottom of a receipt (not to scale). */
function BrandingPreview({ value }: { value: Json }) {
  const str = (k: string) => (typeof value[k] === 'string' ? (value[k] as string).trim() : '')
  const lines = Array.isArray(value.address_lines) ? (value.address_lines as string[]) : []
  const logoId = typeof value.logo_id === 'string' ? value.logo_id : null
  const meta = [
    str('phone') && `Tel. ${str('phone')}`,
    str('registration_number') && `Reg. ${str('registration_number')}`,
  ]
    .filter(Boolean)
    .join(' · ')
  return (
    <Box component="section" aria-labelledby="branding-preview" className="gcare-preview">
      <Group justify="space-between" mb={6}>
        <Text id="branding-preview" className="gcare-nav-section">
          Receipt preview
        </Text>
        <Text size="xs" c="dimmed">
          Updates as you type
        </Text>
      </Group>
      <div className="gcare-receipt">
        <Group gap="sm" wrap="nowrap" align="flex-start">
          {logoId && <Image src={logoUrl(logoId)} alt="" h={40} w={40} fit="contain" />}
          <Box miw={0}>
            <Text fw={700} size="md" lh={1.25}>
              {str('display_name') || 'Facility name'}
            </Text>
            {lines.map((l, i) => (
              <Text key={i} size="xs" lh={1.4}>
                {l}
              </Text>
            ))}
            {meta && (
              <Text size="xs" lh={1.4}>
                {meta}
              </Text>
            )}
          </Box>
        </Group>
        <div className="gcare-receipt-rule" />
        <Text size="xs" fw={600}>
          RECEIPT
        </Text>
        <div className="gcare-receipt-lines" aria-hidden>
          <span />
          <span />
          <span />
        </div>
        <div className="gcare-receipt-rule" />
        <Text size="xs" ta="center" c={str('receipt_footer') ? undefined : 'dimmed'}>
          {str('receipt_footer') || 'Receipt footer'}
        </Text>
      </div>
    </Box>
  )
}

/** Readable summary of a setting value. */
export function ValueSummary({ settingKey, value }: { settingKey: string; value: unknown }) {
  const v = (value ?? {}) as Json
  if (settingKey === 'TRIAGE') {
    const t = v as unknown as TriageValue
    const limits = Object.entries(t.alarm_limits ?? {})
    return (
      <dl className="gcare-dl gcare-dl-left">
        <dt>Levels</dt>
        <dd>
          {(t.urgency_levels ?? []).length === 0
            ? 'none'
            : t.urgency_levels.map((l) => `${l.rank}. ${l.label} (${l.code})`).join(', ')}
        </dd>
        <dt>Alarm limits</dt>
        <dd>
          {limits.length === 0 ? 'none' : limits.map(([m, l]) => `${m} ${l.low ?? '–'} to ${l.high ?? '–'}`).join('; ')}
        </dd>
      </dl>
    )
  }
  if (settingKey === 'BILLING_OPTIONS') {
    return <Text size="xs">Visits without an invoice: last {String(v.billable_visit_days)} days</Text>
  }
  return (
    <Group gap="sm" align="flex-start" wrap="nowrap">
      {typeof v.logo_id === 'string' && (
        <Image src={logoUrl(v.logo_id)} alt="Proposed logo" h={32} w={32} fit="contain" />
      )}
      <dl className="gcare-dl gcare-dl-left">
        <dt>Name</dt>
        <dd>{String(v.display_name ?? '')}</dd>
        {Array.isArray(v.address_lines) && v.address_lines.length > 0 && (
          <>
            <dt>Address</dt>
            <dd>{(v.address_lines as string[]).join(', ')}</dd>
          </>
        )}
        {typeof v.phone === 'string' && (
          <>
            <dt>Phone</dt>
            <dd>{v.phone}</dd>
          </>
        )}
        {typeof v.registration_number === 'string' && (
          <>
            <dt>Registration</dt>
            <dd>{v.registration_number}</dd>
          </>
        )}
        {typeof v.receipt_footer === 'string' && (
          <>
            <dt>Receipt footer</dt>
            <dd>{v.receipt_footer}</dd>
          </>
        )}
      </dl>
    </Group>
  )
}
