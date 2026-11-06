// Import a GPX or TCX file (fit-app.md §Web scope 4). The bytes go to the SDK, which parses them with
// native's `ActivityFileParser`; nothing is fetched.
//
// ⚠⚠ Every outcome is said as it is:
//  - a refusal renders by `kind` (unsupported format, `.FIT` binary, malformed, no workouts, too large);
//  - a saved workout whose route / heart-rate upload has not reached the relay is SAVED, and says so
//    ("will upload when online"), never "failed";
//  - the answer lists each STORED workout once: two tracks with the same first fix are one workout,
//    and the panel says how many were stored, never how many tracks the file had;
//  - a service failure partway may have saved some workouts first; the panel says a retry is safe.
import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import type { FitWorkout } from '@tenshu/sdk/fit'
import { MAX_ACTIVITY_FILE_BYTES } from '../lib/bounds'
import { FIT_ERROR_KINDS, type Failure } from '../lib/errors'
import { refetchHead } from '../lib/history'
import { importActivityFile } from '../lib/proxy'
import { Button, OutcomeText } from './ui'
import { workoutTitle } from '../lib/workout'

// The refusals the SDK makes BEFORE it writes anything: a file it cannot read saved nothing.
const NOTHING_SAVED = new Set<string>([
  'error_fit_unsupported_format',
  'error_fit_binary_unsupported',
  'error_fit_malformed_file',
  'error_fit_no_workouts',
  'error_fit_file_too_large',
])

type State =
  | { kind: 'idle' }
  | { kind: 'busy'; name: string }
  | { kind: 'done'; name: string; workouts: readonly FitWorkout[]; pending: readonly string[] }
  | { kind: 'failed'; name: string; failure: Failure }
  | { kind: 'rejected'; name: string; error: unknown }

export function ImportPanel() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const input = useRef<HTMLInputElement>(null)
  const [state, setState] = useState<State>({ kind: 'idle' })

  async function run(file: File) {
    setState({ kind: 'busy', name: file.name })
    // ⚠ The gateway refuses an oversized file untyped (a bridge rejection), so the cap is checked here.
    if (file.size > MAX_ACTIVITY_FILE_BYTES) {
      setState({ kind: 'failed', name: file.name, failure: { kind: 'error_fit_file_too_large' } })
      return
    }
    try {
      const bytes = new Int8Array(await file.arrayBuffer())
      const result = await importActivityFile(file.name, bytes)
      if (result.ok) {
        setState({ kind: 'done', name: file.name, workouts: result.workouts, pending: result.streamsPending })
      } else {
        setState({ kind: 'failed', name: file.name, failure: result.error })
      }
    } catch (error) {
      setState({ kind: 'rejected', name: file.name, error })
    }
    // Saved rows show at once; the change signal would re-read the head a moment later anyway.
    refetchHead(queryClient)
  }

  return (
    <section aria-labelledby="import-heading" className="rounded-lg border border-line bg-surface-2 px-4 py-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 id="import-heading" className="text-sm font-semibold text-text">
            {t('import.title')}
          </h2>
          <p className="text-xs text-text-2">{t('import.hint')}</p>
        </div>
        <Button size="sm" variant="outline" disabled={state.kind === 'busy'} onClick={() => input.current?.click()}>
          {state.kind === 'busy' ? t('common.working') : t('import.choose')}
        </Button>
        <input
          ref={input}
          type="file"
          accept=".gpx,.tcx,application/gpx+xml,application/vnd.garmin.tcx+xml"
          className="hidden"
          data-testid="import-input"
          aria-label={t('import.choose')}
          onChange={(e) => {
            const file = e.currentTarget.files?.[0]
            e.currentTarget.value = ''
            if (file) void run(file)
          }}
        />
      </div>
      <Result state={state} />
    </section>
  )
}

function Result({ state }: { state: State }) {
  const { t } = useTranslation()
  if (state.kind === 'idle' || state.kind === 'busy') return null
  if (state.kind === 'rejected') {
    return (
      <div className="mt-3" data-testid="import-result">
        <OutcomeText outcome={{ error: state.error }} />
      </div>
    )
  }
  if (state.kind === 'failed') {
    const known = (FIT_ERROR_KINDS as readonly string[]).includes(state.failure.kind)
    return (
      <div className="mt-3 space-y-1" data-testid="import-result" data-kind={state.failure.kind}>
        <OutcomeText outcome={{ failure: state.failure }} />
        {!(known && NOTHING_SAVED.has(state.failure.kind)) && <p className="text-xs text-text-2">{t('import.maybeSaved')}</p>}
      </div>
    )
  }
  const pending = new Set(state.pending)
  return (
    <div className="mt-3" role="status" data-testid="import-result">
      <p className="text-sm text-text">{t('import.saved', { count: state.workouts.length, name: state.name })}</p>
      <ul className="mt-1 space-y-0.5">
        {state.workouts.map((w) => (
          <li key={w.workoutId} className="text-xs text-text-2" data-id={w.workoutId}>
            {workoutTitle(t, w)}
            {pending.has(w.workoutId) && <span className="ml-1 text-warning">— {t('import.pending')}</span>}
          </li>
        ))}
      </ul>
    </div>
  )
}
