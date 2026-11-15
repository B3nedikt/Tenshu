// Scales a decoded rung down off the main thread (see `downscale.ts`): to a centred `px`-square, or
// with `fit` to the whole picture within one.
interface Request {
  readonly id: number
  readonly blob: Blob
  readonly px: number
  readonly fit?: boolean
}

self.onmessage = async (event: MessageEvent<Request>) => {
  const { id, blob, px, fit } = event.data
  try {
    const bitmap = await createImageBitmap(blob)
    if (fit) {
      // The whole picture, its long side at most px — what `object-contain` will show. Never up.
      const scale = Math.min(1, px / Math.max(bitmap.width, bitmap.height))
      const w = Math.max(1, Math.round(bitmap.width * scale))
      const h = Math.max(1, Math.round(bitmap.height * scale))
      const canvas = new OffscreenCanvas(w, h)
      const context = canvas.getContext('2d')
      if (!context) throw new Error('no 2d context')
      context.imageSmoothingEnabled = true
      context.imageSmoothingQuality = 'high'
      context.drawImage(bitmap, 0, 0, w, h)
      bitmap.close()
      self.postMessage({ id, blob: await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.9 }) })
      return
    }
    const short = Math.min(bitmap.width, bitmap.height)
    // Never up: a rung smaller than the tile is drawn as it is.
    const side = Math.min(px, short)
    const canvas = new OffscreenCanvas(side, side)
    const context = canvas.getContext('2d')
    if (!context) throw new Error('no 2d context')
    context.imageSmoothingEnabled = true
    context.imageSmoothingQuality = 'high'
    // The centred square of the short side — what `object-cover` would have shown.
    const sx = (bitmap.width - short) / 2
    const sy = (bitmap.height - short) / 2
    context.drawImage(bitmap, sx, sy, short, short, 0, 0, side, side)
    bitmap.close()
    const out = await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.9 })
    self.postMessage({ id, blob: out })
  } catch (error) {
    self.postMessage({ id, error: String(error) })
  }
}
