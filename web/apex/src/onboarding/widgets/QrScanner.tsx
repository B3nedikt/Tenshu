// Webcam-based QR scanner for UX §4a.1. Uses the platform
// `BarcodeDetector` when present (Chrome/Edge desktop, Chrome Android);
// falls back to a friendly "use the paste field below" hint on
// platforms without it (Firefox/Safari today). Track B's "scan or paste"
// affordance per the locked-in pairing direction (web is requester) is
// the manual paste, with this scanner as a fast-path when supported.
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

interface BarcodeDetectorLike {
  detect(image: ImageBitmapSource): Promise<readonly { rawValue: string }[]>
}
interface BarcodeDetectorCtor {
  new (init?: { formats?: readonly string[] }): BarcodeDetectorLike
  getSupportedFormats?(): Promise<readonly string[]>
}

function getBarcodeDetector(): BarcodeDetectorCtor | null {
  if (typeof window === 'undefined') return null
  const w = window as unknown as { BarcodeDetector?: BarcodeDetectorCtor }
  return w.BarcodeDetector ?? null
}

export type QrScannerStatus = 'idle' | 'requesting' | 'scanning' | 'unsupported' | 'denied' | 'error'

export function QrScanner({
  onResult,
  active,
}: {
  readonly onResult: (text: string) => void
  readonly active: boolean
}) {
  const { t } = useTranslation('onboarding')
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const Detector = getBarcodeDetector()
  // Lazy initializer keeps the synchronous setStatus calls out of the
  // effect body — `unsupported` is decided once at mount.
  const [status, setStatus] = useState<QrScannerStatus>(() =>
    Detector === null ? 'unsupported' : 'idle',
  )

  useEffect(() => {
    if (!active || Detector === null) return
    let cancelled = false
    const detector = new Detector({ formats: ['qr_code'] })
    const video = videoRef.current

    async function start() {
      setStatus('requesting')
      let stream: MediaStream
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: 'environment' },
          audio: false,
        })
      } catch (err) {
        if (cancelled) return
        const denied = err instanceof DOMException && err.name === 'NotAllowedError'
        setStatus(denied ? 'denied' : 'error')
        return
      }
      if (cancelled) {
        stream.getTracks().forEach(t => t.stop())
        return
      }
      streamRef.current = stream
      if (video !== null) {
        video.srcObject = stream
        await video.play().catch(() => {})
      }
      setStatus('scanning')

      // Poll the video for QR codes ~10× per second. This is gentle enough
      // for desktop and mobile both — BarcodeDetector itself caps the
      // throughput.
      while (!cancelled) {
        if (video !== null && video.readyState >= 2) {
          try {
            const codes = await detector.detect(video)
            if (codes.length > 0 && codes[0] !== undefined) {
              onResult(codes[0].rawValue)
              return
            }
          } catch {
            // detection errors are non-fatal; just retry
          }
        }
        await new Promise(r => setTimeout(r, 100))
      }
    }
    void start()

    return () => {
      cancelled = true
      const stream = streamRef.current
      if (stream !== null) {
        stream.getTracks().forEach(t => t.stop())
        streamRef.current = null
      }
      if (video !== null) video.srcObject = null
    }
  }, [active, Detector, onResult])

  if (!active) return null

  if (status === 'unsupported') {
    return (
      <div className="rounded-md border border-line bg-surface p-4 text-sm text-text-2">
        {t('qrScanner.unsupported')}
      </div>
    )
  }
  if (status === 'denied') {
    return (
      <div className="rounded-md border border-danger/30 bg-danger/10 p-4 text-sm text-danger">
        {t('qrScanner.denied')}
      </div>
    )
  }
  if (status === 'error') {
    return (
      <div className="rounded-md border border-danger/30 bg-danger/10 p-4 text-sm text-danger">
        {t('qrScanner.error')}
      </div>
    )
  }
  return (
    <div className="overflow-hidden rounded-md border border-line bg-black">
      <video
        ref={videoRef}
        muted
        playsInline
        className="aspect-video w-full object-cover"
      />
      <p className="px-3 py-2 text-center text-xs text-gray-300">
        {status === 'requesting' ? t('qrScanner.requesting') : t('qrScanner.pointHint')}
      </p>
    </div>
  )
}
