/// <reference lib="dom" />
// @vitest-environment jsdom
//
// The hub's carousel script (hub.ts HUB_SCRIPT_BODY) run against the hub's real static markup:
// hover and keyboard focus select, a mouse click follows the link, a tap only selects, the panel's
// button opens the page, and the links follow the visitor's platform; and the one-frame showcase:
// the selected entry's screenshots cycle in a stage that never changes size, with dots, a pause
// button and every hold — the same contract AppCarousel.test.tsx pins for the landing page.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ADVANCE_MS } from '../src/landing/carousel/carouselModel.ts'
import { buildSupportSite } from './generate.ts'
import { loadSupportChrome } from './loadChrome.ts'

const site = await buildSupportSite({ chrome: await loadSupportChrome('test') })
const hub = site.hubPages.find((p) => p.url === '/apps/support/en')!
const body = /<body[^>]*>([\s\S]*)<\/body>/.exec(hub.html)![1]!
const script = /<script>(\(function\(\)\{var cfg=[\s\S]*?)<\/script>/.exec(hub.html)![1]!

// jsdom gives this page no storage (an opaque origin); a Map stands in, cleared per mount.
const stored = new Map<string, string>()
Object.defineProperty(window, 'localStorage', {
  configurable: true,
  value: { getItem: (k: string) => stored.get(k) ?? null, setItem: (k: string, v: string) => void stored.set(k, String(v)), clear: () => stored.clear() },
})

function mount(userAgent = 'Mozilla/5.0 (X11; Linux x86_64)', keep = false) {
  Object.defineProperty(navigator, 'userAgent', { value: userAgent, configurable: true })
  if (!keep) stored.clear()
  document.body.innerHTML = body.replace(/<script>[\s\S]*?<\/script>/g, '')
  new Function(script)()
}
const name = (id: string) => document.querySelector<HTMLElement>(`[data-hub-name="${id}"]`)!
const selected = () => [...document.querySelectorAll('[data-hub-panel].is-selected')].map((p) => p.getAttribute('data-hub-panel'))
function pointer(el: Element, type: string) {
  const e = new Event('pointerdown', { bubbles: true })
  Object.defineProperty(e, 'pointerType', { value: type })
  el.dispatchEvent(e)
}
/** A click as the browser sends it; returns whether the default (navigation) went ahead. */
function click(el: Element, detail = 1) {
  // Read at the window, after the hub's handlers ran, then stopped: jsdom cannot navigate.
  let followed = false
  window.addEventListener('click', (e) => { followed = !e.defaultPrevented; e.preventDefault() }, { once: true })
  el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, detail }))
  return followed
}

describe('the hub carousel', () => {
  beforeEach(() => mount())

  it('moves every panel into one stage between the two lists, Getting started selected', () => {
    const hubEl = document.querySelector('[data-hub]')!
    expect(hubEl.classList.contains('is-enhanced')).toBe(true)
    expect([...hubEl.children].map((c) => c.className)).toEqual(['hub-list', 'hub-stage', 'hub-list'])
    expect(document.querySelectorAll('.hub-stage > [data-hub-panel]')).toHaveLength(11)
    expect(selected()).toEqual(['getting-started'])
    expect(name('getting-started').getAttribute('aria-current')).toBe('true')
  })

  it('hovering or focusing a name selects it, without a click', () => {
    name('drive').dispatchEvent(new MouseEvent('mouseenter'))
    expect(selected()).toEqual(['drive'])
    name('control').focus()
    expect(selected()).toEqual(['control'])
    expect(name('drive').hasAttribute('aria-current')).toBe(false)
  })

  it('a mouse click follows the link; a keyboard Enter does too', () => {
    pointer(name('drive'), 'mouse')
    expect(click(name('drive'))).toBe(true)
    expect(click(name('control'), 0)).toBe(true)
  })

  it('a tap only selects; the panel\'s button opens the page', () => {
    pointer(name('drive'), 'touch')
    expect(click(name('drive'))).toBe(false)
    expect(selected()).toEqual(['drive'])
    const button = document.querySelector<HTMLAnchorElement>('[data-hub-panel="drive"] .hub-open')!
    expect(button.textContent).toBe('Open Drive support')
    expect(click(button)).toBe(true)
  })

  it('a coming-soon name is reachable by keyboard and shows its placeholder', () => {
    expect(name('maps').tagName).toBe('SPAN')
    expect(name('maps').getAttribute('tabindex')).toBe('0')
    pointer(name('maps'), 'touch')
    click(name('maps'))
    expect(selected()).toEqual(['maps'])
    expect(document.querySelector('[data-hub-panel="maps"] .hub-placeholder')).not.toBeNull()
  })

  it('points the support links at the visitor\'s platform when the app has a page for it', () => {
    mount('Mozilla/5.0 (Linux; Android 15; Pixel 9)')
    for (const a of document.querySelectorAll('a[data-hub-app="drive"]')) expect(a.getAttribute('href')).toBe('/apps/drive/support/android/en')
    // Linux has no Drive page: the static default stays.
    mount('Mozilla/5.0 (X11; Linux x86_64)')
    for (const a of document.querySelectorAll('a[data-hub-app="drive"]')) expect(a.getAttribute('href')).toBe('/apps/drive/support/web/en')
  })
})

describe('the hub carousel, iPhone | Android', () => {
  const MAC = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15'
  const WINDOWS = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36'
  const ANDROID = 'Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36'
  const shownOf = (id: string) => document.querySelector(`[data-hub-panel="${id}"] [data-hub-viewer].is-shown`)!.getAttribute('data-hub-platform')
  const pick = (id: string, p: string) => document.querySelector<HTMLButtonElement>(`[data-hub-panel="${id}"] [data-hub-pick="${p}"]`)!
  afterEach(() => {
    Reflect.deleteProperty(navigator, 'userAgentData')
  })

  it('defaults by OS: an iPhone on a Mac, Android on Windows and Android', () => {
    for (const [ua, want] of [[MAC, 'ios'], [WINDOWS, 'android'], [ANDROID, 'android']] as const) {
      mount(ua)
      expect(document.querySelector('[data-hub]')!.getAttribute('data-shot-platform'), ua).toBe(want)
      for (const id of ['getting-started', 'control', 'drive']) expect(shownOf(id), `${ua} ${id}`).toBe(want)
      expect(pick('drive', want).getAttribute('aria-pressed')).toBe('true')
    }
  })

  it('prefers navigator.userAgentData.platform over the user agent string', () => {
    Object.defineProperty(navigator, 'userAgentData', { value: { platform: 'macOS' }, configurable: true })
    mount(WINDOWS)
    expect(shownOf('drive')).toBe('ios')
  })

  it('reveals the switch, and the choice applies to every entry and survives a reload', () => {
    mount(WINDOWS)
    expect(document.querySelector('[data-hub-panel="drive"] .hub-platform')!.hasAttribute('hidden')).toBe(false)
    name('drive').dispatchEvent(new MouseEvent('mouseenter'))
    pick('drive', 'ios').click()
    expect(shownOf('drive')).toBe('ios')
    expect(shownOf('control')).toBe('ios')
    expect(pick('drive', 'ios').getAttribute('aria-pressed')).toBe('true')
    expect(pick('drive', 'android').getAttribute('aria-pressed')).toBe('false')
    expect(localStorage.getItem('tenshu.shotPlatform')).toBe('ios')
    mount(WINDOWS, true)
    expect(shownOf('drive')).toBe('ios')
  })

  it('an entry with one platform\'s captures shows those, its switch disabled with a note', () => {
    Object.defineProperty(navigator, 'userAgent', { value: MAC, configurable: true })
    stored.clear()
    document.body.innerHTML = body.replace(/<script>[\s\S]*?<\/script>/g, '')
    // Drive without its iPhone set, as an app whose iOS captures are not published yet is built.
    document.querySelector('[data-hub-panel="drive"] [data-hub-platform="ios"]')!.remove()
    new Function(script)()
    expect(shownOf('drive')).toBe('android')
    expect(shownOf('control')).toBe('ios')
    expect(pick('drive', 'android').getAttribute('aria-pressed')).toBe('true')
  })
})

describe('the hub carousel, one frame', () => {
  let reduced = false
  beforeEach(() => {
    vi.useFakeTimers()
    reduced = false
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: reduced && query === '(prefers-reduced-motion: reduce)', addEventListener: () => {} }))
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    Object.defineProperty(document, 'hidden', { value: false, configurable: true })
  })

  const panel = (id: string) => document.querySelector<HTMLElement>(`[data-hub-panel="${id}"]`)!
  // The viewer the switch shows (Linux: Android).
  const view = (id = 'drive') => panel(id).querySelector<HTMLElement>('[data-hub-viewer].is-shown')!
  const current = (id = 'drive') => [...view(id).querySelectorAll('[data-hub-slide]')].findIndex((s) => s.classList.contains('is-current'))
  const dots = (id = 'drive') => [...view(id).querySelectorAll<HTMLButtonElement>('[data-hub-dot]')]
  const rotate = (id = 'drive') => view(id).querySelector<HTMLButtonElement>('[data-hub-rotate]')!
  const tick = (ms = ADVANCE_MS) => vi.advanceTimersByTime(ms)
  function showDrive() {
    mount()
    name('drive').dispatchEvent(new MouseEvent('mouseenter'))
  }

  it('starts on Getting started (one screenshot): no controls, no timer', () => {
    mount()
    expect(panel('getting-started').querySelector('.hub-controls')).toBeNull()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('cycles the selected entry\'s screenshots every ADVANCE_MS and loops; the selection stays', () => {
    showDrive()
    expect(current()).toBe(0)
    tick(ADVANCE_MS - 1)
    expect(current()).toBe(0)
    tick(1)
    expect(current()).toBe(1)
    tick()
    expect(current()).toBe(2)
    tick()
    expect(current()).toBe(0)
    tick(ADVANCE_MS * 7)
    expect(selected()).toEqual(['drive'])
  })

  it('reveals the controls and adds the APG carousel semantics', () => {
    showDrive()
    const viewer = view('drive')
    expect(viewer.getAttribute('role')).toBe('region')
    expect(viewer.getAttribute('aria-roledescription')).toBe('carousel')
    expect(viewer.getAttribute('aria-label')).toBe('Drive')
    expect(viewer.querySelector('.hub-controls')!.hasAttribute('hidden')).toBe(false)
    const slides = [...viewer.querySelectorAll('[data-hub-slide]')]
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
    expect(viewer.querySelector('.hub-shots')!.getAttribute('aria-live')).toBe('off')
  })

  it('pauses while the pointer is over the phone or the dots', () => {
    showDrive()
    const shots = view('drive').querySelector('.hub-shots')!
    shots.dispatchEvent(new MouseEvent('mouseenter'))
    tick(ADVANCE_MS * 3)
    expect(current()).toBe(0)
    expect(shots.getAttribute('aria-live')).toBe('polite')
    shots.dispatchEvent(new MouseEvent('mouseleave'))
    tick()
    expect(current()).toBe(1)
    const row = view('drive').querySelector('.hub-dots')!
    row.dispatchEvent(new MouseEvent('mouseenter'))
    tick(ADVANCE_MS * 3)
    expect(current()).toBe(1)
    row.dispatchEvent(new MouseEvent('mouseleave'))
    tick()
    expect(current()).toBe(2)
  })

  it('pauses while keyboard focus is in the dots', () => {
    showDrive()
    const real = Element.prototype.matches
    vi.spyOn(Element.prototype, 'matches').mockImplementation(function (this: Element, selector: string) {
      return selector === ':focus-visible' ? true : real.call(this, selector)
    })
    dots()[1]!.focus()
    tick(ADVANCE_MS * 3)
    expect(current()).toBe(0)
    dots()[1]!.blur()
    tick()
    expect(current()).toBe(1)
  })

  it('pauses while the tab is hidden', () => {
    showDrive()
    Object.defineProperty(document, 'hidden', { value: true, configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    tick(ADVANCE_MS * 3)
    expect(current()).toBe(0)
    Object.defineProperty(document, 'hidden', { value: false, configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    tick()
    expect(current()).toBe(1)
  })

  it('the pause button stops and restarts the cycle, and its label says which', () => {
    showDrive()
    expect(rotate().getAttribute('aria-label')).toBe('Pause screenshots')
    rotate().click()
    expect(rotate().getAttribute('aria-label')).toBe('Play screenshots')
    expect(rotate().getAttribute('data-state')).toBe('stopped')
    expect(view('drive').querySelector('.hub-shots')!.getAttribute('aria-live')).toBe('polite')
    tick(ADVANCE_MS * 3)
    expect(current()).toBe(0)
    rotate().click()
    expect(rotate().getAttribute('aria-label')).toBe('Pause screenshots')
    tick()
    expect(current()).toBe(1)
  })

  it('a dot picks its screenshot; selecting another entry shows its first and restarts', () => {
    showDrive()
    dots()[2]!.click()
    expect(current()).toBe(2)
    expect(dots().map((d) => d.getAttribute('aria-current'))).toEqual([null, null, 'true'])
    name('control').dispatchEvent(new MouseEvent('mouseenter'))
    expect(current('control')).toBe(0)
    tick(ADVANCE_MS - 1)
    expect(current('control')).toBe(0)
    tick(1)
    expect(current('control')).toBe(1)
    name('drive').dispatchEvent(new MouseEvent('mouseenter'))
    expect(current('drive')).toBe(0)
  })

  it('under reduced motion: no timer, no pause button; the dots still pick', () => {
    reduced = true
    showDrive()
    expect(vi.getTimerCount()).toBe(0)
    tick(ADVANCE_MS * 3)
    expect(current()).toBe(0)
    expect(rotate().hasAttribute('hidden')).toBe(true)
    dots()[1]!.click()
    expect(current()).toBe(1)
  })

  // ⚠ The reason for the one frame: nothing on the carousel may change with the selection, or the
  // CSS could size the stage by it and the names would move under the pointer again.
  it('keeps the same stage markup whatever is selected: no per-count attribute', () => {
    mount()
    const hubEl = document.querySelector('[data-hub]')!
    const stage = document.querySelector('.hub-stage')!
    const attrs = (el: Element) => [...el.attributes].map((a) => `${a.name}=${a.value}`).sort()
    const before = { hub: attrs(hubEl), stage: attrs(stage) }
    for (const id of ['drive', 'control', 'photos', 'getting-started']) {
      name(id).dispatchEvent(new MouseEvent('mouseenter'))
      expect({ hub: attrs(hubEl), stage: attrs(stage) }, id).toEqual(before)
    }
    expect(hubEl.hasAttribute('data-stage-count')).toBe(false)
    expect(document.body.innerHTML).not.toMatch(/data-stage-count|data-count/)
  })
})

// The narrow layout's row of cards (AppCarousel.tsx CardRow) — the same contract
// AppCarousel.test.tsx pins for the landing page's live row.
describe('the hub carousel, the narrow row of cards', () => {
  const MAC = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15'
  let reduced = false
  beforeEach(() => {
    reduced = false
    // `matches` live, as a real MediaQueryList's is: the script reads it at each scroll.
    vi.stubGlobal('matchMedia', (query: string) => ({
      get matches() {
        return reduced && query === '(prefers-reduced-motion: reduce)'
      },
      addEventListener: () => {},
    }))
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })
  const mobile = () => document.querySelector<HTMLElement>('[data-hub-mobile]')!
  const row = () => document.querySelector<HTMLElement>('[data-hub-row]')!
  const card = (id: string) => row().querySelector<HTMLElement>(`[data-hub-card="${id}"]`)!
  const dots = () => [...document.querySelectorAll<HTMLButtonElement>('[data-hub-show]')]
  /** Cards 300px wide every 310px in a 390px row; returns the row's scrollTo spy. */
  function layOut() {
    Object.defineProperty(row(), 'clientWidth', { value: 390, configurable: true })
    row().querySelectorAll<HTMLElement>('[data-hub-card]').forEach((c, i) => {
      Object.defineProperty(c, 'offsetLeft', { value: 45 + i * 310, configurable: true })
      Object.defineProperty(c, 'offsetWidth', { value: 300, configurable: true })
    })
    const scrollTo = vi.fn((opts: ScrollToOptions) => {
      row().scrollLeft = opts.left ?? 0
    })
    row().scrollTo = scrollTo as unknown as typeof HTMLElement.prototype.scrollTo
    return scrollTo
  }

  it('without the script: the row and its cards are there, the switch and the indicator hidden', () => {
    document.body.innerHTML = body.replace(/<script>[\s\S]*?<\/script>/g, '')
    expect(mobile().previousElementSibling!.hasAttribute('data-hub')).toBe(true)
    expect(row().getAttribute('role')).toBe('region')
    expect(mobile().querySelector('.hub-m__switch')!.hasAttribute('hidden')).toBe(true)
    expect(mobile().querySelector('.hub-m__nav')!.hasAttribute('hidden')).toBe(true)
    expect(mobile().hasAttribute('data-shot-platform')).toBe(false)
  })

  it('Getting started is the first card; every card a named group; linked cards are links, coming-soon ones are not', () => {
    mount()
    const groups = [...row().children]
    expect(groups[0]!.getAttribute('data-hub-card')).toBe('getting-started')
    expect(groups).toHaveLength(11)
    for (const g of groups) expect(g.getAttribute('role')).toBe('group')
    expect(card('drive').getAttribute('aria-label')).toBe('Drive')
    const link = card('drive').querySelector('a')!
    expect(link.getAttribute('aria-label')).toBe('Open Drive support')
    expect(link.querySelector('.hub-mcard__cta')!.textContent).toBe('Open Drive support')
    expect(card('getting-started').querySelector('a')!.getAttribute('href')).toBe('/apps/support/getting-started/en')
    expect(card('maps').querySelector('a')).toBeNull()
    expect(card('maps').getAttribute('tabindex')).toBe('0')
    expect(card('maps').querySelector('.hub-mcard__soon')!.textContent).toBe('Coming soon')
  })

  it('reveals the switch and the indicator; the switch drives the row and the stage alike', () => {
    mount()
    expect(mobile().querySelector('.hub-m__switch')!.hasAttribute('hidden')).toBe(false)
    expect(mobile().querySelector('.hub-m__nav')!.hasAttribute('hidden')).toBe(false)
    expect(mobile().getAttribute('data-shot-platform')).toBe('android')
    mobile().querySelector<HTMLButtonElement>('.hub-m__switch [data-hub-pick="ios"]')!.click()
    expect(mobile().getAttribute('data-shot-platform')).toBe('ios')
    expect(document.querySelector('[data-hub]')!.getAttribute('data-shot-platform')).toBe('ios')
    expect(mobile().querySelector('.hub-m__switch [data-hub-pick="ios"]')!.getAttribute('aria-pressed')).toBe('true')
    expect(localStorage.getItem('tenshu.shotPlatform')).toBe('ios')
    // And the stage's switch moves the row's.
    document.querySelector<HTMLButtonElement>('[data-hub-panel="drive"] [data-hub-pick="android"]')!.click()
    expect(mobile().getAttribute('data-shot-platform')).toBe('android')
    expect(mobile().querySelector('.hub-m__switch [data-hub-pick="android"]')!.getAttribute('aria-pressed')).toBe('true')
  })

  it('defaults by OS like the stage, and points the cards\' links at the visitor\'s platform', () => {
    mount(MAC)
    expect(mobile().getAttribute('data-shot-platform')).toBe('ios')
    expect(card('drive').querySelector('a')!.getAttribute('href')).toBe(document.querySelector('[data-hub-name="drive"]')!.getAttribute('href'))
    expect(card('drive').querySelector('a')!.getAttribute('href')).toMatch(/^\/apps\/drive\/support\/macos\/en$/)
  })

  it('the indicator: one "Show X" button per card, the first current; a tap scrolls to its card', () => {
    mount()
    const scrollTo = layOut()
    expect(dots()).toHaveLength(11)
    expect(dots()[2]!.getAttribute('aria-label')).toBe('Show Drive')
    expect(dots()[0]!.getAttribute('aria-current')).toBe('true')
    dots()[2]!.click()
    expect(scrollTo).toHaveBeenLastCalledWith({ left: 620, behavior: 'smooth' })
    expect(dots()[2]!.getAttribute('aria-current')).toBe('true')
    expect(dots()[0]!.hasAttribute('aria-current')).toBe(false)
  })

  it('follows the scroll position: swiping to the third card makes its button current', async () => {
    mount()
    layOut()
    row().scrollLeft = 620
    row().dispatchEvent(new Event('scroll'))
    await new Promise((r) => setTimeout(r, 50))
    expect(dots().findIndex((d) => d.hasAttribute('aria-current'))).toBe(2)
  })

  it('keyboard: focus scrolls the card into view, the arrow keys move between cards; reduced motion jumps', () => {
    mount()
    const scrollTo = layOut()
    const control = card('control').querySelector('a')!
    control.focus()
    expect(scrollTo).toHaveBeenLastCalledWith({ left: 310, behavior: 'smooth' })
    control.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }))
    expect(document.activeElement).toBe(card('drive').querySelector('a'))
    document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }))
    expect(document.activeElement).toBe(card('photos').querySelector('a'))
    reduced = true
    document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true, cancelable: true }))
    expect(scrollTo).toHaveBeenLastCalledWith({ left: 620, behavior: 'auto' })
  })
})
