// Catches render-time throws below it and shows a recoverable fallback. Without it an uncaught
// error unmounts the whole React tree and blanks the page — no nav, no recovery. Each app uses it
// twice: per view (keyed by the view, so a crashed view recovers on navigation and the shell stays
// usable) and as the root catch-all (`mountApp`). React still has no hook form for this; a class
// with `getDerivedStateFromError` is the only way.
import { Component, type ErrorInfo, type ReactNode } from 'react'

export interface CrashCopy {
  readonly title: string
  readonly body: string
  readonly retry: string
  readonly reload: string
}

interface Props {
  // Names the app in the console line — the one place a developer sees the stack (no bridge
  // logging sink yet).
  readonly appName: string
  // Read at render time, so a localised app reads its catalogue then (a class cannot use hooks).
  readonly copy: () => CrashCopy
  readonly children: ReactNode
}

type State = { error: Error | null }

export class AppErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(`${this.props.appName} UI crashed:`, error, info.componentStack)
  }

  reset = () => this.setState({ error: null })

  render() {
    if (!this.state.error) return this.props.children
    return <Fallback error={this.state.error} copy={this.props.copy()} onRetry={this.reset} />
  }
}

function Fallback({ error, copy, onRetry }: { error: Error; copy: CrashCopy; onRetry: () => void }) {
  return (
    <div role="alert" className="rounded-xl border border-danger/30 bg-danger/10 p-6 text-center">
      <h2 className="text-base font-semibold text-danger">{copy.title}</h2>
      <p className="mx-auto mt-1 max-w-md text-sm text-danger">{copy.body}</p>
      {error.message && (
        <p className="mx-auto mt-2 max-w-md truncate font-mono text-xs text-danger">{error.message}</p>
      )}
      <div className="mt-4 flex justify-center gap-2">
        <button
          type="button"
          onClick={onRetry}
          className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-on-primary hover:bg-primary/90"
        >
          {copy.retry}
        </button>
        <button
          type="button"
          onClick={() => window.location.reload()}
          className="rounded-lg border border-line px-3 py-1.5 text-sm font-medium text-text hover:bg-surface-3"
        >
          {copy.reload}
        </button>
      </div>
    </div>
  )
}
