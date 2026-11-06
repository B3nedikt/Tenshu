import { useRef, useState, type ReactNode } from 'react'
import { useLocation } from 'react-router'
import { BrandLockup } from './BrandLockup'
import { isAlwaysPublicPath } from '../lib/alwaysPublicPaths'

// A pre-launch "under construction" curtain over the whole apex. Visitors enter a
// fixed 6-digit code to reveal the site; once entered it's remembered so they're not
// re-prompted. Enabled by passing a non-empty `code` (wired from
// VITE_CONSTRUCTION_CODE in main.tsx) — empty/unset means no gate, so local dev and
// the post-launch build pass straight through.
//
// ⚠ This is POLISH, NOT SECURITY. The whole apex bundle still ships to the browser;
// the code only discourages casual visitors before marketing. It is intentionally a
// client-side constant (the repo is open source — there is no secret to keep). If you
// ever need to genuinely keep the site unreachable, gate at the edge (Caddy basicauth)
// instead — see docs/how-to/deploy-apex-digitalocean.md.

const STORAGE_KEY = 'tenshu.preview.unlocked'

/**
 * The curtain as main.tsx mounts it: inside the router, lifted for the routes in
 * lib/alwaysPublicPaths.ts (the account-deletion page, which Google Play's reviewers must reach).
 * It follows the CURRENT path, so navigating from such a page into the rest of the site brings
 * the curtain back rather than leaving the site unlocked.
 */
export function RoutedConstructionGate({
  code,
  children,
}: {
  readonly code?: string
  readonly children: ReactNode
}) {
  const { pathname } = useLocation()
  return <ConstructionGate code={isAlwaysPublicPath(pathname) ? undefined : code}>{children}</ConstructionGate>
}
const LENGTH = 6

function readUnlocked(): boolean {
  try {
    return typeof window !== 'undefined' && window.localStorage.getItem(STORAGE_KEY) === '1'
  } catch {
    return false
  }
}

function persistUnlocked() {
  try {
    if (typeof window !== 'undefined') window.localStorage.setItem(STORAGE_KEY, '1')
  } catch {
    // Private-mode / storage-disabled: unlock holds for this session only. Fine.
  }
}

export function ConstructionGate({
  code,
  children,
}: {
  readonly code?: string
  readonly children: ReactNode
}) {
  const expected = code?.trim() ?? ''
  const [unlocked, setUnlocked] = useState(readUnlocked)
  const [digits, setDigits] = useState<string[]>(() => Array<string>(LENGTH).fill(''))
  const [error, setError] = useState(false)
  const inputs = useRef<Array<HTMLInputElement | null>>([])

  // Never gate an embedded context: drive.tenshu.app dials the apex over a hidden
  // iframe (the cross-origin proxy gateway), and that traffic must never see a curtain.
  // Only a real top-level visit to tenshu.app is gated.
  const embedded = typeof window !== 'undefined' && window.top !== window.self

  if (expected === '' || embedded || unlocked) {
    return <>{children}</>
  }

  const attempt = (values: string[]) => {
    if (values.join('') === expected) {
      persistUnlocked()
      setUnlocked(true)
      return
    }
    setError(true)
    setDigits(Array<string>(LENGTH).fill(''))
    inputs.current[0]?.focus()
  }

  const setDigit = (index: number, value: string) => {
    const next = [...digits]
    next[index] = value
    setDigits(next)
    return next
  }

  const handleChange = (index: number, raw: string) => {
    const digit = raw.replace(/\D/g, '').slice(-1) // keep only the last typed numeral
    if (digit === '' && raw !== '') return // ignore non-numeric keystrokes
    setError(false)
    const next = setDigit(index, digit)
    if (digit !== '' && index < LENGTH - 1) {
      inputs.current[index + 1]?.focus()
    }
    if (next.every((d) => d !== '')) attempt(next)
  }

  const handleKeyDown = (index: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace' && digits[index] === '' && index > 0) {
      e.preventDefault()
      setDigit(index - 1, '')
      inputs.current[index - 1]?.focus()
    }
  }

  const handlePaste = (e: React.ClipboardEvent<HTMLInputElement>) => {
    const pasted = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, LENGTH)
    if (pasted === '') return
    e.preventDefault()
    setError(false)
    const next = Array<string>(LENGTH).fill('')
    for (let i = 0; i < pasted.length; i++) next[i] = pasted.charAt(i)
    setDigits(next)
    const lastFilled = Math.min(pasted.length, LENGTH) - 1
    inputs.current[lastFilled]?.focus()
    if (next.every((d) => d !== '')) attempt(next)
  }

  return (
    <div className="flex min-h-dvh items-center justify-center bg-surface px-6 py-12 text-text">
      <main className="w-full max-w-sm text-center">
        <div className="mb-8 flex justify-center">
          <BrandLockup />
        </div>
        <h1 className="text-2xl font-semibold tracking-tight">Putting on the finishing touches</h1>
        <p className="mt-3 text-sm text-text-2">
          Tenshu isn’t quite ready for the world yet. If you have an early-access code, enter it
          below to take a look around.
        </p>

        <div className="mt-8 flex justify-center gap-2" role="group" aria-label="Early-access code">
          {digits.map((digit, index) => (
            <input
              key={index} // fixed-length positional inputs — index is a stable key here
              ref={(el) => {
                inputs.current[index] = el
              }}
              value={digit}
              onChange={(e) => handleChange(index, e.target.value)}
              onKeyDown={(e) => handleKeyDown(index, e)}
              onPaste={handlePaste}
              inputMode="numeric"
              autoComplete="off"
              aria-label={`Digit ${index + 1}`}
              aria-invalid={error}
              autoFocus={index === 0}
              className={`h-14 w-11 rounded-md border bg-surface-2 text-center text-xl font-semibold tabular-nums shadow-sm outline-none transition-colors focus:ring-1 ${
                error
                  ? 'border-danger focus:border-danger focus:ring-danger'
                  : 'border-line focus:border-line-strong focus:ring-line-strong'
              }`}
            />
          ))}
        </div>

        <p
          className={`mt-4 text-sm text-danger transition-opacity ${error ? 'opacity-100' : 'opacity-0'}`}
          role="alert"
          aria-live="polite"
        >
          That code didn’t work — give it another try.
        </p>
      </main>
    </div>
  )
}
