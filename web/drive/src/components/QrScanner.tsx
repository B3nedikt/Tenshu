import { useEffect, useRef, useState } from 'react'
import jsQR from 'jsqr'

// Camera QR scanner (drive-web-mode-b-sharing.md §4, MB-5). Opens the camera via
// getUserMedia (HTTPS only — drive.tenshu.app already is), decodes frames with
// jsQR, and fires [onResult] once with the decoded text. The CMP `QrScanner` in
// :ui-core is Compose — not reusable; this is the web's own decoder.
export function QrScanner({
  onResult,
  onError,
}: {
  onResult: (text: string) => void
  onError?: (message: string) => void
}) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const [status, setStatus] = useState<'starting' | 'scanning' | 'error'>('starting')

  // Latch so a result fires exactly once even if frames keep decoding.
  const doneRef = useRef(false)

  useEffect(() => {
    let stream: MediaStream | null = null
    let raf = 0
    const canvas = document.createElement('canvas')
    const ctx = canvas.getContext('2d', { willReadFrequently: true })

    function fail(message: string) {
      setStatus('error')
      onError?.(message)
    }

    function tick() {
      const video = videoRef.current
      if (doneRef.current || !video || !ctx) return
      if (video.readyState === video.HAVE_ENOUGH_DATA) {
        canvas.width = video.videoWidth
        canvas.height = video.videoHeight
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height)
        const image = ctx.getImageData(0, 0, canvas.width, canvas.height)
        const found = jsQR(image.data, image.width, image.height)
        if (found?.data) {
          doneRef.current = true
          onResult(found.data)
          return
        }
      }
      raf = requestAnimationFrame(tick)
    }

    if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
      fail('This browser has no camera access.')
      return
    }

    navigator.mediaDevices
      .getUserMedia({ video: { facingMode: 'environment' } })
      .then((s) => {
        stream = s
        const video = videoRef.current
        if (!video) return
        video.srcObject = s
        return video.play()
      })
      .then(() => {
        if (!doneRef.current) {
          setStatus('scanning')
          raf = requestAnimationFrame(tick)
        }
      })
      .catch(() => fail("Couldn't open the camera. Check permissions and try again."))

    return () => {
      doneRef.current = true
      cancelAnimationFrame(raf)
      stream?.getTracks().forEach((t) => t.stop())
    }
  }, [onResult, onError])

  return (
    <div className="space-y-2">
      <div className="overflow-hidden rounded-xl border border-line bg-surface-3">
        <video
          ref={videoRef}
          muted
          playsInline
          className="aspect-square w-full object-cover"
        />
      </div>
      <p className="text-center text-xs text-text-3">
        {status === 'starting'
          ? 'Starting camera…'
          : status === 'error'
            ? 'Camera unavailable.'
            : 'Point the camera at the invite QR code.'}
      </p>
    </div>
  )
}
