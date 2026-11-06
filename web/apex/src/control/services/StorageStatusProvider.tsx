// Provider half of the storage-status seam; the context and hook live in
// `storageStatusContext.ts` (a module mixing components with plain exports
// breaks fast refresh — react-refresh/only-export-components).
//
// ⚠ `read` is required, and this module deliberately does not default it to the
// real SDK reader. Doing so would statically import `@tenshu/sdk/storage` here,
// and every test that renders through this provider would then need the Gradle
// bundle built first — the reason eight apex suites already fail in a fresh
// clone. The composition root (`App.tsx`) picks the implementation; that is the
// only place that should know one exists.
import type { ReactNode } from 'react'
import { StorageStatusContext, type StorageStatusReader } from './storageStatusContext'

export function StorageStatusProvider({
  read,
  children,
}: {
  readonly read: StorageStatusReader
  readonly children: ReactNode
}) {
  return <StorageStatusContext.Provider value={read}>{children}</StorageStatusContext.Provider>
}
