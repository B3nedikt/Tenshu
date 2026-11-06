// Smoke coverage for the landing sections (Multiplatform, Apps,
// Hardware, Developers, Founder). These blocks are pure presentational components —
// no SDK probe, no router — so they render standalone. The test pins
// the headlines, the availability/planning badges, and the Apps carousel's
// roster, icons, captures and its one status ("Coming later"). How the
// carousel reacts to hover, focus, clicks and taps: carousel/AppCarousel.test.tsx.
import { afterEach, describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Block6Multiplatform } from './Block6Multiplatform'
import { Block7Apps } from './Block7Apps'
import { HUB_APPS } from '../../../support-pages/hub.ts'
import { APPS_WITH_PAGES, landingAppHref } from '../carousel/landingApps'
import { APP_PAGES } from '../../../support-pages/appPages.ts'
import { Block8Hardware } from './Block8Hardware'
import { BlockDevelopers } from './BlockDevelopers'
import { BlockFounder } from './BlockFounder'
import { BlockPublishing } from './BlockPublishing'
import i18n from '../../i18n'

describe('Block6Multiplatform', () => {
  it('lists every platform, with no availability badges', () => {
    render(<Block6Multiplatform />)
    expect(
      screen.getByRole('heading', { name: 'Runs everywhere.' }),
    ).toBeInTheDocument()
    for (const name of ['Android', 'Desktop', 'Web', 'iOS']) {
      expect(screen.getByRole('heading', { name })).toBeInTheDocument()
    }
    // All four are part of the first release, so no card carries a badge.
    expect(screen.queryByText('Available')).not.toBeInTheDocument()
    expect(screen.queryByText('In progress')).not.toBeInTheDocument()
  })

  it('links Android to Control on Google Play, and iOS nowhere until it has a store id', () => {
    render(<Block6Multiplatform />)
    const links = screen.getAllByRole('link')
    expect(links).toHaveLength(1)
    expect(links[0]).toHaveTextContent('Get it on Google Play')
    expect(links[0]).toHaveAttribute(
      'href',
      'https://play.google.com/store/apps/details?id=app.tenshu.control.android',
    )
  })
})

describe('Block7Apps', () => {
  afterEach(async () => {
    await i18n.changeLanguage('en')
  })

  const APPS = ['Control', 'Drive', 'Photos', 'Maps', 'Timeline', 'Calendar', 'Contacts', 'Fit', 'Home', 'Mia']

  it('keeps the heading and says what every app shares, without counting them', () => {
    const { container } = render(<Block7Apps />)
    expect(screen.getByRole('heading', { name: 'Apps you actually control.' })).toBeInTheDocument()
    expect(
      screen.getByText("Every app is end-to-end encrypted. Only your devices can read what's in them."),
    ).toBeInTheDocument()
    // ⚠ A count goes stale the day an app ships; the old lede said "Seven".
    expect(container.textContent).not.toMatch(/\b(seven|eight|nine|ten|two)\b apps/i)
  })

  it('shows one carousel of the ten apps, in the hub\'s order, each with a card', () => {
    const { container } = render(<Block7Apps />)
    const names = [...container.querySelectorAll('[data-hub-name]')].map((n) => n.textContent)
    expect(names).toEqual(APPS)
    for (const app of APPS) expect(screen.getByRole('heading', { name: app, level: 3 })).toBeInTheDocument()
    expect(container.querySelectorAll('.hub-stage > [data-hub-panel]')).toHaveLength(10)
  })

  // ⚠ The extension was planned copy for a thing with no module; it is gone, and so are the tiers.
  it('has no browser extension, no tiers and no status badges', () => {
    const { container } = render(<Block7Apps />)
    expect(container.textContent).not.toMatch(/extension/i)
    for (const gone of ['Still being built', 'And the rest of the set', 'Available', 'In planning']) {
      expect(screen.queryByText(gone)).toBeNull()
    }
  })

  it('marks only Home and Mia "Coming later", on their cards and never in the list of names', () => {
    const { container } = render(<Block7Apps />)
    const notes = [...container.querySelectorAll('.hub-card__note')]
    expect(notes.map((n) => n.closest('[data-hub-panel]')!.getAttribute('data-hub-panel'))).toEqual(['home', 'mia'])
    for (const n of notes) expect(n.textContent).toBe('Coming later')
    for (const list of container.querySelectorAll('.hub-list')) expect(list.textContent).not.toContain('Coming later')
  })

  it('shows every app\'s real icon, light and dark, beside its name, and no second copy in the stage text', () => {
    const { container } = render(<Block7Apps />)
    for (const id of APPS.map((a) => a.toLowerCase())) {
      const icons = [...container.querySelectorAll<HTMLImageElement>(`[data-hub-name="${id}"] img.hub-icon`)].map((i) => i.getAttribute('src'))
      expect(icons, id).toEqual([`/icons/apps/${id}.webp`, `/icons/apps/${id}-dark.webp`])
      // The highlighted list entry already shows the icon; the stage text repeats only the name.
      expect(container.querySelector(`[data-hub-panel="${id}"] .hub-card__head img`), id).toBeNull()
    }
    expect(container.querySelector('.hub-icon--fallback')).toBeNull()
  })

  it('links Control, Drive, Photos, Calendar, Contacts and Fit to their app pages, and nothing else', () => {
    const { container } = render(<Block7Apps />)
    const linked = [...container.querySelectorAll<HTMLAnchorElement>('a[data-hub-name]')].map((a) => [a.getAttribute('data-hub-name'), a.getAttribute('href')])
    expect(linked).toEqual([
      ['control', '/apps/control'],
      ['drive', '/apps/drive'],
      ['photos', '/apps/photos'],
      ['calendar', '/apps/calendar'],
      ['contacts', '/apps/contacts'],
      ['fit', '/apps/fit'],
    ])
    // The card's name is the named link (and the phone, out of the tab order) — to the same href.
    for (const [id, href] of linked) {
      const card = container.querySelector(`[data-hub-panel="${id}"]`)!
      expect(card.querySelector('.hub-card__name a')!.getAttribute('href'), id!).toBe(href)
      expect(new Set([...card.querySelectorAll('a')].map((a) => a.getAttribute('href'))), id!).toEqual(new Set([href]))
    }
  })

  // ⚠ The landing card's "Open X support" button was removed (maintainer, 2026-10-17): the card's
  // name and the phone are the links. The hub keeps its button (hub.test.ts).
  it('has no "Open X support" button on any card', () => {
    const { container } = render(<Block7Apps />)
    expect(container.querySelector('.hub-open')).toBeNull()
    expect(screen.queryByRole('link', { name: /^Open .* support$/ })).toBeNull()
  })

  it('links an app page for exactly the apps that have one (appPages.ts APP_PAGES)', () => {
    expect([...APPS_WITH_PAGES].sort()).toEqual(APP_PAGES.map((p) => p.app).sort())
  })

  it('takes every link target from landingAppHref, the one seam', () => {
    const { container } = render(<Block7Apps />)
    for (const app of HUB_APPS) {
      const hrefs = new Set([...container.querySelectorAll(`[data-hub-panel="${app.app}"] a, a[data-hub-name="${app.app}"]`)].map((a) => a.getAttribute('href')))
      const target = landingAppHref(app)
      expect([...hrefs], app.app).toEqual(target === null ? [] : [target])
    }
  })

  it('shows the published captures for Control, Drive, Photos, Fit, Calendar and Contacts and a placeholder card for the rest', () => {
    const { container } = render(<Block7Apps />)
    const shots = (id: string, platform = 'android') => container.querySelectorAll(`[data-hub-panel="${id}"] [data-hub-platform="${platform}"] img.shot--light`)
    expect(shots('control')).toHaveLength(2)
    expect(shots('drive')).toHaveLength(3)
    expect(shots('photos')).toHaveLength(3)
    // iPhone: Control's support captures; Drive's and Photos' landing sets (ios/iphone69landing).
    expect(shots('control', 'ios')).toHaveLength(2)
    expect(shots('drive', 'ios')).toHaveLength(3)
    expect(shots('photos', 'ios')).toHaveLength(3)
    // Light only (no dark set yet), so their images are plain `.shot`, not `.shot--light`.
    const plain = (id: string, platform: string) => container.querySelectorAll(`[data-hub-panel="${id}"] [data-hub-platform="${platform}"] img.shot`)
    for (const id of ['fit', 'calendar', 'contacts']) {
      expect(plain(id, 'android'), id).toHaveLength(3)
      expect(plain(id, 'ios'), id).toHaveLength(3)
    }
    for (const img of container.querySelectorAll('img.shot')) {
      expect(img.getAttribute('src')).toMatch(/^https:\/\/assets\.tenshu\.app\/sha256\/[0-9a-f]{64}\.png$/)
    }
    for (const id of ['maps', 'timeline', 'home', 'mia']) {
      expect(container.querySelector(`[data-hub-panel="${id}"] img.shot`), id).toBeNull()
      expect(container.querySelector(`[data-hub-panel="${id}"] .hub-placeholder`), id).not.toBeNull()
    }
  })

  for (const [lang, lede, later] of [
    ['de', 'Jede App ist Ende-zu-Ende-verschlüsselt. Nur deine Geräte können lesen, was darin steckt.', 'Kommt später'],
    ['fr', 'Chaque appli est chiffrée de bout en bout. Seuls vos appareils peuvent lire ce qu’elle contient.', 'À venir'],
  ] as const) {
    it(`carries the lede and "Coming later" in ${lang}`, async () => {
      await i18n.changeLanguage(lang)
      const { container } = render(<Block7Apps />)
      expect(screen.getByText(lede)).toBeInTheDocument()
      expect([...container.querySelectorAll('.hub-card__note')].map((n) => n.textContent)).toEqual([later, later])
      // Captures fall back to English where the language has none.
      expect(container.querySelectorAll('[data-hub-panel="drive"] [data-hub-platform="android"] img.shot--light')).toHaveLength(3)
    })
  }
})

describe('Block8Hardware', () => {
  it('introduces the Hub and the Mia speaker, both in planning', () => {
    render(<Block8Hardware />)
    expect(
      screen.getByRole('heading', { name: 'Dedicated Hardware.' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Tenshu Hub' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Mia speaker' })).toBeInTheDocument()
    expect(screen.getAllByText('In planning')).toHaveLength(2)
  })
})

describe('BlockFounder', () => {
  it('introduces the founder and links out to reach him', () => {
    render(<BlockFounder />)
    expect(
      screen.getByRole('heading', { name: "Who's building this." }),
    ).toBeInTheDocument()
    expect(screen.getByText(/so far on my own/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'GitHub' })).toHaveAttribute(
      'href',
      'https://github.com/B3nedikt',
    )
    expect(screen.getByRole('link', { name: 'Write to me' })).toHaveAttribute(
      'href',
      'mailto:hello@tenshu.app',
    )
  })

  // ⚠ The company sits under German law, where "Cajetan UG" without the
  // "(haftungsbeschränkt)" suffix is a personal-liability risk.
  it('names the company with its full legal form', () => {
    render(<BlockFounder />)
    expect(screen.getByText(/Cajetan UG \(haftungsbeschränkt\)/)).toBeInTheDocument()
  })
})

// ⚠ The SDKs do not exist yet. The block must keep saying so.
describe('BlockDevelopers', () => {
  it('pitches the SDKs as planned', () => {
    render(<BlockDevelopers />)
    expect(
      screen.getByRole('heading', { name: 'Build private apps in months, not years.' }),
    ).toBeInTheDocument()
    expect(screen.getByText('In planning')).toBeInTheDocument()
  })
})

// ⚠ The App Console, the Store and Rei are whitepaper designs. The block must keep saying so.
describe('BlockPublishing', () => {
  it('pitches publishing and Tenshu Rei as planned', () => {
    render(<BlockPublishing />)
    expect(
      screen.getByRole('heading', { name: 'Publish anywhere, trusted everywhere.' }),
    ).toBeInTheDocument()
    expect(screen.getByText('In planning')).toBeInTheDocument()
    expect(screen.getByText(/Tenshu Rei records who signed it/)).toBeInTheDocument()
  })
})

// The German and French copy is machine-translated (languages.ts `reviewed`), so a reviewer may
// reword it, but the facts that carry legal or contact weight must survive every language.
describe('BlockFounder translations', () => {
  afterEach(async () => {
    await i18n.changeLanguage('en')
  })

  for (const [lang, contact] of [['de', 'Schreib mir'], ['fr', 'Écrivez-moi']] as const) {
    it(`keeps the contact address and the legal form in ${lang}`, async () => {
      await i18n.changeLanguage(lang)
      render(<BlockFounder />)
      expect(screen.getByRole('link', { name: contact })).toHaveAttribute(
        'href',
        'mailto:hello@tenshu.app',
      )
      expect(screen.getByText(/Cajetan UG \(haftungsbeschränkt\)/)).toBeInTheDocument()
    })
  }
})
