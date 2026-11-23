import { Alert, Button, Group, Modal, Stack, Text, Textarea } from '@mantine/core'
import { useState, type ReactNode } from 'react'
import { ApiRequestError } from '../api/client'

/**
 * Explicit confirmation for consequential actions (02-UI-UX). With `requireReason`, the user must
 * type a reason (corrections, merge/link, refunds, exemptions, break-glass); the reason is passed
 * to `onConfirm` and must be sent to the backend, which records it in the audit trail. The reason
 * starts empty every time the dialog opens, so text typed for one record never carries over to the
 * next. Pass the mutation's `error` to show a failure inside the dialog, where the user is looking.
 *
 * Keyboard: focus starts in the dialog (reason field or Cancel), Escape cancels, focus returns
 * to the opener on close (Mantine Modal).
 */
export function ConfirmDialog({
  opened,
  title,
  children,
  confirmLabel,
  danger = false,
  requireReason = false,
  busy = false,
  confirmDisabled = false,
  error = null,
  onConfirm,
  onCancel,
}: {
  opened: boolean
  title: string
  children: ReactNode
  confirmLabel: string
  danger?: boolean
  requireReason?: boolean
  busy?: boolean
  /** Extra condition, e.g. a choice inside the dialog not made yet. */
  confirmDisabled?: boolean
  /** The failed request, shown inside the dialog (e.g. a 409 after someone else acted first). */
  error?: unknown
  onConfirm: (reason: string | null) => void
  onCancel: () => void
}) {
  const [reason, setReason] = useState('')
  const [wasOpened, setWasOpened] = useState(opened)
  if (opened !== wasOpened) {
    setWasOpened(opened)
    if (opened) setReason('')
  }
  const trimmed = reason.trim()
  const canConfirm = !busy && !confirmDisabled && (!requireReason || trimmed.length > 0)

  const close = () => {
    setReason('')
    onCancel()
  }

  return (
    <Modal opened={opened} onClose={close} title={title} centered trapFocus returnFocus>
      <Stack>
        <Text component="div">{children}</Text>
        {error != null && (
          <Alert color="red" variant="light" title="This was not saved" role="alert">
            {error instanceof ApiRequestError ? error.message : 'Something went wrong.'}
            {error instanceof ApiRequestError && error.status === 409 && ' The list has been reloaded; check it again.'}
          </Alert>
        )}
        {requireReason && (
          <Textarea
            label="Reason"
            description="Recorded in the audit trail with your name and the time."
            required
            autosize
            minRows={2}
            value={reason}
            onChange={(e) => setReason(e.currentTarget.value)}
            data-autofocus
          />
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={close} data-autofocus={requireReason ? undefined : true}>
            Cancel
          </Button>
          <Button
            color={danger ? 'red' : undefined}
            disabled={!canConfirm}
            loading={busy}
            onClick={() => onConfirm(requireReason ? trimmed : null)}
          >
            {confirmLabel}
          </Button>
        </Group>
      </Stack>
    </Modal>
  )
}
