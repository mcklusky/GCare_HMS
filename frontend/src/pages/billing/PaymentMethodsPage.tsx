import { Badge, Button, Group, Modal, Stack, Switch, Table, Text, TextInput } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { createPaymentMethod, listPaymentMethods, updatePaymentMethod, type PaymentMethod } from '../../api/payments'
import { EmptyState, ErrorState, PageTitle, QueryState } from '../../components/states'

/** Payment methods (task 4.3): administrator-managed, empty until added. */
export function PaymentMethodsPage() {
  const queryClient = useQueryClient()
  const [adding, setAdding] = useState(false)
  const methods = useQuery({ queryKey: ['payment-methods'], queryFn: ({ signal }) => listPaymentMethods(signal) })
  const toggle = useMutation({
    mutationFn: (m: PaymentMethod) => updatePaymentMethod(m, m.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['payment-methods'] }),
  })

  return (
    <Stack>
      <Group justify="space-between">
        <PageTitle>Payment methods</PageTitle>
        <Button onClick={() => setAdding(true)}>Add method</Button>
      </Group>
      <Text size="sm" c="dimmed">
        Cash methods count toward the cash drawer when a cashier closes a shift. Whether a method is cash cannot be
        changed later; deactivate it and add a new one instead.
      </Text>
      {toggle.isError && <ErrorState error={toggle.error} />}
      <QueryState
        query={methods}
        loadingLabel="Loading methods…"
        isEmpty={(rows) => rows.length === 0}
        empty={<EmptyState title="No payment methods yet">Add the ways your hospital accepts payment.</EmptyState>}
      >
        {(rows) => (
          <Table aria-label="Payment methods">
            <Table.Thead>
              <Table.Tr>
                <Table.Th scope="col">Code</Table.Th>
                <Table.Th scope="col">Name</Table.Th>
                <Table.Th scope="col">Cash</Table.Th>
                <Table.Th scope="col">Status</Table.Th>
                <Table.Th scope="col">Action</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((m) => (
                <Table.Tr key={m.id}>
                  <Table.Td>{m.code}</Table.Td>
                  <Table.Td>{m.name}</Table.Td>
                  <Table.Td>{m.is_cash ? 'Yes' : 'No'}</Table.Td>
                  <Table.Td>
                    <Badge variant="light" color={m.status === 'ACTIVE' ? 'green' : 'gray'}>
                      {m.status === 'ACTIVE' ? 'Active' : 'Inactive'}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <Button
                      size="xs"
                      variant="default"
                      loading={toggle.isPending && toggle.variables?.id === m.id}
                      onClick={() => toggle.mutate(m)}
                    >
                      {m.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
                    </Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
      <AddMethodDialog opened={adding} onClose={() => setAdding(false)} />
    </Stack>
  )
}

function AddMethodDialog({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [cash, setCash] = useState(false)
  const close = () => {
    setCode('')
    setName('')
    setCash(false)
    add.reset()
    onClose()
  }
  const add = useMutation({
    mutationFn: () => createPaymentMethod({ code, name: name.trim(), is_cash: cash }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['payment-methods'] })
      close()
    },
  })
  const valid = /^[A-Z0-9][A-Z0-9_-]*$/.test(code) && name.trim().length > 0

  return (
    <Modal opened={opened} onClose={close} title="Add payment method">
      <form
        aria-label="Add payment method"
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
            maxLength={100}
            value={name}
            onChange={(e) => setName(e.currentTarget.value)}
          />
          <Switch
            label="Cash (counted in the drawer)"
            checked={cash}
            onChange={(e) => setCash(e.currentTarget.checked)}
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
