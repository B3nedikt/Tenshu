// A large rung, scaled to the tile before it is drawn: a 1024 px `XLARGE` decoded per tile is ~3 MB
// of bitmap, a 320 px square ~0.4 MB. Done in a worker (decode + resample are the heavy part), with
// the best smoothing the canvas has.
//
// ⚠ Where there is no worker or no `OffscreenCanvas` (jsdom, an old browser), the rung is returned
// as it is: sharper than `LARGE` still, only heavier.

let worker: Worker | null | undefined
let nextId = 0
const pending = new Map<number, (blob: Blob | null) => void>()

function getWorker(): Worker | null {
  if (worker !== undefined) return worker
  if (typeof Worker === 'undefined' || typeof OffscreenCanvas === 'undefined') return (worker = null)
  try {
    worker = new Worker(new URL('./downscale.worker.ts', import.meta.url), { type: 'module' })
    worker.onmessage = (event: MessageEvent<{ id: number; blob?: Blob; error?: string }>) => {
      const settle = pending.get(event.data.id)
      pending.delete(event.data.id)
      if (event.data.error) console.warn(`tile downscale failed: ${event.data.error}`)
      settle?.(event.data.blob ?? null)
    }
    // ⚠ A worker that fails to load or dies (an out-of-memory decode) posts nothing: settle every
    // waiting request, and draw unscaled from then on — else the tile cache's in-flight slots stay
    // taken and no sharp tile loads again this session.
    const fail = () => {
      worker?.terminate()
      worker = null
      for (const settle of pending.values()) settle(null)
      pending.clear()
    }
    worker.onerror = fail
    worker.onmessageerror = fail
  } catch {
    worker = null
  }
  return worker
}

/**
 * [blob] as a centred [px]-square — or with [fit] the whole picture within one — never scaled up;
 * [blob] itself when that cannot be done here.
 */
export async function scaleTile(blob: Blob, px: number, fit = false): Promise<Blob> {
  const w = getWorker()
  if (!w) return blob
  const id = nextId++
  const out = await new Promise<Blob | null>((resolve) => {
    pending.set(id, resolve)
    w.postMessage({ id, blob, px, fit })
  })
  return out ?? blob
}
