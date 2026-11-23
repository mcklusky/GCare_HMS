import { Button, Group, Modal, Stack, Text, Textarea } from '@mantine/core'
import { useState, type ReactNode } from 'react'

/**
 * Explicit confirmation for consequential actions (02-UI-UX). With `requireReason`, the user must
 * type a reason (corrections, merge/link, refunds, exemptions, break-glass); the reason is passed
 * to `onConfirm` and must be sent to the backend, which records it in the audit trail.
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
  onConfirm: (reason: string | null) => void
  onCancel: () => void
}) {
  const [reason, setReason] = useState('')
  const trimmed = reason.trim()
  const canConfirm = !busy && (!requireReason || trimmed.length > 0)

  const close = () => {
    setReason('')
    onCancel()
  }

  return (
    <Modal opened={opened} onClose={close} title={title} centered trapFocus returnFocus>
      <Stack>
        <Text component="div">{children}</Text>
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
