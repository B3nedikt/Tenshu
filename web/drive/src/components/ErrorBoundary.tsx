import type { ReactNode } from 'react'
import { AppErrorBoundary, type CrashCopy } from '@tenshu/app-kit/ui'

// Drive's copy for the shared boundary (`@tenshu/app-kit/ui`). Used twice: per-tab in App (so a
// view crash keeps the shell usable) and as the root catch-all in main.
const COPY: CrashCopy = {
  title: 'Something went wrong',
  body: 'This part of Drive hit an unexpected error. Your files are safe — nothing was changed.',
  retry: 'Try again',
  reload: 'Reload',
}

export function ErrorBoundary({ children }: { children: ReactNode }) {
  return (
    <AppErrorBoundary appName="Drive" copy={() => COPY}>
      {children}
    </AppErrorBoundary>
  )
}
