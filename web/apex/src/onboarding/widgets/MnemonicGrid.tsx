// 24-word recovery-phrase grid for UX §4b.3. Displays the words in a
// 4×6 grid with monospace + generous spacing. The clipboard helper
// kicks off a 30-second countdown banner per UX §4b.3 ("Clipboard will
// be cleared in 30 seconds") and writes an empty string at the end.
//
// Per UX §7.1, the mnemonic only escapes apex memory if the user
// explicitly copies it; the parent owns the words array and never
// persists it.
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'

export function MnemonicGrid({
  words,
  obscured = false,
}: {
  readonly words: readonly string[]
  readonly obscured?: boolean
}) {
  return (
    <ol className="grid grid-cols-2 gap-2 sm:grid-cols-3 md:grid-cols-4">
      {words.map((word, i) => (
        <li
          key={i}
          className="flex items-baseline gap-2 rounded-md border border-line bg-surface px-3 py-2"
        >
          <span className="w-6 select-none font-mono text-xs text-text-3">
            {(i + 1).toString().padStart(2, ' ')}
          </span>
          <span
            className={[
              'font-mono text-sm',
              obscured ? 'select-none blur-sm' : '',
            ].join(' ')}
          >
            {word}
          </span>
        </li>
      ))}
    </ol>
  )
}

export function ClipboardCountdownButton({
  text,
  seconds = 30,
  className,
}: {
  readonly text: string
  readonly seconds?: number
  readonly className?: string
}) {
  const { t } = useTranslation('onboarding')
  const [remaining, setRemaining] = useState<number | null>(null)
  useEffect(() => {
    if (remaining === null) return
    if (remaining > 0) {
      const id = setTimeout(() => setRemaining(remaining - 1), 1000)
      return () => clearTimeout(id)
    }
    // remaining === 0: clear the clipboard, then null out via a 0-delay
    // timeout so the "set state in effect" lint sees an external trigger.
    void navigator.clipboard.writeText('').catch(() => {})
    const id = setTimeout(() => setRemaining(null), 0)
    return () => clearTimeout(id)
  }, [remaining])

  async function onCopy() {
    try {
      await navigator.clipboard.writeText(text)
      setRemaining(seconds)
    } catch {
      setRemaining(null)
    }
  }

  return (
    <div className={className}>
      <button
        type="button"
        onClick={() => void onCopy()}
        className="rounded-md border border-line bg-surface px-3 py-2 text-sm font-medium text-text-2 shadow-sm hover:bg-surface-3"
      >
        {remaining === null ? t('mnemonicGrid.copy') : t('mnemonicGrid.copied')}
      </button>
      {remaining !== null && (
        <p
          role="status"
          aria-live="polite"
          className="mt-2 rounded-md border border-warning/30 bg-warning/10 px-3 py-2 text-xs text-warning"
        >
          {t('mnemonicGrid.clearingNotice', { seconds: remaining })}
        </p>
      )}
    </div>
  )
}
