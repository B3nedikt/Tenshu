import type { ReactNode } from 'react'
import { AppErrorBoundary } from '@tenshu/app-kit/ui'
import i18n from '../i18n'

// The shared boundary (`@tenshu/app-kit/ui`) with this app's copy. ⚠ The copy is read at render
// time from the i18n instance: the boundary is a class and cannot use hooks.
function copy() {
  return {
    title: i18n.t('crash.title'),
    body: i18n.t('crash.body'),
    retry: i18n.t('crash.retry'),
    reload: i18n.t('crash.reload'),
  }
}

export function ErrorBoundary({ children }: { children: ReactNode }) {
  return (
    <AppErrorBoundary appName="Maps" copy={copy}>
      {children}
    </AppErrorBoundary>
  )
}
