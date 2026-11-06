import { Suspense, lazy } from 'react'
import { useRuntimeRequested } from '../lib/runtimeRequested'

// RuntimeLeaseNotice imports the Kotlin runtime, so the root cannot import it (web-app-suite.md F6).
// It only has something to say once this page asked for the runtime, so load it then.
const RuntimeLeaseNotice = lazy(() =>
  import('./RuntimeLeaseNotice').then(m => ({ default: m.RuntimeLeaseNotice })),
)

export function RuntimeLeaseNoticeSlot() {
  const requested = useRuntimeRequested()
  if (!requested) return null
  return (
    <Suspense fallback={null}>
      <RuntimeLeaseNotice />
    </Suspense>
  )
}
