import { describe, expect, it } from 'vitest'
import { createAppQueryClient, defineQueryKeys, sessionQueryKeys } from './query'

describe('query plumbing', () => {
  it('merges the session keys into the app’s factory, unchanged', () => {
    const keys = defineQueryKeys({ files: (path: string) => ['files', path] as const })
    expect(keys.runtimeLease()).toEqual(['runtimeLease'])
    expect(keys.runtimeLease()).toEqual(sessionQueryKeys.runtimeLease())
    expect(keys.files('/a')).toEqual(['files', '/a'])
  })

  it('does not retry a failed query', () => {
    expect(createAppQueryClient().getDefaultOptions().queries?.retry).toBe(false)
  })
})
