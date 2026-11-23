import { Alert, Anchor, Button, Group, Select, SimpleGrid, Stack, Table, Text, TextInput } from '@mantine/core'
import { IconSearch, IconUserPlus } from '@tabler/icons-react'
import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useLocation } from 'react-router'
import { ApiRequestError } from '../../api/client'
import {
  IDENTIFIER_LABEL,
  SEX_LABEL,
  birthLabel,
  displayName,
  searchPatients,
  type IdentifierType,
  type SearchCriteria,
  type Sex,
} from '../../api/patients'
import { Permission } from '../../api/types'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { IncompleteBadge } from '../../components/PatientBadges'
import { EmptyState, ErrorState, LoadingState, PageHeader } from '../../components/states'
import { takeQuickSearch } from '../../shell/quickSearch'

const EMPTY: SearchCriteria = {
  number: '',
  name: '',
  birth_year: '',
  sex: '',
  phone: '',
  identifier_type: '',
  identifier_value: '',
}

/**
 * Patient search (FR-003). Criteria live in memory only, never in the URL or browser storage, so
 * names and numbers do not end up in browser history.
 */
export function PatientSearchPage() {
  const me = useCurrentMe()
  const location = useLocation()
  // A number typed in the header's quick search arrives in memory (shell/quickSearch.ts).
  const [initial] = useState(() => {
    const n = takeQuickSearch()
    return n ? { ...EMPTY, number: n } : null
  })
  const [form, setForm] = useState<SearchCriteria>(initial ?? EMPTY)
  const [submitted, setSubmitted] = useState<SearchCriteria | null>(initial)
  const [seenKey, setSeenKey] = useState(location.key)
  if (location.key !== seenKey) {
    setSeenKey(location.key)
    const n = takeQuickSearch()
    if (n) {
      setForm({ ...EMPTY, number: n })
      setSubmitted({ ...EMPTY, number: n })
    }
  }

  const results = useQuery({
    queryKey: ['patients', 'search', submitted],
    queryFn: ({ signal }) => searchPatients(submitted!, signal),
    enabled: submitted !== null,
    retry: false,
  })

  const set = (key: keyof SearchCriteria) => (value: string | null) => setForm((f) => ({ ...f, [key]: value ?? '' }))

  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    setSubmitted({ ...form })
  }

  const fieldError = (field: string) =>
    results.error instanceof ApiRequestError ? results.error.fieldErrors[field]?.[0] : undefined
  const tooBroad = results.error instanceof ApiRequestError && results.error.code === 'SEARCH_TOO_BROAD'

  return (
    <Stack>
      <PageHeader
        title="Find a patient"
        description="Search before registering, so the same person never gets two records."
        actions={
          hasPermission(me, Permission.PATIENT_REGISTER) && (
            <Button component={Link} to="/patients/new" leftSection={<IconUserPlus size={18} aria-hidden />}>
              Register new patient
            </Button>
          )
        }
      />

      <form onSubmit={onSubmit} aria-label="Patient search">
        <Stack>
          <Text size="sm" c="dimmed">
            Search with a patient number, an ID document or a phone number, or with a name plus birth year or sex.
          </Text>
          <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }}>
            <TextInput
              label="Patient number"
              placeholder="P1234567-3"
              value={form.number}
              onChange={(e) => set('number')(e.currentTarget.value)}
              error={fieldError('number')}
            />
            <TextInput label="Name" value={form.name} onChange={(e) => set('name')(e.currentTarget.value)} />
            <TextInput
              label="Birth year"
              inputMode="numeric"
              maxLength={4}
              value={form.birth_year}
              onChange={(e) => set('birth_year')(e.currentTarget.value.replace(/\D/g, ''))}
            />
            <Select
              label="Sex"
              data={Object.entries(SEX_LABEL).map(([value, label]) => ({ value, label }))}
              value={form.sex || null}
              onChange={(v) => set('sex')((v as Sex) ?? '')}
              clearable
            />
            <TextInput
              label="Phone"
              type="tel"
              value={form.phone}
              onChange={(e) => set('phone')(e.currentTarget.value)}
              error={fieldError('phone')}
            />
            <Select
              label="ID document type"
              data={Object.entries(IDENTIFIER_LABEL).map(([value, label]) => ({ value, label }))}
              value={form.identifier_type || null}
              onChange={(v) => set('identifier_type')((v as IdentifierType) ?? '')}
              clearable
              error={fieldError('identifier_type')}
            />
            <TextInput
              label="ID document number"
              value={form.identifier_value}
              onChange={(e) => set('identifier_value')(e.currentTarget.value)}
            />
          </SimpleGrid>
          <Group>
            <Button type="submit" leftSection={<IconSearch size={18} aria-hidden />}>
              Search
            </Button>
            <Button
              variant="default"
              onClick={() => {
                setForm(EMPTY)
                setSubmitted(null)
              }}
            >
              Clear
            </Button>
          </Group>
        </Stack>
      </form>

      {submitted === null ? null : results.isPending ? (
        <LoadingState label="Searching…" />
      ) : tooBroad ? (
        <Alert color="blue" variant="light" role="alert" title="Add one more detail">
          {results.error?.message}
        </Alert>
      ) : results.isError ? (
        results.error instanceof ApiRequestError && results.error.status === 400 ? null : (
          <ErrorState error={results.error} onRetry={() => void results.refetch()} />
        )
      ) : results.data.items.length === 0 ? (
        <EmptyState title="No matching patients">
          Check the spelling, or search with another detail before registering a new patient.
        </EmptyState>
      ) : (
        <Table striped highlightOnHover>
          <Table.Caption>
            {results.data.total} matching patient{results.data.total === 1 ? '' : 's'}
            {results.data.total > results.data.items.length ? ` (showing ${results.data.items.length})` : ''}
          </Table.Caption>
          <Table.Thead>
            <Table.Tr>
              <Table.Th scope="col">Patient number</Table.Th>
              <Table.Th scope="col">Name</Table.Th>
              <Table.Th scope="col">Born</Table.Th>
              <Table.Th scope="col">Sex</Table.Th>
              <Table.Th scope="col">Notes</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {results.data.items.map((p) => (
              <Table.Tr key={p.id}>
                <Table.Td>{p.patient_number}</Table.Td>
                <Table.Td>
                  <Anchor component={Link} to={`/patients/${p.id}`}>
                    {displayName(p)}
                  </Anchor>
                </Table.Td>
                <Table.Td>{birthLabel(p)}</Table.Td>
                <Table.Td>{SEX_LABEL[p.sex]}</Table.Td>
                <Table.Td>
                  {p.details_incomplete && <IncompleteBadge />}
                  {p.status === 'LINKED' && <Text size="sm">Linked record</Text>}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  )
}
