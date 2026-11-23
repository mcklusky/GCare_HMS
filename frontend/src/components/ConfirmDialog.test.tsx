import { MantineProvider } from '@mantine/core'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiRequestError } from '../api/client'
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

  it('starts with an empty reason each time it opens, so a reason never carries over to the next record', async () => {
    const dialog = (opened: boolean) => (
      <MantineProvider env="test">
        <ConfirmDialog
          opened={opened}
          title="Acknowledge"
          confirmLabel="Acknowledge"
          requireReason
          onConfirm={vi.fn()}
          onCancel={vi.fn()}
        >
          Record A or B
        </ConfirmDialog>
      </MantineProvider>
    )
    const view = render(dialog(true))
    await userEvent.type(await screen.findByRole('textbox', { name: /Reason/ }), 'Patient A recalled')
    // Confirmed: the caller closes the dialog without Cancel, then opens it for the next record.
    view.rerender(dialog(false))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    view.rerender(dialog(true))
    expect(await screen.findByRole('textbox', { name: /Reason/ })).toHaveValue('')
    expect(screen.getByRole('button', { name: 'Acknowledge' })).toBeDisabled()
  })

  it('shows a failed save inside the dialog', async () => {
    renderDialog({
      error: new ApiRequestError(409, { code: 'CONFLICT', message: 'This form was changed. Reload it.' }, null),
    })
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('This form was changed. Reload it.')
    expect(alert).toHaveTextContent('reloaded')
  })
})
