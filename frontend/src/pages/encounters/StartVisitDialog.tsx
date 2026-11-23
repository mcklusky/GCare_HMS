import { Button, Group, Modal, SegmentedControl, Select, Stack, Text } from '@mantine/core'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { listDepartments, openEncounter, type EncounterType } from '../../api/encounters'
import { useFacilityContext } from '../../auth/facilityContextValue'
import { ErrorState } from '../../components/states'

/** From the patient record: open a visit and put the patient in a department queue. */
export function StartVisitDialog({ patientId, onClose }: { patientId: string; onClose: () => void }) {
  const navigate = useNavigate()
  const { current: facility } = useFacilityContext()
  const [departmentId, setDepartmentId] = useState<string | null>(null)
  const [type, setType] = useState<EncounterType>('NEW_VISIT')
  const departments = useQuery({
    queryKey: ['encounter-departments', facility?.id],
    queryFn: ({ signal }) => listDepartments(facility!.id, signal),
    enabled: !!facility,
  })
  const start = useMutation({
    mutationFn: () =>
      openEncounter({
        patient_id: patientId,
        facility_id: facility!.id,
        department_id: departmentId!,
        encounter_type: type,
      }),
    onSuccess: (d) => void navigate(`/encounters/${d.encounter.id}`),
  })

  return (
    <Modal opened onClose={onClose} title="Start a visit" centered>
      <Stack>
        <Text size="sm">At {facility?.name ?? 'no facility'}. The patient joins the department queue.</Text>
        <SegmentedControl
          aria-label="Visit type"
          value={type}
          onChange={(v) => setType(v as EncounterType)}
          data={[
            { value: 'NEW_VISIT', label: 'New visit' },
            { value: 'FOLLOW_UP', label: 'Follow-up' },
          ]}
        />
        <Select
          label="Department"
          required
          data={(departments.data ?? []).map((d) => ({ value: d.id, label: d.name }))}
          value={departmentId}
          onChange={setDepartmentId}
          comboboxProps={{ withinPortal: false }}
          data-autofocus
        />
        {start.isError && <ErrorState error={start.error} />}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={!departmentId || !facility} loading={start.isPending} onClick={() => start.mutate()}>
            Start visit
          </Button>
        </Group>
      </Stack>
    </Modal>
  )
}
