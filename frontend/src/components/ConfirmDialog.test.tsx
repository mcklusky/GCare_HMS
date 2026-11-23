import { MantineProvider } from '@mantine/core'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConfirmDialog } from './ConfirmDialog'

function renderDialog(props: Partial<Parameters<typeof ConfirmDialog>[0]> = {}) {
  const onConfirm = vi.fn()
  const onCancel = vi.fn()
  render(
    <MantineProvider env="test">
      <ConfirmDialog
        opened
        title="Reverse payment"
        confirmLabel="Reverse"
        onConfirm={onConfirm}
        onCancel={onCancel}
        {...props}
      >
        The original payment stays visible.
      </ConfirmDialog>
    </MantineProvider>,
  )
  return { onConfirm, onCancel }
}

describe('ConfirmDialog', () => {
  it('requires a reason before confirming and passes it on trimmed', async () => {
    const { onConfirm } = renderDialog({ requireReason: true })

    const confirm = await screen.findByRole('button', { name: 'Reverse' })
    expect(confirm).toBeDisabled()

    await userEvent.type(screen.getByRole('textbox', { name: /Reason/ }), '  Duplicate charge  ')
    expect(confirm).toBeEnabled()
    await userEvent.click(confirm)

    expect(onConfirm).toHaveBeenCalledWith('Duplicate charge')
  })

  it('a whitespace-only reason is not a reason', async () => {
    renderDialog({ requireReason: true })

    await userEvent.type(await screen.findByRole('textbox', { name: /Reason/ }), '   ')

    expect(screen.getByRole('button', { name: 'Reverse' })).toBeDisabled()
  })

  it('confirms without a reason when none is required', async () => {
    const { onConfirm } = renderDialog()

    await userEvent.click(await screen.findByRole('button', { name: 'Reverse' }))

    expect(onConfirm).toHaveBeenCalledWith(null)
  })

  it('Escape cancels', async () => {
    const { onCancel, onConfirm } = renderDialog({ requireReason: true })
    await screen.findByRole('dialog', { name: 'Reverse payment' })

    await userEvent.keyboard('{Escape}')

    expect(onCancel).toHaveBeenCalled()
    expect(onConfirm).not.toHaveBeenCalled()
  })
})
