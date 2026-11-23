import { Badge, Button, Group, Modal, Stack, Table, Text, TextInput } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { createReason, listReasons, updateReason, type ExemptionReason } from '../../api/adjustments'
import { EmptyState, ErrorState, PageTitle, QueryState } from '../../components/states'

/** Exemption reasons (task 4.4): administrator-managed, empty until added. No eligibility rules. */
export function ExemptionReasonsPage() {
  const queryClient = useQueryClient()
  const [adding, setAdding] = useState(false)
  const reasons = useQuery({ queryKey: ['exemption-reasons'], queryFn: ({ signal }) => listReasons(signal) })
  const toggle = useMutation({
    mutationFn: (r: ExemptionReason) => updateReason(r, r.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['exemption-reasons'] }),
  })

  return (
    <Stack>
      <Group justify="space-between">
        <PageTitle>Exemption reasons</PageTitle>
        <Button onClick={() => setAdding(true)}>Add reason</Button>
      </Group>
      <Text size="sm" c="dimmed">
        Reasons a fee can be waived, as agreed by your hospital. Each exemption still needs approval by a second person.
      </Text>
      {toggle.isError && <ErrorState error={toggle.error} />}
      <QueryState
        query={reasons}
        loadingLabel="Loading reasons…"
        isEmpty={(rows) => rows.length === 0}
        empty={<EmptyState title="No exemption reasons yet">Add the reasons your hospital accepts.</EmptyState>}
      >
        {(rows) => (
          <Table aria-label="Exemption reasons">
            <Table.Thead>
              <Table.Tr>
                <Table.Th scope="col">Code</Table.Th>
                <Table.Th scope="col">Name</Table.Th>
                <Table.Th scope="col">Status</Table.Th>
                <Table.Th scope="col">Action</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((r) => (
                <Table.Tr key={r.id}>
                  <Table.Td>{r.code}</Table.Td>
                  <Table.Td>{r.name}</Table.Td>
                  <Table.Td>
                    <Badge variant="light" color={r.status === 'ACTIVE' ? 'green' : 'gray'}>
                      {r.status === 'ACTIVE' ? 'Active' : 'Inactive'}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <Button
                      size="xs"
                      variant="default"
                      loading={toggle.isPending && toggle.variables?.id === r.id}
                      onClick={() => toggle.mutate(r)}
                    >
                      {r.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
                    </Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
      <AddReasonDialog opened={adding} onClose={() => setAdding(false)} />
    </Stack>
  )
}

function AddReasonDialog({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const close = () => {
    setCode('')
    setName('')
    add.reset()
    onClose()
  }
  const add = useMutation({
    mutationFn: () => createReason({ code, name: name.trim() }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['exemption-reasons'] })
      close()
    },
  })
  const valid = /^[A-Z0-9][A-Z0-9_-]*$/.test(code) && name.trim().length > 0

  return (
    <Modal opened={opened} onClose={close} title="Add exemption reason">
      <form
        aria-label="Add exemption reason"
        onSubmit={(e) => {
          e.preventDefault()
          if (valid) add.mutate()
        }}
      >
        <Stack>
          <TextInput
            label="Code"
            required
            maxLength={30}
            value={code}
            onChange={(e) => setCode(e.currentTarget.value.toUpperCase())}
          />
          <TextInput
            label="Name"
            required
            maxLength={200}
            value={name}
            onChange={(e) => setName(e.currentTarget.value)}
          />
          {add.isError && <ErrorState error={add.error} />}
          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              Cancel
            </Button>
            <Button type="submit" disabled={!valid} loading={add.isPending}>
              Save
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  )
}
