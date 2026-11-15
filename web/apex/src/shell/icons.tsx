// Stroke icons both shells use. Decorative: every caller gives its control an accessible name.

export function GearIcon({ size = 18 }: { readonly size?: number }) {
  return (
    <svg aria-hidden viewBox="0 0 20 20" width={size} height={size} fill="none" stroke="currentColor" strokeWidth={1.6}>
      <circle cx={10} cy={10} r={2.5} />
      <path d="M10 1.5l1.2 2.1 2.4-.5.5 2.4 2.1 1.2-1.2 2.1.5 2.4-2.4.5-1.2 2.1-2.1-1.2-2.4 1.2-1.2-2.1-2.4-.5.5-2.4-2.1-1.2 1.2-2.1-.5-2.4 2.4.5L10 1.5z" />
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
