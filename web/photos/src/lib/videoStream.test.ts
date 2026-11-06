import { afterEach, describe, expect, it, vi } from 'vitest'

// Mock the apex proxy so buildStreamResponse can be driven without a runtime: readContentRange
// returns canned bytes, and we assert the response plan (status + Range headers + body).
// ⚠ The mock's own type has to move with the surface, or `tsc -b` fails while `vitest` passes —
// the production build typechecks test files, the test runner does not.
type RangeRead =
  | { ok: true; bytes: Int8Array | null }
  | { ok: false; error: { kind: string; message?: string } }
const readContentRange =
  vi.fn<(handle: string, offset: number, length: number) => Promise<RangeRead>>()
vi.mock('./proxy', () => ({
  openContentStream: vi.fn(),
  closeContentStream: vi.fn(),
  readContentRange: (h: string, o: number, l: number) => readContentRange(h, o, l),
}))

const { buildStreamResponse, registerStream, unregisterStream } = await import('./videoStream')
const { streamPath } = await import('./streamProtocol')

afterEach(() => {
  readContentRange.mockReset()
})

describe('buildStreamResponse', () => {
  it('serves a 206 with Range headers for a known token', async () => {
    readContentRange.mockResolvedValueOnce({ ok: true, bytes: new Int8Array([1, 2, 3, 4]) })
    const token = registerStream({ handle: 'h1', totalSize: 1000, contentType: 'video/mp4' })

    const plan = await buildStreamResponse(streamPath(token), 'bytes=0-')

    expect(plan.status).toBe(206)
    expect(plan.headers['Content-Type']).toBe('video/mp4')
    expect(plan.headers['Content-Range']).toBe('bytes 0-3/1000')
    expect(plan.headers['Content-Length']).toBe('4')
    expect(plan.headers['Accept-Ranges']).toBe('bytes')
    expect(new Uint8Array(plan.body!)).toEqual(new Uint8Array([1, 2, 3, 4]))
    unregisterStream(token)
  })

  it('reads at the requested offset for a mid-file range', async () => {
    readContentRange.mockResolvedValueOnce({ ok: true, bytes: new Int8Array([9, 9]) })
    const token = registerStream({ handle: 'h2', totalSize: 5000, contentType: 'video/webm' })

    const plan = await buildStreamResponse(streamPath(token), 'bytes=2048-2049')

    expect(readContentRange).toHaveBeenCalledWith('h2', 2048, 2)
    expect(plan.status).toBe(206)
    expect(plan.headers['Content-Range']).toBe('bytes 2048-2049/5000')
    unregisterStream(token)
  })

  it('404s an unknown / unregistered token', async () => {
    const plan = await buildStreamResponse(streamPath('nope'), 'bytes=0-')
    expect(plan.status).toBe(404)
    expect(readContentRange).not.toHaveBeenCalled()
  })

  it('500s when the range read fails', async () => {
    readContentRange.mockResolvedValueOnce({ ok: false, error: { kind: 'read' } })
    const token = registerStream({ handle: 'h3', totalSize: 100, contentType: 'video/mp4' })

    const plan = await buildStreamResponse(streamPath(token), 'bytes=0-')

    expect(plan.status).toBe(500)
    unregisterStream(token)
  })

  it('reports the actual end when a read returns fewer bytes (EOF)', async () => {
    // Asked for a 2 MiB window near EOF but only 10 bytes remain.
    readContentRange.mockResolvedValueOnce({ ok: true, bytes: new Int8Array(10) })
    const token = registerStream({ handle: 'h4', totalSize: 4_000_000, contentType: 'video/mp4' })

    const plan = await buildStreamResponse(streamPath(token), 'bytes=3999990-')

    expect(plan.headers['Content-Range']).toBe('bytes 3999990-3999999/4000000')
    expect(plan.headers['Content-Length']).toBe('10')
    unregisterStream(token)
  })
})
