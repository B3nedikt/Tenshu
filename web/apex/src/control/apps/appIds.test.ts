import { describe, expect, it } from 'vitest'
import { HOSTED_APPS } from '../../apps/registry'
import { nativeAppId, webAppIdOf } from './appIds'

describe('app ids', () => {
  // ⚠ Mirrors the `*_APP_ID` constants in `BundledAppManifestSource.kt` — the ids native devices
  // register under. A hosted app missing here has no way to be "used on" a native device.
  it('maps every hosted app to the native catalogue id', () => {
    expect(Object.fromEntries(HOSTED_APPS.map(app => [app.id, nativeAppId(app.id)]))).toEqual({
      drive: 'app.tenshu.drive',
      photos: 'app.tenshu.photos',
      calendar: 'app.tenshu.calendar',
      contacts: 'app.tenshu.contacts',
      fit: 'app.tenshu.fit',
      timeline: 'app.tenshu.timeline',
      maps: 'app.tenshu.maps',
      home: 'app.tenshu.home',
      mia: 'app.tenshu.mia',
    })
  })

  it('round-trips, and refuses ids outside the first-party namespace', () => {
    for (const app of HOSTED_APPS) expect(webAppIdOf(nativeAppId(app.id))).toBe(app.id)
    expect(webAppIdOf('com.example.notes')).toBeNull()
    expect(webAppIdOf('app.tenshu.')).toBeNull()
  })
})
