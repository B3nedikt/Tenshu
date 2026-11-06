// The app carousel's live mode (the landing page) against the interaction contract the hub's
// script implements for the static mode (support-pages/hub-script.test.ts pins that half): hover
// and keyboard focus select, a mouse click follows the link, a tap only selects and the panel's
// button opens the entry; and the one-frame showcase — the selected entry's screenshots cycle in a
// stage that never changes size, with dots, a pause button and every hold. Plus the landing
// roster's own facts that do not depend on the copy.
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render } from '@testing-library/react'
import { renderToStaticMarkup } from 'react-dom/server'
import { HUB_APPS } from '../../../support-pages/hub.ts'
import { AppCarousel } from './AppCarousel'
import { ADVANCE_MS, type CarouselEntry, type CarouselLabels } from './carouselModel'
import { APPS_WITH_PAGES, APP_ICON_IDS, LANDING_APPS, LANDING_SCREENS, landingShots } from './landingApps'

const LABELS: CarouselLabels = {
  screenshot: (i, n, name) => `Screenshot ${i} of ${n}: ${name}`,
  pause: 'Pause screenshots',
  play: 'Play screenshots',
  show: (name) => `Show ${name}`,
  platform: { group: 'Device', ios: 'iPhone', android: 'Android', onlyAndroid: 'No iPhone screenshots yet', onlyIos: 'No Android screenshots yet' },
}

const ENTRIES: CarouselEntry[] = [
  { id: 'control', name: 'Control', href: '/apps/control/support', openLabel: 'Open Control support', shots: [] },
  { id: 'drive', name: 'Drive', href: '/apps/drive/support', openLabel: 'Open Drive support', shots: [] },
  { id: 'photos', name: 'Photos', href: null, shots: [] },
]

function mount(cards = false) {
  const { container } = render(<AppCarousel mode="live" cards={cards} entries={ENTRIES} listLabel="Tenshu apps" labels={LABELS} />)
  const name = (id: string) => container.querySelector<HTMLElement>(`[data-hub-name="${id}"]`)!
  const selected = () => [...container.querySelectorAll('[data-hub-panel].is-selected')].map((p) => p.getAttribute('data-hub-panel'))
  return { container, name, selected }
}

/** A click as the browser sends it; returns whether the default (navigation) went ahead. */
function click(el: Element, detail = 1) {
  const event = new MouseEvent('click', { bubbles: true, cancelable: true, detail })
  act(() => {
    el.dispatchEvent(event)
  })
  return !event.defaultPrevented
}

describe('AppCarousel, live', () => {
  it('renders the enhanced layout the hub script builds: names, one stage, names; the first selected', () => {
    const { container, name, selected } = mount()
    const root = container.querySelector('[data-hub]')!
    expect(root.classList.contains('is-enhanced')).toBe(true)
    expect([...root.children].map((c) => c.className)).toEqual(['hub-list', 'hub-stage', 'hub-list'])
    expect(container.querySelectorAll('.hub-stage > [data-hub-panel]')).toHaveLength(3)
    expect(selected()).toEqual(['control'])
    expect(name('control').getAttribute('aria-current')).toBe('true')
  })

  it('hovering or focusing a name selects it, without a click', () => {
    const { name, selected } = mount()
    fireEvent.mouseEnter(name('drive'))
    expect(selected()).toEqual(['drive'])
    fireEvent.focus(name('photos'))
    expect(selected()).toEqual(['photos'])
    expect(name('drive').hasAttribute('aria-current')).toBe(false)
  })

  it('a mouse click follows the link; a keyboard Enter does too', () => {
    const { name } = mount()
    fireEvent.pointerDown(name('drive'), { pointerType: 'mouse' })
    expect(click(name('drive'))).toBe(true)
    expect(click(name('control'), 0)).toBe(true)
  })

  it('a tap only selects; the panel\'s button opens the entry', () => {
    const { container, name, selected } = mount()
    fireEvent.pointerDown(name('drive'), { pointerType: 'touch' })
    expect(click(name('drive'))).toBe(false)
    expect(selected()).toEqual(['drive'])
    const button = container.querySelector<HTMLAnchorElement>('[data-hub-panel="drive"] .hub-open')!
    expect(button.textContent).toBe('Open Drive support')
    expect(button.getAttribute('href')).toBe('/apps/drive/support')
    expect(click(button)).toBe(true)
  })

  it('a name without a link is keyboard-reachable, selects on click, and its panel has no button', () => {
    const { container, name, selected } = mount()
    expect(name('photos').tagName).toBe('SPAN')
    expect(name('photos').getAttribute('tabindex')).toBe('0')
    fireEvent.pointerDown(name('photos'), { pointerType: 'mouse' })
    click(name('photos'))
    expect(selected()).toEqual(['photos'])
    expect(container.querySelector('[data-hub-panel="photos"] a')).toBeNull()
    expect(container.querySelector('[data-hub-panel="photos"] .hub-placeholder')).not.toBeNull()
  })
})

describe('AppCarousel, cards (the landing page)', () => {
  it('a card has no "Open X support" button: its name is the named link, the phone the same link', () => {
    const { container, name, selected } = mount(true)
    expect(container.querySelector('.hub-open')).toBeNull()
    const link = container.querySelector<HTMLAnchorElement>('[data-hub-panel="drive"] .hub-card__name a')!
    expect(link.textContent).toBe('Drive')
    expect(link.getAttribute('href')).toBe('/apps/drive/support')
    expect(link.getAttribute('data-hub-app')).toBeNull()
    expect(click(link)).toBe(true)
    // A tap on the list's name still only selects; the card then offers its own link.
    fireEvent.pointerDown(name('drive'), { pointerType: 'touch' })
    expect(click(name('drive'))).toBe(false)
    expect(selected()).toEqual(['drive'])
    // No page: a plain heading, no link anywhere on the card.
    expect(container.querySelector('[data-hub-panel="photos"] .hub-card__name')!.innerHTML).toBe('Photos')
    expect(container.querySelector('[data-hub-panel="photos"] a')).toBeNull()
  })

  it('the card text is one body (head and line) inside the text column: what --stage-text-anchor centres', () => {
    const entries = [{ ...ENTRIES[1]!, tagline: 'Your files.' }]
    const { container } = render(<AppCarousel mode="live" cards entries={entries} listLabel="Tenshu apps" labels={LABELS} />)
    const text = container.querySelector('[data-hub-panel="drive"] .hub-card__text')!
    expect(text.children).toHaveLength(1)
    const body = text.firstElementChild!
    expect(body.className).toBe('hub-card__body')
    expect([...body.children].map((c) => c.className)).toEqual(['hub-card__head', 'hub-card__tagline'])
  })
})

describe('AppCarousel, iPhone | Android', () => {
  const img = (n: string) => ({ url: `https://assets.tenshu.app/sha256/${n}.png`, width: 1080, height: 2400 })
  const ios = (n: string) => ({ light: { url: `https://assets.tenshu.app/sha256/${n}.png`, width: 1320, height: 2868 }, dark: null, alt: n })
  const both: CarouselEntry[] = [
    { id: 'control', name: 'Control', href: '/c', shots: [{ light: img('a1'), dark: null, alt: 'a1' }], iosShots: [ios('i1'), ios('i2')] },
    { id: 'drive', name: 'Drive', href: '/d', shots: [{ light: img('d1'), dark: null, alt: 'd1' }, { light: img('d2'), dark: null, alt: 'd2' }] },
    { id: 'maps', name: 'Maps', href: null, shots: [] },
  ]
  const stored = new Map<string, string>()
  beforeEach(() => {
    stored.clear()
    vi.stubGlobal('localStorage', { getItem: (k: string) => stored.get(k) ?? null, setItem: (k: string, v: string) => void stored.set(k, v) })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })
  const mountBoth = () => render(<AppCarousel mode="live" entries={both} listLabel="Apps" labels={LABELS} />)
  const shownOf = (c: HTMLElement, id: string) => c.querySelector(`[data-hub-panel="${id}"] [data-hub-viewer].is-shown`)?.getAttribute('data-hub-platform')

  it('switches every entry to the chosen platform, in that platform\'s frame, and remembers it', () => {
    const { container, unmount } = mountBoth()
    // jsdom's user agent is not Apple's: Android first.
    expect(shownOf(container, 'control')).toBe('android')
    const iphone = container.querySelector<HTMLButtonElement>('[data-hub-panel="control"] [data-hub-pick="ios"]')!
    expect(iphone.textContent).toBe('iPhone')
    expect(iphone.closest('[role="group"]')!.getAttribute('aria-label')).toBe('Device')
    fireEvent.click(iphone)
    expect(shownOf(container, 'control')).toBe('ios')
    expect(iphone.getAttribute('aria-pressed')).toBe('true')
    expect(container.querySelector('[data-hub]')!.getAttribute('data-shot-platform')).toBe('ios')
    expect(container.querySelector('[data-hub-platform="ios"] .hub-shot')!.classList.contains('hub-shot--ios')).toBe(true)
    expect(container.querySelector('[data-hub-platform="android"] .hub-shot')!.classList.contains('hub-shot--android')).toBe(true)
    expect(stored.get('tenshu.shotPlatform')).toBe('ios')
    unmount()
    const again = mountBoth()
    expect(shownOf(again.container, 'control')).toBe('ios')
  })

  it('an entry with one platform\'s captures shows those, its switch disabled with a note', () => {
    stored.set('tenshu.shotPlatform', 'ios')
    const { container } = mountBoth()
    expect(shownOf(container, 'drive')).toBe('android')
    const sw = container.querySelector('[data-hub-panel="drive"] .hub-platform')!
    for (const b of sw.querySelectorAll('button')) expect(b.disabled).toBe(true)
    expect(sw.querySelector('[data-hub-pick="android"]')!.getAttribute('aria-pressed')).toBe('true')
    expect(sw.querySelector('.hub-platform__note')!.textContent).toBe('No iPhone screenshots yet')
    // Both platforms: enabled, no note.
    expect(container.querySelector('[data-hub-panel="control"] .hub-platform__note')!.textContent).toBe('')
    // None: a placeholder, the switch keeps its room but is idle.
    expect(container.querySelector('[data-hub-panel="maps"] .hub-platform')!.classList.contains('hub-platform--idle')).toBe(true)
  })

  it('cycles only the shown platform\'s screenshots', () => {
    vi.useFakeTimers()
    try {
      stored.set('tenshu.shotPlatform', 'ios')
      const { container } = mountBoth()
      const cur = () => [...container.querySelectorAll('[data-hub-panel="control"] [data-hub-platform="ios"] [data-hub-slide]')].findIndex((s) => s.classList.contains('is-current'))
      expect(cur()).toBe(0)
      act(() => {
        vi.advanceTimersByTime(ADVANCE_MS)
      })
      expect(cur()).toBe(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('without JavaScript (static): Android first, the switch hidden', () => {
    const html = renderToStaticMarkup(<AppCarousel mode="static" entries={both} listLabel="Apps" labels={LABELS} />)
    expect(html).toContain('<div class="hub-views"><div class="hub-viewer" data-hub-viewer="" data-hub-platform="android">')
    expect(html.match(/<div class="hub-platform" hidden="">/g)).toHaveLength(2)
    expect(html).not.toContain('is-shown')
  })
})

describe('AppCarousel, mirrored lists', () => {
  // The DOM is the same on both sides (icon, then name: decorative icon, reading order unchanged);
  // the CSS mirrors the LEFT list so its icons sit on the stage side too. jsdom has no layout, so
  // the straight columns themselves are measured in a browser (the PR's Playwright pass); this pins
  // the rules that make them.
  const css = readFileSync(join(__dirname, 'appCarousel.css'), 'utf8').replace(/\s+/g, ' ')
  const wide = css.slice(css.indexOf('@media (min-width: 64rem)'))

  it('reverses the left list\'s names and right-aligns it; the right list stays icon-first, left-aligned', () => {
    expect(wide).toContain('.is-enhanced .hub-list:first-child { align-items: flex-end; }')
    expect(wide).toMatch(/\.is-enhanced \.hub-list:first-child \.hub-name \{ flex-direction: row-reverse;/)
    expect(wide).toContain('.is-enhanced .hub-list:last-child { align-items: flex-start; }')
    expect(wide).not.toMatch(/\.hub-list:last-child \.hub-name \{ flex-direction: row-reverse/)
  })

  it('spreads each list over 75% of the phone\'s height, centred on the phone', () => {
    expect(wide).toContain('justify-content: space-between;')
    expect(wide).toContain('min-height: calc(var(--phone-h) * 0.75);')
    expect(wide).toContain('margin-top: calc(var(--phone-top) + var(--phone-h) * 0.125);')
  })

  it('puts every name\'s icon first in the DOM on both sides, live and static', () => {
    const entries = ENTRIES.map((e) => ({ ...e, icon: null }))
    const { container } = render(<AppCarousel mode="live" entries={entries} listLabel="Tenshu apps" labels={LABELS} />)
    for (const n of container.querySelectorAll('[data-hub-name]')) expect(n.firstElementChild!.classList.contains('hub-name__icon')).toBe(true)
    const html = renderToStaticMarkup(<AppCarousel mode="static" entries={entries} listLabel="Tenshu apps" labels={LABELS} />)
    expect(html.match(/data-hub-name="[^"]+"[^>]*><span class="hub-icon hub-icon--fallback hub-name__icon"/g)).toHaveLength(3)
  })
})

describe('AppCarousel, one frame', () => {
  const img = { url: 'u', width: 1080, height: 2400 }
  const shot = (alt: string) => ({ light: { ...img, url: alt }, dark: null, alt })
  const SHOWCASE: CarouselEntry[] = [
    { id: 'control', name: 'Control', href: '/apps/control/support', openLabel: 'Open Control support', shots: [shot('c1'), shot('c2')] },
    { id: 'drive', name: 'Drive', href: '/apps/drive/support', openLabel: 'Open Drive support', shots: [shot('d1'), shot('d2'), shot('d3')] },
    { id: 'start', name: 'Getting started', href: '/start', openLabel: 'Open Getting started', shots: [shot('s1')] },
    { id: 'photos', name: 'Photos', href: null, shots: [] },
  ]
  let reduced = false

  beforeEach(() => {
    vi.useFakeTimers()
    reduced = false
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: reduced && query === '(prefers-reduced-motion: reduce)',
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }))
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    Object.defineProperty(document, 'hidden', { value: false, configurable: true })
  })

  function show(initial = 'drive') {
    const { container } = render(<AppCarousel mode="live" entries={SHOWCASE} listLabel="Apps" labels={LABELS} initial={initial} />)
    const panel = (id: string) => container.querySelector<HTMLElement>(`[data-hub-panel="${id}"]`)!
    const current = (id = 'drive') => [...panel(id).querySelectorAll('[data-hub-slide]')].findIndex((s) => s.classList.contains('is-current'))
    const dots = (id = 'drive') => [...panel(id).querySelectorAll<HTMLButtonElement>('.hub-dot')]
    const rotate = (id = 'drive') => panel(id).querySelector<HTMLButtonElement>('.hub-rotate')!
    const name = (id: string) => container.querySelector<HTMLElement>(`[data-hub-name="${id}"]`)!
    const tick = (ms = ADVANCE_MS) => act(() => vi.advanceTimersByTime(ms))
    return { container, panel, current, dots, rotate, name, tick }
  }

  it('cross-fades the selected entry\'s screenshots every ADVANCE_MS and loops; the selection stays', () => {
    const { container, current, tick } = show()
    expect(current()).toBe(0)
    tick(ADVANCE_MS - 1)
    expect(current()).toBe(0)
    tick(1)
    expect(current()).toBe(1)
    tick()
    expect(current()).toBe(2)
    tick()
    expect(current()).toBe(0)
    tick(ADVANCE_MS * 5)
    expect([...container.querySelectorAll('[data-hub-panel].is-selected')].map((p) => p.getAttribute('data-hub-panel'))).toEqual(['drive'])
  })

  it('does not advance an entry with one screenshot or a placeholder, and gives it no controls', () => {
    for (const id of ['start', 'photos']) {
      const { panel, tick, unmount } = { ...show(id), unmount: () => document.body.replaceChildren() }
      expect(panel(id).querySelector('.hub-controls')).toBeNull()
      expect(panel(id).querySelector('[aria-roledescription]')).toBeNull()
      tick(ADVANCE_MS * 3)
      unmount()
    }
    expect(vi.getTimerCount()).toBe(0)
  })

  it('pauses while the pointer is over the phone or the dots, and resumes when it leaves', () => {
    const { panel, current, tick } = show()
    const shots = panel('drive').querySelector('.hub-shots')!
    fireEvent.mouseEnter(shots)
    tick(ADVANCE_MS * 3)
    expect(current()).toBe(0)
    expect(shots.getAttribute('aria-live')).toBe('polite')
    fireEvent.mouseLeave(shots)
    expect(shots.getAttribute('aria-live')).toBe('off')
    tick()
    expect(current()).toBe(1)
    const dots = panel('drive').querySelector('.hub-dots')!
    fireEvent.mouseEnter(dots)
    tick(ADVANCE_MS * 3)
    expect(current()).toBe(1)
    fireEvent.mouseLeave(dots)
    tick()
    expect(current()).toBe(2)
  })

  it('pauses while KEYBOARD focus is in the dots; a mouse-focused dot is held by the hover alone', () => {
    const { dots, current, tick } = show()
    // jsdom has no keyboard modality: :focus-visible is what the browser answers after a Tab.
    const real = Element.prototype.matches
    let keyboard = false
    vi.spyOn(Element.prototype, 'matches').mockImplementation(function (this: Element, selector: string) {
      return selector === ':focus-visible' ? keyboard : real.call(this, selector)
    })
    act(() => dots()[2]!.focus())
    tick()
    expect(current()).toBe(1)
    act(() => dots()[2]!.blur())
    keyboard = true
    act(() => dots()[1]!.focus())
    tick(ADVANCE_MS * 3)
    expect(current()).toBe(1)
    act(() => dots()[1]!.blur())
    tick()
    expect(current()).toBe(2)
  })

  it('pauses while the tab is hidden', () => {
    const { current, tick } = show()
    Object.defineProperty(document, 'hidden', { value: true, configurable: true })
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    tick(ADVANCE_MS * 3)
    expect(current()).toBe(0)
    Object.defineProperty(document, 'hidden', { value: false, configurable: true })
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    tick()
    expect(current()).toBe(1)
  })

  it('the pause button stops and restarts the cycle, and says which it will do (APG rotation control)', () => {
    const { panel, rotate, current, tick } = show()
    const shots = panel('drive').querySelector('.hub-shots')!
    expect(rotate().getAttribute('aria-label')).toBe('Pause screenshots')
    expect(shots.getAttribute('aria-live')).toBe('off')
    act(() => rotate().click())
    expect(rotate().getAttribute('aria-label')).toBe('Play screenshots')
    expect(rotate().getAttribute('data-state')).toBe('stopped')
    expect(shots.getAttribute('aria-live')).toBe('polite')
    tick(ADVANCE_MS * 3)
    expect(current()).toBe(0)
    act(() => rotate().click())
    expect(rotate().getAttribute('aria-label')).toBe('Pause screenshots')
    tick()
    expect(current()).toBe(1)
  })

  it('carries the APG carousel semantics: a labelled carousel region, labelled slides, real buttons', () => {
    const { panel, dots } = show()
    const viewer = panel('drive').querySelector('[data-hub-viewer]')!
    expect(viewer.getAttribute('role')).toBe('region')
    expect(viewer.getAttribute('aria-roledescription')).toBe('carousel')
    expect(viewer.getAttribute('aria-label')).toBe('Drive')
    const slides = [...panel('drive').querySelectorAll('[data-hub-slide]')]
    expect(slides.map((s) => [s.getAttribute('role'), s.getAttribute('aria-roledescription'), s.getAttribute('aria-label')])).toEqual([
      ['group', 'slide', 'Screenshot 1 of 3: Drive'],
      ['group', 'slide', 'Screenshot 2 of 3: Drive'],
      ['group', 'slide', 'Screenshot 3 of 3: Drive'],
    ])
    expect(dots().map((d) => [d.tagName, d.type, d.getAttribute('aria-label')])).toEqual([
      ['BUTTON', 'button', 'Screenshot 1 of 3: Drive'],
      ['BUTTON', 'button', 'Screenshot 2 of 3: Drive'],
      ['BUTTON', 'button', 'Screenshot 3 of 3: Drive'],
    ])
    expect(dots().map((d) => d.getAttribute('aria-current'))).toEqual(['true', null, null])
    // The phone is still the entry's link, out of the tab order (the button is the named link).
    const link = panel('drive').querySelector('a.hub-shot__link')!
    expect(link.getAttribute('href')).toBe('/apps/drive/support')
    expect(link.getAttribute('tabindex')).toBe('-1')
  })

  it('a dot picks its screenshot directly', () => {
    const { dots, current } = show()
    act(() => dots()[2]!.click())
    expect(current()).toBe(2)
    expect(dots().map((d) => d.getAttribute('aria-current'))).toEqual([null, null, 'true'])
    act(() => dots()[0]!.click())
    expect(current()).toBe(0)
  })

  it('selecting an entry shows its first screenshot and restarts its cycle', () => {
    const { name, current, tick } = show()
    tick()
    tick()
    expect(current('drive')).toBe(2)
    fireEvent.mouseEnter(name('control'))
    expect(current('control')).toBe(0)
    tick(ADVANCE_MS - 1)
    expect(current('control')).toBe(0)
    tick(1)
    expect(current('control')).toBe(1)
    fireEvent.mouseEnter(name('drive'))
    expect(current('drive')).toBe(0)
  })

  it('under reduced motion: no timer at all, no pause button; the dots still pick', () => {
    reduced = true
    const { dots, rotate, current, tick } = show()
    expect(vi.getTimerCount()).toBe(0)
    tick(ADVANCE_MS * 3)
    expect(current()).toBe(0)
    expect(rotate().hidden).toBe(true)
    act(() => dots()[1]!.click())
    expect(current()).toBe(1)
  })

  // ⚠ The reason for the one frame: the stage must not change size between selections, or the
  // names move under the pointer. The size is the CSS's (one --phone-w); what the markup must not
  // do is carry anything per-selection the CSS could size by.
  it('keeps the same stage markup whatever is selected: no per-count attribute or class', () => {
    const { container, name } = show('control')
    const root = container.querySelector('[data-hub]')!
    const stage = container.querySelector('.hub-stage')!
    const snapshot = () => ({
      root: [...root.attributes].map((a) => `${a.name}=${a.value}`).sort(),
      stage: [...stage.attributes].map((a) => `${a.name}=${a.value}`).sort(),
      panels: [...stage.children].map((p) => p.getAttribute('data-hub-panel')),
    })
    const before = snapshot()
    expect(root.hasAttribute('data-stage-count')).toBe(false)
    for (const id of ['drive', 'start', 'photos', 'control']) {
      fireEvent.mouseEnter(name(id))
      expect(snapshot(), id).toEqual(before)
    }
    expect(container.innerHTML).not.toMatch(/data-stage-count|data-count|hub-stage--/)
  })
})

describe('AppCarousel, static', () => {
  it('is the hub\'s no-JS markup: two lists of whole entries, nothing selected, nothing focusable added', () => {
    const html = renderToStaticMarkup(<AppCarousel mode="static" entries={ENTRIES} listLabel="Tenshu apps" labels={LABELS} />)
    expect(html).toMatch(/^<div class="hub" data-hub="">/)
    expect(html.match(/<ul class="hub-list" aria-label="Tenshu apps">/g)).toHaveLength(2)
    expect(html).not.toContain('is-enhanced')
    expect(html).not.toContain('is-selected')
    // The stage's half adds nothing focusable (the hub script makes a coming-soon name focusable);
    // the narrow row's coming-soon card is focusable as markup, so Tab reaches every card.
    const stage = html.slice(0, html.indexOf('<div class="hub hub-m"'))
    expect(stage).not.toContain('tabindex="0"')
    // The rotation controls are in the markup, hidden until the hub script enhances it; no carousel
    // semantics and no current slide without it.
    expect(html).not.toContain('aria-roledescription')
    expect(html).not.toContain('is-current')
    // Every entry's panel stays inside its own <li>.
    expect(html.match(/<li class="hub-entry"><(a|span) [^>]*data-hub-name="[a-z]+"[^>]*>[^<]*<\/(a|span)><div class="hub-panel" data-hub-panel=/g)).toHaveLength(3)
  })
})

describe('frame modes', () => {
  const image = { url: 'https://assets.tenshu.app/sha256/a.png', width: 1080, height: 2400 }
  const framedImage = { url: 'https://assets.tenshu.app/sha256/b.png', width: 1240, height: 2620 }

  it('puts a bare capture in the CSS phone frame, and shows a pre-framed one as is', () => {
    const entries: CarouselEntry[] = [
      { id: 'bare', name: 'Bare', href: null, shots: [{ light: image, dark: null, alt: 'bare' }] },
      { id: 'framed', name: 'Framed', href: null, shots: [{ light: framedImage, dark: null, alt: 'framed', framed: { vendor: 'google', bare: { light: image, dark: null } } }] },
    ]
    const { container } = render(<AppCarousel mode="live" entries={entries} listLabel="Apps" labels={LABELS} />)
    const shot = (id: string) => container.querySelector(`[data-hub-panel="${id}"] .hub-shot`)!
    expect(shot('bare').className).toBe('hub-shot hub-shot--android is-current')
    expect(shot('framed').className).toBe('hub-shot hub-shot--framed is-current')
    // The same <img>, its own size: the frame mode changes the CSS around it, not the image.
    expect(shot('framed').querySelector('img')!.getAttribute('src')).toBe(framedImage.url)
    expect(shot('framed').querySelector('img')!.getAttribute('width')).toBe('1240')
  })

  it('looks a pre-framed capture up under its framed/ key', () => {
    const manifests = {
      drive: {
        base: 'https://assets.tenshu.app/sha256/',
        screens: {
          'android/phone/en/01-files': { sha256: 'a'.repeat(64), width: 1080, height: 2400 },
          'android/phone/en/framed/01-files': { sha256: 'b'.repeat(64), width: 1240, height: 2620 },
        },
      },
    }
    const [bare] = landingShots('drive', ['01-files'], 'en', 'x', manifests)
    const [framed] = landingShots('drive', [{ screen: '01-files', framed: true }], 'en', 'x', manifests)
    expect(bare!.framed).toBeUndefined()
    expect(bare!.light!.url).toContain('a'.repeat(64))
    expect(framed!.framed!.vendor).toBe('google')
    expect(framed!.framed!.bare!.light!.url).toContain('a'.repeat(64))
    expect(framed!.light!.url).toContain('b'.repeat(64))
  })

  // ⚠ The bezel licences (store-presence-and-support-pages.md §3.2.1): Apple's and Google's frames
  // never share a layout.
  it('never shows two vendors\' frames on one stage: mixed, every framed capture falls back to bare', () => {
    const apple = { light: framedImage, dark: null, alt: 'a', framed: { vendor: 'apple' as const, bare: { light: image, dark: null } } }
    const google = { light: framedImage, dark: null, alt: 'g', framed: { vendor: 'google' as const, bare: { light: image, dark: null } } }
    const orphan = { light: framedImage, dark: null, alt: 'o', framed: { vendor: 'google' as const, bare: null } }
    const mixed: CarouselEntry[] = [
      { id: 'one', name: 'One', href: null, shots: [apple] },
      { id: 'two', name: 'Two', href: null, shots: [google, orphan] },
    ]
    const { container } = render(<AppCarousel mode="live" entries={mixed} listLabel="Apps" labels={LABELS} />)
    expect(container.querySelector('.hub-shot--framed')).toBeNull()
    expect([...container.querySelectorAll('[data-hub] img.shot')].map((i) => i.getAttribute('src'))).toEqual([image.url, image.url])
    // The narrow row's cards (each its first capture) fall back the same way.
    expect([...container.querySelectorAll('[data-hub-row] img.shot')].map((i) => i.getAttribute('src'))).toEqual([image.url, image.url])
    // One vendor alone keeps its frames.
    const single = render(<AppCarousel mode="static" entries={[mixed[1]!]} listLabel="Apps" labels={LABELS} />)
    expect(single.container.querySelectorAll('[data-hub] .hub-shot--framed')).toHaveLength(2)
    expect(single.container.querySelectorAll('[data-hub-row] .hub-shot--framed')).toHaveLength(1)
  })

  it('renders the reserved footnote line only when one is given', () => {
    const plain = renderToStaticMarkup(<AppCarousel mode="static" entries={ENTRIES} listLabel="Apps" labels={LABELS} />)
    expect(plain).not.toContain('hub-footnote')
    const noted = renderToStaticMarkup(<AppCarousel mode="static" entries={ENTRIES} listLabel="Apps" labels={LABELS} footnote="Trademarks." />)
    expect(noted).toContain('<p class="hub-footnote">Trademarks.</p>')
  })
})

describe('the landing roster', () => {
  it('is the hub\'s roster, linking an app page where there is one, else a support page exactly where the hub does', () => {
    expect(LANDING_APPS.map((a) => a.id)).toEqual(HUB_APPS.map((a) => a.app))
    for (const app of HUB_APPS) {
      expect(LANDING_APPS.find((a) => a.id === app.app)!.href, app.app).toBe(APPS_WITH_PAGES.has(app.app) ? `/apps/${app.app}` : app.support ? `/apps/${app.app}/support` : null)
    }
  })

  it('has an icon file, light and dark, for every app it claims one for', () => {
    expect([...APP_ICON_IDS].sort()).toEqual(HUB_APPS.map((a) => a.app).sort())
    const publicDir = join(__dirname, '..', '..', '..', 'public')
    for (const app of LANDING_APPS) {
      for (const path of [app.icon!.light, app.icon!.dark]) expect(existsSync(join(publicDir, path)), path).toBe(true)
    }
  })

  it('reads a landing-set key from the <device>landing captures, never the support capture of the same number', () => {
    const manifests = {
      drive: {
        base: 'https://assets.tenshu.app/sha256/',
        screens: {
          'android/phone/en/01-files': { sha256: 'a'.repeat(64), width: 1080, height: 2400 },
          'android/phonelanding/en/01-files': { sha256: 'c'.repeat(64), width: 1080, height: 2400 },
          'android/phonelanding/en/dark/01-files': { sha256: 'd'.repeat(64), width: 1080, height: 2400 },
        },
      },
    }
    const [shot] = landingShots('drive', [{ screen: '01-files', landing: true }], 'de', 'x', manifests)
    expect(shot!.light!.url).toContain('c'.repeat(64))
    expect(shot!.dark!.url).toContain('d'.repeat(64))
    expect(landingShots('drive', [{ screen: '02-photos', landing: true }], 'en', 'x', manifests)).toEqual([])
  })

  it('names only landing-set screens the walk captures (store/<app>/screens.json landing.captured)', () => {
    const store = join(__dirname, '..', '..', '..', '..', '..', 'store')
    for (const [app, platforms] of Object.entries(LANDING_SCREENS)) {
      for (const ref of [...platforms.android, ...platforms.ios]) {
        if (typeof ref === 'string' || !ref.landing) continue
        const screens = JSON.parse(readFileSync(join(store, app, 'screens.json'), 'utf8')) as { landing: { captured: Record<string, unknown> } }
        expect(Object.keys(screens.landing.captured), `${app}/${ref.screen}`).toContain(ref.screen)
      }
    }
  })

  it('skips a screen that is not published instead of showing a broken image', () => {
    expect(landingShots('drive', ['01-files', '99-not-a-screen'], 'en', 'Tenshu Drive')).toHaveLength(1)
    expect(landingShots('photos', ['01-library'], 'en', 'Tenshu Photos')).toEqual([])
  })
})

// The narrow layout (under 64rem): a row of cards, swiped with native scroll snap, the switch once
// above it and one icon button per card under it (AppCarousel.tsx CardRow). The hub script's half
// is pinned by hub-script.test.ts.
describe('AppCarousel, the narrow row of cards', () => {
  const img = (n: string) => ({ url: `https://assets.tenshu.app/sha256/${n}.png`, width: 1080, height: 2400 })
  const ios = (n: string) => ({ light: { url: `https://assets.tenshu.app/sha256/${n}.png`, width: 1320, height: 2868 }, dark: null, alt: n })
  const ROW: CarouselEntry[] = [
    { id: 'control', name: 'Control', href: '/apps/control/support', openLabel: 'Open Control support', tagline: 'Your devices.', shots: [{ light: img('a1'), dark: null, alt: 'a1' }, { light: img('a2'), dark: null, alt: 'a2' }], iosShots: [ios('i1')] },
    { id: 'drive', name: 'Drive', href: '/apps/drive/support', openLabel: 'Open Drive support', tagline: 'Your files.', shots: [{ light: img('d1'), dark: null, alt: 'd1' }] },
    { id: 'maps', name: 'Maps', href: null, tagline: 'Your places.', note: 'Coming later', shots: [] },
  ]
  const stored = new Map<string, string>()
  let reduced = false
  beforeEach(() => {
    stored.clear()
    reduced = false
    vi.stubGlobal('localStorage', { getItem: (k: string) => stored.get(k) ?? null, setItem: (k: string, v: string) => void stored.set(k, v) })
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: reduced && query === '(prefers-reduced-motion: reduce)', media: query, addEventListener: () => {}, removeEventListener: () => {} }))
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })
  const mountRow = (cards = true) => {
    const { container } = render(<AppCarousel mode="live" cards={cards} entries={ROW} listLabel="Tenshu apps" labels={LABELS} footnote="Frames are illustrations." />)
    const row = container.querySelector<HTMLElement>('[data-hub-row]')!
    const card = (id: string) => row.querySelector<HTMLElement>(`[data-hub-card="${id}"]`)!
    const dots = () => [...container.querySelectorAll<HTMLButtonElement>('[data-hub-show]')]
    return { container, row, card, dots }
  }
  /** Lays the cards out side by side, 300px wide every 310px, in a 390px row. */
  function layOut(row: HTMLElement) {
    Object.defineProperty(row, 'clientWidth', { value: 390, configurable: true })
    row.querySelectorAll<HTMLElement>('[data-hub-card]').forEach((c, i) => {
      Object.defineProperty(c, 'offsetLeft', { value: 45 + i * 310, configurable: true })
      Object.defineProperty(c, 'offsetWidth', { value: 300, configurable: true })
    })
    const scrollTo = vi.fn((opts: ScrollToOptions) => {
      row.scrollLeft = opts.left ?? 0
    })
    row.scrollTo = scrollTo as unknown as typeof row.scrollTo
    return scrollTo
  }

  it('sits beside the stage, never inside it, and the footnote stays under both', () => {
    const { container } = mountRow()
    expect([...container.children].map((c) => c.className)).toEqual(['hub is-enhanced hub--cards', 'hub hub-m hub-m--cards', 'hub-footnote'])
    // The stage's children are untouched: the desktop layout keys on :first-child / :last-child.
    expect([...container.querySelector('[data-hub]')!.children].map((c) => c.className)).toEqual(['hub-list', 'hub-stage', 'hub-list'])
  })

  it('is a labelled region of one group per entry, each named after it, in order', () => {
    const { row } = mountRow()
    expect(row.getAttribute('role')).toBe('region')
    expect(row.getAttribute('aria-label')).toBe('Tenshu apps')
    const groups = [...row.children]
    expect(groups.map((g) => [g.getAttribute('role'), g.getAttribute('aria-label')])).toEqual([['group', 'Control'], ['group', 'Drive'], ['group', 'Maps']])
  })

  it('the whole card is the link: icon, name, line and phone inside one <a>, named by the name', () => {
    const { card } = mountRow()
    const links = card('drive').querySelectorAll('a')
    expect(links).toHaveLength(1)
    const link = links[0]!
    expect(link.getAttribute('href')).toBe('/apps/drive/support')
    expect(link.querySelector('.hub-mcard__name')!.textContent).toBe('Drive')
    expect(link.querySelector('.hub-mcard__line')!.textContent).toBe('Your files.')
    expect(link.querySelector('img.shot')!.getAttribute('src')).toBe('https://assets.tenshu.app/sha256/d1.png')
    expect(document.getElementById(link.getAttribute('aria-labelledby')!)!.textContent).toBe('Drive')
    expect(document.getElementById(link.getAttribute('aria-describedby')!)!.textContent).toBe('Your files.')
    // A landing card carries no button.
    expect(card('drive').querySelector('.hub-mcard__cta')).toBeNull()
  })

  it('shows each entry\'s FIRST screenshot only: no rotation, no dots on a card', () => {
    const { card } = mountRow()
    const android = card('control').querySelector('[data-hub-platform="android"]')!
    expect([...android.querySelectorAll('img.shot')].map((i) => i.getAttribute('src'))).toEqual(['https://assets.tenshu.app/sha256/a1.png'])
    expect(card('control').querySelector('[data-hub-dot], [data-hub-rotate]')).toBeNull()
    expect(card('control').getAttribute('data-platforms')).toBe('android ios')
    expect(card('control').querySelector('[data-hub-platform="ios"] .hub-shot')!.classList.contains('hub-shot--ios')).toBe(true)
  })

  it('an entry without a target is not a link, but its card still takes focus', () => {
    const { card } = mountRow()
    expect(card('maps').querySelector('a')).toBeNull()
    expect(card('maps').getAttribute('tabindex')).toBe('0')
    expect(card('maps').querySelector('.hub-mcard__note')!.textContent).toBe('Coming later')
    expect(card('maps').querySelector('.hub-placeholder')).not.toBeNull()
  })

  it('the hub\'s look (no cards): the link is named "Open X support", which the card shows as a label', () => {
    const { card } = mountRow(false)
    const link = card('drive').querySelector('a')!
    expect(link.getAttribute('aria-label')).toBe('Open Drive support')
    const cta = link.querySelector('.hub-mcard__cta')!
    expect(cta.textContent).toBe('Open Drive support')
    expect(cta.getAttribute('aria-hidden')).toBe('true')
    expect(card('drive').querySelector('.hub-mcard__line')).toBeNull()
  })

  it('one switch above the row: it sets the row\'s platform, shares the stage\'s and remembers it', () => {
    const { container } = mountRow()
    const mobile = container.querySelector<HTMLElement>('[data-hub-mobile]')!
    const sw = mobile.querySelectorAll('.hub-m__switch [data-hub-pick]')
    expect(sw).toHaveLength(2)
    expect(mobile.querySelector('.hub-m__switch')!.compareDocumentPosition(mobile.querySelector('[data-hub-row]')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(mobile.getAttribute('data-shot-platform')).toBe('android')
    fireEvent.click(mobile.querySelector('.hub-m__switch [data-hub-pick="ios"]')!)
    expect(mobile.getAttribute('data-shot-platform')).toBe('ios')
    expect(mobile.querySelector('.hub-m__switch [data-hub-pick="ios"]')!.getAttribute('aria-pressed')).toBe('true')
    expect(container.querySelector('[data-hub]')!.getAttribute('data-shot-platform')).toBe('ios')
    expect(stored.get('tenshu.shotPlatform')).toBe('ios')
  })

  it('a card with one platform\'s captures says so when the other is chosen', () => {
    const { card } = mountRow()
    expect(card('drive').getAttribute('data-platforms')).toBe('android')
    expect(card('drive').querySelector('.hub-mcard__fallback')!.textContent).toBe('No iPhone screenshots yet')
    expect(card('control').querySelector('.hub-mcard__fallback')).toBeNull()
  })

  it('the indicator: one labelled button per card, the one in view current, a tap scrolls to its card', () => {
    const { row, dots } = mountRow()
    const scrollTo = layOut(row)
    expect(dots().map((d) => d.getAttribute('aria-label'))).toEqual(['Show Control', 'Show Drive', 'Show Maps'])
    expect(dots().map((d) => d.hasAttribute('aria-current'))).toEqual([true, false, false])
    fireEvent.click(dots()[2]!)
    // Card 2's left (665) less half the slack ((390 - 300) / 2).
    expect(scrollTo).toHaveBeenLastCalledWith({ left: 620, behavior: 'smooth' })
    expect(dots()[2]!.getAttribute('aria-current')).toBe('true')
  })

  it('follows the scroll position: swiping to a card makes its button current', async () => {
    const { row, dots } = mountRow()
    layOut(row)
    row.scrollLeft = 310
    fireEvent.scroll(row)
    await act(() => new Promise((r) => setTimeout(r, 50)))
    expect(dots().map((d) => d.hasAttribute('aria-current'))).toEqual([false, true, false])
  })

  it('keyboard: focus scrolls its card into view; the arrow keys move between cards', () => {
    const { row, card } = mountRow()
    const scrollTo = layOut(row)
    const drive = card('drive').querySelector('a')!
    act(() => drive.focus())
    expect(scrollTo).toHaveBeenLastCalledWith({ left: 310, behavior: 'smooth' })
    fireEvent.keyDown(drive, { key: 'ArrowRight' })
    expect(document.activeElement).toBe(card('maps'))
    expect(scrollTo).toHaveBeenLastCalledWith({ left: 620, behavior: 'smooth' })
    fireEvent.keyDown(card('maps'), { key: 'ArrowRight' })
    expect(document.activeElement).toBe(card('maps'))
    fireEvent.keyDown(card('maps'), { key: 'ArrowLeft' })
    expect(document.activeElement).toBe(drive)
  })

  it('under reduced motion it jumps, never scrolls smoothly', () => {
    reduced = true
    const { row, dots } = mountRow()
    const scrollTo = layOut(row)
    fireEvent.click(dots()[1]!)
    expect(scrollTo).toHaveBeenLastCalledWith({ left: 310, behavior: 'auto' })
  })

  it('without JavaScript (static): the row swipes on its own; switch and indicator hidden, Android shown', () => {
    const html = renderToStaticMarkup(<AppCarousel mode="static" entries={ROW} listLabel="Tenshu apps" labels={LABELS} />)
    const mobile = html.slice(html.indexOf('<div class="hub hub-m"'))
    expect(mobile).toMatch(/^<div class="hub hub-m" data-hub-mobile=""><div class="hub-platform hub-m__switch" hidden="">/)
    expect(mobile).not.toContain('data-shot-platform')
    expect(mobile).toContain('<div class="hub-row" role="region" aria-label="Tenshu apps" data-hub-row="">')
    expect(mobile).toContain('<div class="hub-m__nav" hidden="">')
    expect(mobile).not.toContain('aria-current')
    expect(mobile.match(/<a class="hub-mcard__link" href="[^"]+"/g)).toHaveLength(2)
    // The first view is Android's; CSS shows it until the hub script sets a platform.
    expect(mobile).toContain('<span class="hub-mcard__phone"><span class="hub-mcard__view" data-hub-platform="android">')
  })
})

describe('AppCarousel, responsive screenshots', () => {
  const BASE = 'https://assets.tenshu.app/sha256/'
  const png = (c: string) => ({
    url: `${BASE}${c.repeat(64)}.png`,
    width: 1080,
    height: 2400,
    variants: [
      { url: `${BASE}${c.repeat(63)}1.webp`, width: 540 },
      { url: `${BASE}${c.repeat(63)}2.webp`, width: 960 },
    ],
  })
  const shot = (light: string, dark: string) => ({ light: png(light), dark: png(dark), alt: 'x' })
  const entries: CarouselEntry[] = [
    { id: 'a', name: 'A', href: null, shots: [shot('a', 'b'), shot('c', 'd')] },
    { id: 'b', name: 'B', href: null, shots: [shot('e', 'f')] },
    { id: 'c', name: 'C', href: null, shots: [shot('0', '1')] },
  ]
  const original = window.matchMedia
  afterEach(() => {
    window.matchMedia = original
  })
  /** A window [wide] or narrow, in the OS theme [dark]. */
  const viewport = (wide: boolean, dark = false) => {
    window.matchMedia = ((query: string) => ({
      matches: query.includes('min-width: 64rem') ? wide : query.includes('prefers-color-scheme: dark') ? dark : false,
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
    })) as unknown as typeof window.matchMedia
  }
  const mountRow = () => render(<AppCarousel mode="live" cards entries={entries} listLabel="Apps" labels={LABELS} />).container
  const loading = (imgs: Iterable<Element>) => [...imgs].map((i) => i.getAttribute('loading'))

  it('names the WebP copies in srcset, sized to the phone, and keeps the PNG as src with its size', () => {
    viewport(true)
    const img = mountRow().querySelector<HTMLImageElement>('[data-hub-panel="a"] .shot--light')!
    expect(img.getAttribute('src')).toBe(png('a').url)
    expect(img.getAttribute('srcset')).toBe(`${png('a').variants[0]!.url} 540w, ${png('a').variants[1]!.url} 960w`)
    expect(img.getAttribute('sizes')).toMatch(/^\(min-width: 64rem\) 75mm, /)
    expect([img.getAttribute('width'), img.getAttribute('height'), img.getAttribute('decoding')]).toEqual(['1080', '2400', 'async'])
  })

  it('without copies: a plain src, no srcset or sizes', () => {
    viewport(true)
    const plain: CarouselEntry[] = [{ id: 'p', name: 'P', href: null, shots: [{ light: { url: `${BASE}p.png`, width: 1080, height: 2400 }, dark: null, alt: 'p' }] }]
    const img = render(<AppCarousel mode="live" entries={plain} listLabel="Apps" labels={LABELS} />).container.querySelector('[data-hub-panel="p"] img')!
    expect(img.hasAttribute('srcset')).toBe(false)
    expect(img.hasAttribute('sizes')).toBe(false)
  })

  it('wide: only the stage first phone, in the theme that shows, loads eagerly — at high priority', () => {
    viewport(true, true)
    const c = mountRow()
    const eager = [...c.querySelectorAll('img[loading="eager"]')]
    expect(eager).toHaveLength(1)
    expect(eager[0]!.getAttribute('src')).toBe(png('b').url) // entry a, first slide, dark
    expect(eager[0]!.getAttribute('fetchpriority')).toBe('high')
    // Its light twin, the next slide, the other panels and the whole row stay lazy.
    expect(loading(c.querySelectorAll('[data-hub-mobile] img'))).toEqual(Array(6).fill('lazy'))
  })

  it('narrow: the first two cards of the row, in the theme that shows, load eagerly; nothing else', () => {
    viewport(false)
    const c = mountRow()
    const eager = [...c.querySelectorAll('img[loading="eager"]')].map((i) => i.getAttribute('src'))
    expect(eager).toEqual([png('a').url, png('e').url])
    expect(c.querySelectorAll('[data-hub] img[loading="eager"]')).toHaveLength(0)
    expect(c.querySelector('img[fetchpriority]')).toBeNull()
  })

  it('static markup (the hub) is all lazy: the hub script decides once it knows the layout', () => {
    const html = renderToStaticMarkup(<AppCarousel mode="static" cards entries={entries} listLabel="Apps" labels={LABELS} />)
    expect(html).not.toContain('loading="eager"')
    // React writes the attribute as `srcSet`; HTML attribute names are case-insensitive.
    expect(html).toMatch(/ srcset="[^"]+ 540w, [^"]+ 960w"/i)
  })

  it('reads the WebP copies from the sidecar into the manifest, narrowest first', () => {
    const manifests = {
      drive: {
        base: BASE,
        screens: { 'android/phone/en/01-files': { sha256: 'a'.repeat(64), width: 1080, height: 2400 } },
        derivatives: { ['a'.repeat(64)]: [{ width: 960, sha256: 'c'.repeat(64) }, { width: 540, sha256: 'b'.repeat(64) }] },
      },
    }
    const [s] = landingShots('drive', ['01-files'], 'en', 'x', manifests)
    expect(s!.light!.variants).toEqual([
      { url: `${BASE}${'b'.repeat(64)}.webp`, width: 540 },
      { url: `${BASE}${'c'.repeat(64)}.webp`, width: 960 },
    ])
  })

  it('every PNG the landing carousel shows from the committed manifests has its WebP copies', () => {
    for (const app of LANDING_APPS) {
      for (const p of ['android', 'ios'] as const) {
        for (const s of landingShots(app.id, app.screens[p], 'en', 'x', undefined, p)) {
          // Red here: a capture was published by a store-publish.sh without WebP derivatives (an old
          // checkout of it). Run scripts/store-publish.sh from this one: it backfills every missing one.
          for (const image of [s.light, s.dark]) if (image) expect(image.variants?.length, `${image.url} has no WebP copies`).toBeGreaterThan(0)
        }
      }
    }
  })
})
