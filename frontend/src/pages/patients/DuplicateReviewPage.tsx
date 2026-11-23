import { Anchor, Badge, Button, Card, Group, SimpleGrid, Stack, Text } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import {
  SEX_LABEL,
  birthLabel,
  displayName,
  listDuplicateFlags,
  markNotDuplicate,
  type DuplicateFlag,
  type PatientSummary,
} from '../../api/patients'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, PageTitle, QueryState } from '../../components/states'

const ORIGIN_LABEL: Record<DuplicateFlag['origin'], string> = {
  REGISTERED_DESPITE_MATCH: 'Registered despite a likely duplicate',
  STAFF_REPORT: 'Reported by staff',
}

/** Duplicate review queue for supervisors (policy §3). Linking records comes in task 2.3. */
export function DuplicateReviewPage() {
  const queryClient = useQueryClient()
  const [deciding, setDeciding] = useState<DuplicateFlag | null>(null)
  const flags = useQuery({ queryKey: ['duplicate-flags'], queryFn: ({ signal }) => listDuplicateFlags(signal) })
  const decide = useMutation({
    mutationFn: ({ flag, note }: { flag: DuplicateFlag; note: string }) => markNotDuplicate(flag, note),
    onSuccess: () => {
      setDeciding(null)
      void queryClient.invalidateQueries({ queryKey: ['duplicate-flags'] })
    },
  })

  return (
    <Stack>
      <PageTitle>Duplicate review</PageTitle>
      <Text size="sm" c="dimmed">
        Compare each pair. Decide only after checking ID documents or asking the patient. You cannot decide items you
        raised yourself.
      </Text>
      {decide.isError && <ErrorState error={decide.error} />}
      <QueryState
        query={flags}
        loadingLabel="Loading review items…"
        isEmpty={(page) => page.items.length === 0}
        empty={<EmptyState title="Nothing to review">No possible duplicates are waiting.</EmptyState>}
      >
        {(page) => (
          <Stack>
            {page.items.map((flag) => (
              <Card key={flag.id} withBorder component="article" aria-label={`Review item ${flag.patient.patient_number}`}>
                <Stack gap="sm">
                  <Group gap="xs">
                    <Badge variant="light">{ORIGIN_LABEL[flag.origin]}</Badge>
                    <Text size="sm">Match score {flag.score}</Text>
                    <Text size="sm" c="dimmed">
                      Raised {new Date(flag.created_at).toLocaleString()}
                    </Text>
                  </Group>
                  <Text size="sm">Reason given: {flag.reason}</Text>
                  <SimpleGrid cols={{ base: 1, sm: 2 }}>
                    <PatientCell patient={flag.patient} />
                    <PatientCell patient={flag.candidate} />
                  </SimpleGrid>
                  <Group>
                    <Button variant="default" onClick={() => setDeciding(flag)}>
                      Different people
                    </Button>
                    <Text size="sm" c="dimmed">
                      Linking records that are the same person comes in the next release.
                    </Text>
                  </Group>
                </Stack>
              </Card>
            ))}
          </Stack>
        )}
      </QueryState>
      <ConfirmDialog
        opened={deciding !== null}
        title="Mark as different people?"
        confirmLabel="Mark as different people"
        requireReason
        busy={decide.isPending}
        onConfirm={(note) => deciding && decide.mutate({ flag: deciding, note: note ?? '' })}
        onCancel={() => setDeciding(null)}
      >
        The two records stay separate and will not be suggested as duplicates again. Say how you checked.
      </ConfirmDialog>
    </Stack>
  )
}

function PatientCell({ patient }: { patient: PatientSummary }) {
  return (
    <Card withBorder padding="sm" bg="gray.0">
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
