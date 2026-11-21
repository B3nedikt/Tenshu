// The hero's showcase against its contract (HeroShowcase.tsx): one clock for the row and both
// devices, hover and focus hold it on an app, every icon links to its app's page (a first tap only
// shows the app), the pause button, and every app's frame 1 resolving from the published manifests
// on both devices.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render } from '@testing-library/react'
import i18n from '../../i18n'
import { ADVANCE_MS } from '../carousel/carouselModel'
import { HERO_APPS, desktopShot, landingShots } from '../carousel/landingApps'
import { HeroShowcase } from './HeroShowcase'

function mount() {
  const { container } = render(<HeroShowcase />)
  const icon = (id: string) => container.querySelector<HTMLElement>(`[data-hero-app="${id}"]`)!
  const current = () => container.querySelector('[data-hero-app][aria-current="true"]')?.getAttribute('data-hero-app')
  /** The app each device shows: the index of its shown slide, mapped back to the app. */
  const shown = (device: 'laptop' | 'phone') => {
    const slides = [...container.querySelectorAll(`.hero-${device}__screen > .hero-slide`)]
    return HERO_APPS[slides.findIndex((s) => s.getAttribute('data-shown') === 'true')]?.id
  }
  const toggle = () => container.querySelector<HTMLButtonElement>('.hero-toggle')!
  return { container, icon, current, shown, toggle }
}

/** Advance the clock by [steps] whole advances, one at a time: each next timeout is set after a render. */
const tick = (steps = 1) => {
  for (let i = 0; i < steps; i++) act(() => void vi.advanceTimersByTime(ADVANCE_MS))
}

beforeEach(async () => {
  await i18n.changeLanguage('en')
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
})

describe('HeroShowcase', () => {
  it('every hero app has its frame 1 published for the laptop and the phone, light and dark on the laptop', () => {
    for (const app of HERO_APPS) {
      const laptop = desktopShot(app.id, app.desktop, 'en', 'alt')
      expect(laptop?.light, `${app.id} laptop light`).toBeTruthy()
      expect(laptop?.dark, `${app.id} laptop dark`).toBeTruthy()
      expect(landingShots(app.id, [app.phone], 'en', 'alt', undefined, 'android'), `${app.id} phone`).toHaveLength(1)
    }
  })

  it('a language without its own capture falls back to English', () => {
    expect(desktopShot('drive', '01-files', 'fr', 'alt')?.light?.url).toBe(desktopShot('drive', '01-files', 'en', 'alt')?.light?.url)
  })

  it('one clock moves the row and both devices together, wrapping round', () => {
    const { current, shown } = mount()
    expect([current(), shown('laptop'), shown('phone')]).toEqual(['drive', 'drive', 'drive'])
    tick()
    expect([current(), shown('laptop'), shown('phone')]).toEqual(['photos', 'photos', 'photos'])
    tick(HERO_APPS.length - 1)
    expect([current(), shown('laptop'), shown('phone')]).toEqual(['drive', 'drive', 'drive'])
  })

  it('hovering an icon shows that app and holds the clock until the pointer leaves the row', () => {
    const { container, icon, current, shown } = mount()
    fireEvent.mouseEnter(icon('contacts'))
    expect([current(), shown('laptop')]).toEqual(['contacts', 'contacts'])
    tick(3)
    expect(current()).toBe('contacts')
    fireEvent.mouseLeave(container.querySelector('.hero-apps')!)
    tick()
    expect(current()).toBe('fit')
  })

  it('every icon links to its app\'s own page', () => {
    const { icon } = mount()
    for (const app of HERO_APPS) expect(icon(app.id).getAttribute('href')).toBe(`/apps/${app.id}`)
  })

  /** A click as the browser sends it after a pointerdown of [pointerType]; whether it navigates. */
  const click = (el: HTMLElement, pointerType: 'mouse' | 'touch', between?: () => void) => {
    fireEvent.pointerDown(el, { pointerType })
    between?.()
    const event = new MouseEvent('click', { bubbles: true, cancelable: true, detail: 1 })
    act(() => void el.dispatchEvent(event))
    return !event.defaultPrevented
  }

  it('a mouse click opens the app\'s page', () => {
    const { icon } = mount()
    expect(click(icon('fit'), 'mouse')).toBe(true)
  })

  it('a first tap shows the app and stops the clock; a tap on the showing app opens its page', () => {
    const { icon, current, toggle } = mount()
    expect(click(icon('calendar'), 'touch')).toBe(false)
    expect(current()).toBe('calendar')
    tick(3)
    expect(current()).toBe('calendar')
    expect(toggle().getAttribute('aria-label')).toBe('Play')
    expect(click(icon('calendar'), 'touch')).toBe(true)
  })

  it('a first tap still only shows the app when the browser focuses or hovers the link before its click', () => {
    const { icon, current } = mount()
    const el = icon('contacts')
    // Touch browsers may focus the link, and fire compatibility mouse events, between pointerdown
    // and click — both make the app current before the click decides.
    expect(click(el, 'touch', () => {
      fireEvent.focus(el)
      fireEvent.mouseEnter(el)
    })).toBe(false)
    expect(current()).toBe('contacts')
    expect(click(el, 'touch')).toBe(true)
  })

  it('the pause button sits in the devices, not in the row of apps — one in the laptop, one in the phone', () => {
    const { container, toggle } = mount()
    expect(toggle().closest('.hero-laptop')).not.toBeNull()
    // The narrow layout hides the laptop: the phone carries its own copy (hero.css shows one).
    const phone = container.querySelector<HTMLButtonElement>('.hero-phone .hero-toggle--phone')!
    expect(phone).not.toBeNull()
    act(() => void phone.click())
    expect(toggle().getAttribute('aria-label')).toBe('Play')
    expect(phone.getAttribute('aria-label')).toBe('Play')
    expect(container.querySelector('.hero-apps button')).toBeNull()
  })

  it('the pause button stops and restarts the clock', () => {
    const { current, toggle } = mount()
    act(() => void toggle().click())
    tick(2)
    expect(current()).toBe('drive')
    act(() => void toggle().click())
    tick()
    expect(current()).toBe('photos')
  })

  it('only the shown slide carries alt text and is exposed', () => {
    const { container } = mount()
    const exposed = [...container.querySelectorAll('.hero-laptop__screen > .hero-slide:not([aria-hidden])')]
    expect(exposed).toHaveLength(1)
    expect(exposed[0]!.querySelector('img')!.getAttribute('alt')).toBe('Drive on a laptop')
    const hiddenAlts = [...container.querySelectorAll('.hero-slide[aria-hidden] img')].map((i) => i.getAttribute('alt'))
    expect(hiddenAlts.every((a) => a === '')).toBe(true)
  })
})
