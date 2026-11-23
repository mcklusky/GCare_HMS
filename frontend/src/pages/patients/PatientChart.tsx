import { Alert, Anchor, Badge, Card, List, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core'
import { IconAlertTriangle } from '@tabler/icons-react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { getChart } from '../../api/chart'
import { CERTAINTY_LABEL } from '../../api/clinical'
import { MEASURES, STATUS_LABEL, TYPE_LABEL } from '../../api/encounters'
import { CATEGORY_LABEL, ORDER_STATUS_LABEL, PRIORITY_LABEL, listPatientOrders } from '../../api/orders'
import { Permission } from '../../api/types'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { EmptyState, QueryState } from '../../components/states'
import { ImagingResultsSection } from '../imaging/ImagingResultsSection'

/**
 * Patient chart (task 3.4): visits, and, with permission, reported allergies, latest triage,
 * diagnoses and orders across visits. Read only; each item links to its visit.
 */
export function PatientChart({ patientId }: { patientId: string }) {
  const me = useCurrentMe()
  const chart = useQuery({ queryKey: ['chart', patientId], queryFn: ({ signal }) => getChart(patientId, signal) })

  return (
    <QueryState query={chart} loadingLabel="Loading chart…">
      {(c) => (
        <Stack>
          {c.reported_allergies && c.reported_allergies.length > 0 && (
            <Alert
              color="orange"
              variant="light"
              icon={<IconAlertTriangle aria-hidden />}
              title="Allergies reported at recent triage"
            >
              <List size="sm">
                {c.reported_allergies.map((a) => (
                  <List.Item key={`${a.encounter_id}-${a.recorded_at}`}>
                    {a.note} ({a.recorded_by.display_name}, {new Date(a.recorded_at).toLocaleDateString()})
                  </List.Item>
                ))}
              </List>
              <Text size="xs" mt={4}>
                As reported, not verified. Absence here does not mean the patient has no allergies.
              </Text>
            </Alert>
          )}
          <SimpleGrid cols={{ base: 1, md: 2 }}>
            {c.latest_observation !== null || c.reported_allergies !== null ? (
              <Card withBorder>
                <Title order={2} size="h4" mb="xs">
                  Latest triage
                </Title>
                {c.latest_observation ? (
                  <>
                    <Text size="sm">
                      {MEASURES.filter((m) => c.latest_observation![m.key] !== null)
                        .map((m) => `${m.label} ${c.latest_observation![m.key]} ${m.unit}`)
                        .join(' · ') || 'Notes only'}
                    </Text>
                    <Text size="xs" c="dimmed">
                      {c.latest_observation.recorded_by.display_name},{' '}
                      {new Date(c.latest_observation.recorded_at).toLocaleString()}
                    </Text>
                  </>
                ) : (
                  <Text size="sm">No observations recorded.</Text>
                )}
              </Card>
            ) : null}
            {c.diagnoses !== null && (
              <Card withBorder>
                <Title order={2} size="h4" mb="xs">
                  Diagnoses
                </Title>
                {c.diagnoses.length === 0 ? (
                  <Text size="sm">None recorded.</Text>
                ) : (
                  <List size="sm" aria-label="Diagnoses across visits">
                    {c.diagnoses.map((d) => (
                      <List.Item key={d.id}>
                        {d.description}{' '}
                        <Badge size="xs" variant="light" color={d.certainty === 'CONFIRMED' ? 'blue' : 'gray'}>
                          {CERTAINTY_LABEL[d.certainty]}
                        </Badge>{' '}
                        <Anchor component={Link} to={`/encounters/${d.encounter_id}`} size="xs">
                          {new Date(d.recorded_at).toLocaleDateString()}
                        </Anchor>
                      </List.Item>
                    ))}
                  </List>
                )}
              </Card>
            )}
          </SimpleGrid>
          <Card withBorder>
            <Title order={2} size="h4" mb="xs">
              Visits
            </Title>
            {c.visits.length === 0 ? (
              <Text size="sm">No visits at your facilities.</Text>
            ) : (
              <Table aria-label="Visits">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th scope="col">Arrived</Table.Th>
                    <Table.Th scope="col">Visit</Table.Th>
                    <Table.Th scope="col">Status</Table.Th>
                    <Table.Th scope="col">Clinician</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {c.visits.map((v) => (
                    <Table.Tr key={v.id}>
                      <Table.Td>
                        <Anchor component={Link} to={`/encounters/${v.id}`}>
                          {new Date(v.arrived_at).toLocaleString()}
                        </Anchor>
                      </Table.Td>
                      <Table.Td>
                        {TYPE_LABEL[v.encounter_type]}, {v.department.name}
                      </Table.Td>
                      <Table.Td>{STATUS_LABEL[v.status]}</Table.Td>
                      <Table.Td>{v.clinician?.display_name ?? '—'}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            )}
          </Card>
          {hasPermission(me, Permission.ORDER_READ) && <PatientOrders patientId={patientId} />}
          {hasPermission(me, Permission.RADIOLOGY_REPORT_READ) && <ImagingResultsSection patientId={patientId} />}
        </Stack>
      )}
    </QueryState>
  )
}

function PatientOrders({ patientId }: { patientId: string }) {
  const orders = useQuery({
    queryKey: ['patient-orders', patientId],
    queryFn: ({ signal }) => listPatientOrders(patientId, signal),
  })
  return (
    <Card withBorder>
      <Title order={2} size="h4" mb="xs">
        Orders and referrals
      </Title>
      <QueryState
        query={orders}
        loadingLabel="Loading orders…"
        isEmpty={(rows) => rows.length === 0}
        empty={<EmptyState title="No orders" />}
      >
        {(rows) => (
          <Table aria-label="Orders across visits">
            <Table.Thead>
              <Table.Tr>
                <Table.Th scope="col">Requested</Table.Th>
                <Table.Th scope="col">Item</Table.Th>
                <Table.Th scope="col">Priority</Table.Th>
                <Table.Th scope="col">Status</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((o) => (
                <Table.Tr key={o.id}>
                  <Table.Td>
                    <Anchor component={Link} to={`/encounters/${o.encounter_id}`}>
                      {new Date(o.requested_at).toLocaleString()}
                    </Anchor>
                  </Table.Td>
                  <Table.Td>
                    {o.item.name}
                    <Text size="xs">{CATEGORY_LABEL[o.item.category]}</Text>
                  </Table.Td>
                  <Table.Td>{PRIORITY_LABEL[o.priority]}</Table.Td>
                  <Table.Td>{ORDER_STATUS_LABEL[o.status]}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
    </Card>
  )
}
