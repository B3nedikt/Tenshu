// Shown when a push stream ended: what is on screen may be stale, and saying so with a way to
// reconnect beats a list that silently stopped updating (pairs with `useLive`'s `streamError`).
import { Button } from '@tenshu/web-tokens/ui'

export interface StreamNoticeProps {
  readonly live: { readonly streamError: Error | null; readonly retry: () => void }
  readonly copy: { readonly stopped: string; readonly reconnect: string }
}

export function StreamNotice({ live, copy }: StreamNoticeProps) {
  if (!live.streamError) return null
  return (
    <div role="alert" className="flex items-center justify-between gap-3 rounded-lg border border-warning/30 bg-warning/10 px-4 py-2 text-sm text-warning">
      <span>{copy.stopped}</span>
      <Button size="sm" variant="outline" onClick={live.retry}>
        {copy.reconnect}
      </Button>
    </div>
  )
}
