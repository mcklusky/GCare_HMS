import { Badge, Button, Group, Modal, Select, Stack, Switch, Table, TextInput } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { apiFetch } from '../../api/client'
import {
  CATEGORY_LABEL,
  createCatalogItem,
  listCatalog,
  updateCatalogItem,
  type CatalogItem,
  type Category,
} from '../../api/orders'
import type { Department } from '../../api/types'
import { useFacilityContext } from '../../auth/facilityContextValue'
import { EmptyState, ErrorState, PageHeader, QueryState } from '../../components/states'

/**
 * Order catalog administration (task 3.3). Starts empty: the hospital decides what can be
 * ordered. Items are never deleted, only deactivated; orders keep their own copy of the name.
 */
export function OrderCatalogPage() {
  const [editing, setEditing] = useState<CatalogItem | 'new' | null>(null)
  const catalog = useQuery({ queryKey: ['order-catalog', 'all'], queryFn: ({ signal }) => listCatalog(true, signal) })

  return (
    <Stack>
      <PageHeader
        title="Order catalog"
        description="What clinicians can order or refer to. Enter only items approved for your hospital."
        actions={<Button onClick={() => setEditing('new')}>Add item</Button>}
      />
      <QueryState
        query={catalog}
        loadingLabel="Loading catalog…"
        isEmpty={(rows) => rows.length === 0}
        empty={
          <EmptyState title="The catalog is empty">
            Add the tests, studies and referrals your hospital offers.
          </EmptyState>
        }
      >
        {(rows) => (
          <Table aria-label="Order catalog">
            <Table.Thead>
              <Table.Tr>
                <Table.Th scope="col">Code</Table.Th>
                <Table.Th scope="col">Name</Table.Th>
                <Table.Th scope="col">Type</Table.Th>
                <Table.Th scope="col">Performed by</Table.Th>
                <Table.Th scope="col">Status</Table.Th>
                <Table.Th scope="col">Action</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((i) => (
                <Table.Tr key={i.id}>
                  <Table.Td>{i.code}</Table.Td>
                  <Table.Td>{i.name}</Table.Td>
                  <Table.Td>{CATEGORY_LABEL[i.category]}</Table.Td>
                  <Table.Td>{i.performing_department?.name ?? '—'}</Table.Td>
                  <Table.Td>
                    <Badge variant="light" color={i.status === 'ACTIVE' ? 'teal' : 'gray'}>
                      {i.status === 'ACTIVE' ? 'Active' : 'Inactive'}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <Button size="xs" variant="default" onClick={() => setEditing(i)} aria-label={`Edit ${i.name}`}>
                      Edit
                    </Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
      {editing && <ItemDialog item={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </Stack>
  )
}

function ItemDialog({ item, onClose }: { item: CatalogItem | null; onClose: () => void }) {
  const queryClient = useQueryClient()
  const { current: facility } = useFacilityContext()
  const [code, setCode] = useState(item?.code ?? '')
  const [name, setName] = useState(item?.name ?? '')
  const [category, setCategory] = useState<Category | null>(item?.category ?? null)
  const [departmentId, setDepartmentId] = useState<string | null>(item?.performing_department?.id ?? null)
  const [active, setActive] = useState(item ? item.status === 'ACTIVE' : true)
  const departments = useQuery({
    queryKey: ['facilities', facility?.id, 'departments'],
    queryFn: ({ signal }) =>
      apiFetch<Department[]>(`/api/v1/facilities/${encodeURIComponent(facility!.id)}/departments`, { signal }),
    enabled: !!facility,
  })
  const save = useMutation({
    mutationFn: () =>
      item
        ? updateCatalogItem(item, {
            name: name.trim(),
            performing_department_id: departmentId,
            status: active ? 'ACTIVE' : 'INACTIVE',
          })
        : createCatalogItem({
            code: code.trim(),
            name: name.trim(),
            category: category!,
            performing_department_id: departmentId,
          }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['order-catalog'] })
      onClose()
    },
  })
  const valid = name.trim() && (item || (code.trim() && category))

  return (
    <Modal opened onClose={onClose} title={item ? `Edit ${item.code}` : 'Add catalog item'} centered>
      <Stack>
        <TextInput
          label="Code"
          description="Capital letters, digits, - or _. Cannot be changed later."
          required
          disabled={!!item}
          value={code}
          onChange={(e) => setCode(e.currentTarget.value.toUpperCase())}
          maxLength={50}
          data-autofocus
        />
        <TextInput
          label="Name"
          required
          value={name}
          onChange={(e) => setName(e.currentTarget.value)}
          maxLength={200}
        />
        <Select
          label="Type"
          required
          disabled={!!item}
          data={(Object.keys(CATEGORY_LABEL) as Category[]).map((c) => ({ value: c, label: CATEGORY_LABEL[c] }))}
          value={category}
          onChange={(v) => setCategory(v as Category | null)}
          comboboxProps={{ withinPortal: false }}
        />
        <Select
          label="Performed by"
          description="Department whose worklist receives the order. Empty for referrals outside the hospital."
          clearable
          data={(departments.data ?? [])
            .filter((d) => d.status === 'ACTIVE')
            .map((d) => ({ value: d.id, label: d.name }))}
          value={departmentId}
          onChange={setDepartmentId}
          comboboxProps={{ withinPortal: false }}
        />
        {item && (
          <Switch
            label="Active (can be ordered)"
            checked={active}
            onChange={(e) => setActive(e.currentTarget.checked)}
          />
        )}
        {save.isError && <ErrorState error={save.error} />}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={!valid} loading={save.isPending} onClick={() => save.mutate()}>
            Save
          </Button>
        </Group>
      </Stack>
    </Modal>
  )
}
