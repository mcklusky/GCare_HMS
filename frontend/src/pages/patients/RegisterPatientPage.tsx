import {
  ActionIcon,
  Alert,
  Anchor,
  Button,
  Card,
  Checkbox,
  Group,
  List,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core'
import { IconAlertTriangle, IconPlus, IconTrash } from '@tabler/icons-react'
import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiRequestError } from '../../api/client'
import {
  EVIDENCE_LABEL,
  IDENTIFIER_LABEL,
  SEX_LABEL,
  SOURCE_LABEL,
  birthLabel,
  displayName,
  duplicateCandidatesOf,
  registerPatient,
  type DuplicateCandidate,
  type IdentifierInput,
  type IdentifierType,
  type RegisterRequest,
  type RegistrationSource,
  type Sex,
} from '../../api/patients'
import { useFacilityContext } from '../../auth/facilityContextValue'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { BandBadge } from '../../components/PatientBadges'
import { ErrorState, PageTitle } from '../../components/states'

interface FormState {
  facilityId: string | null
  source: RegistrationSource
  familyName: string
  givenNames: string
  birthDate: string
  estimated: boolean
  sex: Sex
  phone: string
  identifiers: IdentifierInput[]
}

const MAX_IDENTIFIERS = 5

/**
 * Patient registration with the duplicate check (FR-003, FR-004; policy §3). Every field except
 * facility and source is optional so an unknown patient can always be registered. Form input
 * stays in memory only.
 */
export function RegisterPatientPage() {
  const navigate = useNavigate()
  const { facilities, current } = useFacilityContext()
  const [form, setForm] = useState<FormState>({
    facilityId: current?.id ?? null,
    source: 'WALK_IN',
    familyName: '',
    givenNames: '',
    birthDate: '',
    estimated: false,
    sex: 'UNKNOWN',
    phone: '',
    identifiers: [],
  })
  const [candidates, setCandidates] = useState<DuplicateCandidate[] | null>(null)
  const [confirming, setConfirming] = useState(false)

  const mutation = useMutation({
    mutationFn: registerPatient,
    onSuccess: (patient) =>
      navigate(`/patients/${patient.id}`, { state: { registeredNumber: patient.patient_number } }),
    onError: (error) => {
      const found = duplicateCandidatesOf(error)
      if (found) setCandidates(found)
    },
  })

  const update = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setForm((f) => ({ ...f, [key]: value }))
    setCandidates(null) // details changed: the duplicate check must run again
  }

  const request = (review?: RegisterRequest['duplicate_review']): RegisterRequest => ({
    registration_facility_id: form.facilityId ?? '',
    registration_source: form.source,
    family_name: form.familyName || undefined,
    given_names: form.givenNames || undefined,
    birth_date: form.birthDate || undefined,
    birth_date_estimated: form.estimated,
    sex: form.sex,
    phone: form.phone || undefined,
    identifiers: form.identifiers.filter((i) => i.value.trim() !== ''),
    duplicate_review: review,
  })

  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    mutation.mutate(request())
  }

  const registerDespiteCandidates = (reason: string | null) => {
    setConfirming(false)
    mutation.mutate(
      request({ reviewed_candidate_ids: candidates!.map((c) => c.patient.id), reason: reason ?? undefined }),
    )
  }

  const fieldError = (field: string) =>
    mutation.error instanceof ApiRequestError ? mutation.error.fieldErrors[field]?.[0] : undefined
  const otherError =
    mutation.isError && !duplicateCandidatesOf(mutation.error) && !(mutation.error instanceof ApiRequestError && mutation.error.status === 400)
  const anyLikely = candidates?.some((c) => c.band === 'LIKELY') ?? false

  return (
    <Stack maw={900}>
      <Anchor component={Link} to="/patients" size="sm">
        ← Find a patient
      </Anchor>
      <PageTitle>Register a new patient</PageTitle>
      <Text size="sm" c="dimmed">
        Search first: most patients are already registered. Unknown details can be left empty when care must
        proceed; the record is then marked “details incomplete”.
      </Text>

      <form onSubmit={onSubmit} aria-label="Patient registration">
        <Stack>
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            <Select
              label="Registering facility"
              required
              data={facilities.map((f) => ({ value: f.id, label: f.name }))}
              value={form.facilityId}
              onChange={(v) => update('facilityId', v)}
              allowDeselect={false}
            />
            <Select
              label="How the patient arrived"
              required
              data={Object.entries(SOURCE_LABEL).map(([value, label]) => ({ value, label }))}
              value={form.source}
              onChange={(v) => v && update('source', v as RegistrationSource)}
              allowDeselect={false}
            />
            <TextInput
              label="Family name"
              value={form.familyName}
              onChange={(e) => update('familyName', e.currentTarget.value)}
              maxLength={100}
              error={fieldError('familyName')}
            />
            <TextInput
              label="Given names"
              value={form.givenNames}
              onChange={(e) => update('givenNames', e.currentTarget.value)}
              maxLength={150}
            />
            <TextInput
              label="Date of birth"
              type="date"
              value={form.birthDate}
              onChange={(e) => update('birthDate', e.currentTarget.value)}
              error={fieldError('birth_date')}
            />
            <Checkbox
              mt={{ sm: 'xl' }}
              label="Date of birth is estimated"
              checked={form.estimated}
              onChange={(e) => update('estimated', e.currentTarget.checked)}
            />
            <Select
              label="Sex"
              data={Object.entries(SEX_LABEL).map(([value, label]) => ({ value, label }))}
              value={form.sex}
              onChange={(v) => v && update('sex', v as Sex)}
              allowDeselect={false}
            />
            <TextInput
              label="Phone"
              type="tel"
              value={form.phone}
              onChange={(e) => update('phone', e.currentTarget.value)}
              maxLength={30}
            />
          </SimpleGrid>

          <Stack gap="xs">
            <Text fw={600}>ID documents</Text>
            {form.identifiers.map((id, index) => (
              <Group key={index} align="flex-end" wrap="wrap">
                <Select
                  label="Type"
                  data={Object.entries(IDENTIFIER_LABEL).map(([value, label]) => ({ value, label }))}
                  value={id.type}
                  onChange={(v) =>
                    v &&
                    update(
                      'identifiers',
                      form.identifiers.map((x, i) => (i === index ? { ...x, type: v as IdentifierType } : x)),
                    )
                  }
                  allowDeselect={false}
                  w={180}
                />
                <TextInput
                  label="Number"
                  value={id.value}
                  maxLength={50}
                  onChange={(e) => {
                    const value = e.currentTarget.value
                    update('identifiers', form.identifiers.map((x, i) => (i === index ? { ...x, value } : x)))
                  }}
                />
                <Checkbox
                  mb={8}
                  label="Seen and checked"
                  checked={id.verified}
                  onChange={(e) => {
                    const verified = e.currentTarget.checked
                    update('identifiers', form.identifiers.map((x, i) => (i === index ? { ...x, verified } : x)))
                  }}
                />
                <ActionIcon
                  variant="default"
                  mb={4}
                  aria-label={`Remove ID document ${index + 1}`}
                  onClick={() => update('identifiers', form.identifiers.filter((_, i) => i !== index))}
                >
                  <IconTrash size={16} aria-hidden />
                </ActionIcon>
              </Group>
            ))}
            {form.identifiers.length < MAX_IDENTIFIERS && (
              <Group>
                <Button
                  variant="default"
                  size="xs"
                  leftSection={<IconPlus size={14} aria-hidden />}
                  onClick={() =>
                    update('identifiers', [...form.identifiers, { type: 'NATIONAL_ID', value: '', verified: false }])
                  }
                >
                  Add ID document
                </Button>
              </Group>
            )}
          </Stack>

          {mutation.error instanceof ApiRequestError && mutation.error.status === 400 && (
            <Alert color="red" variant="light" role="alert" title="Check the form">
              <List size="sm">
                {Object.entries(mutation.error.fieldErrors).map(([field, messages]) => (
                  <List.Item key={field}>
                    {field}: {messages.join(', ')}
                  </List.Item>
                ))}
              </List>
            </Alert>
          )}
          {otherError && <ErrorState error={mutation.error} />}

          {!candidates && (
            <Group>
              <Button type="submit" loading={mutation.isPending} disabled={!form.facilityId}>
                Check and register
              </Button>
            </Group>
          )}
        </Stack>
      </form>

      {candidates && (
        <Card withBorder component="section" aria-labelledby="candidates-title">
          <Stack>
            <Group gap="xs">
              <IconAlertTriangle aria-hidden />
              <Title order={2} size="h3" id="candidates-title">
                This patient may already be registered
              </Title>
            </Group>
            <Text>
              Compare each record with the patient. If one is the same person, open it instead of registering a new
              record.
            </Text>
            {candidates.map((c) => (
              <Card key={c.patient.id} withBorder padding="sm" aria-label={`Candidate ${c.patient.patient_number}`}>
                <Group justify="space-between" align="flex-start">
                  <Stack gap={2}>
                    <Group gap="xs">
                      <Text fw={600}>{displayName(c.patient)}</Text>
                      <BandBadge band={c.band} />
                    </Group>
                    <Text size="sm">
                      {c.patient.patient_number} · Born {birthLabel(c.patient)} · {SEX_LABEL[c.patient.sex]}
                    </Text>
                    <Text size="sm" c="dimmed">
                      Why suggested: {c.evidence.map((e) => EVIDENCE_LABEL[e]).join(', ')}
                    </Text>
                  </Stack>
                  <Button component={Link} to={`/patients/${c.patient.id}`} variant="light" size="xs">
                    This is the patient: open record
                  </Button>
                </Group>
              </Card>
            ))}
            <Group>
              <Button color={anyLikely ? 'red' : undefined} variant="outline" onClick={() => setConfirming(true)}>
                None of these is the patient: register new record
              </Button>
            </Group>
          </Stack>
        </Card>
      )}

      <ConfirmDialog
        opened={confirming}
        title="Register a new record?"
        confirmLabel="Register new record"
        danger={anyLikely}
        requireReason={anyLikely}
        busy={mutation.isPending}
        onConfirm={registerDespiteCandidates}
        onCancel={() => setConfirming(false)}
      >
        {anyLikely
          ? 'At least one record is a likely duplicate. Explain why this is a different person (for example, ID document checked). A supervisor will review it.'
          : 'Confirm you compared every suggested record and none of them is this patient.'}
      </ConfirmDialog>
    </Stack>
  )
}
