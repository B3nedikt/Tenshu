import * as RDialog from '@radix-ui/react-dialog'
import { useState, type FormEvent, type ReactNode } from 'react'
import { Button } from './Button'
import { Field } from './Field'

// Generic modal on Radix Dialog — focus-trap, Escape, scroll-lock, backdrop
// dismissal and proper aria all come from the primitive. Controlled by mounting:
// rendering <Dialog> shows it; `onClose` fires on Escape / backdrop / close.
export function Dialog({
  title,
  onClose,
  children,
}: {
  title: string
  onClose: () => void
  children: ReactNode
}) {
  return (
    <RDialog.Root
      open
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
    >
      <RDialog.Portal>
        <RDialog.Overlay className="fixed inset-0 z-30 bg-black/40" />
        <RDialog.Content
          className="fixed left-1/2 top-1/2 z-40 w-full max-w-sm -translate-x-1/2 -translate-y-1/2 rounded-xl bg-surface-2 p-5 shadow-xl focus:outline-none"
          aria-describedby={undefined}
        >
          <RDialog.Title className="text-base font-semibold text-text">{title}</RDialog.Title>
          <div className="mt-4">{children}</div>
        </RDialog.Content>
      </RDialog.Portal>
    </RDialog.Root>
  )
}

// A single-text-input dialog (New folder, Rename). `onSubmit` performs the
// action and resolves `null` on success (the dialog closes) or an error
// message (shown, dialog stays open).
export function PromptDialog({
  title,
  label,
  initialValue = '',
  submitLabel,
  onSubmit,
  onClose,
}: {
  title: string
  label: string
  initialValue?: string
  submitLabel: string
  onSubmit: (value: string) => Promise<string | null>
  onClose: () => void
}) {
  const [value, setValue] = useState(initialValue)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const trimmed = value.trim()
    if (!trimmed) return
    setBusy(true)
    setError(null)
    const err = await onSubmit(trimmed)
    if (err) {
      setError(err)
      setBusy(false)
    } else {
      onClose()
    }
  }

  return (
    <Dialog title={title} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <Field
          autoFocus
          label={label}
          value={value}
          error={error}
          onChange={(e) => setValue(e.target.value)}
        />
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" disabled={busy || !value.trim()}>
            {busy ? 'Working…' : submitLabel}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

// A confirm/cancel dialog (Delete). `onConfirm` resolves `null` on success or
// an error message.
export function ConfirmDialog({
  title,
  message,
  confirmLabel,
  onConfirm,
  onClose,
}: {
  title: string
  message: ReactNode
  confirmLabel: string
  onConfirm: () => Promise<string | null>
  onClose: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function confirm() {
    setBusy(true)
    setError(null)
    const err = await onConfirm()
    if (err) {
      setError(err)
      setBusy(false)
    } else {
      onClose()
    }
  }

  return (
    <Dialog title={title} onClose={onClose}>
      <div className="space-y-4">
        <div className="text-sm text-text-2">{message}</div>
        {error && <p className="text-sm text-danger">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="danger" disabled={busy} onClick={confirm}>
            {busy ? 'Working…' : confirmLabel}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
