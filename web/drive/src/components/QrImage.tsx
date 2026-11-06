import { useEffect, useState } from 'react'
import QRCode from 'qrcode'

// Renders [value] as a QR image (drive-web-mode-b-sharing.md §3, the QR-display
// surface — MB-5). Pure display; no camera. The inviter shows this for an
// in-person scan, carrying the same invite link as the copy/share surfaces (its
// K_link rides the URL fragment and never reaches the relay).
// A finished render attempt, tagged with the input that produced it.
// `dataUrl: null` means that input failed to encode.
type QrRender = { value: string; size: number; dataUrl: string | null }

export function QrImage({ value, size = 200 }: { value: string; size?: number }) {
  // One state holding the render *and* its input, written only from the async
  // callbacks; resetting on a `value`/`size` change is a render-time comparison
  // rather than a synchronous setState in the effect (which would cascade an
  // extra render on every input change).
  const [render, setRender] = useState<QrRender | null>(null)
  const current = render?.value === value && render.size === size ? render : null

  useEffect(() => {
    let cancelled = false
    QRCode.toDataURL(value, { width: size, margin: 1 })
      .then((url) => {
        if (!cancelled) setRender({ value, size, dataUrl: url })
      })
      .catch(() => {
        if (!cancelled) setRender({ value, size, dataUrl: null })
      })
    return () => {
      cancelled = true
    }
  }, [value, size])

  if (current?.dataUrl === null) {
    return <p className="text-sm text-danger">Couldn&rsquo;t render the QR code.</p>
  }
  const dataUrl = current?.dataUrl
  if (!dataUrl) {
    return (
      <div
        className="animate-pulse rounded-lg bg-surface-3"
        style={{ width: size, height: size }}
        aria-hidden
      />
    )
  }
  return (
    <img
      src={dataUrl}
      alt="Invite QR code"
      width={size}
      height={size}
      className="rounded-lg border border-line"
    />
  )
}
