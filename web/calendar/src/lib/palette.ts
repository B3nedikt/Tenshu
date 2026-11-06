// The colours a calendar can be — `calendar-shared-logic`'s `CalendarPalette` (CA-5f). A fixed
// palette, not a picker: a calendar colour is a label, and must stay distinguishable at a glance.

export const PALETTE: readonly number[] = [
  0xff4285f4, // blue
  0xff0f9d58, // green
  0xffdb4437, // red
  0xfff4b400, // amber
  0xff9c27b0, // purple
  0xff00acc1, // teal
  0xffff7043, // deep orange
  0xff757575, // grey
].map((c) => c | 0) // the signed 32-bit Int the row stores

// ⚠⚠ Compares RGB only: rows with alpha 0 exist (the first calendar used to be seeded `0x4285F4`),
// and every renderer forces the alpha opaque.
export function sameColour(a: number, b: number): boolean {
  return (a & 0xffffff) === (b & 0xffffff)
}

/** The next colour not already taken; the first again once the palette runs out. */
export function nextColour(taken: readonly number[]): number {
  return PALETTE.find((c) => !taken.some((t) => sameColour(t, c))) ?? PALETTE[0]!
}

/** CSS for a stored ARGB `Int`, alpha forced opaque. */
export function cssColour(argb: number): string {
  return `#${(argb & 0xffffff).toString(16).padStart(6, '0')}`
}

export const UNKNOWN_CALENDAR_COLOUR = 0xff757575 | 0
