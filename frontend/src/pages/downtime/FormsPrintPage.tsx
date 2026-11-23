import { Anchor, Box, Button, Group, Stack, Text, Title } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router'
import { formTypeLabel, getBatch, type FormBatch, type FormType } from '../../api/downtime'
import { QueryState } from '../../components/states'

/** Blank fields printed on each paper form; they match what is keyed afterwards (docs/22). */
const FIELDS: Record<FormType, string[]> = {
  REGISTRATION: [
    'Family name',
    'Given names',
    'Date of birth (tick if estimated ☐)',
    'Sex: ☐ Female ☐ Male ☐ Other ☐ Unknown',
    'Phone',
    'Existing patient number (if known)',
    'Arrived: ☐ Walk-in ☐ Referral ☐ Emergency ☐ Other',
    'Department',
    'Visit: ☐ New visit ☐ Follow-up',
    'Arrival date and time',
  ],
  TRIAGE: [
    'Date and time taken',
    'Temperature (°C)',
    'Pulse (bpm)',
    'Respiratory rate (/min)',
    'Blood pressure (systolic / diastolic mmHg)',
    'SpO₂ (%)',
    'Weight (kg)          Height (cm)',
    'Pain (0–10)',
    'Urgency level',
    'Allergies',
    'Notes',
  ],
  ORDER: [
    'Date and time ordered',
    'Test, imaging or procedure',
    'Priority: ☐ Routine ☐ Urgent',
    'Indication',
    'Ordered by (name and signature)',
  ],
  PAYMENT: [
    'Date and time received',
    'Amount and currency',
    'Payment method',
    'Received by (name and signature)',
    'Patient copy given ☐',
  ],
}

/** Printable numbered downtime forms of one issued block, one per page. */
export function FormsPrintPage() {
  const { id = '' } = useParams()
  const batch = useQuery({ queryKey: ['downtime-batch', id], queryFn: ({ signal }) => getBatch(id, signal) })
  return (
    <Stack>
      <style>{'@media print { header, nav, .no-print { display: none !important; } }'}</style>
      <Group className="no-print">
        <Anchor component={Link} to="/downtime" size="sm">
          ← Downtime
        </Anchor>
        <Button onClick={() => window.print()}>Print</Button>
      </Group>
      <QueryState query={batch} loadingLabel="Loading forms…">
        {(b) => <Forms batch={b} />}
      </QueryState>
    </Stack>
  )
}

function Forms({ batch }: { batch: FormBatch }) {
  const numbers = Array.from({ length: batch.last_number - batch.first_number + 1 }, (_, i) => batch.first_number + i)
  const prefix = batch.first_label.split('-')[0]
  return (
    <>
      <Text className="no-print" size="sm">
        {formTypeLabel(batch.form_type)} forms {batch.first_label} – {batch.last_label} for {batch.facility_name}.
      </Text>
      {numbers.map((n) => {
        const label = `${prefix}-${String(n).padStart(6, '0')}`
        return (
          <Box
            key={n}
            component="section"
            aria-label={`Form ${label}`}
            p="md"
            style={{ border: '1px solid #444', breakAfter: 'page' }}
          >
            <Group justify="space-between" align="start">
              <div>
                <Text size="sm">
                  {batch.facility_name} ({batch.facility_code}) · GCare HMS downtime form
                </Text>
                <Title order={2} size="h3">
                  {formTypeLabel(batch.form_type)}
                </Title>
              </div>
              <Text fw={700} size="xl">
                {label}
              </Text>
            </Group>
            {batch.form_type !== 'REGISTRATION' && <FieldLine label="Patient's registration form number (REG-…)" />}
            {FIELDS[batch.form_type].map((f) => (
              <FieldLine key={f} label={f} />
            ))}
            <Text size="xs" mt="md">
              Keep this form for reconciliation. After the outage it is keyed under its number and checked by a second
              person where required.
            </Text>
          </Box>
        )
      })}
    </>
  )
}

function FieldLine({ label }: { label: string }) {
  return (
    <Box mt="sm" pb={4} style={{ borderBottom: '1px solid #888', minHeight: 36 }}>
      <Text size="sm">{label}</Text>
    </Box>
  )
}
