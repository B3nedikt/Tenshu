// The hosted-app registry (web-app-suite.md F4): one file per app, and the conventions the deploy
// scripts and the scaffold derive from an app's id. Each test here is a rule a new app's registry
// file could break without anything else noticing until a browser refuses a frame.
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { findHostedApp, HOSTED_APPS, SESSION_METHODS } from './index'

const WEB = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..')

// The app packages, as `scripts/run-web-gate.sh` and `deploy/build-apex-image.sh` find them: a
// `web/<dir>` with a `vite.config.ts`, apex aside.
function appPackages(): string[] {
  return readdirSync(WEB, { withFileTypes: true })
    .filter((d) => d.isDirectory() && d.name !== 'apex' && existsSync(resolve(WEB, d.name, 'vite.config.ts')))
    .map((d) => d.name)
    .sort()
}

afterEach(() => {
  vi.unstubAllEnvs()
})

describe('hosted-app registry', () => {
  it('has an entry for every web app, and an app for every entry', () => {
    // A `web/<id>` without an entry is built into the image but never framed or admitted; an entry
    // without a package frames a 404. Either way: add `apex/src/apps/registry/apps/<id>.ts`.
    expect(appPackages().length).toBeGreaterThanOrEqual(2)
    expect(HOSTED_APPS.map((app) => app.id).sort()).toEqual(appPackages())
  })

  it('keeps Drive then Contacts first, in that order', () => {
    expect(HOSTED_APPS.slice(0, 2).map((app) => app.id)).toEqual(['drive', 'contacts'])
    expect(findHostedApp('contacts')?.title).toBe('Contacts')
    expect(findHostedApp('nope')).toBeNull()
  })

  it.each(HOSTED_APPS.map((app) => [app.id, app] as const))(
    '%s follows the naming conventions the deploy scripts derive',
    (id, app) => {
      expect(app.envName).toBe(`VITE_${id.toUpperCase().replace(/-/g, '_')}_ORIGIN`)
      expect(app.prodOrigin).toBe(`https://${id}.tenshu.app`)
      expect(app.manifest.id).toBe(id)
      expect(app.routePath).toMatch(/\/\*$/)
      // The app's own dev server listens where the apex will frame it.
      const config = readFileSync(resolve(WEB, id, 'vite.config.ts'), 'utf8')
      expect(config).toMatch(new RegExp(`\\bport:\\s*${app.devPort}\\b`))
    },
  )

  it('gives no two apps the same id, origin, port, route or launch path', () => {
    for (const key of ['id', 'prodOrigin', 'envName', 'devPort', 'routePath'] as const) {
      const values = HOSTED_APPS.map((app) => app[key])
      expect(new Set(values).size, key).toBe(values.length)
    }
    const launch = HOSTED_APPS.map((app) => app.manifest.launchPath)
    expect(new Set(launch).size).toBe(launch.length)
  })

  it.each(HOSTED_APPS.map((app) => [app.id, app] as const))(
    '%s reads its origin from its own env name, and falls back to its dev server',
    (_id, app) => {
      expect(app.origin()).toBe(`http://localhost:${app.devPort}`)
      vi.stubEnv(app.envName, 'https://configured.example')
      expect(app.origin()).toBe('https://configured.example')
    },
  )

  // ⚠⚠ The PROD guard: a localhost fallback in a production build would hand that origin the app's
  // whole allowlist.
  it.each(HOSTED_APPS.map((app) => [app.id, app] as const))(
    '%s never falls back to localhost in a production build',
    (_id, app) => {
      vi.stubEnv('PROD', true)
      expect(app.origin()).toBe(app.prodOrigin)
    },
  )

  it.each(HOSTED_APPS.map((app) => [app.id, app] as const))(
    '%s gets the session methods, exact names only, no duplicates',
    (_id, app) => {
      expect(app.methods.slice(0, SESSION_METHODS.length)).toEqual(SESSION_METHODS)
      expect(new Set(app.methods).size).toBe(app.methods.length)
      expect(app.methods.filter((m) => m === '*' || m.endsWith('.*'))).toEqual([])
    },
  )
})
