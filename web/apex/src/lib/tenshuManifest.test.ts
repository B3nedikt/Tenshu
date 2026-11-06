import { describe, expect, it } from 'vitest'
import { validateTenshuManifest } from './tenshuManifest'

// The manifest Drive serves from its origin (web/drive/public/manifest.webmanifest).
function driveManifest() {
  return {
    name: 'Drive',
    short_name: 'Drive',
    display: 'standalone',
    start_url: 'https://drive.tenshu.app/',
    tenshu: {
      appId: 'tenshu/app/drive',
      version: '0.1.0',
      proxyOrigin: 'https://tenshu.app',
      semanticPermissions: [
        {
          id: 'tenshu/file/service/listFiles',
          humanDescription: 'List files in folders you select',
          scope: ['/'],
        },
      ],
    },
  }
}

describe('validateTenshuManifest', () => {
  it('accepts the Drive manifest and returns the typed shape', () => {
    const result = validateTenshuManifest(driveManifest())
    expect(result.valid).toBe(true)
    if (result.valid) {
      expect(result.manifest.appId).toBe('tenshu/app/drive')
      expect(result.manifest.proxyOrigin).toBe('https://tenshu.app')
      expect(result.manifest.semanticPermissions).toHaveLength(1)
      expect(result.manifest.semanticPermissions[0]?.scope).toEqual(['/'])
    }
  })

  it('rejects a non-object', () => {
    const result = validateTenshuManifest('nope')
    expect(result).toEqual({ valid: false, errors: ['manifest is not an object'] })
  })

  it('requires the PWA name', () => {
    const m = driveManifest()
    delete (m as { name?: unknown }).name
    const result = validateTenshuManifest(m)
    expect(result.valid).toBe(false)
    if (!result.valid) expect(result.errors).toContain('missing required PWA field: name')
  })

  it('requires the tenshu block', () => {
    const result = validateTenshuManifest({ name: 'Drive' })
    expect(result.valid).toBe(false)
    if (!result.valid) expect(result.errors).toContain('missing required field: tenshu')
  })

  it('collects every missing required tenshu field', () => {
    const result = validateTenshuManifest({ name: 'Drive', tenshu: {} })
    expect(result.valid).toBe(false)
    if (!result.valid) {
      expect(result.errors).toContain('missing required field: tenshu.appId')
      expect(result.errors).toContain('missing required field: tenshu.version')
      expect(result.errors).toContain('missing required field: tenshu.proxyOrigin')
      expect(result.errors).toContain('missing required field: tenshu.semanticPermissions (array)')
    }
  })

  it('rejects a malformed proxyOrigin', () => {
    const m = driveManifest()
    m.tenshu.proxyOrigin = 'tenshu.app/drive'
    const result = validateTenshuManifest(m)
    expect(result.valid).toBe(false)
    if (!result.valid) {
      expect(result.errors.some((e) => e.includes('not a valid origin'))).toBe(true)
    }
  })

  it('rejects a permission entry missing id / humanDescription', () => {
    const m = driveManifest()
    m.tenshu.semanticPermissions = [{ id: '', humanDescription: '', scope: ['/'] }]
    const result = validateTenshuManifest(m)
    expect(result.valid).toBe(false)
    if (!result.valid) {
      expect(result.errors).toContain('tenshu.semanticPermissions[0].id is missing')
      expect(result.errors).toContain('tenshu.semanticPermissions[0].humanDescription is missing')
    }
  })

  it('accepts optional reiPublication / versionHash when well-formed', () => {
    const m = driveManifest()
    ;(m.tenshu as Record<string, unknown>).reiPublication = 'rei://publication/0xabc'
    ;(m.tenshu as Record<string, unknown>).versionHash = 'sha256:def'
    const result = validateTenshuManifest(m)
    expect(result.valid).toBe(true)
    if (result.valid) {
      expect(result.manifest.reiPublication).toBe('rei://publication/0xabc')
      expect(result.manifest.versionHash).toBe('sha256:def')
    }
  })
})
