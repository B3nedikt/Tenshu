// The toolbar's few glyphs, inline (no icon library in the bundle): 18 px, `currentColor` strokes,
// decorative — every button that draws one carries its own aria-label and title.
import type { ReactNode } from 'react'

function Glyph({ children }: { children: ReactNode }) {
  return (
    <svg aria-hidden viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      {children}
    </svg>
  )
}

export const HeartIcon = () => (
  <Glyph>
    <path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z" />
  </Glyph>
)

export const AlbumAddIcon = () => (
  <Glyph>
    <rect x="3" y="5" width="14" height="14" rx="2" />
    <path d="M20 9v8a3 3 0 0 1-3 3H8M10 9v6M7 12h6" />
  </Glyph>
)

export const AlbumRemoveIcon = () => (
  <Glyph>
    <rect x="3" y="5" width="14" height="14" rx="2" />
    <path d="M20 9v8a3 3 0 0 1-3 3H8M7 12h6" />
  </Glyph>
)

export const TrashIcon = () => (
  <Glyph>
    <path d="M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3" />
  </Glyph>
)

export const InfoIcon = () => (
  <Glyph>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 11v5M12 8h.01" />
  </Glyph>
)

export const CloseIcon = () => (
  <Glyph>
    <path d="M6 6l12 12M18 6L6 18" />
  </Glyph>
)

// Material's outlined glyphs, filled — the SAME icons native's density button draws
// (`Icons.Outlined.AutoAwesomeMosaic` / `GridView`, photos-shared-ui `App.kt`), so the switch reads
// alike on both.
function MaterialGlyph({ d }: { d: string }) {
  return (
    <svg aria-hidden viewBox="0 0 24 24" width="18" height="18" fill="currentColor">
      <path d={d} />
    </svg>
  )
}

export const MosaicIcon = () => (
  <MaterialGlyph d="M3 5v14c0 1.1.89 2 2 2h6V3H5c-1.11 0-2 .9-2 2zm6 14H5V5h4v14zM19 3h-6v8h8V5c0-1.1-.9-2-2-2zm0 6h-4V5h4v4zm-6 12h6c1.1 0 2-.9 2-2v-6h-8v8zm2-6h4v4h-4v-4z" />
)

export const GridViewIcon = () => (
  <MaterialGlyph d="M3 3v8h8V3H3zm6 6H5V5h4v4zm-6 4v8h8v-8H3zm6 6H5v-4h4v4zm4-16v8h8V3h-8zm6 6h-4V5h4v4zm-6 4v8h8v-8h-8zm6 6h-4v-4h4v4z" />
)

// Material `AspectRatio` (whole photographs) and `CropSquare` (square crops).
export const AspectRatioIcon = () => (
  <MaterialGlyph d="M19 12h-2v3h-3v2h5v-5zM7 9h3V7H5v5h2V9zm14-6H3c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16.01H3V4.99h18v14.02z" />
)

export const CropSquareIcon = () => (
  <MaterialGlyph d="M18 4H6c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 14H6V6h12v12z" />
)
