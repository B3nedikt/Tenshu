// The app carousel: app names arranged around a stage that shows the selected entry. One component
// for two pages, in two modes (docs/proposals/web-about-tenshu-public.md §9,
// store-presence-and-support-pages.md §4.7):
//
//   - `static` — the support hub (support-pages/generate.ts, via supportChrome.tsx's
//     renderToStaticMarkup). Two plain lists of cards, every entry its name, its screenshots and a
//     real link: what a crawler, App Review and a visitor without JavaScript get. The hub's inline
//     script (support-pages/hub.ts HUB_SCRIPT_BODY) then moves the panels into one stage and wires
//     the interaction below. Never hydrated.
//   - `live` — the landing page's Apps section (Block7Apps). The SPA has no no-JS state to keep, so
//     it renders the enhanced layout directly — the exact DOM the hub script produces — and React
//     does what the script does.
//
// The interaction contract, the same in both (pinned by hub-script.test.ts and
// AppCarousel.test.tsx):
//   - hovering a name, or focusing it with the keyboard, selects it;
//   - with a mouse, a click on a linked name then follows the link; a keyboard Enter (`detail === 0`)
//     follows it too;
//   - a tap (touch or pen) only selects; the hub panel's button opens the entry (on a landing card,
//     which has no button, the card's name and the phone do);
//   - a name with no link is a focusable <span>, so the keyboard reaches it;
//   - the stage is ONE phone frame, the same size whatever is selected, so the names never move;
//   - on wide screens the two lists mirror each other around the stage, every icon on the stage side
//     of its name, so each side's icons form one straight column (appCarousel.css);
//   - the selected entry's screenshots cross-fade inside that frame every ADVANCE_MS (selecting an
//     entry shows its first one and restarts the cycle); the SELECTION never changes on its own;
//   - the dots under the phone pick a screenshot; the pause/play button stops and restarts the
//     cycle (WCAG 2.2.2; the W3C APG Carousel pattern: `aria-roledescription` carousel/slide,
//     `aria-live` off while rotating and polite when not);
//   - the cycle pauses while the pointer is over the phone or the dots, while keyboard focus is in
//     them, while the tab is hidden and while the stage is offscreen;
//   - under reduced motion there is no cycle and no fade (appCarousel.css), and no pause button;
//   - the "iPhone | Android" switch, at the end of the dots' row (pause and dots at its start, the
//     row as wide as the phone; appCarousel.css), picks which platform's captures EVERY entry
//     shows, each in its own device frame; the stored choice (`tenshu.shotPlatform`) else the OS
//     rule (hub.ts defaultShotPlatform). An entry with captures on one platform only shows those,
//     its switch disabled with a note. Without JavaScript: Android, no switch.
//
// ⚠ The classes are `hub-*` and the attributes `data-hub-*` because the hub's script, its tests and
// its no-JS CSS were written against them first. They are the carousel's names now, on both pages.
import { useEffect, useRef, useState, type FocusEvent, type KeyboardEvent, type MouseEvent, type PointerEvent, type ReactNode } from 'react'
import { defaultShotPlatform } from '../../../support-pages/hub.ts'
import {
  ADVANCE_MS,
  CAROUSEL_SHOT_SIZES,
  SHOT_PLATFORM_STORAGE_KEY,
  oneFrameVendor,
  platformSets,
  shotSrcset,
  shownPlatform,
  splitEntries,
  type CarouselEntry,
  type CarouselIcon,
  type CarouselLabels,
  type CarouselShot,
  type ShotPlatform,
} from './carouselModel'

export interface AppCarouselProps {
  readonly entries: readonly CarouselEntry[]
  /** The aria-label of the two lists of names ("Tenshu apps"). */
  readonly listLabel: string
  /**
   * `static` for renderToStaticMarkup (the hub, enhanced by its own script); `live` for a React
   * page (the landing). See the file comment.
   */
  readonly mode: 'static' | 'live'
  /** `live` only: the entry selected first. Defaults to the first one. */
  readonly initial?: string
  /**
   * Each panel as a card — icon, name, tagline (and its note) beside the phone on wide screens,
   * above it on narrow ones — the way the landing page shows an app. A "card" is a layout, not a
   * box: no background, border or radius, the phone's own frame is the only box on the stage. A card has no button: its name
   * and its phone are the entry's links. Without it a panel is the phone and the "Open X support"
   * button, the hub's look.
   */
  readonly cards?: boolean
  /**
   * One quiet line under the carousel. Reserved for the trademark note that has to accompany
   * pre-framed device images or store badges once either is shown; nothing passes it yet.
   */
  readonly footnote?: string
  /** The rotation controls' copy: the dots' (and slides') labels and the pause/play button's. */
  readonly labels: CarouselLabels
}


const OPEN_CLASSES = 'hub-open rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm hover:bg-primary/90'

export function AppCarousel(raw: AppCarouselProps) {
  // ⚠ Never two vendors' device frames on one stage (carouselModel.ts oneFrameVendor).
  const props = { ...raw, entries: oneFrameVendor(raw.entries) }
  const carousel = props.mode === 'static' ? <StaticCarousel {...props} /> : <LiveCarousel {...props} />
  if (!props.footnote) return carousel
  return (
    <>
      {carousel}
      <p className="hub-footnote">{props.footnote}</p>
    </>
  )
}

function StaticCarousel({ entries, listLabel, cards = false, labels }: AppCarouselProps) {
  const lists = splitEntries(entries)
  return (
    <>
      <div className={rootClass(cards, false)} data-hub="">
        {lists.map((list, i) => (
          <ul key={i} className="hub-list" aria-label={listLabel}>
            {list.map((entry) => (
              <li key={entry.id} className={entryClass(entry)}>
                <EntryName entry={entry} />
                <Panel entry={entry} cards={cards} labels={labels} />
              </li>
            ))}
          </ul>
        ))}
      </div>
      <CardRow entries={entries} listLabel={listLabel} cards={cards} labels={labels} />
    </>
  )
}

function LiveCarousel({ entries, listLabel, initial, cards = false, labels }: AppCarouselProps) {
  const [selected, setSelected] = useState(initial ?? entries[0]?.id)
  // The screenshot showing in the selected entry; every other panel rests on its first.
  const [slide, setSlide] = useState(0)
  // The pause/play button's state: the visitor's own choice, kept across selections.
  const [stopped, setStopped] = useState(false)
  // Transient holds: pointer over the phone or dots, focus in them, the tab hidden, the stage away.
  const [hover, setHover] = useState(false)
  const [focusIn, setFocusIn] = useState(false)
  const [tabHidden, setTabHidden] = useState(() => typeof document !== 'undefined' && document.hidden)
  const [offscreen, setOffscreen] = useState(false)
  const reduced = useReducedMotion()
  const [platform, setPlatform] = useState<ShotPlatform>(initialShotPlatform)
  // Which layout and theme the page opened in: the screenshots on screen then load eagerly, the
  // rest lazily (EagerShot). Read once — later changes only ever reveal lazy images, which load.
  const [view] = useState(initialView)
  const stage = useRef<HTMLDivElement>(null)
  // Set by the pointer that starts a click, read by that click: a mouse follows the link, a tap
  // only selects. A ref, not state: it must not re-render, and the click reads it synchronously.
  const touch = useRef(false)
  const [left, right] = splitEntries(entries)
  const selectedEntry = entries.find((e) => e.id === selected)
  const count = selectedEntry ? (platformSets(selectedEntry).find((set) => set.platform === shownPlatform(selectedEntry, platform))?.shots.length ?? 0) : 0
  const running = count > 1 && !reduced && !stopped && !hover && !focusIn && !tabHidden && !offscreen

  useEffect(() => {
    if (!running) return
    const timer = setTimeout(() => setSlide((s) => (s + 1) % count), ADVANCE_MS)
    return () => clearTimeout(timer)
  }, [running, slide, selected, count, platform])

  useEffect(() => {
    const onVisibility = () => setTabHidden(document.hidden)
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [])

  // The STAGE, not the root: under 64rem the stage is display:none (the card row shows instead,
  // CardRow), so it never intersects and nothing rotates out of sight.
  useEffect(() => {
    const el = stage.current
    if (!el || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((records) => setOffscreen(!records.some((r) => r.isIntersecting)))
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  const select = (id: string) => {
    if (id === selected) return
    setSelected(id)
    setSlide(0)
    setHover(false)
    setFocusIn(false)
  }

  const names = (list: readonly CarouselEntry[]) => (
    <ul className="hub-list" aria-label={listLabel}>
      {list.map((entry) => (
        <li key={entry.id} className={entryClass(entry)}>
          <EntryName
            entry={entry}
            current={entry.id === selected}
            focusable
            onSelect={() => select(entry.id)}
            onPointerDown={(e) => {
              touch.current = e.pointerType !== 'mouse'
            }}
            onClick={(e) => {
              if (entry.href !== null && (e.detail === 0 || !touch.current)) return
              e.preventDefault()
              select(entry.id)
            }}
          />
        </li>
      ))}
    </ul>
  )

  const live: LiveRotation = {
    running,
    stopped,
    reduced,
    hold: {
      onMouseEnter: () => setHover(true),
      onMouseLeave: () => setHover(false),
      // KEYBOARD focus holds it; a dot clicked with the mouse is held by the hover, and lets go
      // when the pointer leaves (otherwise one click would stop the cycle until the next click).
      onFocus: (e) => setFocusIn(keyboardFocus(e.target)),
      onBlur: (e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setFocusIn(false)
      },
    },
    pick: setSlide,
    toggle: () => setStopped((s) => !s),
    platform,
    choose: (p) => {
      if (p === platform) return
      storeShotPlatform(p)
      setPlatform(p)
      setSlide(0)
      setHover(false)
      setFocusIn(false)
    },
  }

  return (
    <>
    <div className={rootClass(cards, true)} data-hub="" data-shot-platform={platform}>
      {names(left)}
      <div ref={stage} className="hub-stage">
        {entries.map((entry) => (
          <Panel
            key={entry.id}
            entry={entry}
            cards={cards}
            labels={labels}
            selected={entry.id === selected}
            live={live}
            current={entry.id === selected ? slide : 0}
            eager={view.wide && entry.id === selected ? view.theme : undefined}
          />
        ))}
      </div>
      {names(right)}
    </div>
    <CardRow entries={entries} listLabel={listLabel} cards={cards} labels={labels} live={{ platform, choose: live.choose }} eager={view.wide ? undefined : view.theme} />
    </>
  )
}

/** What a live panel needs to run its rotation: the state and the handlers. */
interface LiveRotation {
  readonly running: boolean
  readonly stopped: boolean
  readonly reduced: boolean
  /** On the phone and the dots: hovering or focusing them holds the cycle. */
  readonly hold: {
    readonly onMouseEnter: () => void
    readonly onMouseLeave: () => void
    readonly onFocus: (e: FocusEvent<HTMLElement>) => void
    readonly onBlur: (e: FocusEvent<HTMLElement>) => void
  }
  readonly pick: (index: number) => void
  readonly toggle: () => void
  /** The visitor's platform choice, and the switch's handler. */
  readonly platform: ShotPlatform
  readonly choose: (platform: ShotPlatform) => void
}

/** The stored choice, else the OS rule (hub.ts defaultShotPlatform — the hub script runs the same source). */
function initialShotPlatform(): ShotPlatform {
  try {
    const stored = window.localStorage.getItem(SHOT_PLATFORM_STORAGE_KEY)
    if (stored === 'ios' || stored === 'android') return stored
  } catch {
    // Blocked storage: the OS rule decides.
  }
  if (typeof navigator === 'undefined') return 'android'
  const uaData = (navigator as Navigator & { readonly userAgentData?: { readonly platform?: string } }).userAgentData
  return defaultShotPlatform(uaData?.platform ?? '', navigator.userAgent)
}

function storeShotPlatform(platform: ShotPlatform) {
  try {
    window.localStorage.setItem(SHOT_PLATFORM_STORAGE_KEY, platform)
  } catch {
    // A per-viewer convenience: without storage the choice lasts until the page reloads.
  }
}

/**
 * The appearance whose capture shows (`.shot--light` / `.shot--dark`, appCarousel.css): the
 * `data-theme` the theme switch set, else the OS preference — the same rule the stylesheet applies.
 */
type Appearance = 'light' | 'dark'

/** The layout (the wide stage from 64rem, else the card row) and the theme the page opened in. */
function initialView(): { readonly wide: boolean; readonly theme: Appearance } {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return { wide: true, theme: 'light' }
  const set = document.documentElement.getAttribute('data-theme')
  const dark = set === 'dark' || (set !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  return { wide: window.matchMedia('(min-width: 64rem)').matches, theme: dark ? 'dark' : 'light' }
}

/** `:focus-visible` — the browser's own "this focus came from the keyboard". Unknown: assume so. */
function keyboardFocus(el: Element): boolean {
  try {
    return el.matches(':focus-visible')
  } catch {
    return true
  }
}

const REDUCED_MOTION = '(prefers-reduced-motion: reduce)'

/** `prefers-reduced-motion: reduce`, live. False where there is no matchMedia (tests, SSR). */
function useReducedMotion(): boolean {
  const query = () => typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(REDUCED_MOTION) : null
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

const rootClass = (cards: boolean, enhanced: boolean) =>
  ['hub', enhanced && 'is-enhanced', cards && 'hub--cards'].filter(Boolean).join(' ')

const entryClass = (entry: CarouselEntry) => (entry.soonLabel ? 'hub-entry hub-entry--soon' : 'hub-entry')

/** The props every link of an entry carries: the class, the href and the hub script's app id. */
function linkProps(entry: CarouselEntry, className: string) {
  return { className, href: entry.href ?? undefined, 'data-hub-app': entry.app }
}

function EntryName({
  entry,
  current = false,
  focusable = false,
  onSelect,
  onPointerDown,
  onClick,
}: {
  readonly entry: CarouselEntry
  readonly current?: boolean
  /** A name with no link gets `tabIndex=0` (live; the hub script adds it to the static markup). */
  readonly focusable?: boolean
  readonly onSelect?: () => void
  readonly onPointerDown?: (e: PointerEvent<HTMLElement>) => void
  readonly onClick?: (e: MouseEvent<HTMLElement>) => void
}) {
  const content: ReactNode = (
    <>
      {entry.icon !== undefined && <AppIcon icon={entry.icon} name={entry.name} className="hub-name__icon" />}
      {entry.name}
      {entry.soonLabel && (
        <>
          {' '}
          <span className="hub-soon">{entry.soonLabel}</span>
        </>
      )}
    </>
  )
  const handlers = onSelect
    ? { onMouseEnter: onSelect, onFocus: onSelect, onPointerDown, onClick, 'aria-current': current ? ('true' as const) : undefined }
    : {}
  return entry.href === null ? (
    <span className="hub-name" data-hub-name={entry.id} tabIndex={focusable ? 0 : undefined} {...handlers}>
      {content}
    </span>
  ) : (
    <a {...linkProps(entry, 'hub-name')} data-hub-name={entry.id} {...handlers}>
      {content}
    </a>
  )
}

/**
 * One entry's panel: (on a card, its text) the phone, under it ONE row: the rotation controls at
 * its start, the "iPhone | Android" switch at its end (the switch is the panel's, the controls each
 * viewer's; CSS lays them on one grid row of .hub-device, appCarousel.css). The phone is one viewer per platform the entry has captures for —
 * `[data-hub-viewer][data-hub-platform]`, Android first — stacked in one grid cell
 * (`.hub-views`), so switching never resizes anything; only the shown one is visible. Each viewer
 * is ONE frame slot — `.hub-shots` — holding its screenshots as slides; on the stage only the
 * current one shows (appCarousel.css). Without the script (static, before the hub script runs) the
 * first viewer's slides sit side by side, the other viewer, the controls and the switch are hidden.
 *
 * ⚠ The screenshots are the same link as the button (on a card, as the card's name): out of the tab
 * order and hidden from screen readers, so that one is the named link per entry. A slide's own
 * label ("Screenshot 2 of 3: Drive") is what a screen reader gets of it.
 */
function Panel({
  entry,
  cards,
  labels,
  selected,
  live,
  current = 0,
  eager,
}: {
  readonly entry: CarouselEntry
  readonly cards: boolean
  readonly labels: CarouselLabels
  readonly selected?: boolean
  /** `live` only; absent in static markup, which the hub script enhances the same way. */
  readonly live?: LiveRotation
  readonly current?: number
  /** `live` only: this panel's phone is on screen at load, in this theme (EagerShot). */
  readonly eager?: Appearance
}) {
  const sets = platformSets(entry)
  const shown = live ? shownPlatform(entry, live.platform) : (sets[0]?.platform ?? null)
  const viewers = sets.length ? (
    sets.map((set) => (
      <Viewer
        key={set.platform}
        entry={entry}
        platform={set.platform}
        shots={set.shots}
        labels={labels}
        live={live}
        shown={set.platform === shown}
        current={set.platform === shown ? current : 0}
        eager={set.platform === shown ? eager : undefined}
      />
    ))
  ) : (
    <div className={live ? 'hub-viewer is-shown' : 'hub-viewer'} data-hub-viewer="">
      <div className="hub-shots">
        <ShotLink entry={entry}>
          <Placeholder entry={entry} cards={cards} />
        </ShotLink>
      </div>
    </div>
  )
  const button = !cards && entry.href !== null && entry.openLabel ? <a {...linkProps(entry, OPEN_CLASSES)}>{entry.openLabel}</a> : null
  const className = ['hub-panel', cards && 'hub-panel--card', selected && 'is-selected'].filter(Boolean).join(' ')
  return (
    <div className={className} data-hub-panel={entry.id}>
      {cards && (
        <div className="hub-card__text">
          {/* The body is what sits at --stage-text-anchor of the phone's height (appCarousel.css). */}
          <div className="hub-card__body">
            <div className="hub-card__head">
              <h3 className="hub-card__name">{entry.href === null ? entry.name : <a {...linkProps(entry, 'hub-card__link')}>{entry.name}</a>}</h3>
              {entry.note && <span className="hub-card__note">{entry.note}</span>}
            </div>
            {entry.tagline && <p className="hub-card__tagline">{entry.tagline}</p>}
          </div>
        </div>
      )}
      <div className="hub-device">
        <div className="hub-views">{viewers}</div>
        <PlatformSwitch sets={sets.map((set) => set.platform)} shown={shown} labels={labels} live={live} />
      </div>
      {button}
    </div>
  )
}

/** One platform's screenshots in its device frame, and their rotation controls. */
function Viewer({
  entry,
  platform,
  shots,
  labels,
  live,
  shown,
  current,
  eager,
}: {
  readonly entry: CarouselEntry
  readonly platform: ShotPlatform
  readonly shots: readonly CarouselShot[]
  readonly labels: CarouselLabels
  readonly live?: LiveRotation
  readonly shown: boolean
  readonly current: number
  readonly eager?: Appearance
}) {
  const n = shots.length
  const rotates = n > 1
  const label = (i: number) => labels.screenshot(i + 1, n, entry.name)
  const hold = live && rotates ? live.hold : {}
  const frame = `hub-shot--${platform}`
  return (
    <div
      className={['hub-viewer', live && shown && 'is-shown'].filter(Boolean).join(' ')}
      data-hub-viewer=""
      data-hub-platform={platform}
      role={rotates && live ? 'region' : undefined}
      aria-roledescription={rotates && live ? 'carousel' : undefined}
      aria-label={rotates && live ? entry.name : undefined}
      aria-hidden={live && !shown ? true : undefined}
    >
      <div className="hub-shots" aria-live={rotates && live ? (live.running && shown ? 'off' : 'polite') : undefined} {...hold}>
        {shots.map((shot, i) => (
          <div
            key={i}
            className={[shot.framed ? 'hub-shot hub-shot--framed' : `hub-shot ${frame}`, live && i === current && 'is-current'].filter(Boolean).join(' ')}
            data-hub-slide=""
            role={rotates ? 'group' : undefined}
            aria-roledescription={rotates && live ? 'slide' : undefined}
            aria-label={rotates ? label(i) : undefined}
          >
            <ShotLink entry={entry}>
              <Shot shot={shot} eager={i === current ? eager : undefined} priority />
            </ShotLink>
          </div>
        ))}
      </div>
      {rotates && (
        <div className="hub-controls" hidden={live ? undefined : true}>
          <button
            type="button"
            className="hub-rotate"
            data-hub-rotate=""
            data-label-pause={labels.pause}
            data-label-play={labels.play}
            data-state={live?.stopped ? 'stopped' : 'rotating'}
            aria-label={live?.stopped ? labels.play : labels.pause}
            hidden={live?.reduced ? true : undefined}
            tabIndex={live && !shown ? -1 : undefined}
            onClick={live?.toggle}
          >
            <span className="hub-rotate__icon" aria-hidden="true" />
          </button>
          <div className="hub-dots" {...hold}>
            {shots.map((_, i) => (
              <button
                key={i}
                type="button"
                className="hub-dot"
                data-hub-dot={i}
                aria-label={label(i)}
                aria-current={live && i === current ? 'true' : undefined}
                tabIndex={live && !shown ? -1 : undefined}
                onClick={live ? () => live.pick(i) : undefined}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

/**
 * The "iPhone | Android" switch: two toggle buttons (`aria-pressed`) in a labelled group. Disabled,
 * with a note, when the entry has captures on one platform only; invisible (still taking its room,
 * so nothing moves) when it has none. Hidden without the script, which shows Android.
 */
function PlatformSwitch({
  sets,
  shown,
  labels,
  live,
}: {
  readonly sets: readonly ShotPlatform[]
  readonly shown: ShotPlatform | null
  readonly labels: CarouselLabels
  readonly live?: LiveRotation
}) {
  const one = sets.length === 1
  const note = one ? (sets[0] === 'android' ? labels.platform.onlyAndroid : labels.platform.onlyIos) : ''
  const options: readonly ShotPlatform[] = ['ios', 'android']
  return (
    <div className={sets.length ? 'hub-platform' : 'hub-platform hub-platform--idle'} hidden={live ? undefined : true}>
      <div className="hub-platform__switch" role="group" aria-label={labels.platform.group}>
        {options.map((p) => (
          <button
            key={p}
            type="button"
            className="hub-platform__option"
            data-hub-pick={p}
            aria-pressed={(shown ?? live?.platform) === p}
            disabled={sets.length < 2 ? true : undefined}
            onClick={live ? () => live.choose(p) : undefined}
          >
            {p === 'ios' ? labels.platform.ios : labels.platform.android}
          </button>
        ))}
      </div>
      <p className="hub-platform__note">{note}</p>
    </div>
  )
}

/** The phone's link to the entry (out of the tab order, hidden: the button or the card's name is the named link). */
function ShotLink({ entry, children }: { readonly entry: CarouselEntry; readonly children: ReactNode }) {
  if (entry.href === null) return <span className="hub-shot__link">{children}</span>
  return (
    <a {...linkProps(entry, 'hub-shot__link')} tabIndex={-1} aria-hidden="true">
      {children}
    </a>
  )
}

/**
 * One screenshot slot's `<img>`s. Each names its WebP copies in `srcset` (sized to the phone,
 * CAROUSEL_SHOT_SIZES) with the PNG as `src`, the fallback, and keeps its width/height so the box is
 * reserved before it loads. With copies, the PNG's aspect ratio is pinned inline: a copy's height is
 * rounded (960 × 2134 for 1080 × 2400), and the box would otherwise follow the loaded copy's ratio
 * and grow by a fraction of a pixel.
 *
 * ⚠ Lazy by default, and that is what keeps the page light: a lazy image with no layout box — the
 * hidden theme's (`display: none`), the hidden layout's, the other platform's viewer — is never
 * fetched. [eager] names the theme of a slot that is ON SCREEN at load (the stage's first phone, the
 * row's first two cards): that one image loads eagerly, at [priority] on the stage, so the first
 * screenshot is never waiting on layout; its other-theme twin stays lazy.
 */
function Shot({ shot, eager, priority = false }: { readonly shot: CarouselShot; readonly eager?: Appearance; readonly priority?: boolean }) {
  const img = (image: NonNullable<CarouselShot['light']>, variant: string, appearance: Appearance | null) => {
    const now = eager !== undefined && (appearance === null || appearance === eager)
    return (
      <img
        className={`shot${variant}`}
        src={image.url}
        srcSet={shotSrcset(image)}
        sizes={image.variants?.length ? CAROUSEL_SHOT_SIZES : undefined}
        alt={shot.alt}
        width={image.width}
        height={image.height}
        style={image.variants?.length ? { aspectRatio: `${image.width} / ${image.height}` } : undefined}
        loading={now ? 'eager' : 'lazy'}
        fetchPriority={now && priority ? 'high' : undefined}
        decoding="async"
      />
    )
  }
  if (shot.light && shot.dark) {
    return (
      <>
        {img(shot.light, ' shot--light', 'light')}
        {img(shot.dark, ' shot--dark', 'dark')}
      </>
    )
  }
  const only = shot.light ?? shot.dark
  return only ? img(only, '', null) : null
}

/** A neutral card in a screenshot's place, in the same phone frame: never a broken image. */
function Placeholder({ entry, cards }: { readonly entry: CarouselEntry; readonly cards: boolean }) {
  return (
    <span className="hub-placeholder">
      <span className="hub-placeholder__screen">
        {cards && entry.icon !== undefined && <AppIcon icon={entry.icon} name={entry.name} className="hub-placeholder__icon" />}
        <span className="hub-placeholder__name">{entry.name}</span>
        {entry.soonLabel && <span className="hub-placeholder__note">{entry.soonLabel}</span>}
      </span>
    </span>
  )
}

/**
 * The app's icon, light and dark (CSS shows the one matching the effective theme, like the
 * screenshots), or a neutral tile with the name's initial when the app has none. Decorative: the
 * name is always beside it.
 */
function AppIcon({ icon, name, className }: { readonly icon: CarouselIcon | null; readonly name: string; readonly className: string }) {
  if (!icon) {
    return (
      <span className={`hub-icon hub-icon--fallback ${className}`} aria-hidden="true">
        {name.slice(0, 1)}
      </span>
    )
  }
  return (
    <>
      <img className={`hub-icon hub-icon--light ${className}`} src={icon.light} alt="" width={128} height={128} decoding="async" />
      <img className={`hub-icon hub-icon--dark ${className}`} src={icon.dark} alt="" width={128} height={128} loading="lazy" decoding="async" />
    </>
  )
}

// ── the narrow layout: a row of cards ────────────────────────────────────
//
// Under 64rem (appCarousel.css `.hub-m`) the names-around-a-stage layout above is display:none and
// this row shows instead — a "peek" carousel: one card per entry, swiped sideways with native CSS
// scroll snap, the next card peeking in at the edge. Each card is the entry's icon, name, line (or
// "Coming soon") and its FIRST screenshot in the drawn frame — no rotation, no dots. The whole card
// is the entry's one link (none for an entry without one). Above the row, the "iPhone | Android"
// switch, once, with the same default and storage as the stage's; under it, one icon button per
// card that shows which card is in view and scrolls to it.
//
// Without JavaScript (the hub's static markup) the row still swipes and snaps; the switch and the
// indicator are `hidden` (the hub script shows and wires them — HUB_SCRIPT_BODY, the same contract)
// and every card shows its first platform's capture (Android, else the one there is).
//
// ⚠ It sits OUTSIDE `[data-hub]`, as its sibling: the stage's CSS keys on `.hub-list:first-child`
// and `:last-child`, so a child added there would break the desktop layout's mirroring.

/** How many of the row's cards are on screen at load (the first, and the next peeking in). */
const EAGER_CARDS = 2

/** The live row's link to the carousel's state: the visitor's platform and the switch's handler. */
interface RowLive {
  readonly platform: ShotPlatform
  readonly choose: (platform: ShotPlatform) => void
}

/** The index of the card whose centre is nearest the row's (the hub script's `rsync` does the same). */
function nearestCard(row: HTMLElement): number {
  const cards = row.querySelectorAll<HTMLElement>('[data-hub-card]')
  const mid = row.scrollLeft + row.clientWidth / 2
  let best = 0
  let distance = Infinity
  cards.forEach((card, i) => {
    const d = Math.abs(card.offsetLeft + card.offsetWidth / 2 - mid)
    if (d < distance) {
      distance = d
      best = i
    }
  })
  return best
}

/** What takes a card's focus: its link, else the card itself (RowCard). */
function cardFocusTarget(card: HTMLElement): HTMLElement {
  return card.querySelector<HTMLElement>('a[href]') ?? card
}

/** Scrolls card [index] to the row's centre; instantly under reduced motion. */
function scrollToCard(row: HTMLElement, index: number, reduced: boolean) {
  const card = row.querySelectorAll<HTMLElement>('[data-hub-card]')[index]
  if (!card) return
  const left = card.offsetLeft - (row.clientWidth - card.offsetWidth) / 2
  if (typeof row.scrollTo === 'function') row.scrollTo({ left, behavior: reduced ? 'auto' : 'smooth' })
  else row.scrollLeft = left
}

function CardRow({
  entries,
  listLabel,
  cards = false,
  labels,
  live,
  eager,
}: {
  readonly entries: readonly CarouselEntry[]
  readonly listLabel: string
  readonly cards?: boolean
  readonly labels: CarouselLabels
  /** Absent in static markup, which the hub script wires the same way. */
  readonly live?: RowLive
  /** `live` only: the row is the layout on screen at load, in this theme — its first two cards load eagerly. */
  readonly eager?: Appearance
}) {
  const row = useRef<HTMLDivElement>(null)
  const [active, setActive] = useState(0)
  const reduced = useReducedMotion()
  const isLive = live !== undefined
  // The switch only means something when some entry has both platforms' captures.
  const switchable = entries.some((e) => platformSets(e).length > 1)

  useEffect(() => {
    const el = row.current
    if (!isLive || !el) return
    let frame = 0
    const sync = () => {
      frame = 0
      setActive(nearestCard(el))
    }
    const onScroll = () => {
      if (!frame) frame = requestAnimationFrame(sync)
    }
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      el.removeEventListener('scroll', onScroll)
      cancelAnimationFrame(frame)
    }
  }, [isLive])

  const cardIndex = (target: EventTarget | null) => {
    const card = target instanceof Element ? target.closest('[data-hub-card]') : null
    return card ? [...(row.current?.querySelectorAll('[data-hub-card]') ?? [])].indexOf(card) : -1
  }
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return
    const from = cardIndex(e.target)
    const el = row.current
    if (from < 0 || !el) return
    const to = Math.min(entries.length - 1, Math.max(0, from + (e.key === 'ArrowRight' ? 1 : -1)))
    e.preventDefault()
    const card = el.querySelectorAll<HTMLElement>('[data-hub-card]')[to]
    if (card) cardFocusTarget(card).focus({ preventScroll: true })
    scrollToCard(el, to, reduced)
  }
  const onFocus = (e: FocusEvent<HTMLDivElement>) => {
    const i = cardIndex(e.target)
    if (i >= 0 && row.current) scrollToCard(row.current, i, reduced)
  }

  return (
    <div className={cards ? 'hub hub-m hub-m--cards' : 'hub hub-m'} data-hub-mobile="" data-shot-platform={live?.platform}>
      <div className="hub-platform hub-m__switch" hidden={isLive ? undefined : true}>
        <div className="hub-platform__switch" role="group" aria-label={labels.platform.group}>
          {(['ios', 'android'] as const).map((p) => (
            <button
              key={p}
              type="button"
              className="hub-platform__option"
              data-hub-pick={p}
              aria-pressed={(live?.platform ?? 'android') === p}
              disabled={switchable ? undefined : true}
              onClick={live ? () => live.choose(p) : undefined}
            >
              {p === 'ios' ? labels.platform.ios : labels.platform.android}
            </button>
          ))}
        </div>
      </div>
      <div ref={row} className="hub-row" role="region" aria-label={listLabel} data-hub-row="" onKeyDown={isLive ? onKeyDown : undefined} onFocus={isLive ? onFocus : undefined}>
        {entries.map((entry, i) => (
          <RowCard
            key={entry.id}
            entry={entry}
            cards={cards}
            labels={labels}
            eager={eager !== undefined && live && i < EAGER_CARDS ? { theme: eager, platform: shownPlatform(entry, live.platform) } : undefined}
          />
        ))}
      </div>
      <div className="hub-m__nav" hidden={isLive ? undefined : true}>
        {entries.map((entry, i) => (
          <button
            key={entry.id}
            type="button"
            className="hub-m__dot"
            data-hub-show={i}
            aria-label={labels.show(entry.name)}
            aria-current={isLive && i === active ? 'true' : undefined}
            onClick={() => {
              setActive(i)
              if (row.current) scrollToCard(row.current, i, reduced)
            }}
          >
            <AppIcon icon={entry.icon ?? null} name={entry.name} className="hub-m__dot-icon" />
          </button>
        ))}
      </div>
    </div>
  )
}

/**
 * One card of the row: a `group` named after the entry, and ONE focus target — the entry's link
 * (named by the card's name, or by the hub's "Open X support", which the card also shows as a
 * button-shaped label), or, with no link, the group itself (`tabIndex=0`), so Tab and the arrow keys
 * reach every card (cardFocusTarget). Its views (one per platform, Android first) are switched by CSS from the row's
 * `data-shot-platform` and the card's `data-platforms`; the note says when a card shows the other
 * platform's capture because it has none for the chosen one.
 */
function RowCard({
  entry,
  cards,
  labels,
  eager,
}: {
  readonly entry: CarouselEntry
  readonly cards: boolean
  readonly labels: CarouselLabels
  /** On screen at load: the platform view that shows, and the theme (Shot `eager`). */
  readonly eager?: { readonly theme: Appearance; readonly platform: ShotPlatform | null }
}) {
  const sets = platformSets(entry)
  const nameId = `hub-mcard-${entry.id}-name`
  const lineId = `hub-mcard-${entry.id}-line`
  const line = cards ? entry.tagline : undefined
  const cta = !cards && entry.href !== null ? entry.openLabel : undefined
  const views = sets.length ? (
    sets.map((set) => {
      const shot = set.shots[0]!
      return (
        <span key={set.platform} className="hub-mcard__view" data-hub-platform={set.platform}>
          <span className={shot.framed ? 'hub-shot hub-shot--framed' : `hub-shot hub-shot--${set.platform}`}>
            {/* The frame's own wrapper (the iPhone frame draws its side button on it), not a link here. */}
            <span className="hub-shot__link">
              <Shot shot={shot} eager={eager?.platform === set.platform ? eager.theme : undefined} />
            </span>
          </span>
        </span>
      )
    })
  ) : (
    <span className="hub-mcard__view">
      <span className="hub-shot__link">
        <Placeholder entry={entry} cards={false} />
      </span>
    </span>
  )
  const only = sets.length === 1 ? sets[0]!.platform : null
  const content = (
    <>
      <span className="hub-mcard__head">
        {entry.icon !== undefined && <AppIcon icon={entry.icon} name={entry.name} className="hub-mcard__icon" />}
        <span className="hub-mcard__name" id={nameId}>
          {entry.name}
        </span>
      </span>
      {entry.note && <span className="hub-mcard__note">{entry.note}</span>}
      {line && (
        <span className="hub-mcard__line" id={lineId}>
          {line}
        </span>
      )}
      {entry.soonLabel && <span className="hub-mcard__soon">{entry.soonLabel}</span>}
      <span className="hub-mcard__phone">{views}</span>
      {only && <span className="hub-mcard__fallback">{only === 'android' ? labels.platform.onlyAndroid : labels.platform.onlyIos}</span>}
      {cta && (
        <span className={CTA_CLASSES} aria-hidden="true">
          {cta}
        </span>
      )}
    </>
  )
  return (
    <div
      className={entry.soonLabel ? 'hub-mcard hub-mcard--soon' : 'hub-mcard'}
      role="group"
      aria-label={entry.name}
      data-hub-card={entry.id}
      data-platforms={sets.map((s) => s.platform).join(' ')}
      tabIndex={entry.href === null ? 0 : undefined}
    >
      {entry.href === null ? (
        <div className="hub-mcard__link">{content}</div>
      ) : (
        <a
          {...linkProps(entry, 'hub-mcard__link')}
          aria-label={cta}
          aria-labelledby={cta ? undefined : nameId}
          aria-describedby={line ? lineId : undefined}
        >
          {content}
        </a>
      )}
    </div>
  )
}

const CTA_CLASSES = 'hub-mcard__cta rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm'
