// @vitest-environment node
//
// The support pages' `shot:` slots against the screens the screenshot pipeline captures
// (docs/how-to/store-screenshots.md §5). `store/<app>/screens.json` is the one list: its
// `captured` names are what the app's UI tests `shot(…)`, its `planned` names are reserved for
// screens no pipeline captures yet. A slot naming anything else could never show a picture, which
// is how the first drafts ended up with `01-control-welcome` against a pipeline that writes
// `01-welcome`.
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { APEX_DIR, APPEARANCES, MANIFEST_KEY_RE, SHOT_RE, SUPPORT_DEVICE, STORE_DIR, loadContent, selectPlatform } from './generate.ts'

const REPO_ROOT = join(APEX_DIR, '..', '..')

interface Screens {
  readonly version: number
  readonly platform: string
  readonly appearances: readonly string[]
  readonly uiTests: readonly string[]
  readonly captured: Readonly<Record<string, { shows: string; devLabOnly?: boolean }>>
  /** The Android pipeline's own list (scripts/store-screenshots-android.sh); absent = none. */
  readonly android?: {
    readonly script: string
    readonly captured: Readonly<Record<string, { shows: string; devLabOnly?: boolean }>>
  }
  readonly planned: Readonly<Record<string, { shows: string; platforms: readonly string[] }>>
}

function screensOf(app: string): Screens {
  const path = join(STORE_DIR, app, 'screens.json')
  expect(existsSync(path), `store/${app}/screens.json is missing`).toBe(true)
  return JSON.parse(readFileSync(path, 'utf8')) as Screens
}

const content = loadContent()
const apps = [...new Set(content.map((c) => c.meta.app))].sort()

/** Every slot on every page, per platform: [page, platform, slot app, screen]. */
const slots = content.flatMap(({ meta, body, where }) =>
  meta.platforms.flatMap((platform) =>
    [...selectPlatform(body, platform, where).matchAll(/\]\((shot:[^)\s]+)\)/g)].map((m) => {
      const shot = SHOT_RE.exec(m[1]!)
      if (!shot) throw new Error(`${where} [${platform}]: malformed slot ${m[1]}`)
      return { where, platform, app: shot[1] ?? meta.app, screen: shot[2]! }
    }),
  ),
)

describe('screen list (store/<app>/screens.json)', () => {
  it.each(apps)('%s: captured names are exactly what its UI tests shoot', (app) => {
    const screens = screensOf(app)
    expect(screens.version).toBe(1)
    expect(screens.uiTests.length).toBeGreaterThan(0)
    const shot = screens.uiTests.flatMap((file) =>
      [...readFileSync(join(REPO_ROOT, file), 'utf8').matchAll(/\bshot\("(\d\d-[a-z0-9-]+)"\)/g)].map((m) => m[1]!),
    )
    expect([...new Set(shot)].sort()).toEqual(Object.keys(screens.captured).sort())
  })

  it.each(apps)('%s: names its appearances — light (the published key form) first, then only known ones', (app) => {
    const { appearances } = screensOf(app)
    expect(appearances[0]).toBe('light')
    for (const a of appearances) expect(APPEARANCES as readonly string[]).toContain(a)
    expect(new Set(appearances).size).toBe(appearances.length)
  })

  it('the manifest key form is spelled the same in the generator and scripts/check-store-assets.py', () => {
    const py = readFileSync(join(REPO_ROOT, 'scripts', 'check-store-assets.py'), 'utf8')
    const pattern = /^MANIFEST_KEY = re\.compile\(r"(.+)"\)$/m.exec(py)?.[1]
    expect(pattern).toBe(MANIFEST_KEY_RE.source.replaceAll('\\/', '/'))
  })

  it.each(apps)('%s: android captured names are exactly what the Android walk shoots, and share the iOS names', (app) => {
    const { android, captured, planned } = screensOf(app)
    if (!android) return
    const walk = readFileSync(join(REPO_ROOT, android.script), 'utf8')
    const shot = [...walk.matchAll(/\bshot\("(\d\d-[a-z0-9-]+)"\)/g)].map((m) => m[1]!)
    // The walk is one file for both apps: each app's list is a subset of what it shoots, and
    // together they are all of it.
    for (const name of Object.keys(android.captured)) expect(shot, name).toContain(name)
    // One number, one screen: an Android name is one the file already gives that number to.
    for (const name of Object.keys(android.captured)) expect([...Object.keys(captured), ...Object.keys(planned)], name).toContain(name)
  })

  // Every store/<app>/screens.json, support pages or not: Timeline, Maps and Mia are walked for their
  // Play listings only (store-screenshots.md §8.2).
  it('the Android walk shoots nothing no app lists', () => {
    const lists = readdirSync(STORE_DIR)
      .filter((app) => existsSync(join(STORE_DIR, app, 'screens.json')))
      .map(screensOf)
      .filter((s) => s.android)
    const walk = readFileSync(join(REPO_ROOT, lists[0]!.android!.script), 'utf8')
    const shot = new Set([...walk.matchAll(/\bshot\("(\d\d-[a-z0-9-]+)"\)/g)].map((m) => m[1]!))
    const listed = new Set(lists.flatMap((s) => Object.keys(s.android!.captured)))
    expect([...shot].sort()).toEqual([...listed].sort())
  })

  // The landing page's own content set (store-screenshots.md §9): its captures are `landing_shot(…)`
  // calls in the same walk, listed per app under `landing` — any store/<app>/screens.json, pages or not.
  it('the landing lists are exactly what the walk\'s landing_shot calls capture', () => {
    const files = readdirSync(STORE_DIR).filter((app) => existsSync(join(STORE_DIR, app, 'screens.json')))
    const lists = files.map((app) => JSON.parse(readFileSync(join(STORE_DIR, app, 'screens.json'), 'utf8')) as { landing?: { script: string; device: string; captured: Record<string, unknown> } })
      .flatMap((s) => (s.landing ? [s.landing] : []))
    expect(lists.length).toBeGreaterThan(0)
    for (const l of lists) expect(l.device).toMatch(/^[a-z0-9]+landing$/)
    const walk = readFileSync(join(REPO_ROOT, lists[0]!.script), 'utf8')
    const shot = new Set([...walk.matchAll(/\blanding_shot\("(\d\d-[a-z0-9-]+)"\)/g)].map((m) => m[1]!))
    const listed = new Set(lists.flatMap((l) => Object.keys(l.captured)))
    expect([...shot].sort()).toEqual([...listed].sort())
  })

  // The iOS twin of the landing set (scripts/store-screenshots.sh --set landing): `landing.ios` lists
  // exactly the UI test's `landingShot(…)` calls, under the same names as the Android landing list.
  it('the iOS landing lists are exactly what their UI tests\' landingShot calls capture', () => {
    const files = readdirSync(STORE_DIR).filter((app) => existsSync(join(STORE_DIR, app, 'screens.json')))
    type IosLanding = { uiTests: readonly string[]; device: string; captured: Record<string, unknown> }
    const lists = files.map((app) => JSON.parse(readFileSync(join(STORE_DIR, app, 'screens.json'), 'utf8')) as { landing?: { captured: Record<string, unknown>; ios?: IosLanding } })
      .flatMap((s) => (s.landing?.ios ? [{ android: s.landing.captured, ios: s.landing.ios }] : []))
    expect(lists.length).toBeGreaterThan(0)
    for (const { android, ios } of lists) {
      expect(ios.device).toMatch(/^[a-z0-9]+landing$/)
      expect(ios.uiTests.length).toBeGreaterThan(0)
      const shot = ios.uiTests.flatMap((file) =>
        [...readFileSync(join(REPO_ROOT, file), 'utf8').matchAll(/\blandingShot\("(\d\d-[a-z0-9-]+)"\)/g)].map((m) => m[1]!),
      )
      expect([...new Set(shot)].sort()).toEqual(Object.keys(ios.captured).sort())
      expect(Object.keys(ios.captured).sort()).toEqual(Object.keys(android).sort())
    }
  })

  it.each(apps)('%s: every name has its own number, and planned ones name their platforms', (app) => {
    const screens = screensOf(app)
    const names = [...Object.keys(screens.captured), ...Object.keys(screens.planned)]
    for (const name of names) expect(name).toMatch(/^\d\d-[a-z0-9-]+$/)
    // A number is never reused for a different screen, so captures never shift.
    const numbers = names.map((n) => n.slice(0, 2))
    expect(new Set(numbers).size, names.join(', ')).toBe(names.length)
    for (const [name, entry] of Object.entries(screens.planned)) {
      expect(entry.platforms.length, name).toBeGreaterThan(0)
      // A planned screen is one no pipeline captures, so never on the pipeline's own platform.
      expect(entry.platforms, name).not.toContain(screens.platform)
    }
  })
})

describe('support-page slots', () => {
  it('there are slots to check', () => {
    expect(slots.length).toBeGreaterThan(20)
  })

  it.each(slots.map((s) => [`${s.where} [${s.platform}] ${s.app}/${s.screen}`, s] as const))(
    '%s names a known screen',
    (_label, { platform, app, screen }) => {
      const screens = screensOf(app)
      if (platform === 'android' && screens.android) {
        // Android's own pipeline: only what it captures.
        expect(Object.keys(screens.android.captured), 'not captured for android').toContain(screen)
      } else if (SUPPORT_DEVICE[platform] !== null || platform === screens.platform) {
        // A platform with a pipeline shows only what that pipeline captures.
        expect(Object.keys(screens.captured), `not captured for ${platform}`).toContain(screen)
      } else if (!(screen in screens.captured)) {
        expect(Object.keys(screens.planned), 'neither captured nor planned').toContain(screen)
        expect(screens.planned[screen]!.platforms, `planned, but not for ${platform}`).toContain(platform)
      }
    },
  )
})
