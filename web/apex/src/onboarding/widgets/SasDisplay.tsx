// Short-authentication-string display per UX §4a.2. Renders the SAS
// large enough to be unambiguous when read off the screen on either
// device. The space inside (e.g. "482 913") is rendered as a wider gap
// for legibility.
export function SasDisplay({ sas }: { readonly sas: string }) {
  return (
    <p
      role="status"
      aria-live="polite"
      className="flex items-baseline gap-3 font-mono text-3xl font-semibold tracking-[0.2em] text-text"
    >
      {sas.split('').map((ch, i) =>
        ch === ' ' ? (
          <span key={i} aria-hidden className="inline-block w-3" />
        ) : (
          <span key={i}>{ch}</span>
        ),
      )}
    </p>
  )
}
