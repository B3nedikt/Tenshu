// Stroke icons both shells use. Decorative: every caller gives its control an accessible name.

// A cog: eight squared teeth on a ring and a hub, round joins so it stays crisp at 18–20 px (the
// earlier 12-point star with mitred joins read as a smudge).
export function GearIcon({ size = 18 }: { readonly size?: number }) {
  return (
    <svg
      aria-hidden
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.7}
      strokeLinejoin="round"
    >
      <path d="M10.27 4.8L10.72 2.49L13.28 2.49L13.73 4.8L15.87 5.69L17.82 4.37L19.63 6.18L18.31 8.13L19.2 10.27L21.51 10.72L21.51 13.28L19.2 13.73L18.31 15.87L19.63 17.82L17.82 19.63L15.87 18.31L13.73 19.2L13.28 21.51L10.72 21.51L10.27 19.2L8.13 18.31L6.18 19.63L4.37 17.82L5.69 15.87L4.8 13.73L2.49 13.28L2.49 10.72L4.8 10.27L5.69 8.13L4.37 6.18L6.18 4.37L8.13 5.69Z" />
      <circle cx={12} cy={12} r={3.1} />
    </svg>
  )
}

export function BellIcon({ size = 18 }: { readonly size?: number }) {
  return (
    <svg aria-hidden viewBox="0 0 20 20" width={size} height={size} fill="none" stroke="currentColor" strokeWidth={1.6}>
      <path d="M5 8a5 5 0 0 1 10 0v4l1.5 2.5h-13L5 12z" />
      <path d="M8 17h4" />
    </svg>
  )
}

export function BackIcon({ size = 18 }: { readonly size?: number }) {
  return (
    <svg aria-hidden viewBox="0 0 20 20" width={size} height={size} fill="none" stroke="currentColor" strokeWidth={1.8}>
      <path d="M12 4l-6 6 6 6" />
    </svg>
  )
}
