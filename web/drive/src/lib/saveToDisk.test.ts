import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const openContentStream = vi.fn()
const readContentRange = vi.fn()
const closeContentStream = vi.fn()
const getData = vi.fn()
vi.mock('./proxy', () => ({
  openContentStream: (p: string) => openContentStream(p),
  readContentRange: (h: string, o: number, l: number) => readContentRange(h, o, l),
  closeContentStream: (h: string) => closeContentStream(h),
  getData: (p: string) => getData(p),
}))

const { canStreamToDisk, streamToDisk } = await import('./saveToDisk')
const WINDOW = 2 * 1024 * 1024

// A writable that records what it was handed, and whether it was closed or aborted — the
// difference between publishing a file and discarding a partial one.
function fakeWritable() {
  const chunks: number[] = []
  return {
    chunks,
    closed: false,
    aborted: false,
    write: vi.fn(async function (this: void, data: ArrayBuffer) {
      chunks.push(data.byteLength)
    }),
    close: vi.fn(async () => { sink.closed = true }),
    abort: vi.fn(async () => { sink.aborted = true }),
  }
}
let sink: ReturnType<typeof fakeWritable>

function installPicker(impl?: () => Promise<unknown>) {
  const w = window as unknown as Record<string, unknown>
  if (impl) w.showSaveFilePicker = impl
  else delete w.showSaveFilePicker
}

beforeEach(() => {
  sink = fakeWritable()
  installPicker(async () => ({ createWritable: async () => sink }))
  closeContentStream.mockResolvedValue(undefined)
})
afterEach(() => { vi.clearAllMocks(); installPicker(undefined) })

describe('streamToDisk', () => {
  // The point of W7: an N-byte file must never be N bytes of heap. If this ever writes the
  // file in one go, the large-file case is back to being undownloadable rather than slow.
  it('writes a large file one window at a time and never holds it whole', async () => {
    const total = WINDOW * 2 + 1234
    openContentStream.mockResolvedValue({ ok: true, handle: 'h1', totalSize: total })
    readContentRange.mockImplementation(async (_h: string, offset: number, length: number) => ({
      ok: true,
      bytes: new Int8Array(Math.min(length, total - offset)),
    }))

    const outcome = await streamToDisk('/big.bin', 'big.bin')

    expect(outcome).toEqual({ kind: 'saved', streamed: true })
    expect(sink.chunks).toEqual([WINDOW, WINDOW, 1234])
    expect(sink.chunks.reduce((a, b) => a + b, 0)).toBe(total)
    expect(Math.max(...sink.chunks)).toBeLessThanOrEqual(WINDOW)
    expect(sink.closed).toBe(true)
    expect(closeContentStream).toHaveBeenCalledWith('h1')
  })

  // ⚠ The stream's size, not the caller's — they come from different reads.
  it('stops at the stream’s own EOF', async () => {
    openContentStream.mockResolvedValue({ ok: true, handle: 'h1', totalSize: WINDOW * 4 })
    let served = 0
    readContentRange.mockImplementation(async () => {
      served += 1
      return { ok: true, bytes: served > 2 ? new Int8Array(0) : new Int8Array(WINDOW) }
    })
    await streamToDisk('/x.bin', 'x.bin')
    expect(sink.chunks).toEqual([WINDOW, WINDOW])
    expect(sink.closed).toBe(true)
  })

  // ⚠⚠ A truncated file that looks complete is worse than no file: the user keeps it, deletes
  // the original, and finds out later.
  it('aborts rather than closes when a read fails midway', async () => {
    openContentStream.mockResolvedValue({ ok: true, handle: 'h1', totalSize: WINDOW * 3 })
    let reads = 0
    // ⚠ The failure is now a RESULT, not a null — which is the whole point: the loop must abort
    // on a reported failure, and can no longer confuse it with "nothing came back".
    readContentRange.mockImplementation(async () =>
      ++reads > 1 ? { ok: false, error: { kind: 'read' } } : { ok: true, bytes: new Int8Array(WINDOW) },
    )

    const outcome = await streamToDisk('/x.bin', 'x.bin')

    expect(outcome.kind).toBe('failed')
    expect(sink.aborted).toBe(true)
    expect(sink.closed).toBe(false)
    // The session is still released even though the write failed.
    expect(closeContentStream).toHaveBeenCalledWith('h1')
  })

  // No seekable session: same bytes, and crucially the handle the user ALREADY picked — not a
  // second save dialog.
  it('falls back to whole-file bytes without asking where to save again', async () => {
    openContentStream.mockResolvedValue({
      ok: false,
      error: { kind: 'unavailable', message: 'file not synced yet or realm locked' },
    })
    getData.mockResolvedValue({ ok: true, bytes: new Int8Array(4096) })
    const picker = vi.fn(async () => ({ createWritable: async () => sink }))
    installPicker(picker)

    const outcome = await streamToDisk('/small.bin', 'small.bin')

    // ⚠⚠ `streamed: false` is the point. This path is correct but expensive — it holds the whole
    // file in memory — so it must be distinguishable from the streaming one. It was not, and a
    // refusal on EVERY file went unnoticed for weeks behind these same green tests.
    expect(outcome).toEqual({ kind: 'saved', streamed: false })
    expect(sink.chunks).toEqual([4096])
    expect(picker).toHaveBeenCalledTimes(1)
  })

  it('reports a dismissed picker as cancelled, not as a failure', async () => {
    installPicker(async () => { throw new DOMException('no', 'AbortError') })
    expect(await streamToDisk('/x', 'x')).toEqual({ kind: 'cancelled' })
  })

  // Firefox/Safari. The caller keeps the in-memory path for these.
  it('reports unsupported where the browser has no picker', async () => {
    installPicker(undefined)
    expect(canStreamToDisk()).toBe(false)
    expect(await streamToDisk('/x', 'x')).toEqual({ kind: 'unsupported' })
    expect(openContentStream).not.toHaveBeenCalled()
  })
})
