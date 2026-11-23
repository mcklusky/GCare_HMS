import { Button, Card, Group, Stack, Table, Text, TextInput, Title } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { decideStudy, getUnlinkedStudies, proposeLink, type Study } from '../../api/imagingWork'
import { useCurrentMe } from '../../auth/MeContext'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, PageHeader, QueryState } from '../../components/states'

/** Studies that did not link automatically: propose, approve (another person) or send back (task 6.3; docs/25). */
export function StudyQueuePage() {
  const me = useCurrentMe()
  const queryClient = useQueryClient()
  const [proposing, setProposing] = useState<Study | null>(null)
  const [deciding, setDeciding] = useState<{ study: Study; decision: 'approve' | 'send-back' } | null>(null)
  const queue = useQuery({ queryKey: ['imaging-queue'], queryFn: ({ signal }) => getUnlinkedStudies(signal) })
  const done = () => void queryClient.invalidateQueries({ queryKey: ['imaging-queue'] })
  const decide = useMutation({
    mutationFn: ({ study, decision, note }: { study: Study; decision: 'approve' | 'send-back'; note: string }) =>
      decideStudy(study, decision, note),
    onSuccess: () => {
      setDeciding(null)
      done()
    },
    onError: done,
  })

  return (
    <Stack>
      <PageHeader
        title="Studies to review"
        description="These studies are in the archive but did not link to an order by themselves. Check the patient and the examination against the archive before proposing a link; a second person approves it."
      />
      <QueryState query={queue} loadingLabel="Loading studies…">
        {(studies) =>
          studies.length === 0 ? (
            <EmptyState title="Nothing to review" />
          ) : (
            <Card withBorder>
              <Table aria-label="Studies waiting for review">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th scope="col">Study</Table.Th>
                    <Table.Th scope="col">Accession on study</Table.Th>
                    <Table.Th scope="col">Patient ID on study</Table.Th>
                    <Table.Th scope="col">Why it waits</Table.Th>
                    <Table.Th scope="col">Action</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {studies.map((s) => (
                    <Table.Tr key={s.id}>
                      <Table.Td>
                        {s.modalities.join(', ') || '—'} · {s.study_date ?? 'no date'}
                        <Text size="xs">
                          {s.description ?? ''} ({s.instance_count ?? '?'} images)
                        </Text>
                      </Table.Td>
                      <Table.Td>{s.accession_number ?? '—'}</Table.Td>
                      <Table.Td>{s.dicom_patient_id ?? '—'}</Table.Td>
                      <Table.Td>
                        {s.status === 'PROPOSED'
                          ? `Link to ${s.proposed_accession_number} proposed by ${s.proposed_by?.display_name}: “${s.proposal_note}”`
                          : s.unmatched_reason}
                      </Table.Td>
                      <Table.Td>
                        {s.status === 'UNMATCHED' && (
                          <Button size="xs" onClick={() => setProposing(s)}>
                            Propose link
                          </Button>
                        )}
                        {s.status === 'PROPOSED' && (
                          <Group gap="xs">
                            {s.proposed_by?.id === me.id ? (
                              <Text size="xs">Your proposal: another person approves.</Text>
                            ) : (
                              <Button
                                size="xs"
                                onClick={() => (decide.reset(), setDeciding({ study: s, decision: 'approve' }))}
                              >
                                Approve
                              </Button>
                            )}
                            <Button
                              size="xs"
                              variant="default"
                              onClick={() => (decide.reset(), setDeciding({ study: s, decision: 'send-back' }))}
                            >
                              Send back
                            </Button>
                          </Group>
                        )}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Card>
          )
        }
      </QueryState>
      {proposing && <ProposeCard key={proposing.id} study={proposing} onDone={() => (setProposing(null), done())} />}
      <ConfirmDialog
        opened={deciding !== null}
        title={deciding?.decision === 'approve' ? 'Approve this link?' : 'Send this proposal back?'}
        confirmLabel={deciding?.decision === 'approve' ? 'Approve' : 'Send back'}
        requireReason
        busy={decide.isPending}
        error={decide.error}
        onConfirm={(note) => deciding && decide.mutate({ ...deciding, note: note ?? '' })}
        onCancel={() => setDeciding(null)}
      >
        {deciding?.decision === 'approve'
          ? 'Check in the archive that the images belong to this patient and examination.'
          : 'The study returns to the queue.'}
      </ConfirmDialog>
    </Stack>
  )
}

function ProposeCard({ study, onDone }: { study: Study; onDone: () => void }) {
  const [accession, setAccession] = useState(study.accession_number ?? '')
  const [note, setNote] = useState('')
  const propose = useMutation({
    mutationFn: () => proposeLink(study, accession.trim(), note.trim()),
    onSuccess: onDone,
  })
  return (
    <Card
      withBorder
      component="form"
      aria-label="Propose a link"
      onSubmit={(e) => (e.preventDefault(), propose.mutate())}
    >
      <Title order={2} size="h4" mb="xs">
        Propose a link
      </Title>
      <Text size="sm" mb="sm">
        Study from {study.study_date ?? 'an unknown date'} with patient ID {study.dicom_patient_id ?? 'none'}. Enter the
        accession number of the order it belongs to.
      </Text>
      <Group align="end">
        <TextInput
          label="Accession number of the order"
          required
          maxLength={16}
          value={accession}
          onChange={(e) => setAccession(e.currentTarget.value)}
        />
        <TextInput
          label="Why this is the right order"
          required
          maxLength={500}
          value={note}
          onChange={(e) => setNote(e.currentTarget.value)}
          w={360}
        />
        <Button type="submit" disabled={!accession.trim() || !note.trim()} loading={propose.isPending}>
          Propose
        </Button>
      </Group>
      {propose.isError && <ErrorState error={propose.error} />}
    </Card>
  )
}
