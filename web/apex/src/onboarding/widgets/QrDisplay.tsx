// Pure QR *render* component for the offer flow
// (`docs/proposals/sync-pairing-inverted-direction.md` §5.3): this browser
// is the new device, so it *displays* the `tenshu://offer?…` code and an
// existing realm device scans it. No camera — the inverse of `QrScanner`.
//
// Sibling to `web/drive/src/components/QrImage.tsx`; kept local to the
// onboarding widgets so the offerer screen and the host `PairNewDeviceDialog`
// share one renderer without pulling in the drive project.
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import QRCode from 'qrcode'

// A finished render attempt, tagged with the input that produced it.
// `dataUrl: null` means that input failed to encode.
type QrRender = { value: string; size: number; dataUrl: string | null }

export function QrDisplay({ value, size = 220 }: { readonly value: string; readonly size?: number }) {
  const { t } = useTranslation('onboarding')
  // One state holding the render *and* the input it was produced from, written
  // only from the async callbacks. Resetting on a `value`/`size` change is then
  // a render-time comparison rather than a synchronous setState in the effect
  // (which would cascade a second render on every input change).
  const [render, setRender] = useState<QrRender | null>(null)
  const current = render?.value === value && render.size === size ? render : null

  useEffect(() => {
    let cancelled = false
    QRCode.toDataURL(value, { width: size, margin: 1 })
      .then(url => {
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
    return <p className="text-sm text-danger">{t('qrDisplay.renderFailed')}</p>
  }
  const dataUrl = current?.dataUrl ?? null
  if (dataUrl === null) {
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
      alt={t('qrDisplay.alt')}
      width={size}
      height={size}
      className="rounded-lg border border-line bg-white p-2"
    />
  )
}
