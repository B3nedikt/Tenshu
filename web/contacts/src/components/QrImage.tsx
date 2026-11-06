import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import QRCode from 'qrcode'

// Renders [value] as a QR image — Drive's `QrImage`, copied (the app-kit extraction is run B2).
// Pure display, encoded locally: the invite link's K_link rides its URL fragment and never leaves
// this tab, let alone reaches the relay.
// A finished render attempt, tagged with the input that produced it.
// `dataUrl: null` means that input failed to encode.
type QrRender = { value: string; size: number; dataUrl: string | null }

export function QrImage({ value, alt, size = 200 }: { value: string; alt: string; size?: number }) {
  const { t } = useTranslation()
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
    return <p className="text-sm text-danger">{t('invite.qrFailed')}</p>
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
      alt={alt}
      width={size}
      height={size}
      className="rounded-lg border border-line"
    />
  )
}
