import { Group, Modal, Text, TextInput } from '@mantine/core'
import { IconArrowRight, IconSearch, IconUserSearch } from '@tabler/icons-react'
import { useId, useMemo, useState, type KeyboardEvent } from 'react'
import { useNavigate } from 'react-router'
import type { NavItem } from './navigation'
import { setQuickSearch } from './quickSearch'

type Option = { kind: 'patient'; id: string; number: string } | { kind: 'page'; id: string; item: NavItem }

/**
 * Keyboard-first "jump to" palette (Ctrl/⌘+K): pages the role allows, and a patient-number search.
 * A typed patient number goes to the search page in memory only, never into the URL or storage.
 * ARIA combobox + listbox; arrow keys move, Enter opens, Escape closes.
 */
export function CommandPalette({
  opened,
  onClose,
  items,
  canSearchPatients,
}: {
  opened: boolean
  onClose: () => void
  items: NavItem[]
  canSearchPatients: boolean
}) {
  const navigate = useNavigate()
  const listId = useId()
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)

  const options = useMemo<Option[]>(() => {
    const q = query.trim().toLowerCase()
    const pages: Option[] = items
      .filter((i) => !q || i.label.toLowerCase().includes(q) || i.section.toLowerCase().includes(q))
      .map((item) => ({ kind: 'page', id: `page-${item.to}`, item }))
    return canSearchPatients && q ? [{ kind: 'patient', id: 'patient', number: query.trim() }, ...pages] : pages
  }, [query, items, canSearchPatients])

  const close = () => {
    setQuery('')
    setActive(0)
    onClose()
  }

  const choose = (o: Option | undefined) => {
    if (!o) return
    if (o.kind === 'patient') {
      setQuickSearch(o.number)
      navigate('/patients', { state: { quickSearch: Date.now() } })
    } else {
      navigate(o.item.to)
    }
    close()
  }

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActive((a) => Math.min(a + 1, options.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((a) => Math.max(a - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      choose(options[active])
    }
  }

  const selected = options[active]

  return (
    <Modal
      opened={opened}
      onClose={close}
      withCloseButton={false}
      padding={0}
      size={560}
      yOffset="12vh"
      title="Jump to"
      styles={{ header: { display: 'none' } }}
      shadow="lg"
    >
      <TextInput
        className="gcare-palette-input"
        aria-label="Jump to a page or patient number"
        placeholder={canSearchPatients ? 'Type a page name or a patient number…' : 'Type a page name…'}
        leftSection={<IconSearch size={17} aria-hidden />}
        value={query}
        onChange={(e) => {
          setQuery(e.currentTarget.value)
          setActive(0)
        }}
        onKeyDown={onKeyDown}
        role="combobox"
        aria-expanded
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={selected ? `${listId}-${selected.id}` : undefined}
        data-autofocus
      />
      <div id={listId} role="listbox" aria-label="Results" style={{ maxHeight: 360, overflowY: 'auto', padding: 6 }}>
        {options.length === 0 && (
          <Text size="sm" c="dimmed" p="sm">
            No matching pages.
          </Text>
        )}
        {options.map((o, i) => (
          <div
            key={o.id}
            id={`${listId}-${o.id}`}
            role="option"
            aria-selected={i === active}
            className="gcare-palette-item"
            onMouseEnter={() => setActive(i)}
            onClick={() => choose(o)}
          >
            {o.kind === 'patient' ? (
              <>
                <IconUserSearch size={16} aria-hidden />
                <Text size="sm" span>
                  Find patient number <strong>{o.number}</strong>
                </Text>
              </>
            ) : (
              <>
                <o.item.icon size={16} stroke={1.7} aria-hidden />
                <Text size="sm" span fw={500}>
                  {o.item.label}
                </Text>
                <Text size="xs" c="dimmed" span>
                  {o.item.section}
                </Text>
              </>
            )}
            {i === active && <IconArrowRight size={14} aria-hidden style={{ marginLeft: 'auto' }} />}
          </div>
        ))}
      </div>
      <Group gap="md" px="md" py={8} className="gcare-navbar-footer">
        <Text size="xs" c="dimmed">
          <kbd className="gcare-kbd">↑</kbd> <kbd className="gcare-kbd">↓</kbd> to move
        </Text>
        <Text size="xs" c="dimmed">
          <kbd className="gcare-kbd">Enter</kbd> to open
        </Text>
        <Text size="xs" c="dimmed">
          <kbd className="gcare-kbd">Esc</kbd> to close
        </Text>
      </Group>
    </Modal>
  )
}
