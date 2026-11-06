import { describe, expect, it } from 'vitest'
import { shareUrl } from './share'

describe('shareUrl', () => {
  it('assembles the canonical URL with the key in the fragment', () => {
    expect(shareUrl({ shareId: 'abc123', urlFragment: 'KEYFRAG' })).toBe(
      'https://tenshu.app/s/abc123#k=KEYFRAG',
    )
  })
})
