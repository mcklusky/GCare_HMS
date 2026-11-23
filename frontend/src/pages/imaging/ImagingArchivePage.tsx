import { Alert, Anchor, Badge, Button, Card, Group, Stack, Table, Text, TextInput, Title } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { findArchiveStudies, getArchiveStatus, safeViewerHref } from '../../api/imaging'
import { EmptyState, PageHeader, QueryState } from '../../components/states'

/** Imaging archive connection check and study look-up for the evaluation and support (task 6.1; docs/24). */
export function ImagingArchivePage() {
  const status = useQuery({ queryKey: ['archive-status'], queryFn: ({ signal }) => getArchiveStatus(signal) })
  const [input, setInput] = useState('')
  const [accession, setAccession] = useState<string | null>(null)
  const studies = useQuery({
    queryKey: ['archive-studies', accession],
    queryFn: ({ signal }) => findArchiveStudies(accession!, signal),
    enabled: accession !== null,
    retry: false,
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    setAccession(input.trim())
  }

  return (
    <Stack>
      <PageHeader
        title="Imaging archive"
        description="The archive and viewer are external and not selected yet. The HMS reads study details only (no images) and opens the archive’s viewer by link. Every look-up is recorded in the audit trail."
      />
      <QueryState query={status} loadingLabel="Checking the archive…">
        {(s) => (
          <Card withBorder>
            <Group>
              <Badge size="lg" variant="light" color={!s.configured ? 'gray' : s.reachable ? 'green' : 'red'}>
                {!s.configured ? 'Not configured' : s.reachable ? 'Answering' : 'Not answering'}
              </Badge>
              <Text size="sm">
                {s.message}
                {s.configured && ` · ${s.latency_ms} ms`} · viewer link{' '}
                {s.viewer_configured ? 'configured' : 'not configured'}
              </Text>
              <Button variant="default" size="xs" onClick={() => void status.refetch()}>
                Check again
              </Button>
            </Group>
          </Card>
        )}
      </QueryState>
      <Card withBorder component="form" aria-label="Find studies by accession number" onSubmit={submit}>
        <Title order={2} size="h4" mb="xs">
          Find studies by accession number
        </Title>
        <Group align="end">
          <TextInput
            label="Accession number"
            maxLength={16}
            value={input}
            onChange={(e) => setInput(e.currentTarget.value)}
          />
          <Button type="submit" disabled={!input.trim()}>
            Find
          </Button>
        </Group>
      </Card>
      {accession !== null && (
        <QueryState query={studies} loadingLabel="Asking the archive…">
          {(list) =>
            list.length === 0 ? (
              <EmptyState title="No study with this accession number" />
            ) : (
              <Card withBorder>
                {list.length > 1 && (
                  <Alert color="orange" mb="sm" title="More than one study">
                    Several studies carry this accession number. They need review before linking.
                  </Alert>
                )}
                <Table aria-label="Studies in the archive">
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th scope="col">Date</Table.Th>
                      <Table.Th scope="col">Modality</Table.Th>
                      <Table.Th scope="col">Description</Table.Th>
                      <Table.Th scope="col">Patient ID on study</Table.Th>
                      <Table.Th scope="col">Series / images</Table.Th>
                      <Table.Th scope="col">Viewer</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {list.map((s) => (
                      <Table.Tr key={s.study_instance_uid}>
                        <Table.Td>{s.study_date ?? '—'}</Table.Td>
                        <Table.Td>{s.modalities.join(', ') || '—'}</Table.Td>
                        <Table.Td>{s.description ?? '—'}</Table.Td>
                        <Table.Td>{s.patient_id ?? '—'}</Table.Td>
                        <Table.Td>
                          {s.series_count ?? '?'} / {s.instance_count ?? '?'}
                        </Table.Td>
                        <Table.Td>
                          {safeViewerHref(s.viewer_url) ? (
                            <Anchor href={safeViewerHref(s.viewer_url)!} target="_blank" rel="noopener noreferrer">
                              Open in viewer
                            </Anchor>
                          ) : (
                            '—'
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
      )}
    </Stack>
  )
}
