import {
  Alert,
  Anchor,
  Button,
  Card,
  Checkbox,
  Group,
  Select,
  Stack,
  Text,
  Textarea,
  TextInput,
  Title,
} from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import {
  discardReport,
  getOrderReports,
  METHOD_LABEL,
  saveReport,
  signReport,
  startReport,
  type CommunicationMethod,
  type Report,
  type Sections,
} from '../../api/radiologyReports'
import { useCurrentMe } from '../../auth/MeContext'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { ErrorState, PageTitle, QueryState } from '../../components/states'
import { ReportText } from './ImagingResultsSection'

/** Write, sign and amend the report of one imaging order (task 6.4; docs/26). */
export function ReportEditorPage() {
  const { orderId = '' } = useParams()
  const me = useCurrentMe()
  const queryClient = useQueryClient()
  const [amendReason, setAmendReason] = useState('')
  const reports = useQuery({
    queryKey: ['order-reports', orderId],
    queryFn: ({ signal }) => getOrderReports(orderId, signal),
  })
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['order-reports', orderId] })
    void queryClient.invalidateQueries({ queryKey: ['radiology-reporting'] })
  }
  const start = useMutation({ mutationFn: (reason?: string) => startReport(orderId, reason), onSuccess: refresh })

  return (
    <Stack>
      <Anchor component={Link} to="/radiology/reporting" size="sm">
        ← Reporting
      </Anchor>
      <PageTitle>Radiology report</PageTitle>
      {start.isError && <ErrorState error={start.error} />}
      <QueryState query={reports} loadingLabel="Loading reports…">
        {(list) => {
          const draft = list.find((r) => r.status === 'DRAFT')
          const signed = list.filter((r) => r.status === 'SIGNED')
          return (
            <Stack>
              {signed.map((r) => (
                <Card withBorder key={r.id}>
                  <ReportText report={r} />
                  {r.critical && (
                    <Text size="sm" c="red" mt="xs">
                      Critical result · told {r.critical_result?.communicated_to}
                    </Text>
                  )}
                </Card>
              ))}
              {draft ? (
                draft.author.id === me.id ? (
                  <DraftEditor key={draft.id + draft.version} draft={draft} onChanged={refresh} />
                ) : (
                  <Alert title="Draft in progress">A draft by {draft.author.display_name} is open.</Alert>
                )
              ) : signed.length === 0 ? (
                <Group>
                  <Button onClick={() => start.mutate(undefined)} loading={start.isPending}>
                    Start report
                  </Button>
                </Group>
              ) : (
                <Card withBorder>
                  <Text size="sm" mb="xs">
                    A signed report never changes. To correct it, start an amendment with a reason; both stay visible.
                  </Text>
                  <Group align="end">
                    <TextInput
                      label="Reason for the amendment"
                      maxLength={1000}
                      value={amendReason}
                      onChange={(e) => setAmendReason(e.currentTarget.value)}
                      w={420}
                    />
                    <Button
                      disabled={!amendReason.trim()}
                      loading={start.isPending}
                      onClick={() => start.mutate(amendReason.trim())}
                    >
                      Start amendment
                    </Button>
                  </Group>
                </Card>
              )}
            </Stack>
          )
        }}
      </QueryState>
    </Stack>
  )
}

function DraftEditor({ draft, onChanged }: { draft: Report; onChanged: () => void }) {
  const [s, setS] = useState<Sections>({
    technique: draft.technique,
    comparison: draft.comparison,
    findings: draft.findings,
    impression: draft.impression,
    critical: draft.critical,
  })
  const [signing, setSigning] = useState(false)
  const [discarding, setDiscarding] = useState(false)
  const [to, setTo] = useState('')
  const [method, setMethod] = useState<CommunicationMethod>('PHONE')
  const [at, setAt] = useState('')
  const [note, setNote] = useState('')
  const dirty =
    s.technique !== draft.technique ||
    s.comparison !== draft.comparison ||
    s.findings !== draft.findings ||
    s.impression !== draft.impression ||
    s.critical !== draft.critical
  const save = useMutation({ mutationFn: () => saveReport(draft, s), onSuccess: onChanged })
  const sign = useMutation({
    mutationFn: () =>
      signReport(
        draft,
        draft.critical
          ? { communicated_to: to.trim(), method, communicated_at: new Date(at).toISOString(), note: note.trim() }
          : undefined,
      ),
    onSuccess: () => {
      setSigning(false)
      onChanged()
    },
  })
  const discard = useMutation({ mutationFn: () => discardReport(draft), onSuccess: onChanged })
  const field = (k: keyof Omit<Sections, 'critical'>, label: string, required = false) => (
    <Textarea
      label={label}
      required={required}
      autosize
      minRows={required ? 4 : 2}
      maxLength={20000}
      value={s[k] ?? ''}
      onChange={(e) => {
        const v = e.currentTarget.value
        setS((x) => ({ ...x, [k]: v }))
      }}
    />
  )
  const canSign = !dirty && !!draft.findings?.trim() && !!draft.impression?.trim()

  return (
    <Card withBorder component="form" aria-label="Report draft" onSubmit={(e) => (e.preventDefault(), save.mutate())}>
      <Title order={2} size="h4" mb="xs">
        {draft.amends_report_id ? `Amendment draft (${draft.amendment_reason})` : 'Draft'}
      </Title>
      <Stack gap="sm">
        {field('technique', 'Technique')}
        {field('comparison', 'Comparison')}
        {field('findings', 'Findings', true)}
        {field('impression', 'Impression', true)}
        <Checkbox
          label="Critical result: I will contact the responsible clinician directly"
          checked={s.critical}
          onChange={(e) => {
            const v = e.currentTarget.checked
            setS((x) => ({ ...x, critical: v }))
          }}
        />
        {save.isError && <ErrorState error={save.error} />}
        {discard.isError && <ErrorState error={discard.error} />}
        <Group>
          <Button type="submit" variant="default" disabled={!dirty} loading={save.isPending}>
            Save draft
          </Button>
          <Button onClick={() => setSigning(true)} disabled={!canSign}>
            Sign
          </Button>
          <Button variant="subtle" color="red" onClick={() => setDiscarding(true)}>
            Discard draft
          </Button>
        </Group>
        {dirty && <Text size="xs">Save the draft before signing.</Text>}
      </Stack>
      <ConfirmDialog
        opened={signing}
        title="Sign this report?"
        confirmLabel="Sign"
        busy={sign.isPending}
        confirmDisabled={draft.critical && (!to.trim() || !at)}
        onConfirm={() => sign.mutate()}
        onCancel={() => setSigning(false)}
      >
        <Stack gap="xs">
          <Text size="sm">A signed report cannot be changed; corrections are amendments.</Text>
          {draft.critical && (
            <>
              <Text size="sm" fw={600}>
                Critical result: record the direct contact.
              </Text>
              <TextInput
                label="Told to (name and role)"
                required
                maxLength={200}
                value={to}
                onChange={(e) => setTo(e.currentTarget.value)}
              />
              <Select
                label="How"
                data={Object.entries(METHOD_LABEL).map(([value, label]) => ({ value, label }))}
                value={method}
                allowDeselect={false}
                onChange={(v) => v && setMethod(v as CommunicationMethod)}
              />
              <TextInput
                type="datetime-local"
                label="When"
                required
                value={at}
                onChange={(e) => setAt(e.currentTarget.value)}
              />
              <TextInput label="Note" maxLength={1000} value={note} onChange={(e) => setNote(e.currentTarget.value)} />
            </>
          )}
          {sign.isError && <ErrorState error={sign.error} />}
        </Stack>
      </ConfirmDialog>
      <ConfirmDialog
        opened={discarding}
        title="Discard this draft?"
        confirmLabel="Discard"
        danger
        busy={discard.isPending}
        onConfirm={() => discard.mutate()}
        onCancel={() => setDiscarding(false)}
      >
        The draft text is kept for the record but no longer shown.
      </ConfirmDialog>
    </Card>
  )
}
