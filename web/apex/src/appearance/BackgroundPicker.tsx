// The background colour picker (docs/proposals/web-apex-home.md §4.14): preset swatches as a radio
// group, then a native colour input for anything else. A custom pick keeps only its hue and
// chroma — the mode sets the lightness — so a near-black or near-white pick still reads in both
// modes; the swatch shows what will actually be drawn.
//
// The colour input previews while it is dragged (applyTint) and writes once it settles: one
// stored row per pick, not one per pointer move. A failed write says so and puts the stored
// colour back.
import { useEffect, useId, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  customTintToken,
  parseTint,
  presetOf,
  tintFromHex,
  tintToHex,
  TINT_PRESET_NAMES,
  TINT_PRESETS,
  DEFAULT_TINT_PRESET,
} from '@tenshu/web-tokens/tint'
import { applyTint, useBackground } from './background'

const SETTLE_MS = 450

// The brand's own wallpaper colours: the "Classic" swatch shows that look, not one hue.
const DEFAULT_SWATCH = 'conic-gradient(from 200deg, #C9BCFF, #FFC7DA, #BFD8FF, #C9BCFF)'

export function BackgroundPicker() {
  const { t } = useTranslation('control')
  const { token, pending, choose } = useBackground()
  const name = useId()
  const [error, setError] = useState<string | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  // The custom token waiting to be written, and the latest hook values, for the timer and unmount.
  const queued = useRef<string | null>(null)
  const latest = useRef({ token, pending, choose })
  useEffect(() => {
    latest.current = { token, pending, choose }
  })
  // ⚠ Leaving (Done, Escape) while a custom pick waits FLUSHES it: dropping it would leave the
  // preview painted — and in the boot mirror — over a colour that was never saved.
  useEffect(
    () => () => {
      if (timer.current === null || queued.current === null) return
      clearTimeout(timer.current)
      const { token: before, choose: write } = latest.current
      void write(queued.current).then(ok => {
        if (!ok) applyTint(parseTint(before ?? ''))
      })
    },
    [],
  )

  // No stored colour to read or change (no runtime, unreadable row): nothing to offer.
  if (token === null) return null
  const stored = token
  const selected = presetOf(stored)
  const custom = selected === null ? parseTint(stored) : null

  async function write(next: string) {
    setError(null)
    if (!(await choose(next))) {
      setError(t('customize.writeFailed'))
      applyTint(parseTint(latest.current.token ?? stored))
    }
  }

  function cancelQueued() {
    if (timer.current !== null) clearTimeout(timer.current)
    timer.current = null
    queued.current = null
  }

  function flushWhenIdle() {
    // One write at a time: a write that overtook another could land first and lose the newer pick.
    if (latest.current.pending) {
      timer.current = setTimeout(flushWhenIdle, 100)
      return
    }
    const next = queued.current
    cancelQueued()
    if (next !== null) void write(next)
  }

  function pickCustom(hex: string) {
    const tint = tintFromHex(hex)
    if (tint === null) return
    applyTint(tint)
    if (timer.current !== null) clearTimeout(timer.current)
    queued.current = customTintToken(tint)
    timer.current = setTimeout(flushWhenIdle, SETTLE_MS)
  }

  return (
    <fieldset className="m-0 flex flex-wrap items-center gap-2 border-0 p-0">
      <legend className="float-left mr-1 p-0 text-sm text-text-2">{t('appearance.background')}</legend>
      <span className="flex flex-wrap items-center gap-1.5">
        {TINT_PRESET_NAMES.map(preset => {
          const tint = TINT_PRESETS[preset]
          return (
            <label key={preset} title={t(`appearance.tint.${preset}`)} className="relative">
              <input
                type="radio"
                name={name}
                value={preset}
                checked={selected === preset}
                aria-disabled={pending || undefined}
                onChange={() => {
                  if (pending) return
                  // A custom pick still waiting must not land after this one and undo it.
                  cancelQueued()
                  applyTint(tint)
                  void write(preset === DEFAULT_TINT_PRESET ? '' : preset)
                }}
                aria-label={t(`appearance.tint.${preset}`)}
                className="peer sr-only"
              />
              <span
                aria-hidden
                style={{ background: tint === null ? DEFAULT_SWATCH : tintToHex(tint) }}
                className="block h-7 w-7 cursor-pointer rounded-full border border-glass-line shadow-glass ring-offset-2 ring-offset-surface peer-checked:ring-2 peer-checked:ring-text peer-focus-visible:outline-2 peer-focus-visible:outline-offset-4 peer-focus-visible:outline-primary"
              />
            </label>
          )
        })}
        <label
          title={t('appearance.tint.custom')}
          data-selected={custom !== null || undefined}
          className="relative grid h-7 w-7 cursor-pointer place-items-center rounded-full ring-offset-2 ring-offset-surface [background:conic-gradient(red,yellow,lime,aqua,blue,magenta,red)] data-[selected]:ring-2 data-[selected]:ring-text focus-within:outline-2 focus-within:outline-offset-4 focus-within:outline-primary"
        >
          <span
            aria-hidden
            style={custom !== null ? { background: tintToHex(custom) } : undefined}
            className="block h-4 w-4 rounded-full border border-white/70 bg-surface"
          />
          <input
            type="color"
            aria-label={t('appearance.tint.custom')}
            // Uncontrolled, re-keyed by the stored token: a controlled value would be put back on
            // every input event and the native picker could snap back mid-drag.
            key={stored}
            defaultValue={tintToHex(custom ?? TINT_PRESETS.blue)}
            onChange={e => pickCustom(e.target.value)}
            className="absolute inset-0 h-full w-full cursor-pointer opacity-0"
          />
        </label>
      </span>
      {error !== null && (
        <span role="alert" className="text-sm text-danger">
          {error}
        </span>
      )}
    </fieldset>
  )
}
