// An app's icon in the apex chrome: the Home dock, the launcher and the in-app top bar.
//
// ⚠ Presentation only, keyed by registry id. An app with no glyph here (a newly registered one)
// falls back to its label's first letter, and one with no colour to the brand amethyst
// (`--app-default-*`), so registering an app still never requires editing this file
// (web-app-suite.md F4).
//
// Each app wears its own colour pair (`--app-<id>-a/-b`, from docs/design/brand/tokens/apps.ts) under
// a white glyph, the same in both schemes. ADR 0027 scopes these colours to the web chrome: the
// native launcher icons keep ADR 0026's tokens.
import type { ReactNode } from 'react'
import { appColorStyle } from './appColor'

const GLYPHS: Readonly<Record<string, ReactNode>> = {
  drive: <path d="M3 7.5a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />,
  photos: (
    <>
      <rect x={3} y={4} width={18} height={16} rx={3} />
      <circle cx={9} cy={10} r={2} />
      <path d="M21 16l-5-5-9 9" />
    </>
  ),
  calendar: (
    <>
      <rect x={3.5} y={5} width={17} height={15} rx={2.5} />
      <path d="M3.5 10h17M8 3v4M16 3v4" />
    </>
  ),
  fit: <path d="M3 12h4l2-5 4 10 2-5h6" />,
  timeline: (
    <>
      <circle cx={6} cy={18} r={2} />
      <circle cx={18} cy={6} r={2} />
      <path d="M8 18h6a3 3 0 0 0 0-6h-4a3 3 0 0 1 0-6h6" />
    </>
  ),
  contacts: (
    <>
      <circle cx={12} cy={9} r={3.5} />
      <path d="M5 19.5a7 7 0 0 1 14 0" />
    </>
  ),
  maps: (
    <>
      <path d="M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2z" />
      <path d="M9 4v14M15 6v14" />
    </>
  ),
  home: (
    <>
      <path d="M4 11l8-6 8 6" />
      <path d="M6 10v9h12v-9" />
    </>
  ),
  mia: <path d="M4 6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9l-5 4z" />,
}

const SIZES = {
  sm: { box: 'h-7 w-7 rounded-m', glyph: 16, text: 'text-xs' },
  md: { box: 'h-10 w-10 rounded-[10px]', glyph: 22, text: 'text-sm' },
  lg: { box: 'h-16 w-16 rounded-[16px]', glyph: 32, text: 'text-xl' },
} as const

export function AppIcon({
  appId,
  label,
  size,
}: {
  readonly appId: string
  readonly label: string
  readonly size: keyof typeof SIZES
}) {
  const s = SIZES[size]
  const glyph = GLYPHS[appId]
  return (
    <span
      aria-hidden
      style={appColorStyle(appId)}
      className={`grid shrink-0 place-items-center bg-linear-160 from-(--a) to-(--b) text-white shadow-[inset_0_1px_0_rgb(255_255_255/0.28),inset_0_-1px_0_rgb(0_0_0/0.12),0_4px_10px_-4px_rgb(0_0_0/0.35)] ${s.box}`}
    >
      {glyph ? (
        <svg viewBox="0 0 24 24" width={s.glyph} height={s.glyph} fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
          {glyph}
        </svg>
      ) : (
        <span className={`font-semibold ${s.text}`}>{label.slice(0, 1)}</span>
      )}
    </span>
  )
}

