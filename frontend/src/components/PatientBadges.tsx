import { Badge, Group, Paper, Stack, Text } from '@mantine/core'
import { IconAlertTriangle, IconCopy, IconId, IconLink } from '@tabler/icons-react'
import type { Band, PatientSummary } from '../api/patients'
import { SEX_LABEL, birthLabel, displayName } from '../api/patients'

/** Duplicate band with text and icon (never colour alone). */
export function BandBadge({ band }: { band: Band }) {
  return band === 'LIKELY' ? (
    <Badge color="red" variant="light" leftSection={<IconAlertTriangle size={12} aria-hidden />}>
      Likely duplicate
    </Badge>
  ) : (
    <Badge color="orange" variant="light" leftSection={<IconCopy size={12} aria-hidden />}>
      Possible duplicate
    </Badge>
  )
}

export function IncompleteBadge() {
  return (
    <Badge color="yellow" variant="light" leftSection={<IconAlertTriangle size={12} aria-hidden />}>
      Details incomplete
    </Badge>
  )
}

/**
 * Patient identity banner (02-UI-UX): name, number, birth date/age, sex, status. Shown at the top
 * of every patient page so staff always see whose record is open.
 */
export function PatientBanner({ patient }: { patient: PatientSummary }) {
  return (
    <Paper
      withBorder
      p="md"
      aria-label="Patient identity"
      component="section"
      style={{ borderLeft: '4px solid var(--mantine-primary-color-filled)' }}
    >
      <Stack gap={4}>
        <Group gap="sm">
          <Text fw={700} size="lg">
            {displayName(patient)}
          </Text>
          {patient.status === 'LINKED' && (
            <Badge color="gray" leftSection={<IconLink size={12} aria-hidden />}>
              Linked to another record
            </Badge>
          )}
          {patient.details_incomplete && <IncompleteBadge />}
        </Group>
        <Group gap="lg">
          <Group gap={4}>
            <IconId size={16} aria-hidden />
            <Text>
              Patient number <strong>{patient.patient_number}</strong>
            </Text>
          </Group>
          <Text>Born: {birthLabel(patient)}</Text>
          <Text>Sex: {SEX_LABEL[patient.sex]}</Text>
        </Group>
      </Stack>
    </Paper>
  )
}
