import { Alert, Badge, Button, Card, Group, Stack, Text, Textarea, Title } from '@mantine/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  SECTIONS,
  discardNote,
  listNotes,
  saveNote,
  signNote,
  startNote,
  type Note,
  type Sections,
} from '../../api/clinical'
import { Permission } from '../../api/types'
import { useCurrentMe } from '../../auth/MeContext'
import { hasPermission } from '../../auth/session'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, QueryState } from '../../components/states'

/**
 * Clinical notes of a visit (FR-008). Drafts are private to their author. Signing is final: a
 * signed note is corrected only by a signed amendment, which keeps its own author and reason.
 */
export function NotesSection({ encounterId, open }: { encounterId: string; open: boolean }) {
  const me = useCurrentMe()
  const queryClient = useQueryClient()
  const canWrite = hasPermission(me, Permission.CLINICAL_NOTE_WRITE)
  const [amending, setAmending] = useState<Note | null>(null)
  const notes = useQuery({ queryKey: ['notes', encounterId], queryFn: ({ signal }) => listNotes(encounterId, signal) })
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['notes', encounterId] })
  const start = useMutation({
    mutationFn: (body: Parameters<typeof startNote>[1]) => startNote(encounterId, body),
    onSuccess: () => {
      setAmending(null)
      refresh()
    },
  })

  const drafts = (notes.data ?? []).filter((n) => n.status === 'DRAFT' && n.author.id === me.id)
  const signed = (notes.data ?? []).filter((n) => n.status === 'SIGNED')
  const byId = new Map(signed.map((n) => [n.id, n]))
  const hasNewDraft = drafts.some((d) => !d.amends_note_id)

  return (
    <Card withBorder>
      <Group justify="space-between" mb="xs">
        <Title order={2} size="h4">
          Clinical notes
        </Title>
        {canWrite && open && !hasNewDraft && (
          <Button size="xs" loading={start.isPending && !amending} onClick={() => start.mutate({})}>
            Write note
          </Button>
        )}
      </Group>
      {start.isError && <ErrorState error={start.error} />}
      <QueryState
        query={notes}
        loadingLabel="Loading notes…"
        isEmpty={(rows) => rows.length === 0}
        empty={<EmptyState title="No notes yet" />}
      >
        {() => (
          <Stack>
            {signed.map((n) => (
              <SignedNote
                key={n.id}
                note={n}
                original={n.amends_note_id ? byId.get(n.amends_note_id) : undefined}
                onAmend={canWrite ? () => setAmending(n) : undefined}
              />
            ))}
            {drafts.map((d) => (
              <DraftEditor key={d.id} note={d} onDone={refresh} />
            ))}
          </Stack>
        )}
      </QueryState>
      <ConfirmDialog
        opened={amending !== null}
        title="Amend this note?"
        confirmLabel="Start amendment"
        requireReason
        busy={start.isPending}
        onConfirm={(reason) =>
          amending &&
          start.mutate({
            amends_note_id: amending.id,
            amendment_reason: reason ?? '',
            history: amending.history ?? undefined,
            examination: amending.examination ?? undefined,
            assessment: amending.assessment ?? undefined,
            plan: amending.plan ?? undefined,
          })
        }
        onCancel={() => setAmending(null)}
      >
        The signed note stays as it is. Your amendment starts as a copy for you to correct, and is shown with your name
        and this reason once you sign it.
      </ConfirmDialog>
    </Card>
  )
}

function SignedNote({ note, original, onAmend }: { note: Note; original?: Note; onAmend?: () => void }) {
  return (
    <Card withBorder padding="sm" component="article" aria-label={`Note by ${note.author.display_name}`}>
      <Group justify="space-between">
        <Text size="sm" fw={600}>
          {note.author.display_name}, signed {new Date(note.signed_at!).toLocaleString()}
        </Text>
        <Group gap="xs">
          {note.amended_by.length > 0 && (
            <Badge color="orange" variant="light">
              Amended later
            </Badge>
          )}
          {onAmend && (
            <Button size="xs" variant="default" onClick={onAmend}>
              Amend
            </Button>
          )}
        </Group>
      </Group>
      {note.amends_note_id && (
        <Alert color="orange" variant="light" mt="xs" p="xs">
          Amendment of the note
          {original
            ? ` by ${original.author.display_name}, signed ${new Date(original.signed_at!).toLocaleString()}`
            : ''}
          . Reason: {note.amendment_reason}
        </Alert>
      )}
      <Stack gap={4} mt="xs">
        {SECTIONS.filter((s) => note[s.key]).map((s) => (
          <div key={s.key}>
            <Text size="sm" fw={600}>
              {s.label}
            </Text>
            <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
              {note[s.key]}
            </Text>
          </div>
        ))}
      </Stack>
    </Card>
  )
}

function DraftEditor({ note, onDone }: { note: Note; onDone: () => void }) {
  const [sections, setSections] = useState<Sections>({
    history: note.history,
    examination: note.examination,
    assessment: note.assessment,
    plan: note.plan,
  })
  const [current, setCurrent] = useState(note)
  const [confirming, setConfirming] = useState<'sign' | 'discard' | null>(null)
  const dirty = SECTIONS.some((s) => (sections[s.key] ?? '') !== (current[s.key] ?? ''))
  const empty = SECTIONS.every((s) => !(sections[s.key] ?? '').trim())

  const save = useMutation({
    mutationFn: () => saveNote(current, sections),
    onSuccess: (saved) => setCurrent(saved),
  })
  const sign = useMutation({
    mutationFn: async () => signNote(dirty ? await saveNote(current, sections) : current),
    onSuccess: () => {
      setConfirming(null)
      onDone()
    },
  })
  const discard = useMutation({
    mutationFn: () => discardNote(current),
    onSuccess: () => {
      setConfirming(null)
      onDone()
    },
  })
  const error = save.error ?? sign.error ?? discard.error

  return (
    <Card withBorder padding="sm" component="form" aria-label={note.amends_note_id ? 'Amendment draft' : 'Note draft'}>
      <Stack>
        <Group gap="xs">
          <Badge variant="light">{note.amends_note_id ? 'Amendment draft' : 'Draft'}</Badge>
          <Text size="xs" c="dimmed">
            Only you can see this until you sign it.
          </Text>
        </Group>
        {note.amendment_reason && <Text size="sm">Reason for amendment: {note.amendment_reason}</Text>}
        {SECTIONS.map((s) => (
          <Textarea
            key={s.key}
            label={s.label}
            autosize
            minRows={2}
            maxLength={20000}
            value={sections[s.key] ?? ''}
            onChange={(e) => {
              const value = e.currentTarget.value
              setSections((prev) => ({ ...prev, [s.key]: value }))
            }}
          />
        ))}
        {error && <ErrorState error={error} />}
        <Group>
          <Button variant="default" disabled={!dirty} loading={save.isPending} onClick={() => save.mutate()}>
            Save draft
          </Button>
          <Button disabled={empty} onClick={() => setConfirming('sign')}>
            Sign
          </Button>
          <Button variant="subtle" color="red" onClick={() => setConfirming('discard')}>
            Discard
          </Button>
          {dirty && (
            <Text size="xs" c="dimmed">
              Unsaved changes
            </Text>
          )}
        </Group>
      </Stack>
      <ConfirmDialog
        opened={confirming === 'sign'}
        title="Sign this note?"
        confirmLabel="Sign note"
        busy={sign.isPending}
        onConfirm={() => sign.mutate()}
        onCancel={() => setConfirming(null)}
      >
        After signing, the note cannot be changed. Mistakes are corrected with an amendment.
      </ConfirmDialog>
      <ConfirmDialog
        opened={confirming === 'discard'}
        title="Discard this draft?"
        confirmLabel="Discard draft"
        danger
        busy={discard.isPending}
        onConfirm={() => discard.mutate()}
        onCancel={() => setConfirming(null)}
      >
        The draft will no longer be shown.
      </ConfirmDialog>
    </Card>
  )
}
