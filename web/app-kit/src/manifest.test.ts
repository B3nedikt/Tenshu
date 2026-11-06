import { describe, expect, it } from 'vitest'
import { serializeManifest, tenshuWebManifest } from './manifest'

const CALENDAR = {
  id: 'calendar',
  name: 'Calendar',
  description: 'Your days, end-to-end encrypted.',
  semanticPermissions: [{ id: 'tenshu/calendar/service', humanDescription: 'Read your calendars', scope: ['/'] }],
}

describe('the app manifest', () => {
  it('derives the app’s home and id from its subdomain, and names the production apex', () => {
    const manifest = tenshuWebManifest(CALENDAR)
    expect(manifest.start_url).toBe('https://calendar.tenshu.app/')
    expect(manifest.scope).toBe('https://calendar.tenshu.app/')
    expect(manifest.tenshu).toEqual({
      appId: 'tenshu/app/calendar',
      version: '0.1.0',
      proxyOrigin: 'https://tenshu.app',
      semanticPermissions: CALENDAR.semanticPermissions,
    })
  })

  it('serialises in the hand-written layout: two spaces, short string arrays inline', () => {
    const text = serializeManifest(CALENDAR)
    expect(text).toContain('        "scope": ["/"]\n')
    expect(text.endsWith('}\n')).toBe(true)
    expect(JSON.parse(text)).toEqual(tenshuWebManifest(CALENDAR))
  })
})
