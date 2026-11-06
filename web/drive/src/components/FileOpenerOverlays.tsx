// The two chrome pieces `useFileOpener` renders while a file is being opened.
// They live here rather than beside the hook because a module that exports a
// hook and also defines components can fast-refresh neither
// (react-refresh/only-export-components).
export function OpeningOverlay({ name }: { name: string }) {
  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/40">
      <div className="rounded-lg bg-surface-2 px-4 py-3 text-sm font-medium text-text shadow-lg">
        Opening “{name}”…
      </div>
    </div>
  )
}

export function ErrorToast({ message, onDismiss }: { message: string; onDismiss: () => void }) {
  return (
    <div className="fixed inset-x-0 bottom-6 z-50 flex justify-center px-4">
      <div className="flex items-center gap-3 rounded-lg bg-danger px-4 py-2 text-sm text-white shadow-lg">
        <span>{message}</span>
        <button
          type="button"
          onClick={onDismiss}
          aria-label="Dismiss"
          className="text-white/80 hover:text-white"
        >
          ✕
        </button>
      </div>
    </div>
  )
}
