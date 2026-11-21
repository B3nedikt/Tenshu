// The hero's showcase (Block1Hero, docs/proposals/store-screenshot-stories.md §5): the five apps as
// a row of icons, and a laptop and a phone that switch to the same app at the same moment — "your
// data on every device" without a word. Under 48rem the phone stands alone.
//
// The contract (HeroShowcase.test.tsx):
//   - ONE clock drives the row and both devices: every ADVANCE_MS the next app, on both at once;
//   - hovering an icon, or focusing it with the keyboard, shows that app and holds the clock there
//     while the pointer or focus stays;
//   - every icon is a link to that app's own page (landingAppHref, `/apps/<app>`): a mouse click or
//     a keyboard Enter opens it. A TAP (touch or pen, which has no hover) on an app that is not
//     showing first shows it and stops the clock; a tap on the showing app opens its page;
//   - the pause/play button stops and restarts the clock (WCAG 2.2.2). It sits in the laptop
//     screen's bottom-left corner (the phone covers the right) — or, where only the phone
//     shows, in the phone's — and appears only while the pointer is over the devices or keyboard
//     focus reaches it (hero.css): a control in plain sight beside the icons read as one of them;
//   - the clock also stops while the tab is hidden or the devices are off screen;
//   - under reduced motion there is no clock and no fade: Drive stands still, and the icons still
//     switch the app.
//
// Every app's captures are in the markup, stacked in one frame, so switching never reflows and the
// next app's image is already loaded when the fade starts. Light and dark captures both: CSS shows
// the effective theme's (`.shot--light` / `.shot--dark`, appCarousel.css).
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AppIcon } from '../carousel/AppCarousel'
import { ADVANCE_MS, shotSrcset, type CarouselImage, type CarouselShot } from '../carousel/carouselModel'
import { HERO_APPS, LANDING_APPS, desktopShot, landingShots } from '../carousel/landingApps'

/** The laptop screen's width on the page (hero.css), for `sizes`. */
const LAPTOP_SIZES = '(min-width: 48rem) 42rem, 0px'
/** The phone screen's width on the page (hero.css), for `sizes`. */
const PHONE_SIZES = '9rem'

interface HeroApp {
  readonly id: string
  readonly name: string
  readonly icon: (typeof LANDING_APPS)[number]['icon']
  /** The app's own page (landingAppHref). */
  readonly href: string | null
  readonly laptop: CarouselShot | null
  readonly phone: CarouselShot | null
}

export function HeroShowcase() {
  const { t, i18n } = useTranslation('landing')
  const lang = (i18n.resolvedLanguage ?? i18n.language ?? 'en').split('-')[0]!
  const apps: HeroApp[] = HERO_APPS.map((hero) => {
    const name = t(`block7.apps.${hero.id}.name`)
    const landing = LANDING_APPS.find((a) => a.id === hero.id)
    return {
      id: hero.id,
      name,
      icon: landing?.icon ?? null,
      href: landing?.href ?? null,
      laptop: desktopShot(hero.id, hero.desktop, lang, t('block1.laptopAlt', { name })),
      phone: landingShots(hero.id, [hero.phone], lang, t('block1.phoneAlt', { name }), undefined, 'android')[0] ?? null,
    }
  })

  const [current, setCurrent] = useState(0)
  const [stopped, setStopped] = useState(false)
  const [held, setHeld] = useState(false)
  const [tabHidden, setTabHidden] = useState(() => typeof document !== 'undefined' && document.hidden)
  const [offscreen, setOffscreen] = useState(false)
  const reduced = useReducedMotion()
  const devices = useRef<HTMLDivElement>(null)
  // Set by the pointer that starts a click, read by that click: a mouse follows, a tap only shows.
  // Whether the tapped app was ALREADY showing is captured at pointer-down too: a touch browser
  // may focus the link (and fire compatibility mouse events) before the click, which would make
  // it current by the time the click asks.
  const touch = useRef(false)
  const tapWasShowing = useRef(false)
  const running = !reduced && !stopped && !held && !tabHidden && !offscreen && apps.length > 1

  useEffect(() => {
    if (!running) return
    const timer = setTimeout(() => setCurrent((c) => (c + 1) % apps.length), ADVANCE_MS)
    return () => clearTimeout(timer)
  }, [running, current, apps.length])

  useEffect(() => {
    const onVisibility = () => setTabHidden(document.hidden)
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [])

  useEffect(() => {
    const el = devices.current
    if (!el || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((records) => setOffscreen(!records.some((r) => r.isIntersecting)))
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  // Rendered twice, once per device, and CSS shows exactly one (hero.css): the laptop's in the wide
  // layout, the phone's in the narrow one, where the laptop is display:none.
  const toggle = (className: string) => (
    <button
      type="button"
      className={className}
      onClick={() => setStopped((s) => !s)}
      aria-label={stopped ? t('block1.play') : t('block1.pause')}
      title={stopped ? t('block1.play') : t('block1.pause')}
    >
      <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true" fill="currentColor">
        {stopped ? <path d="M8 5.5v13l11-6.5z" /> : <path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" />}
      </svg>
    </button>
  )

  return (
    <div className="hero-show" data-hero-motion={reduced ? 'reduced' : 'full'}>
      <ul className="hero-apps" aria-label={t('block1.appsLabel')} onMouseLeave={() => setHeld(false)}>
        {apps.map((app, i) => (
          <li key={app.id}>
            <a
              className="hero-app"
              href={app.href ?? undefined}
              data-hero-app={app.id}
              aria-current={i === current ? 'true' : undefined}
              onMouseEnter={() => {
                setCurrent(i)
                setHeld(true)
              }}
              onFocus={() => {
                setCurrent(i)
                setHeld(true)
              }}
              onBlur={() => setHeld(false)}
              onPointerDown={(e) => {
                touch.current = e.pointerType !== 'mouse'
                tapWasShowing.current = i === current
              }}
              onClick={(e) => {
                // A tap has no hover to show the app first: the first tap shows it, the next opens it.
                if (touch.current && e.detail !== 0 && !tapWasShowing.current) {
                  e.preventDefault()
                  setCurrent(i)
                  setStopped(true)
                }
              }}
            >
              <AppIcon icon={app.icon} name={app.name} className="hero-app__icon" />
              <span className="hero-app__name">{app.name}</span>
            </a>
          </li>
        ))}
      </ul>

      <div ref={devices} className="hero-devices">
        <div className="hero-laptop">
          <div className="hero-laptop__screen">
            {apps.map((app, i) => (
              <Slide key={app.id} shot={app.laptop} shown={i === current} sizes={LAPTOP_SIZES} eager={i === 0} />
            ))}
          </div>
          <div className="hero-laptop__base" aria-hidden="true" />
          {!reduced && toggle('hero-toggle')}
        </div>
        <div className="hero-phone">
          <div className="hero-phone__screen">
            {apps.map((app, i) => (
              <Slide key={app.id} shot={app.phone} shown={i === current} sizes={PHONE_SIZES} eager={i === 0} />
            ))}
          </div>
          {/* The same control for the narrow layout, where the laptop (and its button) is hidden. */}
          {!reduced && toggle('hero-toggle hero-toggle--phone')}
        </div>
      </div>

    </div>
  )
}

/**
 * One app's capture in a device frame: both themes' images, CSS showing the effective one. Only the
 * shown slide is exposed to assistive technology; the rest are stacked behind it at opacity 0.
 */
function Slide({ shot, shown, sizes, eager }: { readonly shot: CarouselShot | null; readonly shown: boolean; readonly sizes: string; readonly eager: boolean }) {
  const both = shot?.light && shot?.dark
  const img = (image: CarouselImage, theme: 'light' | 'dark' | null) => (
    <img
      key={theme ?? 'one'}
      className={theme ? `hero-shot shot--${theme}` : 'hero-shot'}
      src={image.url}
      srcSet={shotSrcset(image)}
      sizes={sizes}
      width={image.width}
      height={image.height}
      alt={shown ? shot!.alt : ''}
      loading={eager ? 'eager' : 'lazy'}
      decoding="async"
    />
  )
  return (
    <div className="hero-slide" data-shown={shown ? 'true' : 'false'} aria-hidden={shown ? undefined : true}>
      {shot && (both ? [img(shot.light!, 'light'), img(shot.dark!, 'dark')] : img((shot.light ?? shot.dark)!, null))}
    </div>
  )
}

const REDUCED_MOTION = '(prefers-reduced-motion: reduce)'

/** `prefers-reduced-motion: reduce`, live. False where there is no matchMedia (tests). */
function useReducedMotion(): boolean {
  const query = () => (typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(REDUCED_MOTION) : null)
  const [reduced, setReduced] = useState(() => query()?.matches ?? false)
  useEffect(() => {
    const mql = query()
    if (!mql) return
    const onChange = () => setReduced(mql.matches)
    mql.addEventListener?.('change', onChange)
    return () => mql.removeEventListener?.('change', onChange)
  }, [])
  return reduced
}
