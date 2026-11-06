// The app carousel's data: what one entry in it is. Shared by the landing page's Apps section
// (Block7Apps, a live React component) and the support hub (support-pages/generate.ts, static
// markup plus support-pages/hub.ts HUB_SCRIPT_BODY). AppCarousel.tsx renders it.
//
// ⚠ Pure types and pure helpers, no imports: plain Node type-imports this file for the
// support-page build (support-pages/generate.ts), so it must stay erasable syntax only.

/** One published capture: its URL and its pixel size (the `<img>`'s width/height). */
export interface CarouselImage {
  /** The full-size PNG: the `<img>`'s `src`, so the fallback where `srcset` is not understood. */
  readonly url: string
  readonly width: number
  readonly height: number
  /**
   * Its smaller WebP copies (store/<app>/screenshots.webp.json, scripts/lib/store_webp.py),
   * narrowest first: the `<img>`'s `srcset`. Absent or empty — a capture published before its
   * derivatives were — and the PNG is all there is.
   */
  readonly variants?: readonly CarouselVariant[]
}

/** One WebP derivative of a capture: its URL and its pixel width (a `srcset` `w` descriptor). */
export interface CarouselVariant {
  readonly url: string
  readonly width: number
}

/**
 * The `srcset` for a capture: its WebP copies by width; `undefined` with none (a plain `src`).
 * ⚠ The PNG is deliberately NOT a candidate: a browser zoomed to 200% on a 2× screen would pick it
 * (MBs) over a 960 px copy that looks the same in a 75mm phone. It stays the `src`, the fallback.
 */
export function shotSrcset(image: CarouselImage): string | undefined {
  if (!image.variants?.length) return undefined
  return image.variants.map((v) => `${v.url} ${v.width}w`).join(', ')
}

/**
 * The phone's screen width on the page, for `sizes` (appCarousel.css): on the wide stage the real
 * 75mm phone (its screen ≈ 95% of that, so 75mm is a safe upper bound); in the narrow row a card's
 * phone, 64% of a card that is 80% of the row and at most 22rem (≈ 51vw, never over 14rem).
 */
export const CAROUSEL_SHOT_SIZES = '(min-width: 64rem) 75mm, (min-width: 27.5rem) 14rem, 51vw'

/**
 * One screenshot slot. Both captures published: both are in the markup and CSS shows the one that
 * matches the effective theme (`.shot--light` / `.shot--dark`). One: that one in both themes.
 * Neither is not a slot — leave it out, and an entry with no slots shows a placeholder card.
 */
export interface CarouselShot {
  readonly light: CarouselImage | null
  readonly dark: CarouselImage | null
  /** Plain text; the renderer escapes it. */
  readonly alt: string
  /**
   * The frame mode, per screenshot. Absent (the default): a bare screen capture, which the carousel
   * puts in its neutral CSS phone frame. Present: "pre-framed" — the image already carries a real
   * device bezel (rendered by scripts/store-frame, D7), so the carousel draws no frame and no screen
   * radius and shows it as is, at the same real-phone width so the layout does not jump.
   */
  readonly framed?: PreFramed
}

/** A pre-framed capture's vendor, and the bare capture to fall back to (oneFrameVendor). */
export interface PreFramed {
  /** Whose bezel the image carries: Apple's (iOS) or Google's (Android). */
  readonly vendor: 'apple' | 'google'
  /** The same screen without a bezel, for the CSS frame; null if none is published. */
  readonly bare: { readonly light: CarouselImage | null; readonly dark: CarouselImage | null } | null
}

/** An app icon, light and dark (scripts/web-app-icons.py), as site paths. */
export interface CarouselIcon {
  readonly light: string
  readonly dark: string
}

/**
 * The platform a set of screenshots was captured on, and so the device frame it is drawn in
 * (appCarousel.css): an Android phone or an iPhone-style one. The visitor picks it with the
 * carousel's "iPhone | Android" switch; the default follows their OS (support-pages/hub.ts
 * defaultShotPlatform) and the choice is remembered (SHOT_PLATFORM_STORAGE_KEY).
 */
export type ShotPlatform = 'android' | 'ios'

/** Where the visitor's switch choice is kept: a per-viewer convenience, like `tenshu.theme`. */
export const SHOT_PLATFORM_STORAGE_KEY = 'tenshu.shotPlatform'

export interface CarouselEntry {
  /** `data-hub-name` / `data-hub-panel`: what selection is keyed by. */
  readonly id: string
  readonly name: string
  /**
   * Where the entry leads: the name (with a mouse), the screenshots and the button (on a landing
   * card, the card's name). `null` is an entry with no page: its name is a focusable `<span>` that
   * only selects. The landing page decides it in landingApps.ts landingAppHref, the hub in
   * support-pages/generate.ts.
   */
  readonly href: string | null
  /** The hub's button under the screenshots ("Open Drive support"); only with an [href], never on a card. */
  readonly openLabel?: string
  /** `data-hub-app` on every link: the hub script re-points those at the visitor's platform. */
  readonly app?: string
  /**
   * The Android captures — also what the page shows without JavaScript. An entry with captures on
   * only one platform shows those whatever the switch says, framed as what they are, with its
   * switch disabled and a note why (CarouselLabels.platform). None on either: a placeholder.
   */
  readonly shots: readonly CarouselShot[]
  /** The iPhone captures (`ios/iphone69…`), drawn in the iPhone-style frame. */
  readonly iosShots?: readonly CarouselShot[]
  /**
   * The hub's greyed-out "Coming soon" entry: a dashed, muted name with the label kept for screen
   * readers only, and the label in its placeholder. The landing page never sets it.
   */
  readonly soonLabel?: string
  /** The app's icon: beside the name in the list (stage side), and on the landing card. */
  readonly icon?: CarouselIcon | null
  /** The landing card's one line under the name. */
  readonly tagline?: string
  /** A quiet label in the card's corner ("Coming later"); nothing in the list of names. */
  readonly note?: string
}

/**
 * How long each screenshot shows in the one frame before the next fades in. The hub script
 * (support-pages/hub.ts HUB_SCRIPT_BODY `ADV`) uses the same; hub.test.ts pins that.
 */
export const ADVANCE_MS = 4000

/** The carousel's rotation controls' copy (AppCarousel `labels`). */
export interface CarouselLabels {
  /** A dot's label, and its slide's: "Screenshot 2 of 3: Drive" (index from 1). */
  readonly screenshot: (index: number, count: number, name: string) => string
  /** The rotation button while the cycle runs, and while it is stopped. */
  readonly pause: string
  readonly play: string
  /** The narrow layout's position indicator: one button per card, "Show Drive". */
  readonly show: (name: string) => string
  /** The "iPhone | Android" switch under the dots. */
  readonly platform: PlatformLabels
}

export interface PlatformLabels {
  /** The switch's group label ("Device"). */
  readonly group: string
  readonly ios: string
  readonly android: string
  /** The note under a disabled switch: the entry has captures on the other platform only. */
  readonly onlyAndroid: string
  readonly onlyIos: string
}

/** The entry's non-empty screenshot sets, Android first (the markup's, and no-JS's, order). */
export function platformSets(entry: Pick<CarouselEntry, 'shots' | 'iosShots'>): { readonly platform: ShotPlatform; readonly shots: readonly CarouselShot[] }[] {
  const sets: { platform: ShotPlatform; shots: readonly CarouselShot[] }[] = [
    { platform: 'android', shots: entry.shots },
    { platform: 'ios', shots: entry.iosShots ?? [] },
  ]
  return sets.filter((s) => s.shots.length > 0)
}

/** Which platform's set an entry shows when the visitor chose [chosen]: that one, else its only one. */
export function shownPlatform(entry: Pick<CarouselEntry, 'shots' | 'iosShots'>, chosen: ShotPlatform): ShotPlatform | null {
  const sets = platformSets(entry)
  return sets.find((s) => s.platform === chosen)?.platform ?? sets[0]?.platform ?? null
}

/**
 * ⚠ One vendor's device frames per stage, never Apple's and Google's side by side (the bezel
 * licences, store-presence-and-support-pages.md §3.2.1). If the entries' pre-framed captures span
 * more than one vendor, EVERY pre-framed capture falls back to its bare capture in the CSS frame
 * (one with no bare capture is dropped). One vendor, or none: the entries are returned unchanged.
 */
export function oneFrameVendor<E extends { readonly shots: readonly CarouselShot[]; readonly iosShots?: readonly CarouselShot[] }>(entries: readonly E[]): readonly E[] {
  const all = (e: E) => [...e.shots, ...(e.iosShots ?? [])]
  const vendors = new Set(entries.flatMap((e) => all(e).flatMap((s) => (s.framed ? [s.framed.vendor] : []))))
  if (vendors.size <= 1) return entries
  const unframe = (shots: readonly CarouselShot[]) =>
    shots.flatMap((s): CarouselShot[] => {
      if (!s.framed) return [s]
      const bare = s.framed.bare
      return bare && (bare.light || bare.dark) ? [{ light: bare.light, dark: bare.dark, alt: s.alt }] : []
    })
  return entries.map((e) => (e.iosShots ? { ...e, shots: unframe(e.shots), iosShots: unframe(e.iosShots) } : { ...e, shots: unframe(e.shots) }))
}

/**
 * The two lists the names are split into, so the stage can sit between them on wide screens: the
 * first half (rounded up) to its left, the rest to its right.
 */
export function splitEntries<T>(entries: readonly T[]): [readonly T[], readonly T[]] {
  const half = Math.ceil(entries.length / 2)
  return [entries.slice(0, half), entries.slice(half)]
}
