// BIP-39 word input with prefix-autocomplete. The wordlist is the
// Kotlin SDK's `Bip39.wordlist` (single source of truth shared with
// `generateMnemonic`/`encode`/`decode`). Per-word validation here is
// purely a UX affordance — final validation always runs through
// `Bip39.decode` on submit, which also catches checksum mismatch.
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { BIP39_WORDLIST, BIP39_WORDSET } from '../../lib/bip39'

export interface WordlistInputProps {
  readonly value: string
  readonly onChange: (next: string) => void
  readonly label: string
  readonly autoFocus?: boolean
  readonly onCommit?: () => void
  readonly placeholder?: string
}

export function WordlistInput({
  value,
  onChange,
  label,
  autoFocus,
  onCommit,
  placeholder,
}: WordlistInputProps) {
  const { t } = useTranslation('onboarding')
  const id = useId()
  const [focused, setFocused] = useState(false)
  // The blur is delayed so a click on a suggestion lands before the list closes. ⚠ The timer is
  // cleared on unmount: one that outlived the component set state on an unmounted tree, and in
  // vitest it fired after jsdom's teardown as `window is not defined`, failing whichever run it
  // landed in.
  const blurTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  useEffect(() => () => clearTimeout(blurTimer.current), [])
  const trimmed = value.trim().toLowerCase()
  const isExact = trimmed !== '' && BIP39_WORDSET.has(trimmed)
  const isInvalidPrefix = trimmed !== '' && !isExact && !BIP39_WORDLIST.some(w => w.startsWith(trimmed))
  const suggestions = useMemo<readonly string[]>(() => {
    if (trimmed === '' || isExact) return []
    return BIP39_WORDLIST.filter(w => w.startsWith(trimmed)).slice(0, 5)
  }, [trimmed, isExact])

  return (
    <div className="relative">
      <label htmlFor={id} className="block text-xs font-medium text-text-2">
        {label}
      </label>
      <input
        id={id}
        type="text"
        autoComplete="off"
        autoCorrect="off"
        autoCapitalize="none"
        spellCheck={false}
        autoFocus={autoFocus}
        value={value}
        onChange={e => onChange(e.target.value)}
        onFocus={() => setFocused(true)}
        onBlur={() => {
          clearTimeout(blurTimer.current)
          blurTimer.current = setTimeout(() => setFocused(false), 120)
        }}
        onKeyDown={e => {
          if (e.key === 'Enter' && isExact && onCommit !== undefined) {
            e.preventDefault()
            onCommit()
          }
          if (e.key === 'Tab' && suggestions.length > 0 && suggestions[0] !== undefined) {
            e.preventDefault()
            onChange(suggestions[0])
          }
        }}
        placeholder={placeholder}
        className={[
          'mt-1 w-full rounded-md border bg-surface px-3 py-2 font-mono text-sm shadow-sm',
          isInvalidPrefix
            ? 'border-danger/30'
            : 'border-line',
        ].join(' ')}
      />
      {focused && suggestions.length > 0 && (
        <ul
          className="absolute left-0 right-0 top-full z-10 mt-1 max-h-48 overflow-y-auto rounded-md border border-line bg-surface shadow-lg"
          role="listbox"
        >
          {suggestions.map(s => (
            <li key={s}>
              <button
                type="button"
                onMouseDown={e => {
                  e.preventDefault()
                  onChange(s)
                }}
                className="w-full px-3 py-1.5 text-left font-mono text-sm hover:bg-surface-2"
              >
                {s}
              </button>
            </li>
          ))}
        </ul>
      )}
      {isInvalidPrefix && (
        <p className="mt-1 text-xs text-danger">{t('wordlistInput.notARecoveryWord')}</p>
      )}
    </div>
  )
}
