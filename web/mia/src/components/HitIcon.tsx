// The glyph for a cited row — native's six (`glyphFor` in ChatScreen.kt): a pin, a clock, a runner,
// a bulb, a calendar, else a document. Inline SVG, so no icon font or third-party fetch.
import type { HitKind } from '../lib/hits'

const PATHS: Readonly<Record<HitKind, string>> = {
  place: 'M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21Zm0-9a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z',
  moment: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Zm0 4v5l3 2',
  workout: 'M13 4a2 2 0 1 0 4 0 2 2 0 0 0-4 0ZM8 20l3-6 3 2v5M6 12l3-4 4 1 3 3h3',
  device: 'M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9V16h7v-2.1A6 6 0 0 0 12 3Z',
  event: 'M4 6h16v14H4zM4 10h16M8 3v4M16 3v4',
  file: 'M6 3h8l4 4v14H6zM14 3v4h4',
}

export function HitIcon({ kind }: { kind: HitKind }) {
  return (
    <svg
      aria-hidden="true"
      data-kind={kind}
      viewBox="0 0 24 24"
      className="h-5 w-5 shrink-0 text-primary"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d={PATHS[kind]} />
    </svg>
  )
}
