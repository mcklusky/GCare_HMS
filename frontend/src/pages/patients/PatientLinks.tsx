import {
  Alert,
  Anchor,
  Badge,
  Button,
  Card,
  Group,
  Radio,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Textarea,
  Title,
  VisuallyHidden,
} from '@mantine/core'
import { IconAlertTriangle, IconLink } from '@tabler/icons-react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import {
  IDENTIFIER_LABEL,
  LINK_STATUS_LABEL,
  SEX_LABEL,
  birthLabel,
  decideLink,
  displayName,
  getPatientLinks,
  listPendingLinks,
  requestLink,
  unlinkPatients,
  type DuplicateFlag,
  type PatientDetail,
  type PatientLink,
  type PatientSummary,
} from '../../api/patients'
import { Permission } from '../../api/types'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, QueryState } from '../../components/states'
import { formatDateTime } from '../../components/time'

/**
 * Linking duplicate records (docs/17 §5, PROVISIONAL). Links, never merges: the duplicate keeps
 * its data and points to the record in use. One person requests, a different person approves.
 */

const LINK_KEYS = [['patient-links'], ['duplicate-flags'], ['patient']] as const

function useInvalidateLinks() {
  const queryClient = useQueryClient()
  return () => LINK_KEYS.forEach((queryKey) => void queryClient.invalidateQueries({ queryKey: [...queryKey] }))
}

/** From the review queue: "same person", choose which record stays in use. */
export function RequestLinkDialog({ flag, onClose }: { flag: DuplicateFlag; onClose: () => void }) {
  const invalidate = useInvalidateLinks()
  const [keep, setKeep] = useState<string>('')
  const mutation = useMutation({
    mutationFn: (reason: string) => {
      const surviving = keep === flag.patient.id ? flag.patient : flag.candidate
      const retired = surviving === flag.patient ? flag.candidate : flag.patient
      return requestLink({
        retired_patient_id: retired.id,
        surviving_patient_id: surviving.id,
        reason,
        flag_id: flag.id,
        flag_version: flag.version,
      })
    },
    onSuccess: () => {
      invalidate()
      onClose()
    },
  })

  return (
    <ConfirmDialog
      opened
      title="Request a link?"
      confirmLabel="Request link"
      requireReason
      busy={mutation.isPending}
      confirmDisabled={keep === ''}
      onConfirm={(reason) => mutation.mutate(reason ?? '')}
      onCancel={onClose}
    >
      <Stack gap="sm">
        <Text size="sm">
          Another person must approve the link. Nothing is merged or deleted: the duplicate keeps its data and points to
          the record you keep.
        </Text>
        <Radio.Group label="Which record stays in use?" value={keep} onChange={setKeep} withAsterisk>
          <Stack gap="xs" mt="xs">
            {[flag.patient, flag.candidate].map((p) => (
              <Radio key={p.id} value={p.id} label={`${p.patient_number} · ${displayName(p)}`} />
            ))}
          </Stack>
        </Radio.Group>
        {mutation.isError && <ErrorState error={mutation.error} />}
        <Text size="sm">Say how you confirmed they are the same person.</Text>
      </Stack>
    </ConfirmDialog>
  )
}

/** Approvers' list of link requests. Requesters cannot decide their own. */
export function PendingLinks() {
  const me = useCurrentMe()
  const invalidate = useInvalidateLinks()
  const [deciding, setDeciding] = useState<{
    link: PatientLink
    decision: 'approve' | 'reject'
  } | null>(null)
  const [override, setOverride] = useState('')
  const links = useQuery({
    queryKey: ['patient-links', 'pending'],
    queryFn: ({ signal }) => listPendingLinks(signal),
  })
  const decide = useMutation({
    mutationFn: ({ link, decision, note }: { link: PatientLink; decision: 'approve' | 'reject'; note: string }) =>
      decideLink(link, decision, note, decision === 'approve' ? override.trim() : undefined),
    onSuccess: () => {
      setDeciding(null)
      setOverride('')
      invalidate()
    },
  })
  const needsOverride = deciding?.decision === 'approve' && deciding.link.identifier_conflicts.length > 0

  return (
    <Stack>
      <Title order={2} size="h3">
        Links waiting for approval
      </Title>
      {decide.isError && <ErrorState error={decide.error} />}
      <QueryState
        query={links}
        loadingLabel="Loading link requests…"
        isEmpty={(page) => page.items.length === 0}
        empty={<EmptyState title="No link requests">No links are waiting for approval.</EmptyState>}
      >
        {(page) => (
          <Stack>
            {page.items.map((link) => {
              const own = link.requested_by === me.id
              return (
                <Card
                  key={link.id}
                  withBorder
                  component="article"
                  aria-label={`Link request ${link.retired.patient_number}`}
                >
                  <Stack gap="sm">
                    <Group gap="xs">
                      <Badge variant="light" leftSection={<IconLink size={12} aria-hidden />}>
                        {LINK_STATUS_LABEL[link.status]}
                      </Badge>
                      <Text size="sm" c="dimmed">
                        Requested {formatDateTime(link.requested_at)}
                      </Text>
                    </Group>
                    <Text size="sm">Reason given: {link.request_reason}</Text>
                    <SimpleGrid cols={{ base: 1, sm: 2 }}>
                      <LinkCell label="Duplicate (will be retired)" patient={link.retired} />
                      <LinkCell label="Record kept in use" patient={link.surviving} />
                    </SimpleGrid>
                    {link.identifier_conflicts.length > 0 && (
                      <Alert
                        color="orange"
                        variant="light"
                        icon={<IconAlertTriangle aria-hidden />}
                        title="Checked ID documents differ"
                      >
                        Both records have a checked{' '}
                        {link.identifier_conflicts.map((t) => IDENTIFIER_LABEL[t]).join(', ')} with different numbers.
                        These may be different people. Approve only with an override reason.
                      </Alert>
                    )}
                    <Group>
                      <Button disabled={own} onClick={() => setDeciding({ link, decision: 'approve' })}>
                        Approve link
                      </Button>
                      <Button
                        variant="default"
                        disabled={own}
                        onClick={() => setDeciding({ link, decision: 'reject' })}
                      >
                        Reject
                      </Button>
                      {own && (
                        <Text size="sm" c="dimmed">
                          You requested this link, so another person must decide it.
                        </Text>
                      )}
                    </Group>
                  </Stack>
                </Card>
              )
            })}
          </Stack>
        )}
      </QueryState>
      <ConfirmDialog
        opened={deciding !== null}
        title={deciding?.decision === 'approve' ? 'Approve this link?' : 'Reject this link?'}
        confirmLabel={deciding?.decision === 'approve' ? 'Approve link' : 'Reject link'}
        requireReason
        busy={decide.isPending}
        confirmDisabled={needsOverride && override.trim() === ''}
        onConfirm={(note) => deciding && decide.mutate({ ...deciding, note: note ?? '' })}
        onCancel={() => {
          setDeciding(null)
          setOverride('')
        }}
      >
        <Stack gap="sm">
          <Text size="sm">
            {deciding?.decision === 'approve'
              ? 'The duplicate record will be retired and point to the record kept in use. It can be reversed later.'
              : 'Both records stay in use. Say why this is not the same person or why the link is wrong.'}
          </Text>
          {needsOverride && (
            <Textarea
              label="Override reason (ID documents differ)"
              required
              value={override}
              onChange={(e) => setOverride(e.currentTarget.value)}
              maxLength={500}
            />
          )}
        </Stack>
      </ConfirmDialog>
    </Stack>
  )
}

function LinkCell({ label, patient }: { label: string; patient: PatientSummary }) {
  return (
    <Card withBorder padding="sm" bg="var(--gcare-page)">
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Anchor component={Link} to={`/patients/${patient.id}`} fw={600}>
        {displayName(patient)}
      </Anchor>
      <Text size="sm">{patient.patient_number}</Text>
      <Text size="sm">
        Born {birthLabel(patient)} · {SEX_LABEL[patient.sex]}
      </Text>
    </Card>
  )
}

/** Banner on a retired record: send staff to the record in use. */
export function LinkedNotice({ patient }: { patient: PatientDetail }) {
  if (patient.status !== 'LINKED' || !patient.linked_to) return null
  return (
    <Alert
      color="orange"
      variant="light"
      icon={<IconLink aria-hidden />}
      title="This record is no longer in use"
      role="status"
    >
      It was linked as a duplicate of{' '}
      <Anchor component={Link} to={`/patients/${patient.linked_to.id}`} fw={600}>
        {patient.linked_to.patient_number}
      </Anchor>
      . Use that record. Nothing here was deleted.
    </Alert>
  )
}

/** Link history of one record, with "reverse link" for approvers. */
export function LinkHistory({ patientId }: { patientId: string }) {
  const me = useCurrentMe()
  const invalidate = useInvalidateLinks()
  const canReverse = hasPermission(me, Permission.PATIENT_LINK_APPROVE)
  const [reversing, setReversing] = useState<PatientLink | null>(null)
  const links = useQuery({
    queryKey: ['patient-links', 'patient', patientId],
    queryFn: ({ signal }) => getPatientLinks(patientId, signal),
  })
  const unlink = useMutation({
    mutationFn: ({ link, reason }: { link: PatientLink; reason: string }) => unlinkPatients(link, reason),
    onSuccess: () => {
      setReversing(null)
      invalidate()
    },
  })

  if (links.data && links.data.length === 0) return null
  return (
    <Card withBorder>
      <Title order={2} size="h4" mb="xs">
        Linked records
      </Title>
      {unlink.isError && <ErrorState error={unlink.error} />}
      <QueryState query={links} loadingLabel="Loading linked records…">
        {(rows) => (
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th scope="col">Duplicate</Table.Th>
                <Table.Th scope="col">Kept in use</Table.Th>
                <Table.Th scope="col">Status</Table.Th>
                <Table.Th scope="col">Reason</Table.Th>
                <Table.Th scope="col">
                  <VisuallyHidden>Actions</VisuallyHidden>
                </Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((l) => (
                <Table.Tr key={l.id}>
                  <Table.Td>{l.retired.patient_number}</Table.Td>
                  <Table.Td>{l.surviving.patient_number}</Table.Td>
                  <Table.Td>{LINK_STATUS_LABEL[l.status]}</Table.Td>
                  <Table.Td>{l.unlink_reason ?? l.decision_note ?? l.request_reason}</Table.Td>
                  <Table.Td>
                    {canReverse && l.status === 'APPROVED' && (
                      <Button size="xs" variant="default" color="red" onClick={() => setReversing(l)}>
                        Reverse link
                      </Button>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
      <ConfirmDialog
        opened={reversing !== null}
        title="Reverse this link?"
        confirmLabel="Reverse link"
        danger
        requireReason
        busy={unlink.isPending}
        onConfirm={(reason) => reversing && unlink.mutate({ link: reversing, reason: reason ?? '' })}
        onCancel={() => setReversing(null)}
      >
        Both records will be in use again. The link and its reversal stay in the history.
      </ConfirmDialog>
    </Card>
  )
}
